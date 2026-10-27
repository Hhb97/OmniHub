import type { DataType } from '@/types'

type TagType = 'primary' | 'success' | 'warning' | 'danger' | 'info'

export interface Option<V = string> {
  label: string
  value: V
}

export const DATA_TYPES: DataType[] = ['INT', 'FLOAT', 'BOOL', 'STRING', 'ENUM', 'JSON']
export const NUMERIC_TYPES: DataType[] = ['INT', 'FLOAT']

export const ONLINE_STATUS_LABEL: Record<string, string> = {
  ONLINE: '在线',
  OFFLINE: '离线',
  INACTIVE: '未激活',
}
export const ONLINE_STATUS_TAG: Record<string, TagType> = {
  ONLINE: 'success',
  OFFLINE: 'danger',
  INACTIVE: 'info',
}

export const COMMAND_STATUS_LABEL: Record<string, string> = {
  PENDING: '待发送',
  SENT: '已发送',
  RECEIVED: '设备已接收',
  SUCCESS: '成功',
  FAILED: '失败',
  TIMEOUT: '超时',
}
export const COMMAND_STATUS_TAG: Record<string, TagType> = {
  PENDING: 'info',
  SENT: 'primary',
  RECEIVED: 'warning',
  SUCCESS: 'success',
  FAILED: 'danger',
  TIMEOUT: 'danger',
}
export const COMMAND_STATUSES = Object.keys(COMMAND_STATUS_LABEL)

export const COMMAND_TYPE_LABEL: Record<string, string> = {
  SERVICE: '服务调用',
  PROPERTY_SET: '属性设置',
  PROPERTY_GET: '属性获取',
}

export const ALARM_LEVEL_LABEL: Record<string, string> = {
  INFO: '提示',
  WARNING: '警告',
  MAJOR: '重要',
  CRITICAL: '紧急',
}
export const ALARM_LEVEL_TAG: Record<string, TagType> = {
  INFO: 'info',
  WARNING: 'warning',
  MAJOR: 'danger',
  CRITICAL: 'danger',
}
export const ALARM_LEVEL_COLOR: Record<string, string> = {
  INFO: '#909399',
  WARNING: '#e6a23c',
  MAJOR: '#f56c6c',
  CRITICAL: '#c0242b',
}

export const EVENT_LEVEL_LABEL: Record<string, string> = {
  INFO: '信息',
  WARNING: '警告',
  ERROR: '错误',
}
export const EVENT_LEVEL_TAG: Record<string, TagType> = {
  INFO: 'info',
  WARNING: 'warning',
  ERROR: 'danger',
}

export const RULE_TYPE_LABEL: Record<string, string> = {
  THRESHOLD: '阈值',
  OFFLINE: '离线',
  EVENT: '事件',
}

export const SCOPE_LABEL: Record<string, string> = {
  PRODUCT: '产品',
  DEVICE: '设备',
}

export const OPERATOR_LABEL: Record<string, string> = {
  GT: '>',
  GTE: '>=',
  LT: '<',
  LTE: '<=',
  EQ: '=',
  NE: '!=',
}

export const ALARM_ACTION_LABEL: Record<string, string> = {
  TRIGGER: '触发',
  ACK: '确认',
  COMMENT: '备注',
  RECOVER: '恢复',
}

export const MESSAGE_TYPE_LABEL: Record<string, string> = {
  PROPERTY: '属性',
  EVENT: '事件',
  STATUS: '状态',
  COMMAND: '指令',
  REPLY: '应答',
}

export const PRODUCT_STATUS_LABEL: Record<string, string> = {
  ENABLED: '启用',
  DISABLED: '停用',
}

export const TREND_INTERVALS: Option[] = [
  { label: '原始数据', value: 'RAW' },
  { label: '1 分钟', value: '1m' },
  { label: '5 分钟', value: '5m' },
  { label: '1 小时', value: '1h' },
  { label: '1 天', value: '1d' },
]

export const IDENTIFIER_REGEX = /^[a-zA-Z][a-zA-Z0-9_]{0,63}$/
export const IDENTIFIER_MESSAGE = '以字母开头，仅含字母、数字、下划线，最长 64 位'
