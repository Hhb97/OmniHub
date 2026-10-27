<template>
  <div v-if="total > 0" class="pagination">
    <el-pagination
      :current-page="page"
      :page-size="size"
      :total="total"
      :page-sizes="[10, 20, 50, 100]"
      :pager-count="5"
      background
      layout="total, sizes, prev, pager, next"
      @current-change="(p: number) => emit('page-change', p)"
      @size-change="(s: number) => emit('size-change', s)"
    />
  </div>
</template>

<script setup lang="ts">
defineProps<{ page: number; size: number; total: number }>()
const emit = defineEmits<{
  (e: 'page-change', page: number): void
  (e: 'size-change', size: number): void
}>()
</script>
