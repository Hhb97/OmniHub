<template>
  <div class="spec">
    <template v-if="dataType === 'INT' || dataType === 'FLOAT'">
      <el-form-item label="最小值">
        <el-input-number
          v-model="spec.min"
          :controls="false"
          :precision="dataType === 'INT' ? 0 : undefined"
        />
      </el-form-item>
      <el-form-item label="最大值">
        <el-input-number
          v-model="spec.max"
          :controls="false"
          :precision="dataType === 'INT' ? 0 : undefined"
        />
      </el-form-item>
      <el-form-item label="步长">
        <el-input-number v-model="spec.step" :controls="false" :min="0" />
      </el-form-item>
    </template>
    <el-form-item v-else-if="dataType === 'STRING'" label="最大长度">
      <el-input-number v-model="spec.maxLength" :controls="false" :min="1" :precision="0" />
    </el-form-item>
    <el-form-item v-else-if="dataType === 'ENUM'" label="枚举项">
      <div class="enum-list">
        <div v-for="(ev, i) in spec.enumValues" :key="i" class="enum-row">
          <el-input v-model="ev.value" placeholder="值" style="width: 110px" />
          <el-input v-model="ev.label" placeholder="显示名称" style="flex: 1" />
          <el-button link type="danger" @click="spec.enumValues?.splice(i, 1)">删除</el-button>
        </div>
        <el-button size="small" @click="addEnum">添加枚举项</el-button>
      </div>
    </el-form-item>
  </div>
</template>

<script setup lang="ts">
import { watchEffect } from 'vue'
import type { DataType, Spec } from '@/types'

const props = defineProps<{ dataType: DataType }>()
const spec = defineModel<Spec>({ required: true })

watchEffect(() => {
  if (props.dataType === 'ENUM' && !spec.value.enumValues) spec.value.enumValues = []
})

function addEnum() {
  if (!spec.value.enumValues) spec.value.enumValues = []
  spec.value.enumValues.push({ value: '', label: '' })
}
</script>

<style scoped>
.enum-list {
  width: 100%;
}
.enum-row {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}
</style>
