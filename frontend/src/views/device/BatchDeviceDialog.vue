<template>
  <el-dialog
    v-model="visible"
    title="批量新增设备"
    width="760px"
    :close-on-click-modal="false"
    :close-on-press-escape="false"
    @open="onOpen"
    @closed="onClosed"
  >
    <template v-if="!created.length">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="所属产品" prop="productId">
          <el-select
            v-model="form.productId"
            filterable
            placeholder="请选择产品"
            style="width: 100%"
          >
            <el-option
              v-for="p in options"
              :key="p.id"
              :label="`${p.name} (${p.productKey})`"
              :value="p.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="名称前缀" prop="namePrefix">
          <el-input
            v-model.trim="form.namePrefix"
            maxlength="64"
            placeholder="例如：温湿度传感器"
          />
        </el-form-item>
        <el-form-item label="创建数量" prop="count">
          <el-input-number v-model="form.count" :min="1" />
        </el-form-item>
        <el-form-item label="名称预览">
          <span class="mono">{{ preview }}</span>
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input v-model="form.description" type="textarea" :rows="2" maxlength="255" />
        </el-form-item>
      </el-form>
      <p class="muted">DeviceKey 与密钥由系统自动生成。全部成功或全部失败。</p>
    </template>

    <template v-else>
      <el-alert
        type="warning"
        show-icon
        :closable="false"
        :title="`已创建 ${created.length} 个设备。设备密钥仅显示这一次，关闭后无法再次查看，请先导出并妥善保存！`"
      />
      <div class="actions">
        <el-button type="primary" @click="copyAll">复制全部 JSON</el-button>
        <el-button type="primary" plain @click="download">下载 JSON 文件</el-button>
        <span class="muted">每项含 brokerUrl、clientId、username、password、deviceSecret</span>
      </div>
      <el-table :data="created" size="small" max-height="360">
        <el-table-column prop="name" label="设备名称" min-width="150" />
        <el-table-column label="ClientId" min-width="200">
          <template #default="{ row }"
            ><span class="mono">{{ row.clientId }}</span></template
          >
        </el-table-column>
        <el-table-column label="密钥" min-width="180">
          <template #default="{ row }"
            ><span class="mono">{{ row.secret.slice(0, 6) }}••••••</span></template
          >
        </el-table-column>
      </el-table>
    </template>

    <template #footer>
      <template v-if="!created.length">
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">创建</el-button>
      </template>
      <template v-else>
        <el-checkbox v-model="confirmed">我已导出并保存密钥</el-checkbox>
        <el-button type="primary" :disabled="!confirmed" @click="visible = false">关闭</el-button>
      </template>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { deviceApi } from '@/api/device'
import { useProductOptions } from '@/composables/useOptions'
import type { DeviceCreated } from '@/types'
import { copyText } from '@/utils/clipboard'
import { mqttBroker } from '@/utils/mqtt'

const emit = defineEmits<{ (e: 'done'): void }>()
const visible = defineModel<boolean>({ default: false })

const { options, load: loadProducts } = useProductOptions(false)
const formRef = ref<FormInstance>()
const submitting = ref(false)
const confirmed = ref(false)
const created = ref<DeviceCreated[]>([])
const form = reactive<{
  productId?: number
  namePrefix: string
  count: number
  description: string
}>({
  productId: undefined,
  namePrefix: '',
  count: 10,
  description: '',
})
const rules: FormRules = {
  productId: [{ required: true, message: '请选择产品', trigger: 'change' }],
  namePrefix: [{ required: true, message: '请输入名称前缀', trigger: 'blur' }],
  count: [{ required: true, message: '请输入数量', trigger: 'change' }],
}

const width = computed(() => Math.max(3, String(form.count).length))
const pad = (n: number) => String(n).padStart(width.value, '0')
const preview = computed(() => {
  const p = form.namePrefix || '前缀'
  return form.count > 1 ? `${p}-${pad(1)} … ${p}-${pad(form.count)}` : `${p}-${pad(1)}`
})

function onOpen() {
  created.value = []
  confirmed.value = false
  void loadProducts()
  formRef.value?.clearValidate()
}
function onClosed() {
  if (created.value.length) emit('done')
  created.value = []
}

async function submit() {
  if (!(await formRef.value?.validate().catch(() => false))) return
  submitting.value = true
  try {
    created.value = await deviceApi.batchCreate({
      productId: form.productId!,
      namePrefix: form.namePrefix,
      count: form.count,
      description: form.description || undefined,
    })
    ElMessage.success(`已创建 ${created.value.length} 个设备`)
  } catch {
    /* 已提示 */
  } finally {
    submitting.value = false
  }
}

/** Same shape for every device so the file can be fed straight to a test program. */
function exportJson(): string {
  const b = mqttBroker()
  const brokerUrl = b.startsWith('<')
    ? `mqtt://${window.location.hostname}:1883`
    : /^[a-z]+:\/\//.test(b)
      ? b
      : `mqtt://${b}`
  return JSON.stringify(
    created.value.map((d) => ({
      name: d.name,
      brokerUrl,
      productKey: d.productKey,
      deviceKey: d.deviceKey,
      deviceSecret: d.secret,
      clientId: d.clientId,
      username: d.clientId,
      password: d.secret,
    })),
    null,
    2,
  )
}
function copyAll() {
  void copyText(exportJson(), `已复制 ${created.value.length} 个设备的接入 JSON`)
}
function download() {
  const blob = new Blob([exportJson()], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `devices-${form.namePrefix || 'batch'}-${Date.now()}.json`
  a.click()
  URL.revokeObjectURL(url)
}
</script>

<style scoped>
.hint {
  margin-left: 12px;
}
.actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 16px 0 12px;
  font-size: 12px;
}
</style>
