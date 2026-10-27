<template>
  <el-input-number
    v-if="dataType === 'INT' || dataType === 'FLOAT'"
    v-model="numModel"
    :min="spec?.min"
    :max="spec?.max"
    :step="spec?.step ?? (dataType === 'INT' ? 1 : 0.1)"
    :precision="dataType === 'INT' ? 0 : undefined"
    controls-position="right"
    style="width: 100%"
  />
  <el-switch
    v-else-if="dataType === 'BOOL'"
    v-model="boolModel"
    active-text="true"
    inactive-text="false"
  />
  <el-select
    v-else-if="dataType === 'ENUM'"
    v-model="enumModel"
    placeholder="请选择"
    clearable
    style="width: 100%"
  >
    <el-option
      v-for="e in spec?.enumValues ?? []"
      :key="String(e.value)"
      :label="`${e.label} (${e.value})`"
      :value="e.value"
    />
  </el-select>
  <el-input
    v-else-if="dataType === 'JSON'"
    v-model="textModel"
    type="textarea"
    :rows="3"
    placeholder='JSON，如 {"a":1}'
  />
  <el-input
    v-else
    v-model="textModel"
    :maxlength="spec?.maxLength"
    :show-word-limit="!!spec?.maxLength"
    clearable
  />
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { DataType, Spec } from '@/types'

defineProps<{ dataType: DataType; spec?: Spec }>()
const model = defineModel<unknown>()

const numModel = computed<number | undefined>({
  get: () => (typeof model.value === 'number' ? model.value : undefined),
  set: (v) => {
    model.value = v
  },
})
const boolModel = computed<boolean>({
  get: () => model.value === true,
  set: (v) => {
    model.value = v
  },
})
const enumModel = computed<string | number | undefined>({
  get: () =>
    typeof model.value === 'string' || typeof model.value === 'number' ? model.value : undefined,
  set: (v) => {
    model.value = v
  },
})
const textModel = computed<string>({
  get: () => (typeof model.value === 'string' ? model.value : ''),
  set: (v) => {
    model.value = v === '' ? undefined : v
  },
})
</script>
