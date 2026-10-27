export type OnlineStatus = 'ONLINE' | 'OFFLINE' | 'INACTIVE'

export interface Device {
  id: number
  name: string
  deviceKey: string
  productId: number
  productName?: string
  productKey?: string
  description?: string
  enabled: boolean
  onlineStatus: OnlineStatus
  mqttConnected?: boolean
  clientId?: string
  lastOnlineAt?: string
  lastOfflineAt?: string
  lastCommunicationAt?: string
  createdAt: string
}

export interface DeviceCreateRequest {
  name: string
  productId: number
  deviceKey?: string
  description?: string
}

/** 仅创建响应含 secret，且只返回一次 */
export type DeviceCreated = Device & { secret: string }

export interface DeviceUpdateRequest {
  name: string
  description?: string
}

export interface DeviceQuery {
  keyword?: string
  productId?: number
  onlineStatus?: OnlineStatus | ''
  enabled?: boolean | ''
}
