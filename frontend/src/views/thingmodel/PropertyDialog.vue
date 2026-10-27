<template>
  <el-dialog
    v-model="visible"
    :title="item ? '编辑属性' : '添加属性'"
    width="560px"
    :close-on-click-modal="false"
    @open="onOpen"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
      <el-form-item label="标识符" prop="identifier">
        <el-input v-model.trim="form.identifier" />
      </el-form-item>
      <el-form-item label="名称" prop="name">
        <el-input v-model.trim="form.name" maxlength="64" />
      </el-form-item>
      <el-form-item label="数据类型" prop="dataType">
        <el-select v-model="form.dataType" style="width: 100%" @change="form.spec = {}">
          <el-option v-for="t in DATA_TYPES" :key="t" :label="t" :value="t" />
        </el-select>
      </el-form-item>
      <SpecFields v-model="form.spec" :data-type="form.dataType" />
      <el-form-item label="单位" prop="unit">
        <el-input v-model.trim="form.unit" maxlength="16" />
      </el-form-item>
      <el-form-item label="读写类型" prop="accessMode">
        <el-radio-group v-model="form.accessMode">
          <el-radio value="R">只读</el-radio>
          <el-radio value="RW">读写</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="描述" prop="description">
        <el-input v-model="form.description" type="textarea" :rows="2" maxlength="255" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" @click="submit">确定</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import type { ThingProperty } from '@/types'
import { DATA_TYPES, IDENTIFIER_MESSAGE, IDENTIFIER_REGEX } from '@/utils/constants'
import SpecFields from './SpecFields.vue'
import { cleanSpec, cloneDeep } from './specUtil'

const props = defineProps<{ item: ThingProperty | null; existing: string[] }>()
const emit = defineEmits<{ (e: 'save', value: ThingProperty): void }>()
const visible = defineModel<boolean>({ default: false })

const formRef = ref<FormInstance>()
const blank = (): ThingProperty => ({
  identifier: '',
  name: '',
  dataType: 'INT',
  unit: '',
  description: '',
  accessMode: 'R',
  spec: {},
})
const form = reactive<ThingProperty & { spec: NonNullable<ThingProperty['spec']> }>(
  blank() as ThingProperty & { spec: NonNullable<ThingProperty['spec']> },
)

const rules: FormRules = {
  identifier: [
    { required: true, message: '请输入标识符', trigger: 'blur' },
    { pattern: IDENTIFIER_REGEX, message: IDENTIFIER_MESSAGE, trigger: 'blur' },
    {
      validator: (_r, v: string, cb: (e?: Error) => void) =>
        props.existing.includes(v) && v !== props.item?.identifier
          ? cb(new Error('标识符已存在'))
          : cb(),
      trigger: 'blur',
    },
  ],
  name: [{ required: true, message: '请输入名称', trigger: 'blur' }],
  dataType: [{ required: true, message: '请选择数据类型', trigger: 'change' }],
}

function onOpen() {
  Object.assign(form, blank(), props.item ? cloneDeep(props.item) : {})
  form.spec = form.spec ?? {}
  formRef.value?.clearValidate()
}

async function submit() {
  if (!(await formRef.value?.validate().catch(() => false))) return
  const spec = cleanSpec(form.dataType, form.spec)
  if (
    (form.dataType === 'INT' || form.dataType === 'FLOAT') &&
    spec.min !== undefined &&
    spec.max !== undefined &&
    spec.min > spec.max
  ) {
    ElMessage.warning('最小值不能大于最大值')
    return
  }
  if (form.dataType === 'ENUM') {
    const list = spec.enumValues ?? []
    if (list.length === 0 || list.some((e) => String(e.value) === '' || !e.label)) {
      ElMessage.warning('请完整填写枚举项（至少一项，值与名称均必填）')
      return
    }
    if (new Set(list.map((e) => String(e.value))).size !== list.length) {
      ElMessage.warning('枚举值不能重复')
      return
    }
  }
  emit('save', { ...form, spec })
  visible.value = false
}
</script>
