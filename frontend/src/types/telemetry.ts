import type { JsonValue } from './common'
import type { DataType } from './thingModel'

export interface LatestValue {
  identifier: string
  name: string
  unit?: string
  dataType: DataType
  value: JsonValue
  reportedAt: string
}

export interface HistoryRecord {
  identifier: string
  value: JsonValue
  ts: string
}

export type TrendInterval = 'RAW' | '1m' | '5m' | '1h' | '1d'

export interface TrendSeries {
  identifier: string
  name: string
  unit?: string
  points: [number, JsonValue][]
}

export interface TrendResult {
  interval: string
  series: TrendSeries[]
}

export interface DeviceEvent {
  id: number
  identifier: string
  name: string
  level: 'INFO' | 'WARNING' | 'ERROR'
  params: unknown
  ts: string
}

export type MessageDirection = 'UP' | 'DOWN'

export interface DeviceMessage {
  id: number
  direction: MessageDirection
  type: 'PROPERTY' | 'EVENT' | 'STATUS' | 'COMMAND' | 'REPLY'
  topic: string
  payload: unknown
  result: 'OK' | 'INVALID' | 'REJECTED'
  message?: string
  createdAt: string
}
