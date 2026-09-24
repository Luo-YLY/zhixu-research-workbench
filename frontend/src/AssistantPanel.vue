<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import Icon from './Icon.vue'
import { ApiError } from './api'
import { assistantApi } from './assistant-api'
import type { AssistantContext, AssistantStatus, ChatMessage, ContextSnapshot, SessionDetail, SessionSummary, TaskDraft } from './assistant-api'
import { readStorage, shouldAcceptSession, writeStorage } from './assistant-state'

const props = defineProps<{ open: boolean; context: AssistantContext; contextLabel: string }>()
const emit = defineEmits<{ close: []; draft: [draft: TaskDraft]; working: [working: boolean] }>()
const SESSION_KEY = 'research-workbench.assistant.session'
const provider = ref<AssistantStatus | null>(null)
const providerError = ref('')
const providerLoading = ref(false)
const sessions = ref<SessionSummary[]>([])
const session = ref<SessionDetail | null>(null)
const selectedId = ref('')
const sessionLoading = ref(false)
const initialized = ref(false)
const historyOpen = ref(false)
const submitting = ref(false)
const cancelling = ref(false)
const draft = ref('')
const chatError = ref('')
const pollError = ref('')
const notice = ref('')
const includeContext = ref(true)
const selectedExcerpt = ref('')
const preview = ref<ContextSnapshot | null>(null)
const previewLoading = ref(false)
const previewError = ref('')
const previewKey = ref('')
const previewExpanded = ref(false)
const pageChanged = ref(false)
const feed = ref<HTMLElement | null>(null)
const composer = ref<HTMLTextAreaElement | null>(null)
const panel = ref<HTMLElement | null>(null)
const drawerMode = ref(window.matchMedia('(max-width: 1449px)').matches)
let sessionGeneration = 0
let contextGeneration = 0
let activePreviewKey = ''
let polling = false
let pollTimer: number | undefined
let noticeTimer: number | undefined
let lastSessionListCheck = 0
let priorOverflow: string | null = null

const contextKey = computed(() => JSON.stringify(props.context))
const effectiveContext = computed<AssistantContext>(() => ({ ...props.context, ...(selectedExcerpt.value ? { selectedText: selectedExcerpt.value } : {}) }))
const effectiveKey = computed(() => JSON.stringify(effectiveContext.value))
const runningSession = computed(() => sessions.value.find(item => item.status === 'RUNNING'))
const isRunning = computed(() => session.value?.status === 'RUNNING')
const otherRunning = computed(() => runningSession.value && runningSession.value.id !== selectedId.value ? runningSession.value : null)
const previewReady = computed(() => !includeContext.value || (!!preview.value && previewKey.value === effectiveKey.value && !previewLoading.value && !previewError.value))
const canSend = computed(() => !!provider.value?.available && !!draft.value.trim() && !submitting.value && !cancelling.value && !sessionLoading.value && !isRunning.value && !runningSession.value && previewReady.value)
const providerLabel = computed(() => provider.value?.provider === 'CODEX_CLI' ? 'Codex CLI' : provider.value?.provider === 'MODEL_API' ? '模型 API' : '模型待配置')
const providerSummary = computed(() => {
  if (providerLoading.value) return '正在检查模型配置…'
  if (providerError.value) return '模型配置状态未确认'
  if (provider.value?.available) return `${providerLabel.value} · 已配置`
  return provider.value?.provider && provider.value.provider !== 'UNCONFIGURED' ? `${providerLabel.value} · 暂不可用` : '暂未连接模型'
})
const composerNotice = computed(() => {
  if (!provider.value?.available) return '模型尚未配置或暂不可用。可以先起草消息、查看上下文，连接模型后再发送。'
  const source = provider.value.provider === 'CODEX_CLI' ? '使用本机 Codex 账户' : '使用已配置的模型接口'
  return `${source}。消息、对话历史与勾选的上下文会发送给模型服务；任务草稿仍需你审核保存。`
})
const suggestions = computed(() => {
  if (props.context.runId) return ['解释当前运行与选中节点', '列出审批前需要检查的证据', '建议下一步研究任务']
  if (props.context.page === 'projects') return ['帮我整理一份研究任务规范', '拆分这个项目的下一步', '给出可检验的验收条件']
  if (props.context.page === 'approvals') return ['如何判断一个结果可以接受？', '拟一份人工审批检查单', '如何写清楚退回原因？']
  if (props.context.page === 'roadmap') return ['下一阶段应该优先实现什么？', '如何逐步接入文献 RAG？', '区分演示能力与真实研究能力']
  return ['梳理我的下一步研究安排', '把研究想法整理为任务规范', '解释当前研究工作流']
})
const formatTime = (value: string) => new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(value))
const messageStatus = (status: string) => ({ QUEUED: '等待执行', RUNNING: '正在回答', FAILED: '调用失败', CANCELLED: '已取消', INTERRUPTED: '执行中断', COMPLETED: '已完成' }[status] || status)
const errorText = (error: unknown) => error instanceof Error ? error.message : '暂时无法完成请求，请重试。'

