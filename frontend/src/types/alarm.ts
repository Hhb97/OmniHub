export type RuleType = 'THRESHOLD' | 'OFFLINE' | 'EVENT'
export type AlarmScope = 'PRODUCT' | 'DEVICE'
export type AlarmOperator = 'GT' | 'GTE' | 'LT' | 'LTE' | 'EQ' | 'NE'
export type AlarmLevel = 'INFO' | 'WARNING' | 'MAJOR' | 'CRITICAL'

export interface AlarmRule {
  id: number
  name: string
  scope: AlarmScope
  productId?: number | null
  deviceId?: number | null
  productName?: string
  deviceName?: string
  ruleType: RuleType
  identifier?: string
  operator?: AlarmOperator
  threshold?: number
  triggerCount: number
  silenceSeconds: number
  level: AlarmLevel
  enabled: boolean
  description?: string
  createdAt: string
}

export type AlarmRuleForm = Omit<AlarmRule, 'id' | 'productName' | 'deviceName' | 'createdAt'>

export interface AlarmRuleQuery {
  keyword?: string
  ruleType?: RuleType | ''
  enabled?: boolean | ''
}

export interface Alarm {
  id: number
  ruleId: number
  ruleName: string
  deviceId: number
  deviceName: string
  ruleType: RuleType
  level: AlarmLevel
  status: 'ACTIVE' | 'RECOVERED'
  acknowledged: boolean
  message?: string
  triggerValue?: unknown
  triggerCount?: number
  firstTriggeredAt: string
  lastTriggeredAt: string
  recoveredAt?: string
  acknowledgedBy?: string
  acknowledgedAt?: string
}

export interface AlarmQuery {
  status?: 'ACTIVE' | 'RECOVERED' | ''
  acknowledged?: boolean | ''
  level?: AlarmLevel | ''
  deviceId?: number
  start?: string
  end?: string
}

export interface AlarmAction {
  id: number
  action: 'TRIGGER' | 'ACK' | 'COMMENT' | 'RECOVER'
  operator?: string
  remark?: string
  createdAt: string
}
