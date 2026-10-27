<template>
  <el-tag :type="tag[level] ?? 'info'" size="small">{{ label[level] ?? level }}</el-tag>
</template>

<script setup lang="ts">
import {
  ALARM_LEVEL_LABEL,
  ALARM_LEVEL_TAG,
  EVENT_LEVEL_LABEL,
  EVENT_LEVEL_TAG,
} from '@/utils/constants'
import { computed } from 'vue'

const props = withDefaults(defineProps<{ level: string; kind?: 'alarm' | 'event' }>(), {
  kind: 'alarm',
})
const label = computed(() => (props.kind === 'alarm' ? ALARM_LEVEL_LABEL : EVENT_LEVEL_LABEL))
const tag = computed(() => (props.kind === 'alarm' ? ALARM_LEVEL_TAG : EVENT_LEVEL_TAG))
</script>
