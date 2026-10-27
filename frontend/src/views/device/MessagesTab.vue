<template>
  <div>
    <div class="toolbar bar">
      <el-radio-group v-model="query.direction" @change="search">
        <el-radio-button value="">全部</el-radio-button>
        <el-radio-button value="UP">上行</el-radio-button>
        <el-radio-button value="DOWN">下行</el-radio-button>
      </el-radio-group>
      <el-button @click="load">刷新</el-button>
    </div>
    <el-table v-loading="loading" :data="rows" size="small" stripe row-key="id">
      <template #empty><TableEmpty :error="error" @retry="load" /></template>
      <el-table-column label="时间" width="180"
        ><template #default="{ row }">{{ formatTime(row.createdAt) }}</template></el-table-column
      >
      <el-table-column label="方向" width="80">
        <template #default="{ row }">
          <el-tag :type="row.direction === 'UP' ? 'success' : 'primary'" size="small">{{
            row.direction === 'UP' ? '上行' : '下行'
          }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="类型" width="80"
        ><template #default="{ row }">{{
          MESSAGE_TYPE_LABEL[row.type] ?? row.type
        }}</template></el-table-column
      >
      <el-table-column label="Topic" min-width="220"
        ><template #default="{ row }"
          ><span class="mono">{{ row.topic }}</span></template
        ></el-table-column
      >
      <el-table-column label="Payload" min-width="320">
        <template #default="{ row }">
          <pre v-if="expanded.has(row.id)" class="payload-pre">{{ prettyJson(row.payload) }}</pre>
          <span v-else class="mono">{{ truncate(row.payload) }}</span>
          <el-button
            v-if="isLong(row.payload)"
            link
            type="primary"
            size="small"
            @click="toggle(row.id)"
          >
            {{ expanded.has(row.id) ? '收起' : '展开' }}
          </el-button>
        </template>
      </el-table-column>
      <el-table-column label="结果" width="100">
        <template #default="{ row }">
          <el-tooltip :disabled="!row.message" :content="row.message" placement="top">
            <el-tag
              :type="
                row.result === 'OK' ? 'success' : row.result === 'INVALID' ? 'warning' : 'danger'
              "
              size="small"
              >{{ row.result }}</el-tag
            >
          </el-tooltip>
        </template>
      </el-table-column>
    </el-table>
    <AppPagination
      :page="page"
      :size="size"
      :total="total"
      @page-change="onPageChange"
      @size-change="onSizeChange"
    />
  </div>
</template>

<script setup lang="ts">
import { reactive } from 'vue'
import AppPagination from '@/components/AppPagination.vue'
import TableEmpty from '@/components/TableEmpty.vue'
import { deviceApi } from '@/api/device'
import { useTable } from '@/composables/useTable'
import type { Device, DeviceMessage, MessageDirection } from '@/types'
import { MESSAGE_TYPE_LABEL } from '@/utils/constants'
import { formatTime, prettyJson, stringify } from '@/utils/format'

const props = defineProps<{ device: Device }>()

const LIMIT = 80
const expanded = reactive(new Set<number>())

const { query, rows, total, page, size, loading, error, load, search, onPageChange, onSizeChange } =
  useTable<DeviceMessage, { direction: MessageDirection | '' }>(
    (p) => deviceApi.messages(props.device.id, p),
    { direction: '' },
  )

function truncate(payload: unknown): string {
  const s = stringify(payload)
  return s.length > LIMIT ? `${s.slice(0, LIMIT)}…` : s
}
function isLong(payload: unknown): boolean {
  return stringify(payload).length > LIMIT
}
function toggle(id: number) {
  if (expanded.has(id)) expanded.delete(id)
  else expanded.add(id)
}
</script>

<style scoped>
.bar {
  margin-bottom: 12px;
}
</style>
