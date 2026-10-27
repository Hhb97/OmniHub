<template>
  <div class="realtime">
    <div class="toolbar bar">
      <span class="ws">
        <i class="dot" :class="wsStatus" />
        {{ wsText }}
      </span>
      <el-button size="small" :loading="latestLoading" @click="refreshAll">刷新</el-button>
    </div>

    <el-alert
      v-if="latestError"
      type="error"
      :title="latestError"
      show-icon
      :closable="false"
      class="alert"
    />

    <div v-loading="latestLoading && cards.length === 0" class="cards">
      <el-empty
        v-if="!latestLoading && cards.length === 0 && !latestError"
        description="设备尚未上报任何属性数据"
        :image-size="80"
      />
      <el-card v-for="c in cards" :key="c.identifier" shadow="hover" class="card">
        <div class="c-name">{{ c.name || c.identifier }}</div>
        <div class="c-value" :class="{ flash: flashIds.has(c.identifier) }">
          {{ displayValue(c.value) }}<span v-if="c.unit" class="unit">{{ c.unit }}</span>
        </div>
        <div class="c-time muted">{{ formatTime(c.reportedAt) }}</div>
      </el-card>
    </div>

    <div class="chart-head toolbar">
      <b>实时曲线</b>
      <el-select
        v-model="selected"
        multiple
        collapse-tags
        collapse-tags-tooltip
        placeholder="选择要显示的属性"
        style="width: 320px"
        @change="loadTrend"
      >
        <el-option
          v-for="p in numericProps"
          :key="p.identifier"
          :label="p.name || p.identifier"
          :value="p.identifier"
        />
      </el-select>
      <span class="muted">最近 {{ WINDOW_MIN }} 分钟</span>
    </div>
    <el-alert
      v-if="trendError"
      type="error"
      :title="trendError"
      show-icon
      :closable="false"
      class="alert"
    />
    <TrendChart
      :series="series"
      :loading="trendLoading"
      empty-text="暂无曲线数据，请选择属性或等待设备上报"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, shallowRef, watch } from 'vue'
import TrendChart from '@/components/TrendChart.vue'
import { deviceApi } from '@/api/device'
import { useWsSubscription } from '@/composables/useWsSubscription'
import type { PropInfo } from '@/composables/useThingModel'
import type { Device, JsonValue, LatestValue, TrendSeries } from '@/types'
import { NUMERIC_TYPES } from '@/utils/constants'
import { formatTime, stringify, toIso } from '@/utils/format'
import { wsStatus } from '@/utils/ws'

const props = defineProps<{ device: Device; properties: PropInfo[]; propsLoading: boolean }>()

const WINDOW_MIN = 30
const MAX_POINTS = 600

const latest = shallowRef<LatestValue[]>([])
const latestLoading = ref(false)
const latestError = ref('')
const flashIds = reactive(new Set<string>())

const selected = ref<string[]>([])
const store = reactive<Record<string, [number, JsonValue][]>>({})
const trendLoading = ref(false)
const trendError = ref('')

const wsText = computed(
  () =>
    ({
      idle: '实时通道未连接',
      connecting: '实时通道连接中…',
      open: '实时通道已连接',
      reconnecting: '实时通道断开，重连中…',
    })[wsStatus.value],
)

const propMap = computed(() => new Map(props.properties.map((p) => [p.identifier, p])))
const cards = computed(() => latest.value)

const numericProps = computed(() => {
  const fromLatest = latest.value
    .filter((l) => NUMERIC_TYPES.includes(l.dataType))
    .map((l) => ({ identifier: l.identifier, name: l.name }))
  const fromModel = props.properties
    .filter((p) => NUMERIC_TYPES.includes(p.dataType))
    .map((p) => ({ identifier: p.identifier, name: p.name }))
  const map = new Map<string, { identifier: string; name: string }>()
  for (const x of [...fromModel, ...fromLatest]) map.set(x.identifier, x)
  return [...map.values()]
})

