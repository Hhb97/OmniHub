<template>
  <div>
    <div class="toolbar tb">
      <el-button v-if="editable" type="primary" size="small" @click="openAdd">添加服务</el-button>
    </div>
    <el-table :data="list" stripe size="small">
      <template #empty><el-empty description="暂无服务" :image-size="60" /></template>
      <el-table-column prop="identifier" label="标识符" min-width="140"
        ><template #default="{ row }"
          ><span class="mono">{{ row.identifier }}</span></template
        ></el-table-column
      >
      <el-table-column prop="name" label="名称" min-width="120" />
      <el-table-column label="输入参数" min-width="200">
        <template #default="{ row }">{{ paramText(row.inputs) }}</template>
      </el-table-column>
      <el-table-column label="输出参数" min-width="180">
        <template #default="{ row }">{{ paramText(row.outputs) }}</template>
      </el-table-column>
      <el-table-column prop="description" label="描述" min-width="140" show-overflow-tooltip />
      <el-table-column v-if="editable" label="操作" width="120" fixed="right">
        <template #default="{ row, $index }">
          <el-button link type="primary" @click="openEdit(row as ThingService, $index)"
            >编辑</el-button
          >
          <el-button link type="danger" @click="list.splice($index, 1)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <ServiceDialog
      v-model="dialog"
      :item="editing"
      :existing="list.map((p) => p.identifier)"
      @save="onSave"
    />
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import type { ThingParam, ThingService } from '@/types'
import ServiceDialog from './ServiceDialog.vue'

defineProps<{ editable: boolean }>()
const list = defineModel<ThingService[]>({ required: true })

const dialog = ref(false)
const editing = ref<ThingService | null>(null)
let editIndex = -1

function openAdd() {
  editing.value = null
  editIndex = -1
  dialog.value = true
}
function openEdit(row: ThingService, index: number) {
  editing.value = row
  editIndex = index
  dialog.value = true
}
function onSave(s: ThingService) {
  if (editIndex >= 0) list.value.splice(editIndex, 1, s)
  else list.value.push(s)
}

function paramText(params: ThingParam[]): string {
  return params.map((o) => `${o.name}(${o.identifier}:${o.dataType})`).join('，') || '-'
}
</script>

<style scoped>
.tb {
  margin-bottom: 12px;
}
</style>
