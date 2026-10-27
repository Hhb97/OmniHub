<template>
  <el-card shadow="never">
    <template #header><span class="card-title">最近上线设备</span></template>
    <el-table v-loading="loading" :data="data ?? []" size="small" max-height="320">
      <template #empty><TableEmpty :error="error" @retry="reload" /></template>
      <el-table-column label="设备名称" min-width="140">
        <template #default="{ row }">
          <router-link :to="`/devices/${row.id}`">{{ row.name }}</router-link>
        </template>
      </el-table-column>
      <el-table-column prop="productName" label="产品" min-width="120" />
      <el-table-column label="状态" width="80">
        <template #default="{ row }"><OnlineTag :status="row.onlineStatus" /></template>
      </el-table-column>
      <el-table-column label="最近上线" width="170">
        <template #default="{ row }">{{ formatTime(row.lastOnlineAt) }}</template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import { watch } from 'vue'
import OnlineTag from '@/components/OnlineTag.vue'
import TableEmpty from '@/components/TableEmpty.vue'
import { dashboardApi } from '@/api/dashboard'
import { useAsyncData } from '@/composables/useAsyncData'
import { formatTime } from '@/utils/format'

const props = defineProps<{ refreshKey: number }>()
const { data, loading, error, reload } = useAsyncData(() => dashboardApi.recentDevices(10))
watch(() => props.refreshKey, reload)
</script>
