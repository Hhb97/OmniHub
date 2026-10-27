<template>
  <div class="params">
    <div v-for="(p, i) in params" :key="i" class="param">
      <div class="row">
        <el-form-item
          :prop="`${propPath}.${i}.identifier`"
          :rules="idRules"
          label-width="0"
          class="grow"
        >
          <el-input v-model.trim="p.identifier" placeholder="标识符" />
        </el-form-item>
        <el-form-item
          :prop="`${propPath}.${i}.name`"
          :rules="nameRules"
          label-width="0"
          class="grow"
        >
          <el-input v-model.trim="p.name" placeholder="名称" />
        </el-form-item>
        <el-form-item label-width="0">
          <el-select v-model="p.dataType" style="width: 110px" @change="onTypeChange(p)">
            <el-option v-for="t in DATA_TYPES" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-button link type="danger" @click="params.splice(i, 1)">删除</el-button>
      </div>
      <div v-if="withSpec && 'spec' in p && p.spec && specTypes.includes(p.dataType)" class="spec">
        <SpecFields v-model="p.spec" :data-type="p.dataType" />
      </div>
    </div>
    <el-button size="small" @click="add">{{ addText }}</el-button>
  </div>
</template>

<script setup lang="ts">
import type { ThingInput, ThingParam } from '@/types'
import { DATA_TYPES, IDENTIFIER_MESSAGE, IDENTIFIER_REGEX } from '@/utils/constants'
import SpecFields from './SpecFields.vue'

const props = defineProps<{
  propPath: string
  withSpec?: boolean
  addText?: string
}>()
const params = defineModel<(ThingParam | ThingInput)[]>({ required: true })

const specTypes = ['INT', 'FLOAT', 'STRING', 'ENUM']

const idRules = [
  { required: true, message: '必填', trigger: 'blur' },
  { pattern: IDENTIFIER_REGEX, message: IDENTIFIER_MESSAGE, trigger: 'blur' },
]
const nameRules = [{ required: true, message: '必填', trigger: 'blur' }]

function add() {
  const p: ThingParam | ThingInput = { identifier: '', name: '', dataType: 'INT' }
  if (props.withSpec) (p as ThingInput).spec = {}
  params.value.push(p)
}

function onTypeChange(p: ThingParam | ThingInput) {
  if (props.withSpec) (p as ThingInput).spec = {}
}
</script>

<style scoped>
.param {
  border: 1px dashed #dcdfe6;
  border-radius: 4px;
  padding: 8px 8px 0;
  margin-bottom: 8px;
}
.row {
  display: flex;
  gap: 8px;
  align-items: flex-start;
}
.grow {
  flex: 1;
  min-width: 0;
}
.spec {
  padding: 0 0 8px;
}
</style>
