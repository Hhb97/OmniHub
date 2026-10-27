<template>
  <el-dialog
    v-model="visible"
    :title="mode === 'ack' ? '确认告警' : '添加备注'"
    width="460px"
    :close-on-click-modal="false"
    @open="remark = ''"
  >
    <p v-if="alarm" class="info">{{ alarm.deviceName }} · {{ alarm.ruleName }}</p>
    <el-form ref="formRef" :model="{ remark }" :rules="rules" label-width="60px" @submit.prevent>
      <el-form-item label="备注" prop="remark">
        <el-input
          v-model="remark"
          type="textarea"
          :rows="4"
          maxlength="255"
          show-word-limit
          :placeholder="mode === 'ack' ? '处理说明（可选）' : '请输入备注'"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">确定</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { alarmApi } from '@/api/alarm'
import type { Alarm } from '@/types'

const props = defineProps<{ mode: 'ack' | 'comment'; alarm: Alarm | null }>()
const emit = defineEmits<{ (e: 'done'): void }>()
const visible = defineModel<boolean>({ default: false })

const formRef = ref<FormInstance>()
const remark = ref('')
const submitting = ref(false)

const rules = computed<FormRules>(() => ({
  remark:
    props.mode === 'comment' ? [{ required: true, message: '请输入备注', trigger: 'blur' }] : [],
}))

async function submit() {
  if (!props.alarm) return
  if (!(await formRef.value?.validate().catch(() => false))) return
  submitting.value = true
  try {
    if (props.mode === 'ack') await alarmApi.ack(props.alarm.id, remark.value)
    else await alarmApi.comment(props.alarm.id, remark.value)
    ElMessage.success(props.mode === 'ack' ? '已确认' : '备注已添加')
    visible.value = false
    emit('done')
  } catch {
    /* 已提示 */
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.info {
  margin: 0 0 12px;
  color: #606266;
}
</style>
