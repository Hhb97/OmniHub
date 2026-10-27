<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <el-input
          v-model.trim="query.username"
          placeholder="操作人"
          clearable
          style="width: 160px"
          @keyup.enter="search"
          @clear="search"
        />
        <el-input
          v-model.trim="query.keyword"
          placeholder="关键字（模块 / 操作 / 路径）"
          clearable
          style="width: 240px"
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
        <el-table-column label="时间" width="170"
          ><template #default="{ row }">{{ formatTime(row.createdAt) }}</template></el-table-column
        >
        <el-table-column prop="username" label="操作人" width="110" />
        <el-table-column prop="module" label="模块" width="110" />
        <el-table-column prop="action" label="操作" min-width="130" />
        <el-table-column prop="method" label="方法" width="80" />
        <el-table-column prop="path" label="路径" min-width="200" show-overflow-tooltip />
        <el-table-column prop="ip" label="IP" width="130" />
        <el-table-column label="结果" width="80">
          <template #default="{ row }"
            ><el-tag :type="row.success ? 'success' : 'danger'" size="small">{{
              row.success ? '成功' : '失败'
            }}</el-tag></template
          >
        </el-table-column>
        <el-table-column prop="costMs" label="耗时(ms)" width="90" align="right" />
        <el-table-column prop="detail" label="详情" min-width="200" show-overflow-tooltip />
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
import type { OperationLog } from '@/types'
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
} = useTable<OperationLog, { username: string; keyword: string }>(
  (p) => systemApi.operationLogs(p),
  {
    username: '',
    keyword: '',
  },
)
</script>
