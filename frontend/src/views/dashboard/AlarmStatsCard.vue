<template>
  <el-card shadow="never">
    <template #header><span class="card-title">告警统计（近 7 天）</span></template>
    <el-alert v-if="error" type="error" :title="error" show-icon :closable="false" />
    <el-row :gutter="16">
      <el-col :xs="24" :md="10">
        <AppChart
          :option="pieOption"
          height="280px"
          :loading="loading"
          :empty="!loading && noLevel"
          empty-text="暂无告警"
        />
      </el-col>
      <el-col :xs="24" :md="14">
        <AppChart
          :option="barOption"
          height="280px"
          :loading="loading"
          :empty="!loading && noDay"
          empty-text="暂无告警"
        />
      </el-col>
    </el-row>
  </el-card>
</template>

<script setup lang="ts">
import { computed, watch } from 'vue'
import AppChart from '@/components/AppChart.vue'
import { dashboardApi } from '@/api/dashboard'
import { useAsyncData } from '@/composables/useAsyncData'
import { ALARM_LEVEL_COLOR, ALARM_LEVEL_LABEL } from '@/utils/constants'

const props = defineProps<{ refreshKey: number }>()
const { data, loading, error, reload } = useAsyncData(() => dashboardApi.alarmStats(7))
watch(() => props.refreshKey, reload)

const noLevel = computed(() => !data.value || data.value.byLevel.every((l) => l.count === 0))
const noDay = computed(() => !data.value || data.value.byDay.every((l) => l.count === 0))

const pieOption = computed(() => ({
  tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
  legend: { bottom: 0 },
  series: [
    {
      name: '告警级别',
      type: 'pie',
      radius: '65%',
      center: ['50%', '45%'],
      data: (data.value?.byLevel ?? []).map((l) => ({
        name: ALARM_LEVEL_LABEL[l.level] ?? l.level,
        value: l.count,
        itemStyle: { color: ALARM_LEVEL_COLOR[l.level] },
      })),
    },
  ],
}))

const barOption = computed(() => ({
  tooltip: { trigger: 'axis' },
  grid: { left: 40, right: 20, top: 20, bottom: 30, containLabel: true },
  xAxis: { type: 'category', data: (data.value?.byDay ?? []).map((d) => d.date) },
  yAxis: { type: 'value', minInterval: 1 },
  series: [
    {
      name: '告警数',
      type: 'bar',
      itemStyle: { color: '#e6a23c' },
      data: (data.value?.byDay ?? []).map((d) => d.count),
    },
  ],
}))
</script>
