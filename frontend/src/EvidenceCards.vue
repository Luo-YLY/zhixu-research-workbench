<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import Icon from './Icon.vue'
import { createEvidenceCard, listEvidenceCards, reviewEvidenceCard, safeLiteratureSource, updateEvidenceCard } from './literature-api'
import type { EvidenceCard, EvidenceCardFields, LiteratureHit } from './literature-api'

export interface EvidenceSource { hit: LiteratureHit; quote: string; token: number }
const props = defineProps<{ projectId: string; pendingSource: EvidenceSource | null }>()
const cards = ref<EvidenceCard[]>([])
const loading = ref(false)
const saving = ref(false)
const activeId = ref('')
const error = ref('')
const notice = ref('')
const source = ref<{ chunkId: string; title: string; pageNumber: number; fileName: string;
  quote: string; sourceUrl: string } | null>(null)
const emptyFields = (): EvidenceCardFields => ({ researchClaim: '', dataRequirements: '', availabilityNote: '',
  reproductionSteps: '', observation: '', discrepancy: '' })
const fields = ref<EvidenceCardFields>(emptyFields())
const message = (value: unknown) => value instanceof Error ? value.message : '操作未完成'
const date = (value: string) => new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))

watch(() => props.projectId, async projectId => {
  cards.value = []; source.value = null; activeId.value = ''; error.value = ''; notice.value = ''
  if (!projectId) return
  loading.value = true
  try { const result = await listEvidenceCards(projectId); if (props.projectId === projectId) cards.value = result }
  catch (cause) { if (props.projectId === projectId) error.value = message(cause) }
  finally { if (props.projectId === projectId) loading.value = false }
}, { immediate: true })

watch(() => props.pendingSource, async selected => {
  if (!selected || !props.projectId) return
  activeId.value = ''
  fields.value = emptyFields()
  source.value = { chunkId: selected.hit.chunkId, title: selected.hit.title,
    pageNumber: selected.hit.pageNumber, fileName: selected.hit.fileName,
    quote: selected.quote, sourceUrl: selected.hit.sourceUrl }
  notice.value = ''; error.value = ''
  await nextTick()
  document.getElementById('evidence-card-editor')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
})

function edit(card: EvidenceCard) {
  activeId.value = card.id
  source.value = { chunkId: card.chunkId, title: card.title, pageNumber: card.pageNumber,
    fileName: card.fileName, quote: card.sourceQuote, sourceUrl: card.sourceUrl }
  fields.value = { researchClaim: card.researchClaim, dataRequirements: card.dataRequirements,
    availabilityNote: card.availabilityNote, reproductionSteps: card.reproductionSteps,
    observation: card.observation, discrepancy: card.discrepancy }
  notice.value = ''; error.value = ''
  nextTick(() => document.getElementById('evidence-card-editor')?.scrollIntoView({ behavior: 'smooth', block: 'start' }))
}

async function save() {
  if (!props.projectId || !source.value || !fields.value.researchClaim.trim()) return
  const projectId = props.projectId
  saving.value = true; error.value = ''; notice.value = ''
  try {
    const card = activeId.value
      ? await updateEvidenceCard(projectId, activeId.value, fields.value)
      : await createEvidenceCard(projectId, source.value.chunkId, source.value.quote, fields.value)
    if (props.projectId !== projectId) return
    cards.value = await listEvidenceCards(projectId)
    source.value = null; activeId.value = ''; fields.value = emptyFields()
    notice.value = `证据卡已保存为待核对：${card.title} · ${card.fileName.toLowerCase().endsWith('.pdf') ? `第 ${card.pageNumber} 页` : '文本文件'}`
  } catch (cause) { if (props.projectId === projectId) error.value = message(cause) }
  finally { saving.value = false }
}

async function setReview(card: EvidenceCard, reviewed: boolean) {
  if (!props.projectId) return
  const projectId = props.projectId
  error.value = ''; notice.value = ''
  try {
    const updated = await reviewEvidenceCard(projectId, card.id, reviewed)
    if (props.projectId !== projectId) return
    cards.value = cards.value.map(item => item.id === updated.id ? updated : item)
    notice.value = reviewed ? '已记录人工核对时间。' : '证据卡已退回待核对。'
  } catch (cause) { if (props.projectId === projectId) error.value = message(cause) }
}
</script>

