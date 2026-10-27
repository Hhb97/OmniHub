<template>
  <el-dialog
    v-model="visible"
    :title="isEdit ? '编辑用户' : '新建用户'"
    width="500px"
    :close-on-click-modal="false"
    @open="onOpen"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
      <el-form-item label="用户名" prop="username">
        <el-input
          v-model.trim="form.username"
          :disabled="isEdit"
          maxlength="64"
          autocomplete="off"
        />
      </el-form-item>
      <el-form-item v-if="!isEdit" label="密码" prop="password">
        <el-input
          v-model="form.password"
          type="password"
          show-password
          autocomplete="new-password"
        />
      </el-form-item>
      <el-form-item label="昵称" prop="nickname">
        <el-input v-model.trim="form.nickname" maxlength="64" />
      </el-form-item>
      <el-form-item label="邮箱" prop="email">
        <el-input v-model.trim="form.email" maxlength="128" />
      </el-form-item>
      <el-form-item label="角色" prop="roleIds">
        <el-select
          v-model="form.roleIds"
          multiple
          placeholder="选择角色"
          style="width: 100%"
          :loading="rolesLoading"
        >
          <el-option
            v-for="r in roles"
            :key="r.id"
            :label="`${r.name} (${r.code})`"
            :value="r.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item v-if="isEdit" label="启用" prop="enabled"
        ><el-switch v-model="form.enabled"
      /></el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { systemApi } from '@/api/system'
import type { Role, SysUser } from '@/types'

const props = defineProps<{ user: SysUser | null }>()
const emit = defineEmits<{ (e: 'saved'): void }>()
const visible = defineModel<boolean>({ default: false })

const formRef = ref<FormInstance>()
const submitting = ref(false)
const roles = ref<Role[]>([])
const rolesLoading = ref(false)
const isEdit = computed(() => !!props.user)
const form = reactive({
  username: '',
  password: '',
  nickname: '',
  email: '',
  roleIds: [] as number[],
  enabled: true,
})

const rules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 64, message: '长度 3-64 位', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 64, message: '密码长度 6-64 位', trigger: 'blur' },
  ],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
}

async function onOpen() {
  Object.assign(form, {
    username: props.user?.username ?? '',
    password: '',
    nickname: props.user?.nickname ?? '',
    email: props.user?.email ?? '',
    roleIds: [],
    enabled: props.user?.enabled ?? true,
  })
  formRef.value?.clearValidate()
  rolesLoading.value = true
  try {
    roles.value = await systemApi.listRoles()
    // 用户接口返回的是角色 code，这里映射回 id
    if (props.user) {
      form.roleIds = roles.value.filter((r) => props.user?.roles.includes(r.code)).map((r) => r.id)
    }
  } catch {
    roles.value = []
  } finally {
    rolesLoading.value = false
  }
}

async function submit() {
  if (!(await formRef.value?.validate().catch(() => false))) return
  submitting.value = true
  try {
    if (props.user) {
      await systemApi.updateUser(props.user.id, {
        nickname: form.nickname,
        email: form.email,
        enabled: form.enabled,
        roleIds: form.roleIds,
      })
    } else {
      await systemApi.createUser({
        username: form.username,
        password: form.password,
        nickname: form.nickname,
        email: form.email,
        roleIds: form.roleIds,
      })
    }
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
