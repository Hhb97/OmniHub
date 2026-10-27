import { http } from './request'
import type { AlarmStats, Device, Overview, ReportTrendPoint, TopDevice } from '@/types'

export const dashboardApi = {
  overview: () => http.get<Overview>('/dashboard/overview'),
  reportTrend: (hours = 24) => http.get<ReportTrendPoint[]>('/dashboard/report-trend', { hours }),
  alarmStats: (days = 7) => http.get<AlarmStats>('/dashboard/alarm-stats', { days }),
  recentDevices: (limit = 10) => http.get<Device[]>('/dashboard/recent-devices', { limit }),
  topDevices: (limit = 5) => http.get<TopDevice[]>('/dashboard/top-devices', { limit }),
}
