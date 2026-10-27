<template>
  <el-dialog
    v-model="visible"
    :title="role ? '编辑角色' : '新建角色'"
    width="560px"
    :close-on-click-modal="false"
    @open="onOpen"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
      <el-form-item label="角色编码" prop="code">
        <el-input v-model.trim="form.code" maxlength="64" placeholder="如 OPERATOR" />
      </el-form-item>
      <el-form-item label="角色名称" prop="name">
        <el-input v-model.trim="form.name" maxlength="64" />
      </el-form-item>
      <el-form-item label="描述" prop="description">
        <el-input v-model="form.description" type="textarea" :rows="2" maxlength="255" />
      </el-form-item>
      <el-form-item label="权限" prop="permissions">
        <div v-loading="treeLoading" class="tree-wrap">
          <el-alert v-if="treeError" type="error" :title="treeError" show-icon :closable="false" />
          <el-tree
            ref="treeRef"
            :data="tree"
            node-key="code"
            show-checkbox
            default-expand-all
            :props="{ label: 'name', children: 'children' }"
          >
            <template #default="{ data }">
              <span>{{ data.name }}</span>
              <span class="muted code">{{ data.code }}</span>
            </template>
          </el-tree>
        </div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { nextTick, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules, type TreeInstance } from 'element-plus'
import { systemApi } from '@/api/system'
import type { PermissionNode, Role } from '@/types'

const props = defineProps<{ role: Role | null }>()
const emit = defineEmits<{ (e: 'saved'): void }>()
const visible = defineModel<boolean>({ default: false })

const formRef = ref<FormInstance>()
const treeRef = ref<TreeInstance>()
const submitting = ref(false)
const tree = ref<PermissionNode[]>([])
const treeLoading = ref(false)
const treeError = ref('')
const form = reactive({ code: '', name: '', description: '' })

const rules: FormRules = {
  code: [
    { required: true, message: '请输入角色编码', trigger: 'blur' },
    {
      pattern: /^[A-Za-z][A-Za-z0-9_]*$/,
      message: '以字母开头，仅含字母、数字、下划线',
      trigger: 'blur',
    },
  ],
  name: [{ required: true, message: '请输入角色名称', trigger: 'blur' }],
}

function leafCodes(nodes: PermissionNode[]): Set<string> {
  const out = new Set<string>()
  const walk = (list: PermissionNode[]) =>
    list.forEach((n) => {
      if (n.children?.length) walk(n.children)
      else out.add(n.code)
    })
  walk(nodes)
  return out
}

async function onOpen() {
  Object.assign(form, {
    code: props.role?.code ?? '',
    name: props.role?.name ?? '',
    description: props.role?.description ?? '',
  })
  formRef.value?.clearValidate()
  treeLoading.value = true
  treeError.value = ''
  try {
    tree.value = await systemApi.permissions()
  } catch (e) {
    treeError.value = e instanceof Error ? e.message : '权限列表加载失败'
    tree.value = []
  } finally {
    treeLoading.value = false
  }
  await nextTick()
  // 仅回填叶子节点，由 el-tree 自动推导父节点的勾选/半选状态
  const leaves = leafCodes(tree.value)
  treeRef.value?.setCheckedKeys((props.role?.permissions ?? []).filter((c) => leaves.has(c)))
}

async function submit() {
  if (!(await formRef.value?.validate().catch(() => false))) return
  const checked = (treeRef.value?.getCheckedKeys(false) ?? []) as string[]
  const half = (treeRef.value?.getHalfCheckedKeys() ?? []) as string[]
  // 勾选的节点 + 半选的父节点（含 MENU 码），保证菜单可见
  const permissions = [...new Set([...checked, ...half])]
  submitting.value = true
  try {
    const body = { code: form.code, name: form.name, description: form.description, permissions }
    if (props.role) await systemApi.updateRole(props.role.id, body)
    else await systemApi.createRole(body)
    ElMessage.success('保存成功')
    visible.value = false
    emit('saved')
  } catch {
    /* 已提示 */
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.tree-wrap {
  width: 100%;
  max-height: 340px;
  overflow: auto;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  padding: 4px 8px;
  min-height: 60px;
}
.code {
  margin-left: 8px;
  font-size: 12px;
}
</style>
