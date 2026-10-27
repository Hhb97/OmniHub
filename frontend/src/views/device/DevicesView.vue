<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <el-input
          v-model.trim="query.keyword"
          placeholder="名称 / DeviceKey"
          clearable
          style="width: 200px"
          @keyup.enter="search"
          @clear="search"
        />
        <el-select
          v-model="query.productId"
          placeholder="产品"
          clearable
          filterable
          style="width: 180px"
          @change="search"
        >
          <el-option v-for="p in productOptions" :key="p.id" :label="p.name" :value="p.id" />
        </el-select>
        <el-select
          v-model="query.onlineStatus"
          placeholder="在线状态"
          clearable
          style="width: 130px"
          @change="search"
        >
          <el-option
            v-for="(label, key) in ONLINE_STATUS_LABEL"
            :key="key"
            :label="label"
            :value="key"
          />
        </el-select>
        <el-select
          v-model="query.enabled"
          placeholder="启用状态"
          clearable
          style="width: 120px"
          @change="search"
        >
          <el-option label="已启用" :value="true" />
          <el-option label="已禁用" :value="false" />
        </el-select>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
        <div class="spacer" />
        <el-button v-permission="'device:create'" type="primary" @click="openCreate"
          >新建设备</el-button
        >
      </div>
    </el-card>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows" stripe>
        <template #empty><TableEmpty :error="error" @retry="load" /></template>
        <el-table-column label="设备名称" min-width="150">
          <template #default="{ row }"
            ><router-link :to="`/devices/${row.id}`">{{ row.name }}</router-link></template
          >
        </el-table-column>
        <el-table-column prop="deviceKey" label="DeviceKey" min-width="150"
          ><template #default="{ row }"
            ><span class="mono">{{ row.deviceKey }}</span></template
          ></el-table-column
        >
        <el-table-column prop="productName" label="所属产品" min-width="130" />
        <el-table-column label="在线状态" width="90"
          ><template #default="{ row }"><OnlineTag :status="row.onlineStatus" /></template
        ></el-table-column>
        <el-table-column label="启用" width="80">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabled"
              :disabled="!canUpdate"
              :before-change="() => toggleEnabled(row as Device)"
            />
          </template>
        </el-table-column>
        <el-table-column label="最近通信" width="170"
          ><template #default="{ row }">{{
            formatTime(row.lastCommunicationAt)
          }}</template></el-table-column
        >
        <el-table-column label="创建时间" width="170"
          ><template #default="{ row }">{{ formatTime(row.createdAt) }}</template></el-table-column
        >
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="router.push(`/devices/${row.id}`)"
              >详情</el-button
            >
            <el-button
              v-permission="'device:update'"
              link
              type="primary"
              @click="openEdit(row as Device)"
              >编辑</el-button
            >
            <el-button
              v-permission="'device:credential'"
              link
              type="warning"
              @click="rotate(row as Device)"
              >重置密钥</el-button
            >
            <el-button
              v-permission="'device:delete'"
              link
              type="danger"
              @click="remove(row as Device)"
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

    <DeviceFormDialog v-model="dialog" :device="editing" @saved="onSaved" />
    <SecretDialog
      v-model="secretDialog"
      :device="secretDevice"
      :secret="secret"
      :title="secretTitle"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import AppPagination from '@/components/AppPagination.vue'
import OnlineTag from '@/components/OnlineTag.vue'
import TableEmpty from '@/components/TableEmpty.vue'
import { deviceApi } from '@/api/device'
import { useProductOptions } from '@/composables/useOptions'
import { useTable } from '@/composables/useTable'
import { useAuthStore } from '@/stores/auth'
import type { Device, DeviceCreated, DeviceQuery } from '@/types'
import { ONLINE_STATUS_LABEL } from '@/utils/constants'
import { formatTime } from '@/utils/format'
import DeviceFormDialog from './DeviceFormDialog.vue'
import SecretDialog from './SecretDialog.vue'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const canUpdate = computed(() => auth.hasPermission('device:update'))
const { options: productOptions } = useProductOptions()

const initialProductId = route.query.productId ? Number(route.query.productId) : undefined
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
} = useTable<Device, DeviceQuery>((p) => deviceApi.list(p), {
  keyword: '',
  productId: initialProductId,
  onlineStatus: '',
  enabled: '',
})

const dialog = ref(false)
const editing = ref<Device | null>(null)
const secretDialog = ref(false)
const secretDevice = ref<Device | null>(null)
const secret = ref('')
const secretTitle = ref('')

// 密钥只在对话框期间保留在内存中
watch(secretDialog, (v) => {
  if (!v) secret.value = ''
})

onMounted(() => {
  if (route.query.create) openCreate()
})

function openCreate() {
  editing.value = null
  dialog.value = true
}
function openEdit(d: Device) {
  editing.value = d
  dialog.value = true
}

function showSecret(device: Device, s: string, title: string) {
  secretDevice.value = device
  secret.value = s
  secretTitle.value = title
  secretDialog.value = true
}

async function onSaved(created?: DeviceCreated) {
  await load()
  if (created) {
    const { secret: s, ...device } = created
    showSecret(device, s, '设备创建成功')
  }
}

async function toggleEnabled(d: Device): Promise<boolean> {
  const next = !d.enabled
  try {
    await ElMessageBox.confirm(
      next
        ? `确定启用设备「${d.name}」吗？`
        : `确定禁用设备「${d.name}」吗？禁用后设备将无法接入。`,
      next ? '启用确认' : '禁用确认',
      { type: 'warning' },
    )
  } catch {
    return false
  }
  try {
    await deviceApi.setEnabled(d.id, next)
    d.enabled = next
    ElMessage.success(next ? '已启用' : '已禁用')
    return true
  } catch {
    return false
  }
}

async function rotate(d: Device) {
  try {
    await ElMessageBox.confirm(
      `重置后旧密钥将立即失效，设备「${d.name}」会被踢下线，需使用新密钥重新接入。确定继续吗？`,
      '重置密钥确认',
      { type: 'warning', confirmButtonText: '确定重置' },
    )
  } catch {
    return
  }
  try {
    const res = await deviceApi.rotateSecret(d.id)
    showSecret(d, res.secret, '密钥已重置')
    await load()
  } catch {
    /* 已提示 */
  }
}

async function remove(d: Device) {
  try {
    await ElMessageBox.confirm(
      `确定删除设备「${d.name}」吗？其历史数据将一并删除，且不可恢复。`,
      '删除确认',
      {
        type: 'warning',
        confirmButtonText: '删除',
      },
    )
  } catch {
    return
  }
  try {
    await deviceApi.remove(d.id)
    ElMessage.success('已删除')
    await load()
  } catch {
    /* 已提示 */
  }
}
</script>
