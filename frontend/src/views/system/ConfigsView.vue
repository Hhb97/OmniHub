<template>
  <div class="page">
    <el-card shadow="never">
      <el-table v-loading="loading" :data="data ?? []" stripe>
        <template #empty><TableEmpty :error="error" @retry="reload" /></template>
        <el-table-column prop="key" label="配置项" min-width="220"
          ><template #default="{ row }"
            ><span class="mono">{{ row.key }}</span></template
          ></el-table-column
        >
        <el-table-column prop="value" label="当前值" min-width="200" show-overflow-tooltip />
        <el-table-column prop="description" label="说明" min-width="260" show-overflow-tooltip />
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }"
            ><el-button link type="primary" @click="openEdit(row as SysConfig)"
              >编辑</el-button
            ></template
          >
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialog" title="修改配置" width="480px" :close-on-click-modal="false">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px" @submit.prevent>
        <el-form-item label="配置项"
          ><span class="mono">{{ form.key }}</span></el-form-item
        >
        <el-form-item v-if="form.description" label="说明"
          ><span class="muted">{{ form.description }}</span></el-form-item
        >
        <el-form-item label="值" prop="value"><el-input v-model="form.value" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import TableEmpty from '@/components/TableEmpty.vue'
import { systemApi } from '@/api/system'
import { useAsyncData } from '@/composables/useAsyncData'
import type { SysConfig } from '@/types'

const { data, loading, error, reload } = useAsyncData(() => systemApi.listConfigs())

const dialog = ref(false)
const submitting = ref(false)
const formRef = ref<FormInstance>()
const form = reactive<SysConfig>({ key: '', value: '', description: '' })
const rules: FormRules = { value: [{ required: true, message: '请输入配置值', trigger: 'blur' }] }

function openEdit(c: SysConfig) {
  Object.assign(form, { key: c.key, value: c.value, description: c.description ?? '' })
  dialog.value = true
}

async function submit() {
  if (!(await formRef.value?.validate().catch(() => false))) return
  submitting.value = true
  try {
    await systemApi.updateConfig(form.key, form.value)
    ElMessage.success('保存成功')
    dialog.value = false
    await reload()
  } catch {
    /* 已提示 */
  } finally {
    submitting.value = false
  }
}
</script>
