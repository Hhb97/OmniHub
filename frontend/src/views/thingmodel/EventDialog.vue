<template>
  <el-dialog
    v-model="visible"
    :title="item ? '编辑事件' : '添加事件'"
    width="600px"
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
      <el-form-item label="事件级别" prop="level">
        <el-radio-group v-model="form.level">
          <el-radio value="INFO">信息</el-radio>
          <el-radio value="WARNING">警告</el-radio>
          <el-radio value="ERROR">错误</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="描述" prop="description">
        <el-input v-model="form.description" type="textarea" :rows="2" maxlength="255" />
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
import type { ThingEvent } from '@/types'
import { IDENTIFIER_MESSAGE, IDENTIFIER_REGEX } from '@/utils/constants'
import ParamListEditor from './ParamListEditor.vue'
import { cloneDeep } from './specUtil'

const props = defineProps<{ item: ThingEvent | null; existing: string[] }>()
const emit = defineEmits<{ (e: 'save', value: ThingEvent): void }>()
const visible = defineModel<boolean>({ default: false })

const formRef = ref<FormInstance>()
const blank = (): ThingEvent => ({
  identifier: '',
  name: '',
  level: 'INFO',
  description: '',
  outputs: [],
})
const form = reactive<ThingEvent>(blank())

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
  formRef.value?.clearValidate()
}

async function submit() {
  if (!(await formRef.value?.validate().catch(() => false))) return
  const ids = form.outputs.map((o) => o.identifier)
  if (new Set(ids).size !== ids.length) {
    ElMessage.warning('输出参数标识符不能重复')
    return
  }
  emit('save', cloneDeep({ ...form }))
  visible.value = false
}
</script>
