export interface Overview {
  productCount: number
  deviceCount: number
  onlineCount: number
  offlineCount: number
  inactiveCount: number
  onlineRate: number
  todayAlarmCount: number
  activeAlarmCount: number
  todayMessageCount: number
}

export interface ReportTrendPoint {
  time: string
  count: number
}

export interface AlarmStats {
  byLevel: { level: string; count: number }[]
  byDay: { date: string; count: number }[]
}

export interface TopDevice {
  deviceId: number
  name: string
  messageCount: number
}
