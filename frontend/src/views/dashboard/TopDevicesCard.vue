<template>
  <el-card shadow="never">
    <template #header><span class="card-title">活跃设备 Top 5</span></template>
    <el-table v-loading="loading" :data="data ?? []" size="small" max-height="320">
      <template #empty><TableEmpty :error="error" @retry="reload" /></template>
      <el-table-column type="index" label="#" width="50" />
      <el-table-column label="设备" min-width="120">
        <template #default="{ row }">
          <router-link :to="`/devices/${row.deviceId}`">{{ row.name }}</router-link>
        </template>
      </el-table-column>
      <el-table-column label="消息数" width="100" align="right">
        <template #default="{ row }">{{ formatNumber(row.messageCount) }}</template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import { watch } from 'vue'
import TableEmpty from '@/components/TableEmpty.vue'
import { dashboardApi } from '@/api/dashboard'
import { useAsyncData } from '@/composables/useAsyncData'
import { formatNumber } from '@/utils/format'

const props = defineProps<{ refreshKey: number }>()
const { data, loading, error, reload } = useAsyncData(() => dashboardApi.topDevices(5))
watch(() => props.refreshKey, reload)
</script>
