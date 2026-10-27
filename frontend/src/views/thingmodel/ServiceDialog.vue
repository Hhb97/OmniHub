<template>
  <el-dialog
    v-model="visible"
    :title="item ? '编辑服务' : '添加服务'"
    width="680px"
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
      <el-form-item label="描述" prop="description">
        <el-input v-model="form.description" type="textarea" :rows="2" maxlength="255" />
      </el-form-item>
      <el-form-item label="输入参数">
        <ParamListEditor
          v-model="form.inputs"
          prop-path="inputs"
          with-spec
          add-text="添加输入参数"
        />
      </el-form-item>
      <el-form-item label="输出参数">
        <ParamListEditor v-model="form.outputs" prop-path="outputs" add-text="添加输出参数" />
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
import type { ThingInput, ThingService } from '@/types'
import { IDENTIFIER_MESSAGE, IDENTIFIER_REGEX } from '@/utils/constants'
import ParamListEditor from './ParamListEditor.vue'
import { cleanSpec, cloneDeep } from './specUtil'

const props = defineProps<{ item: ThingService | null; existing: string[] }>()
const emit = defineEmits<{ (e: 'save', value: ThingService): void }>()
const visible = defineModel<boolean>({ default: false })

const formRef = ref<FormInstance>()
const blank = (): ThingService => ({
  identifier: '',
  name: '',
  description: '',
  inputs: [],
  outputs: [],
})
const form = reactive<ThingService>(blank())

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
}

function onOpen() {
  Object.assign(form, blank(), props.item ? cloneDeep(props.item) : {})
  for (const i of form.inputs) i.spec = i.spec ?? {}
  formRef.value?.clearValidate()
}

function hasDup(list: { identifier: string }[]): boolean {
  const ids = list.map((o) => o.identifier)
  return new Set(ids).size !== ids.length
}

async function submit() {
  if (!(await formRef.value?.validate().catch(() => false))) return
  if (hasDup(form.inputs) || hasDup(form.outputs)) {
    ElMessage.warning('同类参数标识符不能重复')
    return
  }
  const inputs: ThingInput[] = form.inputs.map((i) => ({
    identifier: i.identifier,
    name: i.name,
    dataType: i.dataType,
    spec: cleanSpec(i.dataType, i.spec),
  }))
  emit('save', cloneDeep({ ...form, inputs }))
  visible.value = false
}
</script>
