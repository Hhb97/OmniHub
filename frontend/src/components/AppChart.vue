<template>
  <div class="app-chart" :style="{ height: height }">
    <div ref="el" class="app-chart__canvas" />
    <div v-if="loading" class="app-chart__mask">
      <el-icon class="is-loading" :size="24"><Loading /></el-icon>
    </div>
    <div v-else-if="empty" class="app-chart__mask">
      <el-empty :description="emptyText" :image-size="60" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { Loading } from '@element-plus/icons-vue'
import * as echarts from 'echarts/core'
import type { EChartsCoreOption } from 'echarts/core'
import { BarChart, LineChart, PieChart, GaugeChart } from 'echarts/charts'
import {
  DataZoomComponent,
  GridComponent,
  LegendComponent,
  TitleComponent,
  TooltipComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([
  BarChart,
  LineChart,
  PieChart,
  GaugeChart,
  DataZoomComponent,
  GridComponent,
  LegendComponent,
  TitleComponent,
  TooltipComponent,
  CanvasRenderer,
])

const props = withDefaults(
  defineProps<{
    option: EChartsCoreOption
    height?: string
    loading?: boolean
    empty?: boolean
    emptyText?: string
  }>(),
  { height: '300px', loading: false, empty: false, emptyText: '暂无数据' },
)

const el = ref<HTMLDivElement>()
const chart = shallowRef<echarts.ECharts>()
let observer: ResizeObserver | undefined

function render() {
  if (!chart.value) return
  chart.value.setOption(props.option, true)
}

onMounted(() => {
  if (!el.value) return
  chart.value = echarts.init(el.value)
  render()
  observer = new ResizeObserver(() => chart.value?.resize())
  observer.observe(el.value)
})

watch(() => props.option, render, { deep: false })

onBeforeUnmount(() => {
  observer?.disconnect()
  chart.value?.dispose()
  chart.value = undefined
})
</script>

<style scoped>
.app-chart {
  position: relative;
  width: 100%;
}
.app-chart__canvas {
  width: 100%;
  height: 100%;
}
.app-chart__mask {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(255, 255, 255, 0.85);
  color: #909399;
}
</style>
