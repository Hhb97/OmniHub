<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <el-input
          v-model.trim="query.keyword"
          placeholder="用户名 / 昵称"
          clearable
          style="width: 220px"
          @keyup.enter="search"
          @clear="search"
        />
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
        <div class="spacer" />
        <el-button type="primary" @click="openCreate">新建用户</el-button>
      </div>
    </el-card>
    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows" stripe>
        <template #empty><TableEmpty :error="error" @retry="load" /></template>
        <el-table-column prop="username" label="用户名" min-width="120" />
        <el-table-column prop="nickname" label="昵称" min-width="120" />
        <el-table-column prop="email" label="邮箱" min-width="180" />
        <el-table-column label="角色" min-width="160">
          <template #default="{ row }">
            <el-tag v-for="r in row.roles" :key="r" size="small" class="role">{{ r }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{
              row.enabled ? '启用' : '停用'
            }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="170"
          ><template #default="{ row }">{{ formatTime(row.createdAt) }}</template></el-table-column
        >
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row as SysUser)">编辑</el-button>
            <el-button link type="warning" @click="openReset(row as SysUser)">重置密码</el-button>
            <el-button
              link
              type="danger"
              :disabled="row.id === auth.user?.id"
              @click="remove(row as SysUser)"
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
    <UserFormDialog v-model="dialog" :user="editing" @saved="load" />
    <ResetPasswordDialog v-model="resetDialog" :user="editing" />
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import AppPagination from '@/components/AppPagination.vue'
import TableEmpty from '@/components/TableEmpty.vue'
import { systemApi } from '@/api/system'
import { useTable } from '@/composables/useTable'
import { useAuthStore } from '@/stores/auth'
import type { SysUser } from '@/types'
import { formatTime } from '@/utils/format'
import ResetPasswordDialog from './ResetPasswordDialog.vue'
import UserFormDialog from './UserFormDialog.vue'

const auth = useAuthStore()
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
} = useTable<SysUser, { keyword: string }>((p) => systemApi.listUsers(p), { keyword: '' })

const dialog = ref(false)
const resetDialog = ref(false)
const editing = ref<SysUser | null>(null)

function openCreate() {
  editing.value = null
  dialog.value = true
}
function openEdit(u: SysUser) {
  editing.value = u
  dialog.value = true
}
function openReset(u: SysUser) {
  editing.value = u
  resetDialog.value = true
}

async function remove(u: SysUser) {
  try {
    await ElMessageBox.confirm(`确定删除用户「${u.username}」吗？`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
    })
  } catch {
    return
  }
  try {
    await systemApi.removeUser(u.id)
    ElMessage.success('已删除')
    await load()
  } catch {
    /* 已提示 */
  }
}
</script>

<style scoped>
.role {
  margin-right: 4px;
}
</style>
