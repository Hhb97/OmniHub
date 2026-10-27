<template>
  <div v-if="error" class="table-error">
    <el-result icon="error" :sub-title="error">
      <template #extra>
        <el-button type="primary" @click="emit('retry')">重试</el-button>
      </template>
    </el-result>
  </div>
  <el-empty v-else :description="text" :image-size="80" />
</template>

<script setup lang="ts">
withDefaults(defineProps<{ error?: string; text?: string }>(), { error: '', text: '暂无数据' })
const emit = defineEmits<{ (e: 'retry'): void }>()
</script>

<style scoped>
.table-error :deep(.el-result) {
  padding: 16px 0;
}
</style>
