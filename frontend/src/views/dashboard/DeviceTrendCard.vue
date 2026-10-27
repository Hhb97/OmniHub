<template>
  <el-card shadow="never">
    <template #header>
      <div class="head">
        <span class="card-title">设备属性趋势</span>
        <div class="toolbar">
          <el-select
            v-model="deviceId"
            filterable
            remote
            :remote-method="(k: string) => search(k)"
            :loading="searching"
            placeholder="选择设备（可搜索）"
            style="width: 220px"
            @change="onDeviceChange"
          >
            <el-option v-for="d in options" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
          <el-select
            v-model="identifiers"
            multiple
            collapse-tags
            collapse-tags-tooltip
            placeholder="属性"
            style="width: 220px"
            :disabled="!deviceId"
            @change="loadTrend"
          >
            <el-option
              v-for="p in numericProps"
              :key="p.identifier"
              :label="p.name || p.identifier"
              :value="p.identifier"
            />
          </el-select>
          <el-select v-model="hours" style="width: 110px" :disabled="!deviceId" @change="loadTrend">
            <el-option label="近 1 小时" :value="1" />
            <el-option label="近 6 小时" :value="6" />
            <el-option label="近 24 小时" :value="24" />
          </el-select>
        </div>
      </div>
    </template>
    <el-alert v-if="error" type="error" :title="error" show-icon :closable="false" />
    <TrendChart
      :series="series"
      :loading="loading"
      :empty-text="deviceId ? '所选时间段内暂无数据' : '请选择设备'"
      height="320px"
    />
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, shallowRef } from 'vue'
import TrendChart from '@/components/TrendChart.vue'
import { deviceApi } from '@/api/device'
import { useDeviceSearch } from '@/composables/useOptions'
import type { LatestValue, TrendInterval, TrendSeries } from '@/types'
import { toIso } from '@/utils/format'
import { NUMERIC_TYPES } from '@/utils/constants'

const { options, loading: searching, search } = useDeviceSearch()
const deviceId = ref<number>()
const latest = shallowRef<LatestValue[]>([])
const identifiers = ref<string[]>([])
const hours = ref(1)
const series = shallowRef<TrendSeries[]>([])
const loading = ref(false)
const error = ref('')

const numericProps = computed(() => latest.value.filter((p) => NUMERIC_TYPES.includes(p.dataType)))

function intervalFor(h: number): TrendInterval {
  return h <= 1 ? '1m' : '5m'
}

async function loadTrend() {
  if (!deviceId.value || identifiers.value.length === 0) {
    series.value = []
    return
  }
  loading.value = true
  error.value = ''
  try {
    const end = new Date()
    const start = new Date(end.getTime() - hours.value * 3600_000)
    const res = await deviceApi.trend(deviceId.value, {
      identifiers: identifiers.value.join(','),
      start: toIso(start),
      end: toIso(end),
      interval: intervalFor(hours.value),
    })
    series.value = res.series
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
    series.value = []
  } finally {
    loading.value = false
  }
}

async function onDeviceChange() {
  latest.value = []
  identifiers.value = []
  series.value = []
  if (!deviceId.value) return
  loading.value = true
  error.value = ''
  try {
    latest.value = await deviceApi.latest(deviceId.value)
    identifiers.value = numericProps.value.slice(0, 3).map((p) => p.identifier)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
    loading.value = false
    return
  }
  await loadTrend()
}

onMounted(async () => {
  await search('')
  if (options.value.length > 0) {
    deviceId.value = options.value[0].id
    await onDeviceChange()
  }
})
</script>

<style scoped>
.head {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: space-between;
  align-items: center;
}
</style>
