<template>
  <el-dialog
    v-model="visible"
    :title="isEdit ? '编辑产品' : '新建产品'"
    width="520px"
    :close-on-click-modal="false"
    @open="onOpen"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
      <el-form-item label="产品名称" prop="name">
        <el-input v-model.trim="form.name" maxlength="64" show-word-limit />
      </el-form-item>
      <el-form-item label="ProductKey" prop="productKey">
        <el-input
          v-model.trim="form.productKey"
          maxlength="64"
          :disabled="isEdit"
          placeholder="创建后不可修改"
        />
      </el-form-item>
      <el-form-item label="分类" prop="category">
        <el-input v-model.trim="form.category" maxlength="64" />
      </el-form-item>
      <el-form-item label="接入协议" prop="protocol">
        <el-select v-model="form.protocol" style="width: 100%">
          <el-option label="MQTT" value="MQTT" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态" prop="status">
        <el-radio-group v-model="form.status">
          <el-radio value="ENABLED">启用</el-radio>
          <el-radio value="DISABLED">停用</el-radio>
        </el-radio-group>
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
import { productApi } from '@/api/product'
import type { Product, ProductForm } from '@/types'

const props = defineProps<{ product: Product | null }>()
const emit = defineEmits<{ (e: 'saved'): void }>()
const visible = defineModel<boolean>({ default: false })

const formRef = ref<FormInstance>()
const submitting = ref(false)
const isEdit = computed(() => !!props.product)

const blank = (): ProductForm => ({
  name: '',
  productKey: '',
  category: '',
  protocol: 'MQTT',
  status: 'ENABLED',
  description: '',
})
const form = reactive<ProductForm>(blank())

const rules: FormRules = {
  name: [{ required: true, message: '请输入产品名称', trigger: 'blur' }],
  productKey: [
    { required: true, message: '请输入 ProductKey', trigger: 'blur' },
    { pattern: /^[a-zA-Z0-9_-]+$/, message: '仅支持字母、数字、下划线、中划线', trigger: 'blur' },
  ],
  protocol: [{ required: true, message: '请选择协议', trigger: 'change' }],
}

function onOpen() {
  Object.assign(form, blank())
  if (props.product) {
    const p = props.product
    Object.assign(form, {
      name: p.name,
      productKey: p.productKey,
      category: p.category ?? '',
      protocol: p.protocol,
      status: p.status,
      description: p.description ?? '',
    })
  }
  formRef.value?.clearValidate()
}

async function submit() {
  if (!(await formRef.value?.validate().catch(() => false))) return
  submitting.value = true
  try {
    if (props.product) {
      const { productKey: _k, ...rest } = form
      await productApi.update(props.product.id, rest)
    } else {
      await productApi.create({ ...form })
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
