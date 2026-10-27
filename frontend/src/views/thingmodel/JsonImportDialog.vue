<template>
  <el-dialog v-model="visible" title="JSON 批量编辑" width="760px" top="6vh" @open="onOpen">
    <el-alert type="info" show-icon :closable="false" class="tip">
      <template #title>
        可粘贴完整物模型 <code>{"properties":[],"events":[],"services":[]}</code>，或只粘贴属性数组
        <code>[{...}]</code>。属性缺省字段：<code>name</code>=标识，<code>dataType</code>=FLOAT，
        <code>accessMode</code>=R。结果只应用到当前草稿，仍需点“保存为新版本”。
      </template>
    </el-alert>
    <div class="mode">
      <el-radio-group v-model="mode">
        <el-radio-button value="merge">合并（同标识覆盖，其余保留）</el-radio-button>
        <el-radio-button value="replace">整体替换</el-radio-button>
      </el-radio-group>
      <el-button link type="primary" @click="fillCurrent">载入当前内容</el-button>
      <el-button link type="primary" @click="fillExample">示例</el-button>
    </div>
    <el-input
      v-model="text"
      type="textarea"
      :rows="18"
      spellcheck="false"
      class="editor"
      placeholder='[{"identifier":"temperature","name":"温度","dataType":"FLOAT","unit":"℃","accessMode":"R","spec":{"min":-40,"max":125}}]'
    />
    <el-alert
      v-if="errors.length"
      type="error"
      show-icon
      :closable="false"
      class="errors"
      :title="`发现 ${errors.length} 个问题`"
    >
      <div v-for="e in errors.slice(0, 8)" :key="e">{{ e }}</div>
      <div v-if="errors.length > 8">… 其余 {{ errors.length - 8 }} 个略</div>
    </el-alert>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" @click="apply">应用到草稿</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { ThingModelBody } from '@/types'

const props = defineProps<{ current: ThingModelBody | null }>()
const emit = defineEmits<{ apply: [body: ThingModelBody] }>()
const visible = defineModel<boolean>({ default: false })

const text = ref('')
const mode = ref<'merge' | 'replace'>('merge')
const errors = ref<string[]>([])

const ID = /^[a-zA-Z][a-zA-Z0-9_]{0,63}$/
const TYPES = ['INT', 'FLOAT', 'BOOL', 'STRING', 'ENUM', 'JSON']

const EXAMPLE = JSON.stringify(
  [
    {
      identifier: 'temperature',
      name: '温度',
      dataType: 'FLOAT',
      unit: '℃',
      spec: { min: -40, max: 125 },
    },
    {
      identifier: 'humidity',
      name: '湿度',
      dataType: 'FLOAT',
      unit: '%',
      spec: { min: 0, max: 100 },
    },
    { identifier: 'switch', name: '开关', dataType: 'BOOL', accessMode: 'RW' },
    {
      identifier: 'mode',
      name: '模式',
      dataType: 'ENUM',
      accessMode: 'RW',
      spec: {
        enumValues: [
          { value: '0', label: '自动' },
          { value: '1', label: '手动' },
        ],
      },
    },
  ],
  null,
  2,
)

function onOpen() {
  errors.value = []
  if (!text.value.trim()) fillCurrent()
}
function fillCurrent() {
  text.value = JSON.stringify(
    props.current ?? { properties: [], events: [], services: [] },
    null,
    2,
  )
}
function fillExample() {
  text.value = EXAMPLE
}

type Obj = Record<string, unknown>
const isObj = (v: unknown): v is Obj => typeof v === 'object' && v !== null && !Array.isArray(v)

