<script setup lang="ts">
import { ref, watch } from 'vue'
import Icon from './Icon.vue'
import type { Project } from './api'
import { answerLiterature, listLiterature, safeLiteratureSource, searchLiterature, uploadLiterature } from './literature-api'
import type { LiteratureAnswer, LiteratureDocument, LiteratureSearchResult } from './literature-api'
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
const asking = ref(false)
const uploading = ref(false)
const error = ref('')
const notice = ref('')
const message = (value: unknown) => value instanceof Error ? value.message : '操作未完成'
const bytes = (value: number) => value < 1024 * 1024 ? `${(value / 1024).toFixed(1)} KB` : `${(value / 1024 / 1024).toFixed(1)} MB`
const date = (value: string) => new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium' }).format(new Date(value))

watch(() => props.projects, projects => {
  if (!projects.some(p => p.id === projectId.value)) projectId.value = projects[0]?.id || ''
}, { immediate: true })
watch(projectId, async id => {
  documentId.value = ''; documents.value = []; searchResult.value = null; answer.value = null; error.value = ''; notice.value = ''
  if (!id) return
  loading.value = true
  try { const data = await listLiterature(id); if (projectId.value === id) documents.value = data }
  catch (cause) { if (projectId.value === id) error.value = message(cause) }
  finally { loading.value = false }
}, { immediate: true })
watch(documentId, () => { searchResult.value = null; answer.value = null })

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
    notice.value = '文献已入库，可按项目检索。'
    file.value = null; title.value = ''
    const input = document.getElementById('literature-file') as HTMLInputElement | null
    if (input) input.value = ''
  } catch (cause) { error.value = message(cause) }
  finally { uploading.value = false }
}

async function search() {
  if (!projectId.value || !query.value.trim()) return
  error.value = ''; answer.value = null; searchResult.value = null; searching.value = true
  const selectedProject = projectId.value; const selectedDocument = documentId.value; const question = query.value.trim()
  try {
    const result = await searchLiterature(selectedProject, question, selectedDocument)
    if (projectId.value === selectedProject && documentId.value === selectedDocument && query.value.trim() === question) searchResult.value = result
  } catch (cause) { if (projectId.value === selectedProject && documentId.value === selectedDocument) error.value = message(cause) }
  finally { searching.value = false }
}

async function ask() {
  if (!projectId.value || !query.value.trim()) return
  error.value = ''; answer.value = null; asking.value = true
  const selectedProject = projectId.value; const selectedDocument = documentId.value; const question = query.value.trim()
  try {
    const result = await answerLiterature(selectedProject, question, selectedDocument)
    if (projectId.value === selectedProject && documentId.value === selectedDocument && query.value.trim() === question) {
      answer.value = result
      searchResult.value = { query: question, retrievalVersion: result.retrievalVersion, hits: result.citations }
    }
  } catch (cause) { if (projectId.value === selectedProject && documentId.value === selectedDocument) error.value = message(cause) }
  finally { asking.value = false }
}
</script>

<template>
  <div class="literature-page">
    <div class="literature-intro"><span class="tag green-tag">文献证据</span><p>原文件按项目保存，检索结果可回到对应页并核对文件哈希。模型回答仅在服务端配置模型后可用，回答仍需人工核对引用。</p></div>
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
      <div class="panel-header"><h2>检索与证据问答</h2><span class="tag">项目内检索</span></div>
      <div class="literature-body">
        <label for="literature-document-scope">检索范围</label>
        <select id="literature-document-scope" v-model="documentId" :disabled="!documents.length"><option value="">当前项目的全部文献</option><option v-for="item in documents" :key="item.id" :value="item.id">{{ item.title }} · {{ item.fileName }}</option></select>
        <label for="literature-query">研究问题或关键词</label>
        <textarea id="literature-query" v-model="query" rows="3" maxlength="1000" placeholder="例如：这篇文献如何处理未来信息？"></textarea>
        <div class="literature-actions"><button class="btn secondary" :disabled="searching || asking || !projectId || !query.trim()" @click="search"><Icon name="book" :size="16" />{{ searching ? '正在检索…' : '检索证据' }}</button><button class="btn primary" :disabled="asking || searching || !projectId || !query.trim()" @click="ask"><Icon name="chat" :size="16" />{{ asking ? '正在回答…' : '基于证据回答' }}</button></div>
        <p class="literature-muted">检索无需模型；“基于证据回答”会将问题和命中的原文片段发送给已配置的模型。</p>
      </div>
      <div v-if="answer" class="literature-answer"><strong>{{ answer.status === 'NO_EVIDENCE' ? '证据不足' : '模型回答 · 待核对' }}</strong><p>{{ answer.answer }}</p></div>
      <div v-if="searchResult" class="literature-results"><div class="literature-results-title"><strong>检索证据</strong><small>{{ searchResult.retrievalVersion }} · {{ searchResult.hits.length }} 条</small></div><p v-if="!searchResult.hits.length" class="literature-muted">没有找到相关原文片段。</p><article v-for="(hit, index) in searchResult.hits" :key="hit.chunkId" class="literature-hit"><div><strong>[C{{ index + 1 }}] {{ hit.title }} · {{ hit.fileName.toLowerCase().endsWith('.pdf') ? `第 ${hit.pageNumber} 页` : '文本文件' }}</strong><a v-if="safeLiteratureSource(hit.sourceUrl)" :href="safeLiteratureSource(hit.sourceUrl)" target="_blank" rel="noopener noreferrer">打开出处 <Icon name="arrow" :size="14" /></a></div><p>{{ hit.excerpt }}</p><details><summary>证据标识</summary><code>文件 {{ hit.documentSha256 }}<br />片段 {{ hit.chunkSha256 }}</code></details></article></div>
    </section>
  </div>
</template>
