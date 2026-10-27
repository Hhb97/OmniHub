<template>
  <el-dialog
    v-model="visible"
    :title="isEdit ? '编辑设备' : '新建设备'"
    width="500px"
    :close-on-click-modal="false"
    @open="onOpen"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
      <el-form-item label="设备名称" prop="name">
        <el-input v-model.trim="form.name" maxlength="64" show-word-limit />
      </el-form-item>
      <el-form-item v-if="!isEdit" label="所属产品" prop="productId">
        <el-select v-model="form.productId" filterable placeholder="请选择产品" style="width: 100%">
          <el-option
            v-for="p in options"
            :key="p.id"
            :label="`${p.name} (${p.productKey})`"
            :value="p.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item v-if="!isEdit" label="DeviceKey" prop="deviceKey">
        <el-input v-model.trim="form.deviceKey" maxlength="64" placeholder="留空则自动生成" />
      </el-form-item>
      <el-form-item label="描述" prop="description">
        <el-input
          v-model="form.description"
          type="textarea"
          :rows="3"
          maxlength="255"
          show-word-limit
        />
      </el-form-item>
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
import { deviceApi } from '@/api/device'
import { useProductOptions } from '@/composables/useOptions'
import type { Device, DeviceCreated } from '@/types'

const props = defineProps<{ device: Device | null }>()
const emit = defineEmits<{ (e: 'saved', created?: DeviceCreated): void }>()
const visible = defineModel<boolean>({ default: false })

const { options, load: loadProducts } = useProductOptions(false)
const formRef = ref<FormInstance>()
const submitting = ref(false)
const isEdit = computed(() => !!props.device)
const form = reactive<{ name: string; productId?: number; deviceKey: string; description: string }>(
  {
    name: '',
    productId: undefined,
    deviceKey: '',
    description: '',
  },
)

const rules: FormRules = {
  name: [{ required: true, message: '请输入设备名称', trigger: 'blur' }],
  productId: [{ required: true, message: '请选择产品', trigger: 'change' }],
  deviceKey: [
    { pattern: /^[a-zA-Z0-9_-]*$/, message: '仅支持字母、数字、下划线、中划线', trigger: 'blur' },
  ],
}

function onOpen() {
  Object.assign(form, {
    name: props.device?.name ?? '',
    productId: props.device?.productId,
    deviceKey: props.device?.deviceKey ?? '',
    description: props.device?.description ?? '',
  })
  if (!props.device) void loadProducts()
  formRef.value?.clearValidate()
}

async function submit() {
  if (!(await formRef.value?.validate().catch(() => false))) return
  submitting.value = true
  try {
    if (props.device) {
      await deviceApi.update(props.device.id, { name: form.name, description: form.description })
      ElMessage.success('保存成功')
      visible.value = false
      emit('saved')
    } else {
      const created = await deviceApi.create({
        name: form.name,
        productId: form.productId as number,
        deviceKey: form.deviceKey || undefined,
        description: form.description,
      })
      visible.value = false
      emit('saved', created)
    }
  } catch {
    /* 已提示 */
  } finally {
    submitting.value = false
  }
}
</script>
