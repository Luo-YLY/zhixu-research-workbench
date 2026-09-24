<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { request } from './api'
import type { PlanItem, RecurringSchedule, ResearchTask } from './api'
import Icon from './Icon.vue'

const props = defineProps<{ tasks: ResearchTask[] }>()
const today = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date())
const date = ref(today)
const plans = ref<PlanItem[]>([])
const schedules = ref<RecurringSchedule[]>([])
const busy = ref(false)
const loading = ref(true)
const error = ref('')
const info = ref('')
const manual = reactive({ title: '', taskId: '', plannedTime: '' })
const recurring = reactive({ taskId: '', frequency: 'DAILY', weekday: 1, plannedTime: '09:00', startDate: today })
const weekdays = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
const completed = computed(() => plans.value.filter(item => item.status === 'DONE').length)
const message = (value: unknown) => value instanceof Error ? value.message : '操作未完成，请重试。'
const taskTitle = (id: string | null) => props.tasks.find(task => task.id === id)?.title || ''
const displayTime = (value: string | null) => value ? value.slice(0, 5) : '不限时'

async function load() {
  loading.value = true; error.value = ''
  try {
    const [items, rules] = await Promise.all([
      request<PlanItem[]>(`/plans?date=${encodeURIComponent(date.value)}`), request<RecurringSchedule[]>('/schedules'),
    ])
    plans.value = items; schedules.value = rules
  } catch (cause) { error.value = message(cause) }
  finally { loading.value = false }
}
async function act(operation: () => Promise<unknown>, success: string) {
  if (busy.value) return
  busy.value = true; error.value = ''; info.value = ''
  try { await operation(); await load(); info.value = success }
  catch (cause) { error.value = message(cause) }
  finally { busy.value = false }
}
async function addManual() {
  if (!manual.title.trim()) { error.value = '请填写计划内容。'; return }
  await act(() => request('/plans', { title: manual.title.trim(), date: date.value,
    taskId: manual.taskId || null, plannedTime: manual.plannedTime || null }), '已加入当天计划。')
  if (!error.value) { manual.title = ''; manual.plannedTime = '' }
}
async function addRecurring() {
  if (!recurring.taskId) { error.value = '请选择研究任务。'; return }
  await act(() => request('/schedules', { taskId: recurring.taskId, frequency: recurring.frequency,
    weekday: recurring.frequency === 'WEEKLY' ? Number(recurring.weekday) : null,
    plannedTime: recurring.plannedTime, startDate: recurring.startDate }), '周期规则已保存。')
}
onMounted(() => { void load() })
</script>

<template>
  <div class="planning-page">
    <div class="planning-intro"><div><strong>日常安排</strong><p>时间按北京时间显示。周期规则每天生成待办；运行研究流程仍由你手动启动。</p></div><div class="planning-date"><label>查看日期<input v-model="date" type="date" @change="load" /></label><button class="btn secondary small-btn" :disabled="loading" @click="load">刷新</button></div></div>
    <div v-if="error" class="planning-alert" role="alert">{{ error }}<button type="button" @click="load">重试</button></div>
    <div v-if="info" class="planning-info" role="status">{{ info }}</div>
    <div class="planning-grid">
      <section class="panel planning-section"><div class="panel-header"><h2>{{ date === today ? '今日计划' : `${date} 的计划` }}<span class="count-label">{{ completed }}/{{ plans.length }} 完成</span></h2><Icon name="clock" :size="18" /></div>
        <div v-if="loading" class="planning-empty">正在读取计划…</div>
        <div v-else-if="!plans.length" class="planning-empty">这一天还没有计划。可以手动添加，或为研究任务设置周期。</div>
        <div v-for="item in plans" :key="item.id" class="planning-item" :class="{ done: item.status === 'DONE' }"><button class="planning-check" :disabled="busy" :aria-label="item.status === 'DONE' ? '重新打开计划' : '完成计划'" @click="act(() => request(`/plans/${item.id}/${item.status === 'DONE' ? 'reopen' : 'complete'}`, {}), item.status === 'DONE' ? '已重新打开。' : '已记录完成。')"><Icon :name="item.status === 'DONE' ? 'check' : 'clock'" :size="17" /></button><div><strong>{{ item.title }}</strong><small>{{ displayTime(item.plannedTime) }}<template v-if="item.taskId && taskTitle(item.taskId) !== item.title"> · {{ taskTitle(item.taskId) }}</template><template v-if="item.scheduleId"> · 周期生成</template></small></div></div>
        <form class="planning-form" @submit.prevent="addManual"><h3>添加当天事项</h3><input v-model="manual.title" maxlength="180" required placeholder="例如：核对研报中的收益口径" aria-label="计划内容" /><div class="planning-fields"><input v-model="manual.plannedTime" type="time" aria-label="计划时间，可选" /><select v-model="manual.taskId" aria-label="关联研究任务，可选"><option value="">不关联任务</option><option v-for="task in tasks" :key="task.id" :value="task.id">{{ task.title }}</option></select></div><button class="btn primary" :disabled="busy" type="submit">加入计划</button></form>
      </section>
      <section class="panel planning-section"><div class="panel-header"><h2>周期任务<span class="count-label">{{ schedules.filter(rule => rule.active).length }} 启用</span></h2><Icon name="refresh" :size="18" /></div><div v-if="!schedules.length" class="planning-empty">尚未设置周期规则。</div><div v-for="rule in schedules" :key="rule.id" class="planning-rule"><div><strong>{{ rule.taskTitle }}</strong><small>{{ rule.frequency === 'DAILY' ? '每天' : `每${weekdays[(rule.weekday || 1) - 1]}` }} {{ displayTime(rule.plannedTime) }} · {{ rule.startDate }} 起 · {{ rule.active ? '启用中' : '已暂停' }}</small></div><button class="btn secondary small-btn" :disabled="busy" @click="act(() => request(`/schedules/${rule.id}/${rule.active ? 'pause' : 'resume'}`, {}), rule.active ? '已暂停后续生成。' : '已恢复周期规则。')">{{ rule.active ? '暂停' : '恢复' }}</button></div>
        <form class="planning-form" @submit.prevent="addRecurring"><h3>设置周期</h3><label>研究任务<select v-model="recurring.taskId" required><option value="" disabled>选择任务</option><option v-for="task in tasks" :key="task.id" :value="task.id">{{ task.title }}</option></select></label><div class="planning-fields"><label>频率<select v-model="recurring.frequency"><option value="DAILY">每天</option><option value="WEEKLY">每周</option></select></label><label v-if="recurring.frequency === 'WEEKLY'">星期<select v-model.number="recurring.weekday"><option v-for="(day, index) in weekdays" :key="day" :value="index + 1">{{ day }}</option></select></label><label>时间<input v-model="recurring.plannedTime" type="time" required /></label></div><label>开始日期<input v-model="recurring.startDate" type="date" required /></label><button class="btn primary" :disabled="busy || !tasks.length" type="submit">保存周期规则</button><p class="planning-hint">只生成日计划，不会自动运行当前 DEMO 工作流。</p></form>
      </section>
    </div>
  </div>
