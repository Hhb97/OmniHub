<template>
  <el-dialog
    v-model="visible"
    :title="title"
    width="640px"
    :close-on-click-modal="false"
    :close-on-press-escape="false"
    @closed="confirmed = false"
  >
    <el-alert
      type="warning"
      show-icon
      :closable="false"
      title="设备密钥仅显示这一次，关闭后将无法再次查看，请立即复制并妥善保存！"
    />
    <el-descriptions v-if="device" :column="1" border class="desc">
      <el-descriptions-item label="设备名称">{{ device.name }}</el-descriptions-item>
      <el-descriptions-item label="DeviceKey"
        ><span class="mono">{{ device.deviceKey }}</span></el-descriptions-item
      >
      <el-descriptions-item label="设备密钥 (Secret)">
        <span class="mono secret">{{ secret }}</span>
        <el-button type="primary" link @click="copyText(secret, '密钥已复制')">复制</el-button>
      </el-descriptions-item>
    </el-descriptions>

    <h4>MQTT 连接信息</h4>
    <el-descriptions v-if="device" :column="1" border size="small">
      <el-descriptions-item label="Broker">{{ broker }}</el-descriptions-item>
      <el-descriptions-item label="ClientId">
        <span class="mono">{{ clientId }}</span>
        <el-button link type="primary" @click="copyText(clientId)">复制</el-button>
      </el-descriptions-item>
      <el-descriptions-item label="Username（= ClientId）"
        ><span class="mono">{{ clientId }}</span></el-descriptions-item
      >
      <el-descriptions-item label="Password">设备密钥（Secret）</el-descriptions-item>
    </el-descriptions>
    <el-table v-if="topics.length" :data="topics" size="small" class="topics">
      <el-table-column prop="label" label="用途" width="90" />
      <el-table-column prop="direction" label="方向" width="140" />
      <el-table-column label="Topic" min-width="260">
        <template #default="{ row }"
          ><span class="mono">{{ row.topic }}</span></template
        >
      </el-table-column>
    </el-table>
    <p class="muted tip">Topic 规范详见 docs/mqtt-protocol.md；clientId 与用户名相同，密码为上方设备密钥。</p>

    <template #footer>
      <el-checkbox v-model="confirmed">我已复制并保存密钥</el-checkbox>
      <el-button type="primary" :disabled="!confirmed" @click="visible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import type { Device } from '@/types'
import { copyText } from '@/utils/clipboard'
import { mqttBroker, mqttTopics } from '@/utils/mqtt'

const props = defineProps<{ device: Device | null; secret: string; title: string }>()
const visible = defineModel<boolean>({ default: false })
const confirmed = ref(false)

const broker = mqttBroker()
const clientId = computed(() => props.device?.clientId || props.device?.deviceKey || '')
const topics = computed(() =>
  props.device?.productKey ? mqttTopics(props.device.productKey, props.device.deviceKey) : [],
)
</script>

<style scoped>
.desc {
  margin-top: 16px;
}
.secret {
  word-break: break-all;
  margin-right: 8px;
  font-weight: 600;
}
.topics {
  margin-top: 12px;
}
.tip {
  font-size: 12px;
}
h4 {
  margin: 16px 0 8px;
}
</style>
