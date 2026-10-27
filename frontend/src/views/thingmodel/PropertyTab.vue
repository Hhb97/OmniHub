<template>
  <div>
    <div class="toolbar tb">
      <el-button v-if="editable" type="primary" size="small" @click="openAdd">添加属性</el-button>
    </div>
    <el-table :data="list" stripe size="small">
      <template #empty><el-empty description="暂无属性" :image-size="60" /></template>
      <el-table-column prop="identifier" label="标识符" min-width="140"
        ><template #default="{ row }"
          ><span class="mono">{{ row.identifier }}</span></template
        ></el-table-column
      >
      <el-table-column prop="name" label="名称" min-width="120" />
      <el-table-column prop="dataType" label="类型" width="90" />
      <el-table-column label="取值范围" min-width="170">
        <template #default="{ row }">{{ specText(row as ThingProperty) }}</template>
      </el-table-column>
      <el-table-column prop="unit" label="单位" width="70" />
      <el-table-column label="读写" width="70">
        <template #default="{ row }">{{ row.accessMode === 'RW' ? '读写' : '只读' }}</template>
      </el-table-column>
      <el-table-column v-if="editable" label="操作" width="120" fixed="right">
        <template #default="{ row, $index }">
          <el-button link type="primary" @click="openEdit(row as ThingProperty, $index)"
            >编辑</el-button
          >
          <el-button link type="danger" @click="list.splice($index, 1)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <PropertyDialog
      v-model="dialog"
      :item="editing"
      :existing="list.map((p) => p.identifier)"
      @save="onSave"
    />
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import type { ThingProperty } from '@/types'
import PropertyDialog from './PropertyDialog.vue'

defineProps<{ editable: boolean }>()
const list = defineModel<ThingProperty[]>({ required: true })

const dialog = ref(false)
const editing = ref<ThingProperty | null>(null)
let editIndex = -1

function openAdd() {
  editing.value = null
  editIndex = -1
  dialog.value = true
}
function openEdit(row: ThingProperty, index: number) {
  editing.value = row
  editIndex = index
  dialog.value = true
}
function onSave(p: ThingProperty) {
  if (editIndex >= 0) list.value.splice(editIndex, 1, p)
  else list.value.push(p)
}

function specText(p: ThingProperty): string {
  const s = p.spec
  if (!s) return '-'
  if (p.dataType === 'INT' || p.dataType === 'FLOAT') {
    const parts: string[] = []
    if (s.min !== undefined || s.max !== undefined)
      parts.push(`${s.min ?? '-∞'} ~ ${s.max ?? '+∞'}`)
    if (s.step !== undefined) parts.push(`步长 ${s.step}`)
    return parts.join('，') || '-'
  }
  if (p.dataType === 'STRING') return s.maxLength !== undefined ? `最长 ${s.maxLength}` : '-'
  if (p.dataType === 'ENUM')
    return (s.enumValues ?? []).map((e) => `${e.value}=${e.label}`).join('，') || '-'
  return '-'
}
</script>

<style scoped>
.tb {
  margin-bottom: 12px;
}
</style>
