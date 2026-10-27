<template>
  <el-drawer v-model="visible" title="告警处理记录" size="420px" @open="load">
    <div v-loading="loading" class="body">
      <el-alert v-if="error" type="error" :title="error" show-icon :closable="false">
        <el-button link type="primary" @click="load">重试</el-button>
      </el-alert>
      <el-empty v-else-if="!loading && actions.length === 0" description="暂无处理记录" />
      <el-timeline v-else>
        <el-timeline-item
          v-for="a in actions"
          :key="a.id"
          :timestamp="formatTime(a.createdAt)"
          :type="TYPE[a.action]"
          placement="top"
        >
          <b>{{ ALARM_ACTION_LABEL[a.action] ?? a.action }}</b>
          <span v-if="a.operator" class="muted"> · {{ a.operator }}</span>
          <div v-if="a.remark" class="remark">{{ a.remark }}</div>
        </el-timeline-item>
      </el-timeline>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { alarmApi } from '@/api/alarm'
import type { Alarm, AlarmAction } from '@/types'
import { ALARM_ACTION_LABEL } from '@/utils/constants'
import { formatTime } from '@/utils/format'

const props = defineProps<{ alarm: Alarm | null }>()
const visible = defineModel<boolean>({ default: false })

const TYPE: Record<string, 'danger' | 'primary' | 'info' | 'success'> = {
  TRIGGER: 'danger',
  ACK: 'primary',
  COMMENT: 'info',
  RECOVER: 'success',
}

const actions = ref<AlarmAction[]>([])
const loading = ref(false)
const error = ref('')

async function load() {
  if (!props.alarm) return
  loading.value = true
  error.value = ''
  actions.value = []
  try {
    actions.value = await alarmApi.actions(props.alarm.id)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.body {
  min-height: 120px;
}
.remark {
  margin-top: 4px;
  color: #606266;
  white-space: pre-wrap;
}
</style>
