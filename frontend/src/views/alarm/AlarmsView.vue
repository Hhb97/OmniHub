<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <el-select
          v-model="query.status"
          placeholder="状态"
          clearable
          style="width: 120px"
          @change="search"
        >
          <el-option label="活动中" value="ACTIVE" />
          <el-option label="已恢复" value="RECOVERED" />
        </el-select>
        <el-select
          v-model="query.acknowledged"
          placeholder="确认状态"
          clearable
          style="width: 120px"
          @change="search"
        >
          <el-option label="已确认" :value="true" />
          <el-option label="未确认" :value="false" />
        </el-select>
        <el-select
          v-model="query.level"
          placeholder="级别"
          clearable
          style="width: 110px"
          @change="search"
        >
          <el-option
            v-for="(label, key) in ALARM_LEVEL_LABEL"
            :key="key"
            :label="label"
            :value="key"
          />
        </el-select>
        <el-select
          v-model="query.deviceId"
          placeholder="设备"
          clearable
          filterable
          remote
          :remote-method="(k: string) => searchDevices(k)"
          :loading="deviceLoading"
          style="width: 180px"
          @focus="searchDevices('')"
          @change="onDeviceChange"
        >
          <el-option v-for="d in deviceOptions" :key="d.id" :label="d.name" :value="d.id" />
        </el-select>
        <el-date-picker
          v-model="range"
          type="datetimerange"
          range-separator="至"
          start-placeholder="触发开始"
          end-placeholder="触发结束"
          style="width: 340px; max-width: 100%"
          @change="onRangeChange"
        />
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="onReset">重置</el-button>
      </div>
    </el-card>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows" stripe>
        <template #empty><TableEmpty :error="error" @retry="load" /></template>
        <el-table-column label="级别" width="80"
          ><template #default="{ row }"><LevelTag :level="row.level" /></template
        ></el-table-column>
        <el-table-column prop="ruleName" label="规则" min-width="140" />
        <el-table-column label="设备" min-width="130">
          <template #default="{ row }"
            ><router-link :to="`/devices/${row.deviceId}`">{{
              row.deviceName
            }}</router-link></template
          >
        </el-table-column>
        <el-table-column label="类型" width="70"
          ><template #default="{ row }">{{
            RULE_TYPE_LABEL[row.ruleType] ?? row.ruleType
          }}</template></el-table-column
        >
        <el-table-column prop="message" label="告警信息" min-width="200" show-overflow-tooltip />
        <el-table-column label="触发次数" width="80" align="right"
          ><template #default="{ row }">{{ row.triggerCount ?? '-' }}</template></el-table-column
        >
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ACTIVE' ? 'danger' : 'success'" size="small">{{
              row.status === 'ACTIVE' ? '活动中' : '已恢复'
            }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="确认" width="130">
          <template #default="{ row }">
            <el-tag v-if="row.acknowledged" type="success" size="small" effect="plain"
              >已确认</el-tag
            >
            <el-tag v-else type="warning" size="small" effect="plain">未确认</el-tag>
            <div v-if="row.acknowledgedBy" class="muted small">{{ row.acknowledgedBy }}</div>
          </template>
        </el-table-column>
        <el-table-column label="首次触发" width="170"
          ><template #default="{ row }">{{
            formatTime(row.firstTriggeredAt)
          }}</template></el-table-column
        >
        <el-table-column label="最近触发" width="170"
          ><template #default="{ row }">{{
            formatTime(row.lastTriggeredAt)
          }}</template></el-table-column
        >
        <el-table-column label="恢复时间" width="170"
          ><template #default="{ row }">{{
            formatTime(row.recoveredAt)
          }}</template></el-table-column
        >
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button
              v-permission="'alarm:ack'"
              link
              type="primary"
              :disabled="row.acknowledged"
              @click="openRemark('ack', row as Alarm)"
              >确认</el-button
            >
            <el-button
              v-permission="'alarm:ack'"
              link
              type="primary"
              @click="openRemark('comment', row as Alarm)"
              >备注</el-button
            >
            <el-button link type="primary" @click="openActions(row as Alarm)">处理记录</el-button>
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
    </el-card>

    <AlarmRemarkDialog v-model="remarkDialog" :mode="remarkMode" :alarm="current" @done="refresh" />
    <AlarmActionsDrawer v-model="actionsDrawer" :alarm="current" />
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import AppPagination from '@/components/AppPagination.vue'
import LevelTag from '@/components/LevelTag.vue'
import TableEmpty from '@/components/TableEmpty.vue'
import { alarmApi } from '@/api/alarm'
import { useDeviceSearch } from '@/composables/useOptions'
import { useTable } from '@/composables/useTable'
import { useWsSubscription } from '@/composables/useWsSubscription'
import type { Alarm, AlarmQuery } from '@/types'
import { ALARM_LEVEL_LABEL, RULE_TYPE_LABEL } from '@/utils/constants'
import { debounce } from '@/utils/debounce'
import { formatTime, toIso } from '@/utils/format'
import AlarmActionsDrawer from './AlarmActionsDrawer.vue'
import AlarmRemarkDialog from './AlarmRemarkDialog.vue'

const { options: deviceOptions, loading: deviceLoading, search: searchDevices } = useDeviceSearch()

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
  reset,
  onPageChange,
  onSizeChange,
} = useTable<Alarm, AlarmQuery>((p) => alarmApi.list(p), {
  status: '',
  acknowledged: '',
  level: '',
  deviceId: undefined,
  start: undefined,
  end: undefined,
})

const range = ref<[Date, Date] | null>(null)
function onRangeChange(v: [Date, Date] | null) {
  query.start = v ? toIso(v[0]) : undefined
  query.end = v ? toIso(v[1]) : undefined
  void search()
}

const deviceIdRef = ref<number | undefined>()
function onDeviceChange(id: number | undefined) {
  deviceIdRef.value = id
  void search()
}

async function onReset() {
  range.value = null
  deviceIdRef.value = undefined
  await reset()
}

const remarkDialog = ref(false)
const remarkMode = ref<'ack' | 'comment'>('ack')
const actionsDrawer = ref(false)
const current = ref<Alarm | null>(null)

function openRemark(mode: 'ack' | 'comment', a: Alarm) {
  remarkMode.value = mode
  current.value = a
  remarkDialog.value = true
}
function openActions(a: Alarm) {
  current.value = a
  actionsDrawer.value = true
}

// 实时刷新：dashboard 主题（任意告警变化）+ 筛选了设备时的设备 alarm 消息
const onLive = debounce(() => void refresh(), 800)
useWsSubscription({
  topic: 'dashboard',
  deviceId: deviceIdRef,
  onMessage: (msg) => {
    if (msg.type === 'dashboard' || msg.type === 'alarm') onLive()
  },
})
</script>

<style scoped>
.small {
  font-size: 12px;
}
</style>
