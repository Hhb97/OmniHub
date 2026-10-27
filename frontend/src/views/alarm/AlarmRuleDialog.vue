<template>
  <el-dialog
    v-model="visible"
    :title="rule ? '编辑告警规则' : '新建告警规则'"
    width="600px"
    :close-on-click-modal="false"
    @open="onOpen"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
      <el-form-item label="规则名称" prop="name">
        <el-input v-model.trim="form.name" maxlength="64" show-word-limit />
      </el-form-item>
      <el-form-item label="规则类型" prop="ruleType">
        <el-radio-group v-model="form.ruleType" @change="form.identifier = ''">
          <el-radio-button v-for="(label, key) in RULE_TYPE_LABEL" :key="key" :value="key">{{
            label
          }}</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="作用范围" prop="scope">
        <el-radio-group v-model="form.scope" @change="onScopeChange">
          <el-radio value="PRODUCT">产品</el-radio>
          <el-radio value="DEVICE">设备</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item v-if="form.scope === 'PRODUCT'" label="产品" prop="productId">
        <el-select
          v-model="form.productId"
          filterable
          placeholder="选择产品"
          style="width: 100%"
          @change="onTargetChange"
        >
          <el-option
            v-for="p in productOptions"
            :key="p.id"
            :label="`${p.name} (${p.productKey})`"
            :value="p.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item v-else label="设备" prop="deviceId">
        <el-select
          v-model="form.deviceId"
          filterable
          remote
          :remote-method="(k: string) => searchDevices(k)"
          :loading="deviceLoading"
          placeholder="搜索并选择设备"
          style="width: 100%"
          @change="onDeviceChange"
        >
          <el-option
            v-for="d in deviceOptions"
            :key="d.id"
            :label="`${d.name} (${d.deviceKey})`"
            :value="d.id"
          />
        </el-select>
      </el-form-item>

      <template v-if="form.ruleType === 'THRESHOLD'">
        <el-form-item label="属性" prop="identifier">
          <el-select
            v-model="form.identifier"
            filterable
            allow-create
            default-first-option
            placeholder="选择属性"
            style="width: 100%"
            :loading="modelLoading"
          >
            <el-option
              v-for="p in propertyOptions"
              :key="p.identifier"
              :label="`${p.name} (${p.identifier})`"
              :value="p.identifier"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="条件" required>
          <el-form-item prop="operator" class="inline">
            <el-select v-model="form.operator" style="width: 110px">
              <el-option
                v-for="(label, key) in OPERATOR_LABEL"
                :key="key"
                :label="`${label} (${key})`"
                :value="key"
              />
            </el-select>
          </el-form-item>
          <el-form-item prop="threshold" class="inline">
            <el-input-number
              v-model="form.threshold"
              :controls="false"
              placeholder="阈值"
              style="width: 140px"
            />
          </el-form-item>
        </el-form-item>
        <el-form-item label="连续次数" prop="triggerCount">
          <el-input-number v-model="form.triggerCount" :min="1" :max="1000" :precision="0" />
          <span class="muted hint">连续满足条件的次数才触发</span>
        </el-form-item>
      </template>
      <el-form-item v-else-if="form.ruleType === 'EVENT'" label="事件" prop="identifier">
        <el-select
          v-model="form.identifier"
          filterable
          allow-create
          default-first-option
          placeholder="选择事件"
          style="width: 100%"
          :loading="modelLoading"
        >
          <el-option
            v-for="e in eventOptions"
            :key="e.identifier"
            :label="`${e.name} (${e.identifier})`"
            :value="e.identifier"
          />
        </el-select>
      </el-form-item>
      <el-form-item v-else label="说明">
        <span class="muted">设备离线时触发，上线后自动恢复</span>
      </el-form-item>

      <el-form-item label="告警级别" prop="level">
        <el-select v-model="form.level" style="width: 160px">
          <el-option
            v-for="(label, key) in ALARM_LEVEL_LABEL"
            :key="key"
            :label="label"
            :value="key"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="静默时间" prop="silenceSeconds">
        <el-input-number v-model="form.silenceSeconds" :min="0" :precision="0" />
        <span class="muted hint">秒</span>
      </el-form-item>
      <el-form-item label="启用" prop="enabled"><el-switch v-model="form.enabled" /></el-form-item>
      <el-form-item label="描述" prop="description">
        <el-input v-model="form.description" type="textarea" :rows="2" maxlength="255" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { alarmApi } from '@/api/alarm'
import { deviceApi } from '@/api/device'
import { productApi } from '@/api/product'
import { useDeviceSearch, useProductOptions } from '@/composables/useOptions'
import type { AlarmOperator, AlarmRule, AlarmRuleForm, ThingEvent, ThingProperty } from '@/types'
import { ALARM_LEVEL_LABEL, OPERATOR_LABEL, RULE_TYPE_LABEL } from '@/utils/constants'

const props = defineProps<{ rule: AlarmRule | null }>()
const emit = defineEmits<{ (e: 'saved'): void }>()
const visible = defineModel<boolean>({ default: false })

const formRef = ref<FormInstance>()
const submitting = ref(false)
const { options: productOptions, load: loadProducts } = useProductOptions(false)
const {
  options: deviceOptions,
  loading: deviceLoading,
  search: searchDevices,
  ensure,
} = useDeviceSearch()

