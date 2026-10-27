<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <el-input
          v-model.trim="query.keyword"
          placeholder="名称 / ProductKey"
          clearable
          style="width: 220px"
          @keyup.enter="search"
          @clear="search"
        />
        <el-input
          v-model.trim="query.category"
          placeholder="分类"
          clearable
          style="width: 160px"
          @keyup.enter="search"
          @clear="search"
        />
        <el-select
          v-model="query.status"
          placeholder="状态"
          clearable
          style="width: 120px"
          @change="search"
        >
          <el-option label="启用" value="ENABLED" />
          <el-option label="停用" value="DISABLED" />
        </el-select>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
        <div class="spacer" />
        <el-button v-permission="'product:create'" type="primary" @click="openCreate"
          >新建产品</el-button
        >
      </div>
    </el-card>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows" stripe>
        <template #empty><TableEmpty :error="error" @retry="load" /></template>
        <el-table-column prop="name" label="产品名称" min-width="150" />
        <el-table-column prop="productKey" label="ProductKey" min-width="150">
          <template #default="{ row }"
            ><span class="mono">{{ row.productKey }}</span></template
          >
        </el-table-column>
        <el-table-column prop="category" label="分类" min-width="100" />
        <el-table-column prop="protocol" label="协议" width="90" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ENABLED' ? 'success' : 'info'" size="small">
              {{ PRODUCT_STATUS_LABEL[row.status] ?? row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="deviceCount" label="设备数" width="80" align="right" />
        <el-table-column label="物模型版本" width="100" align="center">
          <template #default="{ row }">{{
            row.thingModelVersion ? `v${row.thingModelVersion}` : '-'
          }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="290" fixed="right">
          <template #default="{ row }">
            <el-button
              v-permission="'thing:view'"
              link
              type="primary"
              @click="router.push(`/products/${row.id}/thing-model`)"
              >物模型</el-button
            >
            <el-button link type="primary" @click="router.push(`/products/${row.id}/devices`)"
              >设备</el-button
            >
            <el-button
              v-permission="'product:update'"
              link
              type="primary"
              @click="openEdit(row as Product)"
              >编辑</el-button
            >
            <el-button
              v-permission="'product:delete'"
              link
              type="danger"
              @click="remove(row as Product)"
              >删除</el-button
            >
          </template>
        </el-table-column>
      </el-table>
      <AppPagination
        :page="page"
        :size="size"
        :total="total"
        @page-change="onPageChange"
        @size-change="onSizeChange"
      />
    </el-card>

    <ProductFormDialog v-model="dialog" :product="editing" @saved="load" />
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import AppPagination from '@/components/AppPagination.vue'
import TableEmpty from '@/components/TableEmpty.vue'
import { productApi } from '@/api/product'
import { useTable } from '@/composables/useTable'
import type { Product, ProductQuery } from '@/types'
import { PRODUCT_STATUS_LABEL } from '@/utils/constants'
import { formatTime } from '@/utils/format'
import ProductFormDialog from './ProductFormDialog.vue'

const router = useRouter()
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
} = useTable<Product, ProductQuery>((p) => productApi.list(p), {
  keyword: '',
  category: '',
  status: '',
})

const dialog = ref(false)
const editing = ref<Product | null>(null)

function openCreate() {
  editing.value = null
  dialog.value = true
}
function openEdit(p: Product) {
  editing.value = p
  dialog.value = true
}

async function remove(p: Product) {
  try {
    await ElMessageBox.confirm(`确定删除产品「${p.name}」吗？此操作不可恢复。`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await productApi.remove(p.id)
    ElMessage.success('已删除')
    await load()
  } catch {
    /* 409 等错误已由请求层提示 */
  }
}
</script>
