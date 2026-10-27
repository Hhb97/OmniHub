<template>
  <el-dialog
    v-model="visible"
    title="重置密码"
    width="420px"
    :close-on-click-modal="false"
    @open="onOpen"
  >
    <p v-if="user" class="info">用户：{{ user.username }}</p>
    <el-form ref="formRef" :model="form" :rules="rules" label-width="90px" @submit.prevent>
      <el-form-item label="新密码" prop="newPassword">
        <el-input
          v-model="form.newPassword"
          type="password"
          show-password
          autocomplete="new-password"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">确定</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { systemApi } from '@/api/system'
import type { SysUser } from '@/types'

const props = defineProps<{ user: SysUser | null }>()
const visible = defineModel<boolean>({ default: false })
const formRef = ref<FormInstance>()
const submitting = ref(false)
const form = reactive({ newPassword: '' })
const rules: FormRules = {
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, max: 64, message: '密码长度 6-64 位', trigger: 'blur' },
  ],
}

function onOpen() {
  form.newPassword = ''
  formRef.value?.clearValidate()
}

async function submit() {
  if (!props.user) return
  if (!(await formRef.value?.validate().catch(() => false))) return
  submitting.value = true
  try {
    await systemApi.resetPassword(props.user.id, form.newPassword)
    ElMessage.success('密码已重置')
    visible.value = false
  } catch {
    /* 已提示 */
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.info {
  margin: 0 0 12px;
}
</style>
