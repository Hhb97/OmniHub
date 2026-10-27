<template>
  <el-card shadow="never">
    <template #header><span class="card-title">设备在线率</span></template>
    <AppChart
      :option="option"
      height="300px"
      :loading="loading && !overview"
      :empty="!overview || overview.deviceCount === 0"
      empty-text="暂无设备"
    />
  </el-card>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import AppChart from '@/components/AppChart.vue'
import type { Overview } from '@/types'

const props = defineProps<{ overview: Overview | null; loading: boolean }>()

const option = computed(() => {
  const o = props.overview
  return {
    tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
    legend: { bottom: 0 },
    title: {
      text: o ? `${o.onlineRate.toFixed(2)}%` : '',
      subtext: '在线率',
      left: 'center',
      top: '36%',
      textStyle: { fontSize: 24 },
      subtextStyle: { fontSize: 12 },
    },
    series: [
      {
        type: 'pie',
        radius: ['50%', '72%'],
        center: ['50%', '45%'],
        avoidLabelOverlap: true,
        label: { show: false },
        data: [
          { name: '在线', value: o?.onlineCount ?? 0, itemStyle: { color: '#67c23a' } },
          { name: '离线', value: o?.offlineCount ?? 0, itemStyle: { color: '#f56c6c' } },
          { name: '未激活', value: o?.inactiveCount ?? 0, itemStyle: { color: '#c0c4cc' } },
        ],
      },
    ],
  }
})
</script>
