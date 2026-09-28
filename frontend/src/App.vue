<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import Icon from './Icon.vue'
import AssistantPanel from './AssistantPanel.vue'
import PlanPage from './PlanPage.vue'
import LiteraturePage from './LiteraturePage.vue'
import type { AssistantContext, TaskDraft } from './assistant-api'
import { readStorage, writeStorage } from './assistant-state'
import { request, statusLabel, terminal } from './api'
import { shouldAcceptRun } from './run-state'
import type { Approval, Dashboard, Project, ResearchTask, RunDetail, RunSummary, SystemInfo } from './api'

type Page = 'overview' | 'projects' | 'planning' | 'literature' | 'runs' | 'approvals' | 'roadmap'
const nav: { id: Page; label: string; icon: string; subtitle: string }[] = [
  { id: 'overview', label: '研究概览', icon: 'grid', subtitle: '让每一次研究，都有迹可循。' },
  { id: 'projects', label: '项目与任务', icon: 'folder', subtitle: '从一个问题开始，把研究拆成可执行的任务。' },
  { id: 'planning', label: '每日计划', icon: 'clock', subtitle: '把周期研究和当天安排放在同一张清单。' },
  { id: 'literature', label: '文献与证据', icon: 'book', subtitle: '定位原文，记录主张、复现步骤与核对结果。' },
  { id: 'runs', label: '运行记录', icon: 'flow', subtitle: '跟踪每一步执行，保留完整的研究过程。' },
  { id: 'approvals', label: '人工审批', icon: 'shield', subtitle: '查看任务与证据，在关键节点作出判断。' },
  { id: 'roadmap', label: '能力路线图', icon: 'map', subtitle: '先跑通研究闭环，再逐步接入真实能力。' },
]
const page = ref<Page>('overview')
const selectedRunId = ref('')
const system = ref<SystemInfo | null>(null)
const dashboard = ref<Dashboard | null>(null)
const projects = ref<Project[]>([])
const tasks = ref<ResearchTask[]>([])
const runs = ref<RunSummary[]>([])
const approvals = ref<Approval[]>([])
const detail = ref<RunDetail | null>(null)
const loading = ref(true)
const refreshing = ref(false)
const loadError = ref('')
const runLoading = ref(false)
const runError = ref('')
const busy = ref(false)
const notification = ref<{ text: string; error: boolean } | null>(null)
const activeProject = ref('')
const taskSearch = ref('')
const runFilter = ref('ALL')
const approvalFilter = ref('PENDING')
const decisionComment = ref('')
const chosenNode = ref('')
const assistantOpen = ref(readStorage('research-workbench.assistant.open') === 'true')
const assistantWorking = ref(false)
const assistantToggle = ref<HTMLButtonElement | null>(null)
const pendingTaskDraft = ref<TaskDraft | null>(null)
const connection = ref<'connecting' | 'live' | 'polling' | 'closed'>('closed')
const modal = ref<'project' | 'task' | ''>('')
const modalRef = ref<HTMLDialogElement | null>(null)
const formError = ref('')
const projectForm = reactive({ name: '', description: '' })
const taskForm = reactive({ projectId: '', title: '', objective: '' })
let stream: EventSource | null = null
let refreshTimer: number | undefined
let pollTimer: number | undefined
let noticeTimer: number | undefined
let baseInFlight = false
let detailGeneration = 0

const currentNav = computed(() => nav.find(item => item.id === page.value)!)
const pendingApprovals = computed(() => approvals.value.filter(item => item.status === 'PENDING'))
const activeRuns = computed(() => runs.value.filter(item => !terminal(item.status)))
const visibleTasks = computed(() => tasks.value.filter(task => (!activeProject.value || task.projectId === activeProject.value) && `${task.title} ${task.objective}`.toLocaleLowerCase().includes(taskSearch.value.trim().toLocaleLowerCase())))
const visibleRuns = computed(() => runs.value.filter(run => runFilter.value === 'ALL' || (runFilter.value === 'ACTIVE' ? !terminal(run.status) : run.status === runFilter.value)))
const visibleApprovals = computed(() => approvals.value.filter(approval => approvalFilter.value === 'ALL' || approval.status === approvalFilter.value))
const selectedNode = computed(() => detail.value?.nodes.find(node => node.id === chosenNode.value) || detail.value?.nodes.find(node => node.status === 'RUNNING' || node.status === 'WAITING_APPROVAL') || detail.value?.nodes[0])
const assistantContext = computed<AssistantContext>(() => {
  if (page.value === 'runs' && selectedRunId.value) {
    const current = detail.value?.id === selectedRunId.value ? detail.value : null
    return { page: page.value, runId: selectedRunId.value, ...(current ? { projectId: current.projectId, taskId: current.taskId, nodeKey: selectedNode.value?.key } : {}) }
  }
  return { page: page.value, ...(page.value === 'projects' && activeProject.value ? { projectId: activeProject.value } : {}) }
})
const assistantContextLabel = computed(() => selectedRunId.value ? `运行详情${selectedNode.value ? ` · ${selectedNode.value.label}` : ''}` : currentNav.value.label)
const orderedNodes = computed(() => [...(detail.value?.nodes || [])].sort((a, b) => a.position - b.position))
const completedNodes = computed(() => detail.value?.nodes.filter(node => node.status === 'SUCCEEDED').length || 0)
const dateLabel = new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' }).format(new Date())
const projectName = (id: string) => projects.value.find(project => project.id === id)?.name || '研究项目'
const latestRun = (taskId: string) => runs.value.find(run => run.taskId === taskId)
const formatTime = (date: string | null | undefined) => date ? new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(date)) : '—'
const formatBytes = (value: number) => value < 1024 ? `${value} B` : value < 1024 * 1024 ? `${(value / 1024).toFixed(1)} KB` : `${(value / 1024 / 1024).toFixed(1)} MB`
const errorMessage = (error: unknown) => error instanceof Error ? error.message : '操作未完成，请重试。'
const safeDownload = (url: string) => /^\/api\/artifacts\/[^/]+\/download$/.test(url) ? url : undefined

