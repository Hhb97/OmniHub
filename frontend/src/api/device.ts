import { http } from './request'
import type {
  Command,
  CommandRequest,
  CommandStatus,
  Device,
  DeviceCreateRequest,
  DeviceCreated,
  DeviceEvent,
  DeviceMessage,
  DeviceQuery,
  DeviceUpdateRequest,
  HistoryRecord,
  LatestValue,
  MessageDirection,
  PageQuery,
  PageResult,
  TrendInterval,
  TrendResult,
} from '@/types'

export interface HistoryQuery extends PageQuery {
  identifiers?: string
  start?: string
  end?: string
}

export interface TrendQuery {
  identifiers?: string
  start?: string
  end?: string
  interval?: TrendInterval
}

export const deviceApi = {
  list: (params: PageQuery & DeviceQuery) => http.get<PageResult<Device>>('/devices', params),
  get: (id: number) => http.get<Device>(`/devices/${id}`),
  create: (body: DeviceCreateRequest) => http.post<DeviceCreated>('/devices', body),
  update: (id: number, body: DeviceUpdateRequest) => http.put<Device>(`/devices/${id}`, body),
  remove: (id: number) => http.delete<void>(`/devices/${id}`),
  rotateSecret: (id: number) => http.post<{ secret: string }>(`/devices/${id}/rotate-secret`),
  setEnabled: (id: number, enabled: boolean) =>
    http.put<void>(`/devices/${id}/enabled`, { enabled }),

  latest: (id: number) => http.get<LatestValue[]>(`/devices/${id}/latest`),
  history: (id: number, params: HistoryQuery) =>
    http.get<PageResult<HistoryRecord>>(`/devices/${id}/history`, params),
  trend: (id: number, params: TrendQuery, silent = false) =>
    http.get<TrendResult>(`/devices/${id}/trend`, params, { silent }),
  events: (id: number, params: PageQuery & { level?: string }) =>
    http.get<PageResult<DeviceEvent>>(`/devices/${id}/events`, params),
  messages: (id: number, params: PageQuery & { direction?: MessageDirection | '' }) =>
    http.get<PageResult<DeviceMessage>>(`/devices/${id}/messages`, params),

  sendCommand: (id: number, body: CommandRequest) =>
    http.post<Command>(`/devices/${id}/commands`, body),
  commands: (id: number, params: PageQuery & { status?: CommandStatus | '' }) =>
    http.get<PageResult<Command>>(`/devices/${id}/commands`, params),
  getCommand: (commandId: number) => http.get<Command>(`/commands/${commandId}`),
}