function showNotice(text: string) {
  notice.value = text
  window.clearTimeout(noticeTimer)
  noticeTimer = window.setTimeout(() => { notice.value = '' }, 7000)
}
function updateSummary(detail: SessionDetail) {
  const index = sessions.value.findIndex(item => item.id === detail.id)
  const summary = { id: detail.id, title: detail.title, status: detail.status, createdAt: detail.createdAt, updatedAt: detail.updatedAt }
  if (index < 0) sessions.value.unshift(summary)
  else sessions.value[index] = summary
  sessions.value.sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))
}
async function scrollToEnd(force = false) {
  const nearEnd = !feed.value || feed.value.scrollHeight - feed.value.scrollTop - feed.value.clientHeight < 100
  await nextTick()
  if (feed.value && (force || nearEnd)) feed.value.scrollTop = feed.value.scrollHeight
}
function applySession(incoming: SessionDetail) {
  if (incoming.id !== selectedId.value || !shouldAcceptSession(session.value, incoming)) return
  const shouldScroll = !feed.value || feed.value.scrollHeight - feed.value.scrollTop - feed.value.clientHeight < 100
  session.value = incoming
  updateSummary(incoming)
  if (shouldScroll) void scrollToEnd(true)
}
async function loadProvider() {
  if (providerLoading.value) return
  providerLoading.value = true
  try { provider.value = await assistantApi.status(); providerError.value = '' }
  catch (error) { providerError.value = errorText(error); provider.value = null }
  finally { providerLoading.value = false }
}
async function loadSessions() {
  const incoming = await assistantApi.sessions()
  sessions.value = incoming.sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))
  // Keep a more recent locally received submit/cancel result over a stale list.
  if (session.value) {
    const summary = sessions.value.find(item => item.id === session.value!.id)
    if (!summary || Date.parse(summary.updatedAt) <= Date.parse(session.value.updatedAt)) updateSummary(session.value)
  }
  lastSessionListCheck = Date.now()
}
async function selectSession(id: string) {
  if (submitting.value || cancelling.value) return
  const generation = ++sessionGeneration
  selectedId.value = id; session.value = null; sessionLoading.value = true; chatError.value = ''; historyOpen.value = false
  writeStorage(SESSION_KEY, id)
  try {
    const incoming = await assistantApi.session(id)
    if (generation !== sessionGeneration) return
    applySession(incoming); void scrollToEnd(true)
  } catch (error) { if (generation === sessionGeneration) chatError.value = errorText(error) }
  finally { if (generation === sessionGeneration) sessionLoading.value = false }
}
async function initialize() {
  if (initialized.value) {
    const results = await Promise.allSettled([loadProvider(), refreshCurrent(), loadSessions()])
    const failed = results.find(result => result.status === 'rejected')
    if (failed?.status === 'rejected') pollError.value = errorText(failed.reason)
    else pollError.value = ''
    return
  }
  initialized.value = true
  void loadProvider()
  sessionLoading.value = true
  try {
    await loadSessions()
    const saved = readStorage(SESSION_KEY)
    const first = sessions.value.find(item => item.id === saved) || sessions.value[0]
    if (first) await selectSession(first.id)
  } catch (error) { chatError.value = errorText(error) }
  finally { sessionLoading.value = false }
}
async function refreshCurrent() {
  const id = selectedId.value
  const generation = sessionGeneration
  if (!id) return
  const incoming = await assistantApi.session(id)
  if (generation === sessionGeneration && selectedId.value === id) applySession(incoming)
}
async function poll() {
  if (polling || !initialized.value || (!props.open && !isRunning.value && !runningSession.value)) return
  polling = true
  try {
    if (isRunning.value || runningSession.value?.id === selectedId.value) await refreshCurrent()
    if (runningSession.value || Date.now() - lastSessionListCheck > 15000) await loadSessions()
    pollError.value = ''
  } catch (error) { if (props.open) pollError.value = `${errorText(error)} 对话记录会在连接恢复后继续更新。` }
  finally { polling = false }
}
async function refreshPreview() {
  if (!props.open || !includeContext.value) return
  const key = effectiveKey.value
  if (previewLoading.value && activePreviewKey === key) return
  const generation = ++contextGeneration
  activePreviewKey = key; previewLoading.value = true; previewError.value = ''
  try {
    const incoming = await assistantApi.context(JSON.parse(key) as AssistantContext)
    if (generation !== contextGeneration || effectiveKey.value !== key) return
    preview.value = incoming; previewKey.value = key
  } catch (error) { if (generation === contextGeneration) previewError.value = errorText(error) }
  finally { if (generation === contextGeneration) previewLoading.value = false }
}
async function createConversation() {
  if (submitting.value || cancelling.value || sessionLoading.value) return
  submitting.value = true; chatError.value = ''
  try {
    const incoming = await assistantApi.create((preview.value?.title || props.contextLabel || '研究讨论').slice(0, 100))
    ++sessionGeneration; selectedId.value = incoming.id; session.value = null
    applySession(incoming); writeStorage(SESSION_KEY, incoming.id)
    historyOpen.value = false; pageChanged.value = false; draft.value = ''
    await nextTick(); composer.value?.focus()
  } catch (error) { chatError.value = errorText(error) }
  finally { submitting.value = false }
}
async function sendMessage() {
  if (!canSend.value) return
  const text = draft.value.trim()
  const include = includeContext.value
  const context = JSON.parse(effectiveKey.value) as AssistantContext
  submitting.value = true; chatError.value = ''
  try {
    if (!selectedId.value) {
      const created = await assistantApi.create((preview.value?.title || props.contextLabel || '研究讨论').slice(0, 100))
      ++sessionGeneration; selectedId.value = created.id; session.value = null
      applySession(created); writeStorage(SESSION_KEY, created.id)
    }
    const incoming = await assistantApi.send(selectedId.value, text, include, context)
    applySession(incoming)
    draft.value = ''; pageChanged.value = false
    void scrollToEnd(true)
  } catch (error) {
    chatError.value = error instanceof ApiError && (error.status === 409 || error.status === 429)
      ? `${errorText(error)} 请等待当前回答完成，或进入正在执行的对话取消。`
      : `${errorText(error)} 输入内容已保留；请先检查消息记录，避免重复提交。`
    await Promise.allSettled([refreshCurrent(), loadSessions()])
  } finally { submitting.value = false }
}
async function cancel() {
  if (!selectedId.value || cancelling.value) return
  cancelling.value = true; chatError.value = ''
  try { applySession(await assistantApi.cancel(selectedId.value)); showNotice('取消请求已提交，执行记录会保留。') }
  catch (error) { chatError.value = errorText(error); await Promise.allSettled([refreshCurrent(), loadSessions()]) }
  finally { cancelling.value = false }
}
function quoteSelection() {
  const selection = window.getSelection()
  const content = document.getElementById('main-content')
  if (!selection || selection.isCollapsed || !content || !selection.anchorNode || !selection.focusNode || !content.contains(selection.anchorNode) || !content.contains(selection.focusNode)) {
    showNotice('请先在左侧工作区选中一段文字，再点击“引用选中文字”。')
    return
  }
  const text = selection.toString().trim()
  if (!text) { showNotice('没有可引用的文字。'); return }
  selectedExcerpt.value = text.slice(0, 2000)
  includeContext.value = true
  showNotice(text.length > 2000 ? '已引用前 2000 字，可在发送前检查或移除。' : '已添加引用，发送前可检查或移除。')
}
function useSuggestion(text: string) { draft.value = text; composer.value?.focus() }
function draftFromMessage(message: ChatMessage) {
  const index = session.value?.messages.findIndex(item => item.id === message.id) ?? -1
  const question = session.value?.messages.slice(0, index).reverse().find(item => item.role === 'USER')?.content || '研究思路'
  emit('draft', { title: `任务草稿 · ${question.split('\n')[0]}`.slice(0, 180), objective: message.content, projectId: props.context.projectId })
}
function composerKey(event: KeyboardEvent) {
  if ((event.ctrlKey || event.metaKey) && event.key === 'Enter' && !event.isComposing) { event.preventDefault(); void sendMessage() }
}
function panelKey(event: KeyboardEvent) {
  if (!props.open || document.querySelector('dialog[open]')) return
  if (event.key === 'Escape') { event.preventDefault(); emit('close'); return }
  if (event.key !== 'Tab' || !window.matchMedia('(max-width: 1449px)').matches) return
  const items = panel.value?.querySelectorAll<HTMLElement>('button:not(:disabled), a[href], input:not(:disabled), textarea:not(:disabled), select:not(:disabled), summary, [tabindex="0"]')
  const visible = items ? [...items].filter(item => item.getClientRects().length) : []
  const first = visible[0]; const last = visible.at(-1)
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
}
function syncDrawerLock() {
  drawerMode.value = window.matchMedia('(max-width: 1449px)').matches
  if (props.open && drawerMode.value) {
    if (priorOverflow === null) priorOverflow = document.documentElement.style.overflow
    document.documentElement.style.overflow = 'hidden'
  } else if (priorOverflow !== null) {
    document.documentElement.style.overflow = priorOverflow
    priorOverflow = null
  }
}
watch(contextKey, () => { selectedExcerpt.value = ''; pageChanged.value = !!session.value?.messages.length })
watch([effectiveKey, includeContext], () => { if (props.open && includeContext.value) void refreshPreview() })
watch(() => props.open, async open => {
  syncDrawerLock()
  if (!open) return
  void initialize(); void refreshPreview()
  await nextTick(); composer.value?.focus()
}, { immediate: true })
watch(() => !!runningSession.value || !!isRunning.value, working => emit('working', working), { immediate: true })
onMounted(() => {
  pollTimer = window.setInterval(() => { void poll() }, 2500)
  document.addEventListener('keydown', panelKey)
  window.addEventListener('resize', syncDrawerLock)
})
onBeforeUnmount(() => {
  window.clearInterval(pollTimer); window.clearTimeout(noticeTimer)
  document.removeEventListener('keydown', panelKey)
  window.removeEventListener('resize', syncDrawerLock)
  if (priorOverflow !== null) document.documentElement.style.overflow = priorOverflow
})
</script>