<template>
  <section class="panel literature-panel evidence-panel">
    <div class="panel-header"><h2>复现证据卡</h2><span class="tag">{{ cards.length }} 张</span></div>
    <div class="literature-body">
      <p class="literature-muted">从检索命中选取原文，保存研究主张、数据要求、可用时间、复现步骤与结果差异。原文、哈希和页码保存后不可改写；“已人工核对”由你手动确认。</p>
      <p v-if="error" class="form-error" role="alert">{{ error }}</p>
      <p v-if="notice" class="literature-notice" role="status">{{ notice }}</p>
      <div v-if="source" id="evidence-card-editor" class="evidence-editor">
        <h3>{{ activeId ? '修改证据卡' : '新建证据卡' }}</h3>
        <p class="literature-muted">{{ source.title }} · {{ source.fileName.toLowerCase().endsWith('.pdf') ? `PDF 物理第 ${source.pageNumber} 页` : '文本文件' }}
          <a v-if="safeLiteratureSource(source.sourceUrl)" :href="safeLiteratureSource(source.sourceUrl)" target="_blank" rel="noopener noreferrer">打开原文件</a>
        </p>
        <div class="evidence-quote"><small>锁定原文</small><p>{{ source.quote }}</p></div>
        <form @submit.prevent="save">
          <label for="evidence-claim">研究主张或待验证问题</label>
          <textarea id="evidence-claim" v-model="fields.researchClaim" maxlength="500" rows="2" required placeholder="这段原文支持什么判断？哪些地方还需要验证？"></textarea>
          <label for="evidence-data">所需数据与口径</label>
          <textarea id="evidence-data" v-model="fields.dataRequirements" maxlength="2000" rows="2" placeholder="字段、样本范围、频率、来源"></textarea>
          <label for="evidence-availability">数据可用时间</label>
          <input id="evidence-availability" v-model="fields.availabilityNote" maxlength="1000" placeholder="例如：财报披露日之后；待查证" />
          <label for="evidence-steps">复现步骤</label>
          <textarea id="evidence-steps" v-model="fields.reproductionSteps" maxlength="4000" rows="3" placeholder="写下确定性的计算或验证步骤"></textarea>
          <label for="evidence-observation">复现结果</label>
          <textarea id="evidence-observation" v-model="fields.observation" maxlength="4000" rows="2" placeholder="记录已实际观察到的结果；未运行可留空"></textarea>
          <label for="evidence-discrepancy">与研报的差异及待查原因</label>
          <textarea id="evidence-discrepancy" v-model="fields.discrepancy" maxlength="4000" rows="2" placeholder="例如：收益曲线差异、数据口径疑点"></textarea>
          <div class="literature-actions"><button class="btn primary" type="submit" :disabled="saving || !fields.researchClaim.trim()"><Icon name="check" :size="16" />{{ saving ? '正在保存…' : '保存为待核对' }}</button><button class="btn secondary" type="button" @click="source = null; activeId = ''">取消</button></div>
          <p v-if="activeId" class="literature-muted">修改研究记录会自动撤销此前的人工核对状态。</p>
        </form>
      </div>
    </div>
    <div v-if="loading" class="literature-body literature-muted">正在读取证据卡…</div>
    <div v-else-if="!cards.length" class="literature-body literature-muted">当前项目还没有证据卡。检索文献后可从原文命中建立第一张。</div>
    <article v-for="card in cards" :key="card.id" class="evidence-card">
      <div class="evidence-card-heading"><strong>{{ card.researchClaim }}</strong><span class="tag" :class="card.status === 'REVIEWED' ? 'green-tag' : ''">{{ card.status === 'REVIEWED' ? '已人工核对' : '待核对' }}</span></div>
      <p class="literature-muted">{{ card.title }} · {{ card.fileName.toLowerCase().endsWith('.pdf') ? `PDF 物理第 ${card.pageNumber} 页` : '文本文件' }} · 更新于 {{ date(card.updatedAt) }}
        <a v-if="safeLiteratureSource(card.sourceUrl)" :href="safeLiteratureSource(card.sourceUrl)" target="_blank" rel="noopener noreferrer">打开出处</a>
      </p>
      <blockquote>{{ card.sourceQuote }}</blockquote>
      <dl class="evidence-fields"><div><dt>数据与口径</dt><dd>{{ card.dataRequirements || '待补充' }}</dd></div><div><dt>可用时间</dt><dd>{{ card.availabilityNote || '待查证' }}</dd></div><div><dt>复现步骤</dt><dd>{{ card.reproductionSteps || '待补充' }}</dd></div><div><dt>复现结果</dt><dd>{{ card.observation || '尚未记录' }}</dd></div><div><dt>结果差异</dt><dd>{{ card.discrepancy || '尚未记录' }}</dd></div></dl>
      <details><summary>来源哈希与核对记录</summary><code>文件 {{ card.documentSha256 }}<br />片段 {{ card.chunkSha256 }}</code><small v-if="card.reviewedAt">人工核对：{{ date(card.reviewedAt) }}</small></details>
      <div class="literature-actions"><button class="btn secondary" @click="edit(card)">编辑研究记录</button><button class="btn secondary" @click="setReview(card, card.status !== 'REVIEWED')">{{ card.status === 'REVIEWED' ? '退回待核对' : '标记已人工核对' }}</button></div>
    </article>
  </section>
</template>
