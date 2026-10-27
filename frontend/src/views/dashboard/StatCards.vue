<template>
  <el-row :gutter="16">
    <el-col v-for="c in cards" :key="c.label" :xs="12" :sm="8" :lg="6" :xl="3" class="col">
      <el-card v-loading="loading && !overview" shadow="never">
        <div class="label">{{ c.label }}</div>
        <div class="value" :style="{ color: c.color }">{{ c.value }}</div>
      </el-card>
    </el-col>
  </el-row>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { Overview } from '@/types'
import { formatNumber } from '@/utils/format'

const props = defineProps<{ overview: Overview | null; loading: boolean }>()

const cards = computed(() => {
  const o = props.overview
  const v = (n: number | undefined) => (o ? formatNumber(n) : '-')
  return [
    { label: '产品总数', value: v(o?.productCount), color: '#409eff' },
    { label: '设备总数', value: v(o?.deviceCount), color: '#409eff' },
    { label: '在线设备', value: v(o?.onlineCount), color: '#67c23a' },
    { label: '离线设备', value: v(o?.offlineCount), color: '#f56c6c' },
    { label: '未激活设备', value: v(o?.inactiveCount), color: '#909399' },
    { label: '今日告警', value: v(o?.todayAlarmCount), color: '#e6a23c' },
    { label: '未处理告警', value: v(o?.activeAlarmCount), color: '#f56c6c' },
    { label: '今日消息数', value: v(o?.todayMessageCount), color: '#409eff' },
  ]
})
</script>

<style scoped>
.col {
  margin-bottom: 16px;
}
.label {
  color: #909399;
  font-size: 13px;
}
.value {
  margin-top: 8px;
  font-size: 26px;
  font-weight: 600;
}
</style>
