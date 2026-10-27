<template>
  <el-descriptions :column="1" size="small" border>
    <el-descriptions-item label="指令编号"
      ><span class="mono">{{ command.commandNo }}</span></el-descriptions-item
    >
    <el-descriptions-item label="类型 / 标识">
      {{ COMMAND_TYPE_LABEL[command.type] ?? command.type }}
      <span v-if="command.identifier" class="mono"> · {{ command.identifier }}</span>
    </el-descriptions-item>
    <el-descriptions-item label="状态">
      <el-tag :type="COMMAND_STATUS_TAG[command.status] ?? 'info'" size="small">
        {{ COMMAND_STATUS_LABEL[command.status] ?? command.status }}
      </el-tag>
    </el-descriptions-item>
    <el-descriptions-item label="参数">
      <pre class="payload-pre">{{ prettyJson(command.params ?? {}) }}</pre>
    </el-descriptions-item>
    <el-descriptions-item
      v-if="command.result !== undefined && command.result !== null"
      label="执行结果"
    >
      <pre class="payload-pre">{{ prettyJson(command.result) }}</pre>
    </el-descriptions-item>
    <el-descriptions-item v-if="command.message" label="说明">{{
      command.message
    }}</el-descriptions-item>
    <el-descriptions-item label="创建人">{{ command.createdBy || '-' }}</el-descriptions-item>
    <el-descriptions-item label="创建时间">{{
      formatTime(command.createdAt)
    }}</el-descriptions-item>
    <el-descriptions-item label="发送时间">{{ formatTime(command.sentAt) }}</el-descriptions-item>
    <el-descriptions-item label="设备接收">{{
      formatTime(command.receivedAt)
    }}</el-descriptions-item>
    <el-descriptions-item label="完成时间">{{
      formatTime(command.finishedAt)
    }}</el-descriptions-item>
  </el-descriptions>
</template>

<script setup lang="ts">
import type { Command } from '@/types'
import { COMMAND_STATUS_LABEL, COMMAND_STATUS_TAG, COMMAND_TYPE_LABEL } from '@/utils/constants'
import { formatTime, prettyJson } from '@/utils/format'

defineProps<{ command: Command }>()
</script>