</template>

<style scoped>
.planning-page{display:flex;flex-direction:column;gap:20px}.planning-intro{display:flex;justify-content:space-between;gap:20px;align-items:center;background:#edf3e9;border:1px solid #dce9d8;border-radius:9px;padding:20px 23px}.planning-intro strong{font-size:15px;color:#415f49}.planning-intro p{font-size:11px;color:#728779;line-height:1.8;margin-top:6px}.planning-date{display:flex;align-items:end;gap:8px}.planning-intro label,.planning-form label{font-size:11px;color:#71846e;display:flex;flex-direction:column;gap:7px}.planning-intro input{min-width:160px}.planning-grid{display:grid;grid-template-columns:1fr 1fr;gap:20px;align-items:start}.planning-section{overflow:hidden}.planning-empty{padding:26px;color:#839486;font-size:12px;line-height:1.8}.planning-item,.planning-rule{display:flex;align-items:center;gap:12px;padding:15px 20px;border-bottom:1px solid #eef1e9}.planning-item>div,.planning-rule>div{min-width:0;flex:1}.planning-item strong,.planning-rule strong{display:block;color:#425a4c;font-size:12px;font-weight:500;overflow-wrap:anywhere}.planning-item small,.planning-rule small{display:block;color:#8b9a8b;font-size:10px;margin-top:6px;line-height:1.6}.planning-item.done strong{text-decoration:line-through;color:#94a396}.planning-check{width:29px;height:29px;border-radius:50%;border:1px solid #bfd3c3;display:grid;place-items:center;color:#5b8c6e;flex:none}.planning-item.done .planning-check{background:#e4f1e4}.planning-form{padding:20px;border-top:1px solid #e9efe7;display:flex;flex-direction:column;gap:12px}.planning-form h3{color:#526b56;margin-bottom:3px}.planning-fields{display:flex;gap:10px}.planning-fields>*{flex:1;min-width:0}.planning-form input,.planning-form select,.planning-intro input{font-size:11px;padding:9px}.planning-form>.btn{align-self:flex-start}.planning-hint{font-size:10px;color:#8b9a8b;line-height:1.7}.planning-alert,.planning-info{padding:11px 15px;border-radius:6px;font-size:11px}.planning-alert{background:#fff0e8;color:#aa7859}.planning-info{background:#e9f4e8;color:#597c61}.planning-alert button{margin-left:14px;text-decoration:underline;color:inherit}@media(max-width:960px){.planning-grid{grid-template-columns:1fr}}@media(max-width:600px){.planning-intro{align-items:stretch;flex-direction:column}.planning-fields{flex-wrap:wrap}}
</style>
