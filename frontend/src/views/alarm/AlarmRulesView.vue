<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <el-input
          v-model.trim="query.keyword"
          placeholder="规则名称"
          clearable
          style="width: 200px"
          @keyup.enter="search"
          @clear="search"
        />
        <el-select
          v-model="query.ruleType"
          placeholder="规则类型"
          clearable
          style="width: 130px"
          @change="search"
        >
          <el-option
            v-for="(label, key) in RULE_TYPE_LABEL"
            :key="key"
            :label="label"
            :value="key"
          />
        </el-select>
        <el-select
          v-model="query.enabled"
          placeholder="启用状态"
          clearable
          style="width: 120px"
          @change="search"
        >
          <el-option label="已启用" :value="true" />
          <el-option label="已停用" :value="false" />
        </el-select>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
        <div class="spacer" />
        <el-button type="primary" @click="openCreate">新建规则</el-button>
      </div>
    </el-card>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows" stripe>
        <template #empty><TableEmpty :error="error" @retry="load" /></template>
        <el-table-column prop="name" label="规则名称" min-width="150" />
        <el-table-column label="类型" width="80"
          ><template #default="{ row }">{{
            RULE_TYPE_LABEL[row.ruleType] ?? row.ruleType
          }}</template></el-table-column
        >
        <el-table-column label="作用范围" min-width="170">
          <template #default="{ row }">
            {{ SCOPE_LABEL[row.scope] ?? row.scope }}：{{
              row.scope === 'PRODUCT' ? row.productName : row.deviceName
            }}
          </template>
        </el-table-column>
        <el-table-column label="触发条件" min-width="190"
          ><template #default="{ row }">{{
            condition(row as AlarmRule)
          }}</template></el-table-column
        >
        <el-table-column label="级别" width="80"
          ><template #default="{ row }"><LevelTag :level="row.level" /></template
        ></el-table-column>
        <el-table-column prop="silenceSeconds" label="静默(秒)" width="90" align="right" />
        <el-table-column label="启用" width="80">
          <template #default="{ row }">
            <el-switch :model-value="row.enabled" :before-change="() => toggle(row as AlarmRule)" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row as AlarmRule)">编辑</el-button>
            <el-button link type="danger" @click="remove(row as AlarmRule)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <AppPagination
        :page="page"
        :size="size"
        :total="total"
        @page-change="onPageChange"
        @size-change="onSizeChange"
      />
    </el-card>

    <AlarmRuleDialog v-model="dialog" :rule="editing" @saved="load" />
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import AppPagination from '@/components/AppPagination.vue'
import LevelTag from '@/components/LevelTag.vue'
import TableEmpty from '@/components/TableEmpty.vue'
import { alarmApi } from '@/api/alarm'
import { useTable } from '@/composables/useTable'
import type { AlarmRule, AlarmRuleQuery } from '@/types'
import { OPERATOR_LABEL, RULE_TYPE_LABEL, SCOPE_LABEL } from '@/utils/constants'
import AlarmRuleDialog from './AlarmRuleDialog.vue'

const {
  query,
  rows,
  total,
  page,
  size,
  loading,
  error,
  load,
  search,
  reset,
  onPageChange,
  onSizeChange,
} = useTable<AlarmRule, AlarmRuleQuery>((p) => alarmApi.listRules(p), {
  keyword: '',
  ruleType: '',
  enabled: '',
})

const dialog = ref(false)
const editing = ref<AlarmRule | null>(null)

function openCreate() {
  editing.value = null
  dialog.value = true
}
function openEdit(r: AlarmRule) {
  editing.value = r
  dialog.value = true
}

function condition(r: AlarmRule): string {
  if (r.ruleType === 'OFFLINE') return '设备离线'
  if (r.ruleType === 'EVENT') return `收到事件 ${r.identifier ?? ''}`
  return `${r.identifier ?? ''} ${OPERATOR_LABEL[r.operator ?? ''] ?? r.operator} ${r.threshold}，连续 ${r.triggerCount} 次`
}

async function toggle(r: AlarmRule): Promise<boolean> {
  try {
    await alarmApi.setRuleEnabled(r.id, !r.enabled)
    r.enabled = !r.enabled
    ElMessage.success(r.enabled ? '已启用' : '已停用')
    return true
  } catch {
    return false
  }
}

async function remove(r: AlarmRule) {
  try {
    await ElMessageBox.confirm(`确定删除告警规则「${r.name}」吗？`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
    })
  } catch {
    return
  }
  try {
    await alarmApi.removeRule(r.id)
    ElMessage.success('已删除')
    await load()
  } catch {
    /* 已提示 */
  }
}
</script>