const propertyOptions = ref<ThingProperty[]>([])
const eventOptions = ref<ThingEvent[]>([])
const modelLoading = ref(false)
/** 设备范围时，所选设备所属产品 */
const devicesProduct = ref<number | undefined>()

type FormState = Omit<AlarmRuleForm, 'productId' | 'deviceId' | 'operator' | 'threshold'> & {
  productId?: number
  deviceId?: number
  operator: AlarmOperator
  threshold?: number
  identifier: string
}

const blank = (): FormState => ({
  name: '',
  scope: 'PRODUCT',
  productId: undefined,
  deviceId: undefined,
  ruleType: 'THRESHOLD',
  identifier: '',
  operator: 'GT',
  threshold: undefined,
  triggerCount: 1,
  silenceSeconds: 300,
  level: 'WARNING',
  enabled: true,
  description: '',
})
const form = reactive<FormState>(blank())

const needIdentifier = (_r: unknown, v: string, cb: (e?: Error) => void) =>
  form.ruleType === 'OFFLINE' || v
    ? cb()
    : cb(new Error(form.ruleType === 'EVENT' ? '请选择事件' : '请选择属性'))

const rules: FormRules = {
  name: [{ required: true, message: '请输入规则名称', trigger: 'blur' }],
  productId: [{ required: true, message: '请选择产品', trigger: 'change' }],
  deviceId: [{ required: true, message: '请选择设备', trigger: 'change' }],
  identifier: [{ validator: needIdentifier, trigger: 'change' }],
  operator: [{ required: true, message: '请选择运算符', trigger: 'change' }],
  threshold: [
    {
      validator: (_r, v: number | undefined, cb: (e?: Error) => void) =>
        form.ruleType !== 'THRESHOLD' || (v !== undefined && v !== null)
          ? cb()
          : cb(new Error('请输入阈值')),
      trigger: 'blur',
    },
  ],
  triggerCount: [{ required: true, message: '请输入连续次数', trigger: 'change' }],
  silenceSeconds: [{ required: true, message: '请输入静默时间', trigger: 'change' }],
}

async function loadModel() {
  propertyOptions.value = []
  eventOptions.value = []
  const pid = form.scope === 'PRODUCT' ? form.productId : devicesProduct.value
  if (!pid) return
  modelLoading.value = true
  try {
    const tm = await productApi.getThingModel(pid, true)
    propertyOptions.value = tm.properties ?? []
    eventOptions.value = tm.events ?? []
  } catch {
    ElMessage.info('无法读取物模型，可手动输入标识符')
  } finally {
    modelLoading.value = false
  }
}

function onScopeChange() {
  form.productId = undefined
  form.deviceId = undefined
  devicesProduct.value = undefined
  form.identifier = ''
  void loadModel()
}
function onTargetChange() {
  form.identifier = ''
  void loadModel()
}
function onDeviceChange(id: number) {
  devicesProduct.value = deviceOptions.value.find((d) => d.id === id)?.productId
  form.identifier = ''
  void loadModel()
}

async function onOpen() {
  Object.assign(form, blank())
  devicesProduct.value = undefined
  propertyOptions.value = []
  eventOptions.value = []
  void loadProducts()
  void searchDevices('')
  const r = props.rule
  if (r) {
    Object.assign(form, {
      name: r.name,
      scope: r.scope,
      productId: r.productId ?? undefined,
      deviceId: r.deviceId ?? undefined,
      ruleType: r.ruleType,
      identifier: r.identifier ?? '',
      operator: r.operator ?? 'GT',
      threshold: r.threshold,
      triggerCount: r.triggerCount,
      silenceSeconds: r.silenceSeconds,
      level: r.level,
      enabled: r.enabled,
      description: r.description ?? '',
    })
    if (r.scope === 'DEVICE' && r.deviceId) {
      try {
        const d = await deviceApi.get(r.deviceId)
        ensure(d)
        devicesProduct.value = d.productId
      } catch {
        /* 已提示 */
      }
    }
    await loadModel()
  }
  formRef.value?.clearValidate()
}

async function submit() {
  if (!(await formRef.value?.validate().catch(() => false))) return
  const isThreshold = form.ruleType === 'THRESHOLD'
  const body: AlarmRuleForm = {
    name: form.name,
    scope: form.scope,
    productId: form.scope === 'PRODUCT' ? form.productId : null,
    deviceId: form.scope === 'DEVICE' ? form.deviceId : null,
    ruleType: form.ruleType,
    identifier: form.ruleType === 'OFFLINE' ? undefined : form.identifier,
    operator: isThreshold ? form.operator : undefined,
    threshold: isThreshold ? form.threshold : undefined,
    triggerCount: isThreshold ? form.triggerCount : 1,
    silenceSeconds: form.silenceSeconds,
    level: form.level,
    enabled: form.enabled,
    description: form.description,
  }
  submitting.value = true
  try {
    if (props.rule) await alarmApi.updateRule(props.rule.id, body)
    else await alarmApi.createRule(body)
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
.inline {
  margin-bottom: 0;
  margin-right: 8px;
}
.hint {
  margin-left: 8px;
  font-size: 12px;
}
</style>
