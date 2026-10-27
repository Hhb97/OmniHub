import { http } from './request'
import type {
  Alarm,
  AlarmAction,
  AlarmQuery,
  AlarmRule,
  AlarmRuleForm,
  AlarmRuleQuery,
  PageQuery,
  PageResult,
} from '@/types'

export const alarmApi = {
  listRules: (params: PageQuery & AlarmRuleQuery) =>
    http.get<PageResult<AlarmRule>>('/alarm-rules', params),
  createRule: (body: AlarmRuleForm) => http.post<AlarmRule>('/alarm-rules', body),
  updateRule: (id: number, body: AlarmRuleForm) => http.put<AlarmRule>(`/alarm-rules/${id}`, body),
  removeRule: (id: number) => http.delete<void>(`/alarm-rules/${id}`),
  setRuleEnabled: (id: number, enabled: boolean) =>
    http.put<void>(`/alarm-rules/${id}/enabled`, { enabled }),

  list: (params: PageQuery & AlarmQuery) => http.get<PageResult<Alarm>>('/alarms', params),
  ack: (id: number, remark: string) => http.post<void>(`/alarms/${id}/ack`, { remark }),
  comment: (id: number, remark: string) => http.post<void>(`/alarms/${id}/comments`, { remark }),
  actions: (id: number) => http.get<AlarmAction[]>(`/alarms/${id}/actions`),
}
