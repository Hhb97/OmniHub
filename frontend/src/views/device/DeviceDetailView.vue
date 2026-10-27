<template>
  <div class="page">
    <el-card v-loading="loading" shadow="never">
      <el-alert v-if="error" type="error" :title="error" show-icon :closable="false">
        <el-button link type="primary" @click="load">重试</el-button>
      </el-alert>
      <template v-if="device">
        <div class="head">
          <h3 class="name">{{ device.name }}</h3>
          <OnlineTag :status="device.onlineStatus" effect="dark" />
          <el-tag v-if="!device.enabled" type="danger" size="small">已禁用</el-tag>
          <div class="spacer" />
          <el-button @click="router.push('/devices')">返回列表</el-button>
        </div>
        <el-descriptions :column="2" size="small" class="desc">
          <el-descriptions-item label="DeviceKey"
            ><span class="mono">{{ device.deviceKey }}</span></el-descriptions-item
          >
          <el-descriptions-item label="所属产品">{{ device.productName }}</el-descriptions-item>
          <el-descriptions-item label="ClientId"
            ><span class="mono">{{ device.clientId || '-' }}</span></el-descriptions-item
          >
          <el-descriptions-item label="MQTT 连接">{{
            device.mqttConnected ? '已连接' : '未连接'
          }}</el-descriptions-item>
          <el-descriptions-item label="最近上线">{{
            formatTime(device.lastOnlineAt)
          }}</el-descriptions-item>
          <el-descriptions-item label="最近离线">{{
            formatTime(device.lastOfflineAt)
          }}</el-descriptions-item>
          <el-descriptions-item label="最近通信">{{
            formatTime(device.lastCommunicationAt)
          }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{
            formatTime(device.createdAt)
          }}</el-descriptions-item>
          <el-descriptions-item v-if="device.description" label="描述" :span="4">{{
            device.description
          }}</el-descriptions-item>
        </el-descriptions>
      </template>
    </el-card>

    <el-card v-if="device" shadow="never">
      <el-tabs v-model="tab">
        <el-tab-pane v-if="canTelemetry" label="实时数据" name="realtime" lazy>
          <RealtimeTab :device="device" :properties="properties" :props-loading="propsLoading" />
        </el-tab-pane>
        <el-tab-pane v-if="canTelemetry" label="历史数据" name="history" lazy>
          <HistoryTab :device="device" :properties="properties" />
        </el-tab-pane>
        <el-tab-pane v-if="canTelemetry" label="事件" name="events" lazy>
          <EventsTab :device="device" />
        </el-tab-pane>
        <el-tab-pane v-if="canTelemetry" label="通信记录" name="messages" lazy>
          <MessagesTab :device="device" />
        </el-tab-pane>
        <el-tab-pane v-if="canControl" label="设备控制" name="control" lazy>
          <ControlTab
            :device="device"
            :thing-model="thingModel"
            :properties="properties"
            :from-thing-model="fromThingModel"
          />
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import OnlineTag from '@/components/OnlineTag.vue'
import { deviceApi } from '@/api/device'
import { useThingModel } from '@/composables/useThingModel'
import { useWsSubscription } from '@/composables/useWsSubscription'
import { useAuthStore } from '@/stores/auth'
import type { Device } from '@/types'
import { formatTime } from '@/utils/format'
import ControlTab from './ControlTab.vue'
import EventsTab from './EventsTab.vue'
import HistoryTab from './HistoryTab.vue'
import MessagesTab from './MessagesTab.vue'
import RealtimeTab from './RealtimeTab.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const deviceId = computed(() => Number(route.params.id))
const canTelemetry = computed(() => auth.hasPermission('telemetry:view'))
const canControl = computed(() => auth.hasPermission('device:control'))

const device = ref<Device | null>(null)
const loading = ref(false)
const error = ref('')
const tab = ref(canTelemetry.value ? 'realtime' : 'control')

const {
  thingModel,
  props: properties,
  loading: propsLoading,
  fromThingModel,
} = useThingModel(
  deviceId,
  computed(() => device.value?.productId),
)

async function load() {
  loading.value = true
  error.value = ''
  try {
    device.value = await deviceApi.get(deviceId.value)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)

// 实时更新在线状态
useWsSubscription({
  deviceId: deviceId.value,
  onMessage: (msg) => {
    if (msg.type === 'status' && device.value) {
      device.value.onlineStatus = msg.onlineStatus
      device.value.mqttConnected = msg.onlineStatus === 'ONLINE'
      const iso = new Date(msg.ts).toISOString()
      if (msg.onlineStatus === 'ONLINE') device.value.lastOnlineAt = iso
      if (msg.onlineStatus === 'OFFLINE') device.value.lastOfflineAt = iso
    } else if (msg.type === 'property' && device.value) {
      device.value.lastCommunicationAt = new Date(msg.ts).toISOString()
    }
  },
})
</script>

<style scoped>
.head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.name {
  margin: 0;
}
.spacer {
  flex: 1;
}
</style>
