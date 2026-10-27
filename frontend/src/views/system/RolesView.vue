<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <el-button @click="load">刷新</el-button>
        <div class="spacer" />
        <el-button type="primary" @click="openCreate">新建角色</el-button>
      </div>
    </el-card>
    <el-card shadow="never">
      <el-table v-loading="loading" :data="roles" stripe>
        <template #empty><TableEmpty :error="error" @retry="load" /></template>
        <el-table-column prop="code" label="角色编码" min-width="140"
          ><template #default="{ row }"
            ><span class="mono">{{ row.code }}</span></template
          ></el-table-column
        >
        <el-table-column prop="name" label="角色名称" min-width="140" />
        <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
        <el-table-column label="权限数" width="90" align="right"
          ><template #default="{ row }">{{ row.permissions.length }}</template></el-table-column
        >
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row as Role)">编辑</el-button>
            <el-button link type="danger" @click="remove(row as Role)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
    <RoleFormDialog v-model="dialog" :role="editing" @saved="load" />
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import TableEmpty from '@/components/TableEmpty.vue'
import { systemApi } from '@/api/system'
import type { Role } from '@/types'
import RoleFormDialog from './RoleFormDialog.vue'

const roles = ref<Role[]>([])
const loading = ref(false)
const error = ref('')
const dialog = ref(false)
const editing = ref<Role | null>(null)

async function load() {
  loading.value = true
  error.value = ''
  try {
    roles.value = await systemApi.listRoles()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
    roles.value = []
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  dialog.value = true
}
function openEdit(r: Role) {
  editing.value = r
  dialog.value = true
}

async function remove(r: Role) {
  try {
    await ElMessageBox.confirm(`确定删除角色「${r.name}」吗？`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
    })
  } catch {
    return
  }
  try {
    await systemApi.removeRole(r.id)
    ElMessage.success('已删除')
    await load()
  } catch {
    /* 已提示 */
  }
}

onMounted(load)
</script>
