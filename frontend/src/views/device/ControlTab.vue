<template>
  <el-row :gutter="16">
    <el-col :xs="24" :lg="10">
      <el-alert
        v-if="device.onlineStatus !== 'ONLINE' || !device.enabled"
        type="warning"
        show-icon
        :closable="false"
        :title="!device.enabled ? '设备已禁用，无法下发指令' : '设备当前不在线，下发指令可能失败'"
        class="gap"
      />
      <el-alert
        v-if="!fromThingModel"
        type="info"
        show-icon
        :closable="false"
        title="无法读取产品物模型（可能缺少 thing:view 权限），服务调用与属性设置不可用"
        class="gap"
      />
      <el-card shadow="never">
        <template #header><span class="card-title">下发指令</span></template>
        <el-form label-width="90px" @submit.prevent>
          <el-form-item label="指令类型">
            <el-radio-group v-model="mode" @change="resetValues">
              <el-radio-button v-for="(label, key) in COMMAND_TYPE_LABEL" :key="key" :value="key">{{
                label
              }}</el-radio-button>
            </el-radio-group>
          </el-form-item>

          <template v-if="mode === 'SERVICE'">
            <el-form-item label="服务">
              <el-select
                v-model="serviceId"
                placeholder="选择服务"
                style="width: 100%"
                @change="inputValues = {}"
              >
                <el-option
                  v-for="s in services"
                  :key="s.identifier"
                  :label="`${s.name} (${s.identifier})`"
                  :value="s.identifier"
                />
              </el-select>
            </el-form-item>
            <el-form-item
              v-for="i in currentService?.inputs ?? []"
              :key="i.identifier"
              :label="i.name || i.identifier"
            >
              <ValueInput
                v-model="inputValues[i.identifier]"
                :data-type="i.dataType"
                :spec="i.spec"
              />
            </el-form-item>
            <el-empty
              v-if="currentService && currentService.inputs.length === 0"
              description="该服务无输入参数"
              :image-size="40"
            />
          </template>

          <template v-else-if="mode === 'PROPERTY_SET'">
            <el-form-item label="可写属性">
              <el-select
                v-model="propId"
                placeholder="选择属性"
                style="width: 100%"
                @change="propValue = undefined"
              >
                <el-option
                  v-for="p in writableProps"
                  :key="p.identifier"
                  :label="`${p.name} (${p.identifier})`"
                  :value="p.identifier"
                />
              </el-select>
            </el-form-item>
            <el-form-item v-if="currentProp" label="属性值">
              <ValueInput
                v-model="propValue"
                :data-type="currentProp.dataType"
                :spec="currentProp.spec"
              />
            </el-form-item>
          </template>

          <template v-else>
            <el-form-item label="属性">
              <el-select
                v-model="getIds"
                multiple
                collapse-tags
                collapse-tags-tooltip
                placeholder="留空表示获取全部"
                style="width: 100%"
              >
                <el-option
                  v-for="p in properties"
                  :key="p.identifier"
                  :label="`${p.name} (${p.identifier})`"
                  :value="p.identifier"
                />
              </el-select>
            </el-form-item>
          </template>

          <el-form-item>
            <el-button type="primary" :loading="sending" @click="submit">发送指令</el-button>
          </el-form-item>
        </el-form>
      </el-card>

      <el-card v-if="lastCommand" shadow="never" class="gap-top">
        <template #header><span class="card-title">最新指令</span></template>
        <CommandDetail :command="lastCommand" />
      </el-card>
    </el-col>

    <el-col :xs="24" :lg="14">
      <el-card shadow="never">
        <template #header>
          <div class="toolbar">
            <span class="card-title">指令历史</span>
            <div class="spacer" />
            <el-select
              v-model="query.status"
              placeholder="状态"
              clearable
              size="small"
              style="width: 120px"
              @change="search"
            >
              <el-option
                v-for="s in COMMAND_STATUSES"
                :key="s"
                :label="COMMAND_STATUS_LABEL[s]"
                :value="s"
              />
            </el-select>
            <el-button size="small" @click="load">刷新</el-button>
          </div>
        </template>
        <el-table v-loading="loading" :data="rows" size="small" stripe>
          <template #empty><TableEmpty :error="error" @retry="load" /></template>
          <el-table-column type="expand">
            <template #default="{ row }"
              ><div class="expand"><CommandDetail :command="row as Command" /></div
            ></template>
          </el-table-column>
          <el-table-column label="时间" width="170"
            ><template #default="{ row }">{{
              formatTime(row.createdAt)
            }}</template></el-table-column
          >
          <el-table-column label="类型" width="90"
            ><template #default="{ row }">{{
              COMMAND_TYPE_LABEL[row.type] ?? row.type
            }}</template></el-table-column
          >
          <el-table-column prop="identifier" label="标识" min-width="110" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag :type="COMMAND_STATUS_TAG[row.status] ?? 'info'" size="small">{{
                COMMAND_STATUS_LABEL[row.status] ?? row.status
              }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="message" label="说明" min-width="120" show-overflow-tooltip />
        </el-table>
        <AppPagination
          :page="page"
          :size="size"
          :total="total"
          @page-change="onPageChange"
          @size-change="onSizeChange"
        />
      </el-card>
    </el-col>
  </el-row>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import AppPagination from '@/components/AppPagination.vue'
import TableEmpty from '@/components/TableEmpty.vue'
import ValueInput from '@/components/ValueInput.vue'
import { deviceApi } from '@/api/device'
import { useTable } from '@/composables/useTable'
import type { PropInfo } from '@/composables/useThingModel'
import { useWsSubscription } from '@/composables/useWsSubscription'
import type {
  Command,
  CommandRequest,
  CommandStatus,
  CommandType,
  DataType,
  Device,
  ThingModel,
} from '@/types'
import {
  COMMAND_STATUS_LABEL,
  COMMAND_STATUS_TAG,
  COMMAND_STATUSES,
  COMMAND_TYPE_LABEL,
} from '@/utils/constants'
import { formatTime } from '@/utils/format'
import { normalizeEnumValue } from '@/views/thingmodel/specUtil'
import CommandDetail from './CommandDetail.vue'

const props = defineProps<{
  device: Device
  thingModel: ThingModel | null
  properties: PropInfo[]
  fromThingModel: boolean
}>()

const mode = ref<CommandType>('SERVICE')
const serviceId = ref('')
const inputValues = ref<Record<string, unknown>>({})
const propId = ref('')
const propValue = ref<unknown>(undefined)
const getIds = ref<string[]>([])
const sending = ref(false)
const lastCommand = ref<Command | null>(null)

const services = computed(() => props.thingModel?.services ?? [])
const currentService = computed(() => services.value.find((s) => s.identifier === serviceId.value))
const writableProps = computed(() => props.properties.filter((p) => p.writable))
const currentProp = computed(() => writableProps.value.find((p) => p.identifier === propId.value))

function resetValues() {
  serviceId.value = ''
  inputValues.value = {}
  propId.value = ''
  propValue.value = undefined
  getIds.value = []
}

/** 把表单值转成可提交的 JSON 值；失败返回 Error */
function convert(dataType: DataType, v: unknown): unknown | Error {
  if (v === undefined || v === null || v === '') return undefined
  if (dataType === 'JSON') {
    try {
      return JSON.parse(String(v))
    } catch {
      return new Error('JSON 格式不正确')
    }
  }
  if (dataType === 'ENUM') return typeof v === 'string' ? normalizeEnumValue(v) : v
  return v
}

function buildRequest(): CommandRequest | null {
  if (mode.value === 'SERVICE') {
    const s = currentService.value
    if (!s) {
      ElMessage.warning('请选择服务')
      return null
    }
    const params: Record<string, unknown> = {}
    for (const i of s.inputs) {
      const val = convert(i.dataType, inputValues.value[i.identifier])
      if (val instanceof Error) {
        ElMessage.warning(`参数「${i.name || i.identifier}」${val.message}`)
        return null
      }
      if (val !== undefined) params[i.identifier] = val
    }
    return { type: 'SERVICE', identifier: s.identifier, params }
  }
  if (mode.value === 'PROPERTY_SET') {
    const p = currentProp.value
    if (!p) {
      ElMessage.warning('请选择要设置的属性')
      return null
    }
    const val = convert(p.dataType, propValue.value)
    if (val instanceof Error) {
      ElMessage.warning(val.message)
      return null
    }
    if (val === undefined) {
      ElMessage.warning('请填写属性值')
      return null
    }
    return { type: 'PROPERTY_SET', identifier: p.identifier, params: { value: val } }
  }
  return { type: 'PROPERTY_GET', params: { identifiers: getIds.value } }
}

async function submit() {
  const req = buildRequest()
  if (!req) return
  sending.value = true
  try {
    const cmd = await deviceApi.sendCommand(props.device.id, req)
    lastCommand.value = cmd
    ElMessage.success('指令已提交')
    upsertRow(cmd)
  } catch {
    /* 409/400 的提示已由请求层展示 */
  } finally {
    sending.value = false
  }
}

const { query, rows, total, page, size, loading, error, load, search, onPageChange, onSizeChange } =
  useTable<Command, { status: CommandStatus | '' }>((p) => deviceApi.commands(props.device.id, p), {
    status: '',
  })

function upsertRow(cmd: Command) {
  const idx = rows.value.findIndex((r) => r.id === cmd.id)
  if (idx >= 0) {
    rows.value.splice(idx, 1, cmd)
  } else if (page.value === 1 && (!query.status || query.status === cmd.status)) {
    rows.value.unshift(cmd)
    if (rows.value.length > size.value) rows.value.pop()
    total.value += 1
  }
}

useWsSubscription({
  deviceId: props.device.id,
  onMessage: (msg) => {
    if (msg.type !== 'command') return
    upsertRow(msg.command)
    if (lastCommand.value?.id === msg.command.id) lastCommand.value = msg.command
  },
})
</script>

<style scoped>
.gap {
  margin-bottom: 12px;
}
.gap-top {
  margin-top: 16px;
}
.expand {
  padding: 8px 16px;
}
</style>