const series = computed<TrendSeries[]>(() =>
  selected.value.map((id) => {
    const info = propMap.value.get(id) ?? latest.value.find((l) => l.identifier === id)
    return { identifier: id, name: info?.name ?? id, unit: info?.unit, points: store[id] ?? [] }
  }),
)

function displayValue(v: JsonValue): string {
  if (typeof v === 'boolean') return v ? 'true' : 'false'
  return stringify(v)
}

async function loadLatest() {
  latestLoading.value = true
  latestError.value = ''
  try {
    latest.value = await deviceApi.latest(props.device.id)
    if (selected.value.length === 0) {
      selected.value = numericProps.value.slice(0, 3).map((p) => p.identifier)
      await loadTrend()
    }
  } catch (e) {
    latestError.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    latestLoading.value = false
  }
}

async function loadTrend() {
  for (const k of Object.keys(store)) delete store[k]
  if (selected.value.length === 0) return
  trendLoading.value = true
  trendError.value = ''
  try {
    const end = new Date()
    const start = new Date(end.getTime() - WINDOW_MIN * 60_000)
    const res = await deviceApi.trend(props.device.id, {
      identifiers: selected.value.join(','),
      start: toIso(start),
      end: toIso(end),
      interval: 'RAW',
    })
    for (const s of res.series) store[s.identifier] = s.points.slice(-MAX_POINTS)
  } catch (e) {
    trendError.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    trendLoading.value = false
  }
}

async function refreshAll() {
  await loadLatest()
  await loadTrend()
}

function flash(id: string) {
  flashIds.add(id)
  setTimeout(() => flashIds.delete(id), 600)
}

useWsSubscription({
  deviceId: props.device.id,
  onMessage: (msg) => {
    if (msg.type !== 'property') return
    const cutoff = msg.ts - WINDOW_MIN * 60_000
    const next = [...latest.value]
    for (const [id, value] of Object.entries(msg.data)) {
      const idx = next.findIndex((l) => l.identifier === id)
      const info = propMap.value.get(id)
      const reportedAt = new Date(msg.ts).toISOString()
      if (idx >= 0) next[idx] = { ...next[idx], value, reportedAt }
      else
        next.push({
          identifier: id,
          name: info?.name ?? id,
          unit: info?.unit,
          dataType: info?.dataType ?? 'STRING',
          value,
          reportedAt,
        })
      flash(id)
      if (selected.value.includes(id)) {
        const arr = store[id] ?? (store[id] = [])
        arr.push([msg.ts, value])
        while (arr.length > MAX_POINTS || (arr.length > 0 && arr[0][0] < cutoff)) arr.shift()
      }
    }
    latest.value = next
  },
})

void loadLatest()

// 断线重连成功后补齐空档数据
let wasDown = false
watch(wsStatus, (s) => {
  if (s === 'reconnecting') wasDown = true
  else if (s === 'open' && wasDown) {
    wasDown = false
    void refreshAll()
  }
})
</script>

<style scoped>
.bar {
  margin-bottom: 12px;
}
.alert {
  margin-bottom: 12px;
}
.ws {
  font-size: 13px;
  color: #606266;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #c0c4cc;
  display: inline-block;
}
.dot.open {
  background: #67c23a;
}
.dot.connecting,
.dot.reconnecting {
  background: #e6a23c;
}
.cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 12px;
  margin-bottom: 20px;
  min-height: 40px;
}
.card {
  text-align: center;
}
.c-name {
  color: #606266;
  font-size: 13px;
}
.c-value {
  font-size: 24px;
  font-weight: 600;
  margin: 6px 0;
  transition: color 0.3s;
  word-break: break-all;
}
.c-value.flash {
  color: #409eff;
}
.unit {
  font-size: 13px;
  margin-left: 4px;
  color: #909399;
  font-weight: normal;
}
.c-time {
  font-size: 12px;
}
.chart-head {
  margin-bottom: 8px;
}
</style>