function normalizeProperty(p: unknown, i: number, errs: string[]): Obj | null {
  const where = `属性[${i}]`
  if (!isObj(p)) {
    errs.push(`${where} 不是对象`)
    return null
  }
  const id = String(p.identifier ?? '')
  if (!ID.test(id)) errs.push(`${where} 标识不合法：“${id}”（字母开头，字母/数字/下划线，最长 64）`)
  const dataType = String(p.dataType ?? 'FLOAT').toUpperCase()
  if (!TYPES.includes(dataType)) errs.push(`${where}(${id}) 数据类型不合法：${dataType}`)
  const accessMode = String(p.accessMode ?? 'R').toUpperCase()
  if (!['R', 'RW'].includes(accessMode)) errs.push(`${where}(${id}) accessMode 只能为 R 或 RW`)
  const spec = isObj(p.spec) ? p.spec : {}
  if (dataType === 'ENUM' && !(Array.isArray(spec.enumValues) && spec.enumValues.length))
    errs.push(`${where}(${id}) 枚举类型必须配置 spec.enumValues`)
  if (typeof spec.min === 'number' && typeof spec.max === 'number' && spec.min > spec.max)
    errs.push(`${where}(${id}) 最小值不能大于最大值`)
  return { ...p, identifier: id, name: String(p.name ?? id), dataType, accessMode, spec }
}

function dedupe(list: Obj[], what: string, errs: string[]) {
  const seen = new Set<string>()
  for (const x of list) {
    const id = String(x.identifier)
    if (seen.has(id)) errs.push(`${what}标识重复：${id}`)
    seen.add(id)
  }
}

function mergeBy<T extends { identifier: string }>(base: T[], incoming: T[]): T[] {
  const map = new Map(base.map((x) => [x.identifier, x]))
  for (const x of incoming) map.set(x.identifier, x)
  return [...map.values()]
}

function apply() {
  errors.value = []
  let parsed: unknown
  try {
    parsed = JSON.parse(text.value)
  } catch (e) {
    errors.value = [`JSON 解析失败：${e instanceof Error ? e.message : e}`]
    return
  }
  const errs: string[] = []
  let props_: unknown[] = []
  let events: unknown[] = []
  let services: unknown[] = []
  if (Array.isArray(parsed)) {
    props_ = parsed
  } else if (isObj(parsed)) {
    props_ = (parsed.properties as unknown[]) ?? []
    events = (parsed.events as unknown[]) ?? []
    services = (parsed.services as unknown[]) ?? []
    if (!Array.isArray(props_) || !Array.isArray(events) || !Array.isArray(services))
      errs.push('properties / events / services 必须是数组')
  } else {
    errs.push('顶层必须是数组或对象')
  }
  if (errs.length) {
    errors.value = errs
    return
  }
  const newProps = props_.map((p, i) => normalizeProperty(p, i, errs)).filter((x): x is Obj => !!x)
  dedupe(newProps, '属性', errs)
  const newEvents = events
    .filter(isObj)
    .map((e) => ({ outputs: [], level: 'INFO', ...e, name: e.name ?? e.identifier }))
  const newServices = services
    .filter(isObj)
    .map((s) => ({ inputs: [], outputs: [], ...s, name: s.name ?? s.identifier }))
  dedupe(newEvents as Obj[], '事件', errs)
  dedupe(newServices as Obj[], '服务', errs)
  for (const e of newEvents as Obj[])
    if (!ID.test(String(e.identifier))) errs.push(`事件标识不合法：${e.identifier}`)
  for (const s of newServices as Obj[])
    if (!ID.test(String(s.identifier))) errs.push(`服务标识不合法：${s.identifier}`)
  if (errs.length) {
    errors.value = errs
    return
  }
  const cur = props.current ?? { properties: [], events: [], services: [] }
  const body = (mode.value === 'replace'
    ? { properties: newProps, events: newEvents, services: newServices }
    : {
        properties: mergeBy(cur.properties, newProps as never[]),
        events: mergeBy(cur.events, newEvents as never[]),
        services: mergeBy(cur.services, newServices as never[]),
      }) as unknown as ThingModelBody
  emit('apply', body)
  ElMessage.success(
    `已应用：属性 ${body.properties.length}、事件 ${body.events.length}、服务 ${body.services.length}（未保存）`,
  )
  visible.value = false
}
</script>

<style scoped>
.tip,
.errors {
  margin-bottom: 12px;
}
.mode {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}
.editor :deep(textarea) {
  font-family: Consolas, Monaco, monospace;
  font-size: 13px;
}
</style>