<template>
  <button v-if="open" class="assistant-backdrop" aria-label="关闭研究助手" tabindex="-1" @click="emit('close')"></button>
  <aside v-show="open" id="research-assistant" ref="panel" class="assistant-panel" :role="drawerMode ? 'dialog' : 'complementary'" :aria-modal="drawerMode ? true : undefined" aria-labelledby="assistant-title">
    <header class="assistant-header"><div class="assistant-heading"><span class="assistant-brand"><Icon name="chat" :size="21" /></span><div><h2 id="assistant-title">研究助手</h2><p><span class="tiny-dot" :class="{ live: provider?.available }"></span>{{ providerSummary }}</p></div></div><button class="icon-button small" aria-label="关闭研究助手" title="关闭（Esc）" @click="emit('close')"><Icon name="close" :size="19" /></button></header>
    <div class="assistant-toolbar"><button class="text-button" :disabled="submitting || cancelling || sessionLoading" @click="createConversation"><Icon name="plus" :size="15" />新对话</button><button class="text-button" :class="{ 'is-on': historyOpen }" :aria-expanded="historyOpen" aria-controls="assistant-history" :disabled="submitting || cancelling" @click="historyOpen = !historyOpen"><Icon name="history" :size="15" />历史对话<span class="assistant-history-count">{{ sessions.length }}</span></button><button class="icon-button small" aria-label="重新检测模型配置" title="重新检测模型配置（不会调用模型）" :disabled="providerLoading" @click="loadProvider"><Icon name="refresh" :size="15" :class="{ spinning: providerLoading }" /></button></div>
    <section v-if="historyOpen" id="assistant-history" class="assistant-history" aria-label="历史对话"><p v-if="!sessions.length" class="assistant-small-empty">还没有保存的对话。</p><button v-for="item in sessions" :key="item.id" class="assistant-history-item" :class="{ selected: selectedId === item.id }" @click="selectSession(item.id)"><span><strong>{{ item.title || '研究讨论' }}</strong><small>{{ formatTime(item.updatedAt) }}</small></span><span v-if="item.status === 'RUNNING'" class="status running">回答中</span><Icon v-else name="chevron" :size="14" /></button></section>
    <section class="assistant-context" aria-label="发送上下文"><div class="assistant-context-head"><label class="assistant-checkbox"><input v-model="includeContext" type="checkbox" /><span>附带当前页面上下文</span></label><button class="assistant-context-toggle" :aria-expanded="previewExpanded" aria-controls="assistant-context-preview" @click="previewExpanded = !previewExpanded">{{ previewExpanded ? '收起预览' : '查看预览' }}<Icon name="chevron" :size="12" /></button></div><div class="assistant-context-current"><Icon :name="context.runId ? 'flow' : context.projectId ? 'folder' : 'grid'" :size="15" /><span>{{ previewReady && includeContext ? preview?.title || contextLabel : contextLabel }}</span><span v-if="!includeContext" class="tag">本条不附带</span></div><p v-if="previewLoading && includeContext" class="assistant-context-summary" role="status">正在从服务器准备当前页面上下文…</p><p v-else-if="previewError && includeContext" class="assistant-context-error" role="alert">{{ previewError }}<button class="text-button" @click="refreshPreview">重试</button></p><p v-else-if="includeContext && previewKey === effectiveKey" class="assistant-context-summary">{{ preview?.summary }}</p><p v-else-if="!includeContext" class="assistant-context-summary">下一条消息不新增页面上下文；这段对话已有的历史仍会保留。</p><div v-if="selectedExcerpt" class="assistant-excerpt"><div><span><Icon name="quote" :size="13" />主动引用 · {{ selectedExcerpt.length }} 字</span><button class="icon-button small" aria-label="移除引用文字" @click="selectedExcerpt = ''"><Icon name="close" :size="13" /></button></div><p>{{ selectedExcerpt }}</p></div><div v-if="previewExpanded" id="assistant-context-preview" class="assistant-context-preview"><p>以下为服务器整理的上下文预览。真正发送时会再记录一份独立快照；实时状态可能在此期间变化。</p><pre v-if="preview && previewKey === effectiveKey">{{ preview.content }}</pre><p v-else>当前预览尚未准备好。</p></div><button class="assistant-quote-button" @mousedown.prevent @click="quoteSelection"><Icon name="quote" :size="13" />引用选中文字</button></section>
    <div v-if="providerError || (provider && !provider.available)" class="assistant-alert" role="status"><Icon name="alert" :size="16" /><span>{{ providerError || provider?.message }}<small>对话会保留；连接可用后才能发送，不会生成模拟回复。</small></span></div>
    <div v-else-if="provider?.available" class="assistant-provider-note"><span>{{ providerLabel }} · 已配置</span><span>五节点工作流仍为 DEMO</span></div>
    <div v-if="otherRunning" class="assistant-alert assistant-running-alert" role="status"><span>另一个对话正在调用模型。当前版本同时执行一条消息。</span><button class="text-button" @click="selectSession(otherRunning.id)">查看</button></div>
    <div v-if="pageChanged" class="assistant-page-notice">页面已切换。已发送消息保留当时的上下文；下一条可使用当前页面。</div>
    <div ref="feed" class="assistant-feed" aria-label="研究对话记录" :aria-busy="sessionLoading">
      <div v-if="sessionLoading" class="assistant-loading" role="status"><span class="spinner"></span><p>读取已保存的对话…</p></div>
      <div v-else-if="!session?.messages.length" class="assistant-empty"><span class="assistant-empty-icon"><Icon name="chat" :size="29" /></span><h3>让想法，在研究现场展开。</h3><p>结合你正在查看的项目、运行和节点，讨论问题、审阅过程或整理下一步。</p><div class="assistant-suggestions"><button v-for="suggestion in suggestions" :key="suggestion" @click="useSuggestion(suggestion)">{{ suggestion }}<Icon name="arrow" :size="14" /></button></div><small>建议只会填入输入框。配置模型后，点击发送才会调用模型。</small></div>
      <template v-else><div class="assistant-conversation-label"><span>{{ session.title || '研究讨论' }}</span><span>已持久保存</span></div><article v-for="message in session.messages" :key="message.id" class="assistant-message" :class="[message.role.toLowerCase(), `message-${message.status.toLowerCase()}`]"><div class="assistant-message-head"><span class="assistant-message-avatar"><Icon v-if="message.role === 'ASSISTANT'" name="chat" :size="13" /><template v-else>我</template></span><strong>{{ message.role === 'USER' ? '你' : '研究助手' }}</strong><small>{{ formatTime(message.createdAt) }}</small><span v-if="message.status !== 'COMPLETED'" class="assistant-message-status">{{ messageStatus(message.status) }}</span></div><details v-if="message.contextTitle || message.contextSummary" class="assistant-sent-context"><summary><Icon name="file" :size="12" />已附带：{{ message.contextTitle || '页面上下文' }}</summary><p>{{ message.contextSummary || '上下文已随本条消息保存。' }}</p><small>这是发送时的记录，不会随当前页面切换。</small></details><div v-if="message.content" class="assistant-message-content">{{ message.content }}</div><div v-else-if="message.status === 'RUNNING' || message.status === 'QUEUED'" class="assistant-thinking" role="status"><span class="spinner small-spinner"></span>研究助手正在准备回答…</div><div v-else class="assistant-message-content assistant-message-empty">{{ message.status === 'COMPLETED' ? '这次调用没有返回可显示的内容。' : '本次回答未完成。执行状态已保留。' }}</div><button v-if="message.role === 'ASSISTANT' && message.status === 'COMPLETED' && message.content.trim()" class="assistant-task-draft" @click="draftFromMessage(message)"><Icon name="file" :size="13" />转为任务草稿<Icon name="arrow" :size="12" /></button></article></template>
    </div>
    <div v-if="notice" class="assistant-notice" role="status">{{ notice }}</div>
    <div v-if="chatError || pollError" class="assistant-chat-error" role="alert"><span>{{ chatError || pollError }}</span><button class="icon-button small" aria-label="关闭对话提示" @click="chatError = ''; pollError = ''"><Icon name="close" :size="14" /></button></div>
    <form class="assistant-composer" @submit.prevent="sendMessage"><label for="assistant-message" class="sr-only">给研究助手的消息</label><textarea id="assistant-message" ref="composer" v-model="draft" maxlength="6000" rows="3" :disabled="submitting" placeholder="讨论当前研究，或描述一个新想法…" @keydown="composerKey"></textarea><div class="assistant-composer-actions"><span>{{ draft.length ? `${draft.length} / 6000` : 'Ctrl / ⌘ + Enter 发送' }}</span><button v-if="isRunning" type="button" class="btn secondary small-btn" :disabled="cancelling || submitting" @click="cancel"><Icon name="stop" :size="12" />{{ cancelling ? '正在取消…' : '停止回答' }}</button><button v-else type="submit" class="btn primary small-btn" :disabled="!canSend"><span v-if="submitting" class="spinner small-spinner"></span><Icon v-else name="send" :size="14" />{{ submitting ? '正在提交…' : '发送' }}</button></div><p>{{ composerNotice }}</p></form>
  </aside>
</template>
