<template>
  <div>
    <div class="toolbar tb">
      <el-button v-if="editable" type="primary" size="small" @click="openAdd">添加事件</el-button>
    </div>
    <el-table :data="list" stripe size="small">
      <template #empty><el-empty description="暂无事件" :image-size="60" /></template>
      <el-table-column prop="identifier" label="标识符" min-width="140"
        ><template #default="{ row }"
          ><span class="mono">{{ row.identifier }}</span></template
        ></el-table-column
      >
      <el-table-column prop="name" label="名称" min-width="120" />
      <el-table-column label="级别" width="90">
        <template #default="{ row }"><LevelTag :level="row.level" kind="event" /></template>
      </el-table-column>
      <el-table-column label="输出参数" min-width="200">
        <template #default="{ row }">
          {{
            row.outputs
              .map((o: ThingParam) => `${o.name}(${o.identifier}:${o.dataType})`)
              .join('，') || '-'
          }}
        </template>
      </el-table-column>
      <el-table-column prop="description" label="描述" min-width="140" show-overflow-tooltip />
      <el-table-column v-if="editable" label="操作" width="120" fixed="right">
        <template #default="{ row, $index }">
          <el-button link type="primary" @click="openEdit(row as ThingEvent, $index)"
            >编辑</el-button
          >
          <el-button link type="danger" @click="list.splice($index, 1)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <EventDialog
      v-model="dialog"
      :item="editing"
      :existing="list.map((p) => p.identifier)"
      @save="onSave"
    />
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import LevelTag from '@/components/LevelTag.vue'
import type { ThingEvent, ThingParam } from '@/types'
import EventDialog from './EventDialog.vue'

defineProps<{ editable: boolean }>()
const list = defineModel<ThingEvent[]>({ required: true })

const dialog = ref(false)
const editing = ref<ThingEvent | null>(null)
let editIndex = -1

function openAdd() {
  editing.value = null
  editIndex = -1
  dialog.value = true
}
function openEdit(row: ThingEvent, index: number) {
  editing.value = row
  editIndex = index
  dialog.value = true
}
function onSave(e: ThingEvent) {
  if (editIndex >= 0) list.value.splice(editIndex, 1, e)
  else list.value.push(e)
}
</script>

<style scoped>
.tb {
  margin-bottom: 12px;
}
</style>
