<script setup lang="ts">
import { ref, watch } from 'vue'
import Icon from './Icon.vue'
import EvidenceCards from './EvidenceCards.vue'
import type { EvidenceSource } from './EvidenceCards.vue'
import type { Project } from './api'
import { answerLiterature, indexLiterature, listLiterature, safeLiteratureSource, searchLiterature, translateSelection, uploadLiterature } from './literature-api'
import type { LiteratureAnswer, LiteratureDocument, LiteratureHit, LiteratureSearchResult, LiteratureTranslation } from './literature-api'
import './literature.css'

const props = defineProps<{ projects: Project[] }>()
const projectId = ref('')
const documentId = ref('')
const documents = ref<LiteratureDocument[]>([])
const title = ref('')
const file = ref<File | null>(null)
const query = ref('')
const searchResult = ref<LiteratureSearchResult | null>(null)
const answer = ref<LiteratureAnswer | null>(null)
const loading = ref(false)
const searching = ref(false)
const translatingChunkId = ref('')
const translations = ref<Record<string, LiteratureTranslation>>({})
const pendingSource = ref<EvidenceSource | null>(null)
const asking = ref(false)
const uploading = ref(false)
const indexing = ref(false)
const error = ref('')
const notice = ref('')
const message = (value: unknown) => value instanceof Error ? value.message : '操作未完成'
const bytes = (value: number) => value < 1024 * 1024 ? `${(value / 1024).toFixed(1)} KB` : `${(value / 1024 / 1024).toFixed(1)} MB`
const date = (value: string) => new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium' }).format(new Date(value))
const semanticStatusLabel = (value: string) => ({ READY: '已完成', PARTIAL: '部分完成', INDEX_REQUIRED: '待建立',
  FAILED: '语义检索暂不可用', UNCONFIGURED: '未配置向量模型' }[value] || value)
let requestGeneration = 0
let sourceToken = 0
let selectionSnapshot = ''
const stillCurrent = (generation: number, project: string, document: string, question: string) =>
  generation === requestGeneration && projectId.value === project && documentId.value === document && query.value.trim() === question

watch(() => props.projects, projects => {
  if (!projects.some(p => p.id === projectId.value)) projectId.value = projects[0]?.id || ''
}, { immediate: true })
watch(projectId, async id => {
  requestGeneration++; translatingChunkId.value = ''; translations.value = {}; pendingSource.value = null
  searching.value = false; asking.value = false
  documentId.value = ''; documents.value = []; searchResult.value = null; answer.value = null; error.value = ''; notice.value = ''
  if (!id) return
  loading.value = true
  try { const data = await listLiterature(id); if (projectId.value === id) documents.value = data }
  catch (cause) { if (projectId.value === id) error.value = message(cause) }
  finally { loading.value = false }
}, { immediate: true })
watch(documentId, () => { requestGeneration++; translatingChunkId.value = ''; translations.value = {}; searching.value = false; asking.value = false; searchResult.value = null; answer.value = null })

function captureSelection() { selectionSnapshot = window.getSelection()?.toString() || '' }
function selectedText() { const value = (selectionSnapshot || window.getSelection()?.toString() || '').trim(); selectionSnapshot = ''; return value }
function belongsToHit(hit: LiteratureHit, quote: string) {
  return hit.excerpt.replace(/\s+/g, ' ').includes(quote.replace(/\s+/g, ' '))
}
function saveEvidenceFrom(hit: LiteratureHit) {
  const selected = selectedText()
  if (!selected || selected.length > 1200 || !belongsToHit(hit, selected)) {
    notice.value = '请先在这条命中的原文中选取不超过 1200 字的句段，再保存证据卡。'
    return
  }
  notice.value = ''
  pendingSource.value = { hit, quote: selected, token: ++sourceToken }
}

