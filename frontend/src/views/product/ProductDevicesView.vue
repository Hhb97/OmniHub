<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <el-button @click="router.push('/products')">返回产品列表</el-button>
        <span v-if="product">
          产品：<b>{{ product.name }}</b>
          <span class="muted mono">（{{ product.productKey }}）</span>
        </span>
        <div class="spacer" />
        <el-button
          v-permission="'device:view'"
          type="primary"
          link
          @click="router.push({ path: '/devices', query: { productId: String(productId) } })"
        >
          在设备管理中查看
        </el-button>
      </div>
    </el-card>
    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows" stripe>
        <template #empty><TableEmpty :error="error" @retry="load" /></template>
        <el-table-column label="设备名称" min-width="160">
          <template #default="{ row }">
            <router-link :to="`/devices/${row.id}`">{{ row.name }}</router-link>
          </template>
        </el-table-column>
        <el-table-column prop="deviceKey" label="DeviceKey" min-width="160">
          <template #default="{ row }"
            ><span class="mono">{{ row.deviceKey }}</span></template
          >
        </el-table-column>
        <el-table-column label="在线状态" width="100">
          <template #default="{ row }"><OnlineTag :status="row.onlineStatus" /></template>
        </el-table-column>
        <el-table-column label="启用" width="80">
          <template #default="{ row }">{{ row.enabled ? '是' : '否' }}</template>
        </el-table-column>
        <el-table-column label="最近通信" width="170">
          <template #default="{ row }">{{ formatTime(row.lastCommunicationAt) }}</template>
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
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppPagination from '@/components/AppPagination.vue'
import OnlineTag from '@/components/OnlineTag.vue'
import TableEmpty from '@/components/TableEmpty.vue'
import { productApi } from '@/api/product'
import { useTable } from '@/composables/useTable'
import type { Device, Product } from '@/types'
import { formatTime } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const productId = computed(() => Number(route.params.id))
const product = ref<Product | null>(null)

const { rows, total, page, size, loading, error, load, onPageChange, onSizeChange } = useTable<
  Device,
  object
>((p) => productApi.devices(productId.value, { page: p.page, size: p.size }), {})

onMounted(async () => {
  try {
    product.value = await productApi.get(productId.value)
  } catch {
    /* 已提示 */
  }
})
</script>
