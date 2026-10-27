<template>
  <AppChart
    :option="option"
    :height="height"
    :loading="loading"
    :empty="isEmpty"
    :empty-text="emptyText"
  />
</template>

<script setup lang="ts">
import { computed } from 'vue'
import AppChart from './AppChart.vue'
import type { TrendSeries } from '@/types'
import { toNumber } from '@/utils/format'

const props = withDefaults(
  defineProps<{
    series: TrendSeries[]
    height?: string
    loading?: boolean
    emptyText?: string
    smooth?: boolean
  }>(),
  { height: '320px', loading: false, emptyText: '所选时间段内暂无数据', smooth: false },
)

const palette = ['#409eff', '#67c23a', '#e6a23c', '#f56c6c', '#909399', '#8e44ad', '#16a085']

const prepared = computed(() =>
  props.series.map((s) => ({
    ...s,
    data: s.points
      .map(([t, v]) => [t, toNumber(v)] as [number, number | null])
      .filter(([, v]) => v !== null),
  })),
)

const isEmpty = computed(() => prepared.value.every((s) => s.data.length === 0))

const option = computed(() => ({
  color: palette,
  tooltip: { trigger: 'axis' },
  legend: { type: 'scroll', top: 0 },
  grid: { left: 48, right: 24, top: 40, bottom: 40, containLabel: true },
  xAxis: { type: 'time' },
  yAxis: { type: 'value', scale: true },
  dataZoom: [{ type: 'inside' }],
  series: prepared.value.map((s) => ({
    name: s.unit ? `${s.name || s.identifier} (${s.unit})` : s.name || s.identifier,
    type: 'line',
    showSymbol: s.data.length < 60,
    smooth: props.smooth,
    data: s.data,
  })),
}))
</script>