async function translateSelected(hit: LiteratureHit) {
  const selected = selectedText()
  if (!selected || selected.length > 600 || !belongsToHit(hit, selected)) {
    notice.value = '请先在这条命中的原文中选取不超过 600 字的句段，再点击翻译。'
    return
  }
  const selectedProject = projectId.value; const generation = requestGeneration
  notice.value = ''; translatingChunkId.value = hit.chunkId
  try {
    const result = await translateSelection(selectedProject, hit.chunkId, selected)
    if (selectedProject !== projectId.value || generation !== requestGeneration) return
    translations.value = { ...translations.value, [hit.chunkId]: result }
    if (!result.translation) notice.value = result.status === 'UNCONFIGURED' ? '尚未配置翻译模型；原文仍可核对。' : '这段译文未通过校验，已保留原文。'
  } catch (cause) { if (selectedProject === projectId.value && generation === requestGeneration) notice.value = message(cause) }
  finally { if (generation === requestGeneration) translatingChunkId.value = '' }
}

function chooseFile(event: Event) {
  file.value = (event.target as HTMLInputElement).files?.[0] || null
  if (file.value && !title.value.trim()) title.value = file.value.name.replace(/\.(pdf|txt|md)$/i, '')
}

async function upload() {
  if (!file.value || !projectId.value || !title.value.trim()) return
  error.value = ''; notice.value = ''; uploading.value = true
  try {
    await uploadLiterature(projectId.value, title.value.trim(), file.value)
    documents.value = await listLiterature(projectId.value)
    notice.value = '文献已入库。跨语言检索前请建立索引；新增文献也需要补建索引。'
    file.value = null; title.value = ''
    const input = document.getElementById('literature-file') as HTMLInputElement | null
    if (input) input.value = ''
  } catch (cause) { error.value = message(cause) }
  finally { uploading.value = false }
}

async function search() {
  if (!projectId.value || !query.value.trim()) return
  const generation = ++requestGeneration
  error.value = ''; answer.value = null; searchResult.value = null; translations.value = {}; searching.value = true; translatingChunkId.value = ''
  const selectedProject = projectId.value; const selectedDocument = documentId.value; const question = query.value.trim()
  try {
    const result = await searchLiterature(selectedProject, question, selectedDocument, false)
    if (!stillCurrent(generation, selectedProject, selectedDocument, question)) return
    searchResult.value = result
  } catch (cause) { if (stillCurrent(generation, selectedProject, selectedDocument, question)) error.value = message(cause) }
  finally { if (generation === requestGeneration) searching.value = false }
}

async function buildIndex() {
  if (!projectId.value || !documents.value.length) return
  error.value = ''; notice.value = ''; indexing.value = true
  const selectedProject = projectId.value; const selectedDocument = documentId.value
  try {
    const result = await indexLiterature(selectedProject, selectedDocument)
    if (projectId.value === selectedProject && documentId.value === selectedDocument) {
      notice.value = `跨语言索引：${result.indexedChunks}/${result.totalChunks} 段已完成（本次新增 ${result.newlyIndexed} 段）。${result.indexedChunks < result.totalChunks ? '点击按钮继续建立剩余索引。' : '现在可以用中英文交叉检索。'}`
      searchResult.value = null; answer.value = null
    }
  } catch (cause) { if (projectId.value === selectedProject) error.value = message(cause) }
  finally { indexing.value = false }
}

async function ask() {
  if (!projectId.value || !query.value.trim()) return
  const generation = ++requestGeneration
  error.value = ''; answer.value = null; asking.value = true; translations.value = {}; translatingChunkId.value = ''
  const selectedProject = projectId.value; const selectedDocument = documentId.value; const question = query.value.trim()
  try {
    const result = await answerLiterature(selectedProject, question, selectedDocument)
    if (stillCurrent(generation, selectedProject, selectedDocument, question)) {
      answer.value = result
      searchResult.value = { query: question, retrievalVersion: result.retrievalVersion, hits: result.citations,
        semanticStatus: result.semanticStatus, translationStatus: result.translationStatus,
        translationModel: result.translationModel }
    }
  } catch (cause) { if (stillCurrent(generation, selectedProject, selectedDocument, question)) error.value = message(cause) }
  finally { if (generation === requestGeneration) asking.value = false }
}
</script>

