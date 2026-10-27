<template>
  <el-card shadow="never">
    <template #header>
      <div class="head">
        <span class="card-title">属性上报趋势</span>
        <el-radio-group v-model="hours" size="small" @change="reload">
          <el-radio-button :value="6">6 小时</el-radio-button>
          <el-radio-button :value="24">24 小时</el-radio-button>
          <el-radio-button :value="72">3 天</el-radio-button>
        </el-radio-group>
      </div>
    </template>
    <el-alert v-if="error" type="error" :title="error" show-icon :closable="false" />
    <AppChart
      :option="option"
      height="300px"
      :loading="loading"
      :empty="!loading && !data?.length"
    />
  </el-card>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import AppChart from '@/components/AppChart.vue'
import { dashboardApi } from '@/api/dashboard'
import { useAsyncData } from '@/composables/useAsyncData'
import { formatHour } from '@/utils/format'

const props = defineProps<{ refreshKey: number }>()
const hours = ref(24)
const { data, loading, error, reload } = useAsyncData(() => dashboardApi.reportTrend(hours.value))
watch(() => props.refreshKey, reload)

const option = computed(() => ({
  tooltip: { trigger: 'axis' },
  grid: { left: 40, right: 20, top: 20, bottom: 30, containLabel: true },
  xAxis: { type: 'category', data: (data.value ?? []).map((p) => formatHour(p.time)) },
  yAxis: { type: 'value', minInterval: 1 },
  series: [
    {
      name: '上报消息数',
      type: 'line',
      smooth: true,
      areaStyle: { opacity: 0.15 },
      itemStyle: { color: '#409eff' },
      data: (data.value ?? []).map((p) => p.count),
    },
  ],
}))
</script>

<style scoped>
.head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