function notify(text: string, error = false) {
  notification.value = { text, error }
  window.clearTimeout(noticeTimer)
  noticeTimer = window.setTimeout(() => { notification.value = null }, error ? 9000 : 4500)
}
watch(assistantOpen, open => writeStorage('research-workbench.assistant.open', String(open)))
async function closeAssistant() { assistantOpen.value = false; await nextTick(); assistantToggle.value?.focus() }
function navigate(target: Page, runId = '') {
  window.location.hash = `/${target}${runId ? `/${encodeURIComponent(runId)}` : ''}`
  readHash()
}
function readHash() {
  const [target, id] = window.location.hash.replace(/^#\/?/, '').split('/')
  page.value = nav.some(item => item.id === target) ? target as Page : 'overview'
  selectedRunId.value = page.value === 'runs' ? id || '' : ''
}
async function loadBase(initial = false) {
  if (baseInFlight) return
  baseInFlight = true
  if (initial) loading.value = true
  refreshing.value = true
  try {
    const [sys, dash, proj, task, run, approval] = await Promise.all([
      request<SystemInfo>('/system'), request<Dashboard>('/dashboard'), request<Project[]>('/projects'),
      request<ResearchTask[]>('/tasks'), request<RunSummary[]>('/runs'), request<Approval[]>('/approvals'),
    ])
    system.value = sys; dashboard.value = dash; projects.value = proj
    tasks.value = [...task].sort((a, b) => b.createdAt.localeCompare(a.createdAt))
    runs.value = [...run].sort((a, b) => b.createdAt.localeCompare(a.createdAt))
    approvals.value = [...approval].sort((a, b) => b.createdAt.localeCompare(a.createdAt))
    loadError.value = ''
  } catch (error) { loadError.value = errorMessage(error) }
  finally { loading.value = false; refreshing.value = false; baseInFlight = false }
}
function stopStream() {
  stream?.close(); stream = null
  window.clearInterval(pollTimer)
  connection.value = 'closed'
}
function applyDetail(run: RunDetail) {
  if (run.id !== selectedRunId.value) return
  if (!shouldAcceptRun(detail.value, run)) return
  detail.value = run
  runError.value = ''
  const summaryIndex = runs.value.findIndex(item => item.id === run.id)
  if (summaryIndex >= 0) runs.value[summaryIndex] = run
  if (terminal(run.status)) stopStream()
}
async function refreshDetail() {
  const id = selectedRunId.value
  if (!id) return
  try { const run = await request<RunDetail>(`/runs/${encodeURIComponent(id)}`); applyDetail(run) }
  catch (error) { if (id === selectedRunId.value) runError.value = errorMessage(error) }
}
async function loadRun(id: string) {
  stopStream()
  const generation = ++detailGeneration
  detail.value = null; chosenNode.value = ''; decisionComment.value = ''; runError.value = ''
  if (!id) { runLoading.value = false; return }
  runLoading.value = true
  try {
    const run = await request<RunDetail>(`/runs/${encodeURIComponent(id)}`)
    if (generation !== detailGeneration) return
    applyDetail(run)
    if (!terminal(run.status)) {
      connection.value = 'connecting'
      const eventSource = new EventSource(`/api/runs/${encodeURIComponent(id)}/events`)
      stream = eventSource
      eventSource.onopen = () => { if (stream === eventSource) connection.value = 'live' }
      eventSource.addEventListener('run', event => {
        if (generation !== detailGeneration || stream !== eventSource) return
        try { applyDetail(JSON.parse((event as MessageEvent).data)); void loadBase() }
        catch { connection.value = 'polling'; void refreshDetail() }
      })
      eventSource.onerror = () => { if (stream === eventSource) connection.value = 'polling' }
      pollTimer = window.setInterval(() => { if (connection.value !== 'live') void refreshDetail() }, 5000)
    }
  } catch (error) { if (generation === detailGeneration) runError.value = errorMessage(error) }
  finally { if (generation === detailGeneration) runLoading.value = false }
}
watch(selectedRunId, id => { void loadRun(id) })

async function openModal(type: 'project' | 'task') {
  formError.value = ''
  if (type === 'task' && !projects.value.length) { type = 'project'; notify('先创建一个项目，就可以添加研究任务。') }
  projectForm.name = ''; projectForm.description = ''
  taskForm.projectId = activeProject.value || projects.value[0]?.id || ''; taskForm.title = ''; taskForm.objective = ''
  modal.value = type
  await nextTick()
  modalRef.value?.showModal()
}
function closeModal() { if (!busy.value) { modalRef.value?.close(); modal.value = '' } }
async function openTaskDraft(draft: TaskDraft) {
  if (!projects.value.length) {
    pendingTaskDraft.value = draft
    await openModal('project')
    notify('先创建一个项目；助手建议已保留，随后会带入任务草稿。')
    return
  }
  await openModal('task')
  taskForm.title = draft.title.slice(0, 180)
  taskForm.objective = draft.objective.slice(0, 4000)
  if (draft.projectId && projects.value.some(project => project.id === draft.projectId)) taskForm.projectId = draft.projectId
  notify(draft.objective.length > 4000 ? '已带入回答的前 4000 字，请整理后保存。完整回答仍在对话中。' : '已填入任务草稿，请检查所属项目、标题与目标，再保存。')
}
async function saveForm() {
  if (busy.value) return
  busy.value = true; formError.value = ''
  let createdProject: Project | null = null
  try {
    if (modal.value === 'project') {
      const project = await request<Project>('/projects', { name: projectForm.name.trim(), description: projectForm.description.trim() })
      createdProject = project
      activeProject.value = project.id; navigate('projects'); notify('项目已创建，可以开始添加研究任务。')
    } else {
      await request<ResearchTask>('/tasks', { projectId: taskForm.projectId, title: taskForm.title.trim(), objective: taskForm.objective.trim() })
      activeProject.value = taskForm.projectId; navigate('projects'); notify('任务已保存，可以启动演示流程。')
    }
    modalRef.value?.close(); modal.value = ''
    await loadBase()
    if (createdProject && !projects.value.some(project => project.id === createdProject!.id)) projects.value.unshift(createdProject)
  } catch (error) { formError.value = errorMessage(error) }
  finally { busy.value = false }
  if (createdProject && pendingTaskDraft.value && !formError.value) {
    const draft = { ...pendingTaskDraft.value, projectId: createdProject.id }
    pendingTaskDraft.value = null
    await openTaskDraft(draft)
  }
}
async function startRun(task: ResearchTask) {
  if (busy.value) return
  busy.value = true
  try {
    const run = await request<RunDetail>(`/tasks/${task.id}/runs`, {}, { 'Idempotency-Key': crypto.randomUUID() })
    navigate('runs', run.id); notify('演示流程已启动，关键步骤将等待你的审批。'); await loadBase()
  } catch (error) { notify(errorMessage(error), true) }
  finally { busy.value = false }
}
async function cancelRun() {
  if (!detail.value || busy.value) return
  busy.value = true
  try { applyDetail(await request<RunDetail>(`/runs/${detail.value.id}/cancel`, {})); notify('本次运行已取消，历史记录已保留。'); await loadBase() }
  catch (error) { notify(errorMessage(error), true) }
  finally { busy.value = false }
}
async function decide(decision: 'APPROVE' | 'REJECT') {
  if (!detail.value?.approval || busy.value) return
  if (decision === 'REJECT' && !decisionComment.value.trim()) { notify('请填写退回原因，便于后续修改。', true); return }
  busy.value = true
  try {
    applyDetail(await request<RunDetail>(`/approvals/${detail.value.approval.id}/decisions`, { decision, comment: decisionComment.value.trim() }))
    notify(decision === 'APPROVE' ? '审批已接受，流程继续归档演示产物。' : '已退回，本次运行记录已保留。')
    await loadBase()
  } catch (error) { notify(errorMessage(error), true); await refreshDetail() }
  finally { busy.value = false }
}
onMounted(() => {
  readHash(); void loadBase(true)
  window.addEventListener('hashchange', readHash)
  refreshTimer = window.setInterval(() => { void loadBase() }, 12000)
})
onBeforeUnmount(() => {
  stopStream(); window.clearInterval(refreshTimer); window.clearTimeout(noticeTimer)
  window.removeEventListener('hashchange', readHash)
})
</script>

<template>
  <div class="app-shell" :class="{ 'assistant-is-open': assistantOpen }">
    <aside class="sidebar">
      <a class="brand" href="#/overview" aria-label="知序研究工作台首页"><span class="brand-mark"><Icon name="book" :size="24" /></span><span>知序<span class="brand-sub">RESEARCH WORKBENCH</span></span></a>
      <div class="workspace-label"><span class="workspace-dot"></span>个人研究空间 <span class="workspace-tag">LOCAL</span></div>
      <nav aria-label="主导航">
        <a v-for="item in nav" :key="item.id" :href="`#/${item.id}`" :class="['nav-item', { active: page === item.id }]" :aria-current="page === item.id ? 'page' : undefined"><Icon :name="item.icon" /><span>{{ item.label }}</span><span v-if="item.id === 'approvals' && pendingApprovals.length" class="nav-count">{{ pendingApprovals.length }}</span></a>
      </nav>
      <div class="sidebar-note"><div class="sidebar-note-icon"><Icon name="flow" :size="18" /></div><strong>从问题，到可复现的过程</strong><p>任务、证据与每一次判断，<br />在同一个空间持续积累。</p></div>
      <div class="sidebar-bottom"><span class="avatar">研</span><div><strong>本地研究者</strong><small>单人工作空间 · V0.5</small></div><span class="online-dot" :class="{ offline: !!loadError }" :title="loadError ? '后端连接异常' : '本地模式'"></span></div>
    </aside>

    <div class="main-shell">
      <header class="topbar"><div class="breadcrumb">工作空间<Icon name="chevron" :size="14" /><span>{{ currentNav.label }}</span><template v-if="selectedRunId"><Icon name="chevron" :size="14" /><span>运行详情</span></template></div><div class="topbar-right"><span class="date-label">{{ dateLabel }}</span><span class="mode-pill">工作流 · DEMO</span><button ref="assistantToggle" class="assistant-topbar-toggle" :class="{ active: assistantOpen }" :aria-expanded="assistantOpen" aria-controls="research-assistant" @click="assistantOpen = !assistantOpen"><Icon name="chat" :size="17" /><span>研究助手</span><span v-if="assistantWorking" class="tiny-dot live" title="研究助手正在回答"></span></button></div></header>
      <main id="main-content">
        <div v-if="loadError" class="error-banner" role="alert"><Icon name="alert" /><div><strong>后端连接暂时不可用</strong><p>{{ loadError }}{{ dashboard ? ' 当前保留上次读取的数据。' : '' }}</p></div><button class="btn subtle" :disabled="refreshing" @click="loadBase(!dashboard)">重新连接</button></div>
        <div v-if="loading && !dashboard" class="loading-state" role="status"><span class="spinner"></span><h2>正在连接研究工作台</h2><p>读取项目、任务和运行状态…</p></div>
        <template v-else-if="dashboard">
          <div v-if="!selectedRunId" class="page-heading"><div><div class="eyebrow">{{ page === 'overview' ? 'YOUR RESEARCH, IN ORDER' : 'RESEARCH WORKSPACE' }}</div><h1>{{ currentNav.label }}</h1><p>{{ currentNav.subtitle }}</p></div><div class="heading-actions"><button class="icon-button" title="刷新数据" aria-label="刷新数据" :disabled="refreshing" @click="loadBase()"><Icon name="refresh" :class="{ spinning: refreshing }" /></button><button v-if="page === 'overview' || page === 'projects'" class="btn primary" @click="openModal('task')"><Icon name="plus" :size="18" />新建任务</button></div></div>

          <template v-if="page === 'overview'">
            <section class="welcome-card"><div><span class="small-label">基础工作流 · 助手界面已就绪</span><h2>把研究想法，推进为可追溯的步骤。</h2><p>建立任务，观察流程，在关键节点审阅，并保存结果。<br class="desktop-break" />研究助手界面与上下文预览已就绪；模型待配置，已预留模型 API 与 Codex CLI 适配。</p><button class="text-button" @click="navigate('projects')">进入项目与任务<Icon name="arrow" :size="17" /></button></div><div class="welcome-graphic" aria-hidden="true"><span class="orbit orbit-one"></span><span class="orbit orbit-two"></span><span class="graphic-node node-a"><Icon name="file" :size="20" /></span><span class="graphic-node node-b"><Icon name="shield" :size="23" /></span><span class="graphic-node node-c"><Icon name="check" :size="19" /></span><span class="graphic-center"><Icon name="flow" :size="34" /></span><span class="graphic-caption">RESEARCH → EVIDENCE → REVIEW</span></div></section>
            <section class="stats-grid" aria-label="工作空间统计"><article class="stat-card"><div><span>研究项目</span><Icon name="folder" :size="19" /></div><strong>{{ dashboard.projectCount.toString().padStart(2, '0') }}</strong><small>{{ dashboard.taskCount }} 个研究任务</small></article><article class="stat-card"><div><span>正在推进</span><Icon name="flow" :size="19" /></div><strong>{{ dashboard.activeRunCount.toString().padStart(2, '0') }}</strong><small>含运行中与等待审批</small></article><article class="stat-card amber"><div><span>等待你审批</span><Icon name="shield" :size="19" /></div><strong>{{ dashboard.pendingApprovalCount.toString().padStart(2, '0') }}</strong><button class="stat-link" @click="navigate('approvals')">前往审批中心 <span>↗</span></button></article><article class="stat-card"><div><span>已完成运行</span><Icon name="check" :size="19" /></div><strong>{{ dashboard.completedRunCount.toString().padStart(2, '0') }}</strong><small>过程与产物均可回溯</small></article></section>
            <div class="overview-grid"><section class="panel"><div class="panel-header"><h2>当前研究进展<span class="count-label">{{ activeRuns.length }}</span></h2><button class="text-button muted" @click="navigate('runs')">所有运行<Icon name="arrow" :size="15" /></button></div><div v-if="!activeRuns.length" class="empty-state compact"><span class="empty-icon"><Icon name="flow" :size="28" /></span><h3>还没有进行中的流程</h3><p>从一个研究任务出发，开始第一条演示流程。</p><button class="btn secondary" @click="navigate('projects')">查看研究任务</button></div><button v-for="run in activeRuns.slice(0, 4)" :key="run.id" class="run-row" @click="navigate('runs', run.id)"><span class="row-icon"><Icon name="file" /></span><span class="row-copy"><strong>{{ run.taskTitle }}</strong><small>{{ projectName(run.projectId) }} · {{ formatTime(run.createdAt) }}</small></span><span class="status" :class="run.status.toLowerCase()">{{ statusLabel(run.status) }}</span><Icon name="chevron" :size="16" /></button><div class="panel-foot"><span class="tiny-dot"></span>状态来自本地数据库，自动更新</div></section><section class="panel workflow-intro"><div class="panel-header"><h2>第一条研究闭环</h2><span class="tag">固定模板 V1</span></div><ol class="mini-workflow"><li v-for="(label, index) in ['定义研究任务', '准备演示证据', '校验输入结构', '人工审阅与判断', '归档运行产物']" :key="label"><span>{{ index + 1 }}</span><div><strong>{{ label }}</strong><small>{{ ['留下目标与输入快照', '记录来源与能力边界', '按规则检查任务规范', '接受，或说明原因退回', '保存文件、哈希与事件'][index] }}</small></div><span v-if="index === 3" class="tag amber-tag">人工关卡</span></li></ol></section></div>
            <section class="scope-note"><Icon name="shield" :size="19" /><p><strong>当前可用：</strong>任务管理、每日计划、演示工作流、审批归档、文献入库与证据检索。跨语言检索与模型回答按当前配置启用。<strong>待接入：</strong>真实研究执行器及协作。</p><button class="text-button" @click="navigate('literature')">进入文献库<Icon name="arrow" :size="15" /></button></section>
          </template>

          <template v-if="page === 'projects'">
            <div class="projects-layout"><section class="panel project-list"><div class="panel-header"><h2>研究项目</h2><button class="icon-button small" aria-label="新建项目" title="新建项目" @click="openModal('project')"><Icon name="plus" :size="18" /></button></div><button class="project-item" :class="{ selected: !activeProject }" @click="activeProject = ''"><Icon name="grid" :size="19" /><span>全部项目</span><small>{{ tasks.length }}</small></button><button v-for="project in projects" :key="project.id" class="project-item" :class="{ selected: activeProject === project.id }" @click="activeProject = project.id"><Icon name="folder" :size="19" /><span>{{ project.name }}</span><small>{{ tasks.filter(task => task.projectId === project.id).length }}</small></button><div v-if="!projects.length" class="empty-state compact"><p>先为你的研究建立一个项目。</p><button class="btn secondary" @click="openModal('project')">创建项目</button></div><div class="project-list-foot"><button class="text-button" @click="openModal('project')"><Icon name="plus" :size="16" />新建项目</button></div></section><section class="task-area"><div class="task-toolbar"><div><h2>{{ activeProject ? projectName(activeProject) : '全部研究任务' }}</h2><p v-if="activeProject" class="project-description">{{ projects.find(project => project.id === activeProject)?.description || '这个项目暂时没有描述。' }}</p></div><label class="search-field"><span class="sr-only">搜索任务</span><input v-model="taskSearch" type="search" placeholder="搜索任务名称或研究目标…" /></label></div><div v-if="!visibleTasks.length" class="panel empty-state"><span class="empty-icon"><Icon name="folder" :size="30" /></span><h3>{{ taskSearch ? '没有匹配的任务' : '为这个项目添加第一个任务' }}</h3><p>{{ taskSearch ? '尝试更简短的关键词，或切换项目。' : '写清目标与范围，后续每次执行都会保留任务快照。' }}</p><button v-if="!taskSearch" class="btn primary" @click="openModal('task')"><Icon name="plus" :size="17" />新建任务</button></div><article v-for="task in visibleTasks" :key="task.id" class="panel task-card"><div class="task-card-top"><span class="tag">{{ projectName(task.projectId) }}</span><span class="task-date">{{ formatTime(task.createdAt) }}</span></div><h3>{{ task.title }}</h3><p class="task-objective">{{ task.objective }}</p><div class="task-card-foot"><span v-if="latestRun(task.id)" class="status" :class="latestRun(task.id)!.status.toLowerCase()">{{ statusLabel(latestRun(task.id)!.status) }}</span><span v-else class="muted-label">尚未执行</span><div><button v-if="latestRun(task.id)" class="btn subtle small-btn" @click="navigate('runs', latestRun(task.id)!.id)">查看上次运行</button><button class="btn secondary small-btn" :disabled="busy || (!!latestRun(task.id) && !terminal(latestRun(task.id)!.status))" @click="startRun(task)"><Icon name="play" :size="14" />启动演示</button></div></div></article></section></div>
          </template>

          <PlanPage v-if="page === 'planning'" :tasks="tasks" />
          <LiteraturePage v-if="page === 'literature'" :projects="projects" />
          <template v-if="page === 'runs' && !selectedRunId">
            <section class="panel"><div class="panel-header"><h2>全部运行<span class="count-label">{{ runs.length }}</span></h2><label class="filter-label">状态<select v-model="runFilter"><option value="ALL">全部状态</option><option value="ACTIVE">进行中</option><option value="WAITING_APPROVAL">等待审批</option><option value="COMPLETED">已完成</option><option value="REJECTED">已退回</option><option value="CANCELLED">已取消</option><option value="FAILED">失败</option></select></label></div><div v-if="!visibleRuns.length" class="empty-state"><span class="empty-icon"><Icon name="flow" :size="30" /></span><h3>这里还没有运行记录</h3><p>启动研究任务后，每次运行都会独立保存。</p><button class="btn secondary" @click="navigate('projects')">前往项目与任务</button></div><div v-else class="table-scroll"><table><thead><tr><th>研究任务</th><th>所属项目</th><th>状态</th><th>创建时间</th><th><span class="sr-only">操作</span></th></tr></thead><tbody><tr v-for="run in visibleRuns" :key="run.id"><td><button class="table-title" @click="navigate('runs', run.id)">{{ run.taskTitle }}</button><small class="record-id">{{ run.id.slice(0, 8) }} · {{ run.executionMode }}</small></td><td>{{ projectName(run.projectId) }}</td><td><span class="status" :class="run.status.toLowerCase()">{{ statusLabel(run.status) }}</span></td><td class="nowrap muted-label">{{ formatTime(run.createdAt) }}</td><td><button class="icon-button" :aria-label="`查看 ${run.taskTitle} 的运行详情`" @click="navigate('runs', run.id)"><Icon name="arrow" :size="18" /></button></td></tr></tbody></table></div></section>
          </template>

          <template v-if="page === 'runs' && selectedRunId">
            <button class="back-button" @click="navigate('runs')"><span>←</span>返回运行记录</button>
            <div v-if="runLoading" class="loading-state" role="status"><span class="spinner"></span><p>正在读取流程状态…</p></div>
            <div v-if="runError" class="error-banner" role="alert"><Icon name="alert" /><p>{{ runError }}</p><button class="btn subtle" @click="detail ? refreshDetail() : loadRun(selectedRunId)">重试</button></div>
            <template v-if="detail"><div class="page-heading run-heading"><div><div class="eyebrow">{{ projectName(detail.projectId) }} / {{ detail.id.slice(0, 8) }}</div><h1>{{ detail.taskTitle }}</h1><p>创建于 {{ formatTime(detail.createdAt) }} · 任务快照与执行记录独立保存</p></div><div class="heading-actions"><span class="status large-status" :class="detail.status.toLowerCase()">{{ statusLabel(detail.status) }}</span><button v-if="!terminal(detail.status)" class="btn secondary small-btn" :disabled="busy" @click="cancelRun"><Icon name="stop" :size="14" />取消运行</button></div></div><div class="demo-notice"><Icon name="alert" :size="17" /><span>DEMO / research_only · 本次工作流未读取文献库，也未执行真实研究计算；研究助手的模型连接可单独配置。</span></div>
            <section class="panel workflow-panel"><div class="panel-header"><h2>研究工作流<span class="tag">模板 V1</span></h2><span class="connection-state"><span :class="['tiny-dot', { live: connection === 'live' }]"></span>{{ connection === 'live' ? '实时连接' : connection === 'polling' ? '每 5 秒刷新' : connection === 'connecting' ? '连接实时事件…' : '运行已结束' }}<span class="progress-count">{{ completedNodes }} / {{ orderedNodes.length }} 完成</span></span></div><div class="workflow-nodes"><button v-for="(node, index) in orderedNodes" :key="node.id" :class="['workflow-node', node.status.toLowerCase(), { 'is-selected': selectedNode?.id === node.id }]" :aria-pressed="selectedNode?.id === node.id" @click="chosenNode = node.id"><span class="node-circle"><Icon v-if="node.status === 'SUCCEEDED'" name="check" :size="21" /><Icon v-else-if="node.status === 'WAITING_APPROVAL'" name="shield" :size="21" /><span v-else>{{ String(index + 1).padStart(2, '0') }}</span></span><strong>{{ node.label }}</strong><small>{{ statusLabel(node.status) }}</small></button></div><div v-if="selectedNode" class="node-inspector"><div><strong>{{ selectedNode.label }}</strong><span class="status" :class="selectedNode.status.toLowerCase()">{{ statusLabel(selectedNode.status) }}</span></div><p>{{ selectedNode.detail || '本节点尚未开始，等待前置步骤完成。' }}</p><small>开始 {{ formatTime(selectedNode.startedAt) }}<span>完成 {{ formatTime(selectedNode.finishedAt) }}</span></small></div></section>
            <section v-if="detail.approval?.status === 'PENDING'" class="approval-callout"><div class="approval-callout-title"><span class="approval-symbol"><Icon name="shield" :size="23" /></span><div><h2>流程已暂停，等待你的判断</h2><p>请检查任务目标、结构校验和事件记录，再决定是否进入归档步骤。</p></div></div><label for="decision-comment">审批意见<span>退回时必填</span></label><textarea id="decision-comment" v-model="decisionComment" maxlength="2000" rows="3" placeholder="记录判断依据、需补充的证据或退回原因…"></textarea><div class="approval-actions"><span>审批仅针对本次演示运行，不代表研究结论已验证。</span><button class="btn reject" :disabled="busy" @click="decide('REJECT')">退回并结束</button><button class="btn primary" :disabled="busy" @click="decide('APPROVE')"><Icon name="check" :size="17" />接受并继续</button></div></section>
            <div class="detail-grid"><section class="panel"><div class="panel-header"><h2>执行事件<span class="count-label">{{ detail.events.length }}</span></h2><Icon name="clock" :size="18" /></div><ol v-if="detail.events.length" class="event-timeline"><li v-for="event in [...detail.events].reverse()" :key="event.id"><span class="event-dot"></span><div><p>{{ event.message }}</p><small>{{ formatTime(event.createdAt) }}<span>{{ event.type }}</span></small></div></li></ol><div v-else class="empty-state compact"><p>等待第一个执行事件。</p></div></section><div class="detail-side"><section class="panel"><div class="panel-header"><h2>研究任务快照</h2><Icon name="file" :size="18" /></div><div class="snapshot-content"><span class="field-label">研究目标</span><p>{{ detail.objective }}</p><span class="field-label">执行模式</span><p><span class="tag">{{ detail.executionMode }}</span><span class="tag">research_only</span></p></div></section><section class="panel"><div class="panel-header"><h2>归档产物<span class="count-label">{{ detail.artifacts.length }}</span></h2><Icon name="download" :size="18" /></div><div v-if="!detail.artifacts.length" class="empty-state compact artifact-empty"><Icon name="file" :size="26" /><p>流程归档后，产物将在这里出现。</p></div><article v-for="artifact in detail.artifacts" :key="artifact.id" class="artifact-item"><div class="artifact-name"><Icon name="file" :size="18" /><strong>{{ artifact.name }}</strong><a v-if="safeDownload(artifact.downloadUrl)" :href="safeDownload(artifact.downloadUrl)" download class="icon-button small" :aria-label="`下载 ${artifact.name}`"><Icon name="download" :size="17" /></a></div><small>{{ formatBytes(artifact.sizeBytes) }} · {{ artifact.mediaType }}</small><details><summary>查看文件哈希</summary><code>{{ artifact.sha256 }}</code></details></article></section><section v-if="detail.approval && detail.approval.status !== 'PENDING'" class="panel"><div class="panel-header"><h2>审批记录</h2><span class="status" :class="detail.approval.status.toLowerCase()">{{ statusLabel(detail.approval.status) }}</span></div><div class="snapshot-content"><p>{{ detail.approval.comment || '未附加审批意见。' }}</p><small>{{ formatTime(detail.approval.decidedAt) }}</small></div></section></div></div>
            </template>
          </template>

          <template v-if="page === 'approvals'"><section class="panel"><div class="panel-header"><h2>审批队列<span class="count-label">{{ pendingApprovals.length }} 待处理</span></h2><label class="filter-label">显示<select v-model="approvalFilter"><option value="PENDING">等待审批</option><option value="ALL">全部记录</option><option value="APPROVED">已接受</option><option value="REJECTED">已退回</option><option value="CANCELLED">已取消</option></select></label></div><div v-if="!visibleApprovals.length" class="empty-state"><span class="empty-icon"><Icon name="shield" :size="30" /></span><h3>{{ approvalFilter === 'PENDING' ? '暂时没有等待审批的任务' : '没有符合条件的审批记录' }}</h3><p>流程进入人工关卡后，会在这里等待你的判断。</p></div><article v-for="approval in visibleApprovals" :key="approval.id" class="approval-row"><span class="row-icon amber-icon"><Icon name="shield" /></span><div class="row-copy"><h3>{{ approval.taskTitle }}</h3><p>{{ approval.comment || '检查演示任务规范与过程记录，决定是否继续归档。' }}</p><small>提交于 {{ formatTime(approval.createdAt) }}</small></div><span class="status" :class="approval.status.toLowerCase()">{{ statusLabel(approval.status) }}</span><button class="btn secondary small-btn" @click="navigate('runs', approval.runId)">{{ approval.status === 'PENDING' ? '查看并审批' : '查看记录' }}<Icon name="arrow" :size="15" /></button></article></section></template>

          <template v-if="page === 'roadmap'"><div class="roadmap-intro"><span class="tag green-tag">V0.5 · 双语文献检索</span><h2>研究过程可追溯，模型能力按需接入。</h2><p>任务、审批和归档形成基础闭环，助手侧栏、每日计划和文献证据检索已可使用。模型适配可选择本地或外部 API；文献语义检索与双语回答按配置启用。下列状态来自服务端能力清单；五节点演示流程不产生真实研究结论。</p></div><section class="capability-grid"><article v-for="capability in system?.capabilities || []" :key="capability.key" class="panel capability-card" :class="{ planned: capability.status === 'PLANNED' }"><div><span class="capability-icon"><Icon :name="capability.status === 'AVAILABLE' ? 'check' : 'clock'" :size="22" /></span><span class="status" :class="capability.status.toLowerCase()">{{ statusLabel(capability.status) }}</span></div><h3>{{ capability.label }}</h3><p>{{ capability.description }}</p></article></section><section class="panel system-panel"><div class="panel-header"><h2>本地工作空间</h2><Icon name="server" :size="18" /></div><dl><div><dt>平台版本</dt><dd>{{ system?.version || '—' }}</dd></div><div><dt>当前数据库</dt><dd>{{ system?.database || '—' }}</dd></div><div><dt>执行方式</dt><dd>{{ system?.executionMode || 'DEMO' }} · 确定性演示流程</dd></div><div><dt>工作流模板</dt><dd>五节点固定流程 V1</dd></div></dl></section></template>
        </template>
        <div v-else-if="!loading" class="empty-state connection-empty"><span class="empty-icon"><Icon name="server" :size="32" /></span><h1>等待本地服务连接</h1><p>工作台需要后端与数据库共同运行。启动服务后，点击“重新连接”。</p></div>
        <footer class="page-footer"><span>知序 · 让研究有迹可循</span><span>LOCAL FIRST <span>·</span> RESEARCH ONLY <span>·</span> V0.5</span></footer>
      </main>
    </div>
    <AssistantPanel :open="assistantOpen" :context="assistantContext" :context-label="assistantContextLabel" @close="closeAssistant" @draft="openTaskDraft" @working="assistantWorking = $event" />
    <div v-if="notification" class="toast" :class="{ 'toast-error': notification.error }" :role="notification.error ? 'alert' : 'status'"><Icon :name="notification.error ? 'alert' : 'check'" :size="19" /><span>{{ notification.text }}</span><button class="icon-button small" aria-label="关闭提示" @click="notification = null"><Icon name="close" :size="16" /></button></div>
    <dialog ref="modalRef" class="form-dialog" aria-labelledby="dialog-title" @cancel="busy ? $event.preventDefault() : (modal = '')" @close="!modalRef?.open && (modal = '')">
      <form @submit.prevent="saveForm"><div class="dialog-heading"><div><span class="eyebrow">START WITH A QUESTION</span><h2 id="dialog-title">{{ modal === 'project' ? '新建研究项目' : '新建研究任务' }}</h2></div><button type="button" class="icon-button" :disabled="busy" aria-label="关闭表单" @click="closeModal"><Icon name="close" /></button></div><p class="dialog-description">{{ modal === 'project' ? '用一个项目组织同一方向的研究、任务与结果。' : '定义研究目标。启动运行时，系统会保存当前任务的独立快照。' }}</p><div v-if="formError" class="form-error" role="alert">{{ formError }}</div><template v-if="modal === 'project'"><label for="project-name">项目名称<span>必填</span></label><input id="project-name" v-model="projectForm.name" required maxlength="120" placeholder="例如：ETF 因子研究" autofocus /><label for="project-description">项目说明</label><textarea id="project-description" v-model="projectForm.description" rows="4" maxlength="2000" placeholder="这个项目希望回答哪些问题？研究范围是什么？"></textarea></template><template v-else-if="modal === 'task'"><label for="task-project">所属项目<span>必填</span></label><select id="task-project" v-model="taskForm.projectId" required><option v-for="project in projects" :key="project.id" :value="project.id">{{ project.name }}</option></select><label for="task-title">任务名称<span>必填</span></label><input id="task-title" v-model="taskForm.title" required maxlength="180" placeholder="例如：梳理一篇研报的复现条件" /><label for="task-objective">研究目标<span>必填</span></label><textarea id="task-objective" v-model="taskForm.objective" required maxlength="4000" rows="5" placeholder="说明需要研究的问题、输入材料、预期产物和验收条件…"></textarea><div class="form-hint"><Icon name="alert" :size="16" />此任务运行使用 DEMO 流程，尚未解析真实文献或执行研究计算。</div></template><div class="dialog-actions"><button type="button" class="btn secondary" :disabled="busy" @click="closeModal">取消</button><button class="btn primary" type="submit" :disabled="busy"><span v-if="busy" class="spinner small-spinner"></span>{{ busy ? '正在保存…' : modal === 'project' ? '创建项目' : '保存任务' }}</button></div></form>
    </dialog>
  </div>
</template>