<template>
  <div class="literature-page">
    <div class="literature-intro"><span class="tag green-tag">文献证据</span><p>先定位原文，再把可核对的句段保存为复现证据卡。跨语言检索需建立向量索引；模型译文与回答按需使用，研究判断以原文件和人工核对为准。</p></div>
    <div v-if="error" class="form-error" role="alert">{{ error }}</div>
    <div v-if="notice" class="literature-notice" role="status">{{ notice }}</div>
    <section class="panel literature-panel">
      <div class="panel-header"><h2>文献库</h2><span class="tag">{{ documents.length }} 篇</span></div>
      <div class="literature-body">
        <label for="literature-project">所属研究项目</label>
        <select id="literature-project" v-model="projectId" :disabled="!projects.length"><option v-for="project in projects" :key="project.id" :value="project.id">{{ project.name }}</option></select>
        <p v-if="!projects.length" class="literature-muted">请先到“项目与任务”创建研究项目。</p>
        <form v-else class="literature-upload" @submit.prevent="upload">
          <label for="literature-file">导入文献 <small>PDF、UTF-8 TXT 或 Markdown，最多 20 MB</small></label>
          <input id="literature-file" type="file" accept=".pdf,.txt,.md,application/pdf,text/plain,text/markdown" required @change="chooseFile" />
          <label for="literature-title">文献标题</label>
          <input id="literature-title" v-model="title" maxlength="240" required placeholder="例如：点时因子评价方法" />
          <button class="btn primary" type="submit" :disabled="uploading || !file || !title.trim()"><Icon name="plus" :size="16" />{{ uploading ? '正在解析…' : '导入文献' }}</button>
        </form>
      </div>
      <div v-if="loading" class="literature-muted literature-body">正在读取文献…</div>
      <div v-else-if="!documents.length" class="literature-muted literature-body">当前项目还没有文献。</div>
      <article v-for="item in documents" :key="item.id" class="literature-document">
        <div><Icon name="file" :size="19" /><div><strong>{{ item.title }}</strong><small>{{ item.fileName }} · {{ item.mediaType === 'application/pdf' ? `${item.pageCount} 页` : '文本文件' }} · {{ item.chunkCount }} 段 · {{ bytes(item.sizeBytes) }} · {{ date(item.createdAt) }}</small></div></div>
        <a :href="`/api/literature/documents/${item.id}/file`" target="_blank" rel="noopener noreferrer">打开原文件</a>
        <details><summary>文件 SHA-256</summary><code>{{ item.sha256 }}</code></details>
      </article>
    </section>

    <section class="panel literature-panel">
      <div class="panel-header"><h2>检索原文</h2><span class="tag">项目内检索</span></div>
      <div class="literature-body">
        <label for="literature-document-scope">检索范围</label>
        <select id="literature-document-scope" v-model="documentId" :disabled="!documents.length"><option value="">当前项目的全部文献</option><option v-for="item in documents" :key="item.id" :value="item.id">{{ item.title }} · {{ item.fileName }}</option></select>
        <label for="literature-query">研究问题或关键词</label>
        <textarea id="literature-query" v-model="query" rows="3" maxlength="1000" placeholder="例如：这篇文献如何处理未来信息？"></textarea>
        <div class="literature-actions"><button class="btn secondary" :disabled="indexing || searching || asking || !documents.length" @click="buildIndex"><Icon name="book" :size="16" />{{ indexing ? '正在建立索引…' : '建立跨语言索引' }}</button><button class="btn primary" :disabled="indexing || searching || asking || !projectId || !query.trim()" @click="search"><Icon name="book" :size="16" />{{ searching ? '正在检索…' : '检索证据' }}</button></div>
        <details class="literature-experimental"><summary>实验性双语回答</summary><p class="literature-muted">回答可能改写原文术语；引用页正确也不代表每句得到支持。请逐句核对。回答会把命中原文发送给配置的模型。</p><button class="btn secondary" :disabled="indexing || asking || searching || !projectId || !query.trim()" @click="ask"><Icon name="chat" :size="16" />{{ asking ? '正在回答…' : '生成待核对回答' }}</button></details>
        <p class="literature-muted">建立索引会把原文发送给配置的向量模型；检索会发送问题。原文检索结果会立即显示，不自动翻译全部命中。</p>
      </div>
      <div v-if="answer" class="literature-answer">
        <strong>{{ answer.status === 'NO_EVIDENCE' ? '证据不足或候选片段不可靠' : `实验性模型回答 · ${answer.translationModel} · 待逐句核对` }}</strong>
        <div class="literature-parallel"><div><small>中文回答</small><p>{{ answer.answerZh }}</p></div><div><small>English answer</small><p>{{ answer.answerEn }}</p></div></div>
      </div>
      <div v-if="searchResult" class="literature-results">
        <div class="literature-results-title"><strong>检索原文</strong><small>{{ searchResult.retrievalVersion }} · {{ searchResult.hits.length }} 条</small></div>
        <p class="literature-muted">跨语言索引：{{ semanticStatusLabel(searchResult.semanticStatus) }}<template v-if="searchResult.totalChunks !== undefined">（{{ searchResult.indexedChunks }}/{{ searchResult.totalChunks }} 段）</template>。选取原文句段后，可单独翻译或保存为证据卡。</p>
        <p v-if="searchResult.totalChunks !== undefined && searchResult.indexedChunks !== undefined && searchResult.indexedChunks < searchResult.totalChunks" class="literature-muted">当前范围还有 {{ searchResult.totalChunks - searchResult.indexedChunks }} 段未建跨语言索引，另一种语言的提问可能漏掉这些文献。</p>
        <p v-if="!searchResult.hits.length" class="literature-muted">没有找到相关原文片段。若使用另一种语言提问，请先为文献建立跨语言索引。</p>
        <article v-for="(hit, index) in searchResult.hits" :key="hit.chunkId" class="literature-hit">
          <div><strong>[C{{ index + 1 }}] {{ hit.title }} · {{ hit.fileName.toLowerCase().endsWith('.pdf') ? `PDF 物理第 ${hit.pageNumber} 页` : '文本文件' }}</strong><a v-if="safeLiteratureSource(hit.sourceUrl)" :href="safeLiteratureSource(hit.sourceUrl)" target="_blank" rel="noopener noreferrer">打开出处 <Icon name="arrow" :size="14" /></a></div>
          <div class="literature-original"><small>原文 · {{ hit.originalLanguage === 'zh' ? '中文' : 'English' }}</small><p>{{ hit.excerpt }}</p></div>
          <div class="literature-actions literature-hit-actions">
            <button class="btn secondary" :disabled="!!translatingChunkId" @mousedown="captureSelection" @click="translateSelected(hit)">{{ translatingChunkId === hit.chunkId ? '正在翻译…' : '翻译选中句段' }}</button>
            <button class="btn secondary" @mousedown="captureSelection" @click="saveEvidenceFrom(hit)">保存选中句段为证据卡</button>
          </div>
          <div v-if="translations[hit.chunkId]" class="literature-translation">
            <small>按需模型译文 · {{ translations[hit.chunkId].model }} · 待核对</small>
            <div class="literature-parallel"><div><small>所选原文</small><p>{{ translations[hit.chunkId].sourceText }}</p></div><div><small>译文</small><p>{{ translations[hit.chunkId].translation || '译文暂不可用，请以原文为准。' }}</p></div></div>
          </div>
          <details><summary>证据标识</summary><code>文件 {{ hit.documentSha256 }}<br />片段 {{ hit.chunkSha256 }}</code></details>
        </article>
      </div>
    </section>
    <EvidenceCards :project-id="projectId" :pending-source="pendingSource" />
  </div>
</template>
