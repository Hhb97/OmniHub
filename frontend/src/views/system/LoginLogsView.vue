<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <el-input
          v-model.trim="query.username"
          placeholder="用户名"
          clearable
          style="width: 180px"
          @keyup.enter="search"
          @clear="search"
        />
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </div>
    </el-card>
    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows" stripe size="small">
        <template #empty><TableEmpty :error="error" @retry="load" /></template>
        <el-table-column label="时间" width="180"
          ><template #default="{ row }">{{ formatTime(row.createdAt) }}</template></el-table-column
        >
        <el-table-column prop="username" label="用户名" min-width="140" />
        <el-table-column prop="ip" label="IP" width="150" />
        <el-table-column label="结果" width="90">
          <template #default="{ row }"
            ><el-tag :type="row.success ? 'success' : 'danger'" size="small">{{
              row.success ? '成功' : '失败'
            }}</el-tag></template
          >
        </el-table-column>
        <el-table-column prop="message" label="说明" min-width="220" show-overflow-tooltip />
      </el-table>
      <AppPagination
        :page="page"
        :size="size"
        :total="total"
        @page-change="onPageChange"
        @size-change="onSizeChange"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import AppPagination from '@/components/AppPagination.vue'
import TableEmpty from '@/components/TableEmpty.vue'
import { systemApi } from '@/api/system'
import { useTable } from '@/composables/useTable'
import type { LoginLog } from '@/types'
import { formatTime } from '@/utils/format'

const {
  query,
  rows,
  total,
  page,
  size,
  loading,
  error,
  load,
  search,
  reset,
  onPageChange,
  onSizeChange,
} = useTable<LoginLog, { username: string }>((p) => systemApi.loginLogs(p), { username: '' })
</script>
