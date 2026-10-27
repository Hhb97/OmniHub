<template>
  <el-tag :type="ONLINE_STATUS_TAG[status] ?? 'info'" :effect="effect" size="small">
    {{ ONLINE_STATUS_LABEL[status] ?? status }}
  </el-tag>
</template>

<script setup lang="ts">
import { ONLINE_STATUS_LABEL, ONLINE_STATUS_TAG } from '@/utils/constants'

withDefaults(defineProps<{ status: string; effect?: 'light' | 'dark' | 'plain' }>(), {
  effect: 'light',
})
</script>
