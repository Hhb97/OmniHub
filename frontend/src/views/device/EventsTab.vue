<template>
  <div>
    <div class="toolbar bar">
      <el-select
        v-model="query.level"
        placeholder="事件级别"
        clearable
        style="width: 140px"
        @change="search"
      >
        <el-option
          v-for="(label, key) in EVENT_LEVEL_LABEL"
          :key="key"
          :label="label"
          :value="key"
        />
      </el-select>
      <el-button @click="load">刷新</el-button>
    </div>
    <el-table v-loading="loading" :data="rows" size="small" stripe>
      <template #empty><TableEmpty :error="error" @retry="load" /></template>
      <el-table-column label="时间" width="180"
        ><template #default="{ row }">{{ formatTime(row.ts) }}</template></el-table-column
      >
      <el-table-column label="级别" width="90"
        ><template #default="{ row }"><LevelTag :level="row.level" kind="event" /></template
      ></el-table-column>
      <el-table-column prop="identifier" label="事件标识" min-width="140" />
      <el-table-column prop="name" label="事件名称" min-width="130" />
      <el-table-column label="参数" min-width="240">
        <template #default="{ row }"
          ><span class="mono">{{ stringify(row.params) }}</span></template
        >
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
import AppPagination from '@/components/AppPagination.vue'
import LevelTag from '@/components/LevelTag.vue'
import TableEmpty from '@/components/TableEmpty.vue'
import { deviceApi } from '@/api/device'
import { useTable } from '@/composables/useTable'
import { useWsSubscription } from '@/composables/useWsSubscription'
import type { Device, DeviceEvent } from '@/types'
import { EVENT_LEVEL_LABEL } from '@/utils/constants'
import { formatTime, stringify } from '@/utils/format'
import { debounce } from '@/utils/debounce'

const props = defineProps<{ device: Device }>()

const {
  query,
  rows,
  total,
  page,
  size,
  loading,
  error,
  load,
  refresh,
  search,
  onPageChange,
  onSizeChange,
} = useTable<DeviceEvent, { level: string }>((p) => deviceApi.events(props.device.id, p), {
  level: '',
})

const onEvent = debounce(() => {
  if (page.value === 1) void refresh()
}, 500)

useWsSubscription({
  deviceId: props.device.id,
  onMessage: (msg) => {
    if (msg.type === 'event') onEvent()
  },
})
</script>

<style scoped>
.bar {
  margin-bottom: 12px;
}
</style>
