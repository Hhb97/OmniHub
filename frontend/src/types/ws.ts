import type { Alarm } from './alarm'
import type { Command } from './command'
import type { JsonValue } from './common'
import type { OnlineStatus } from './device'

export type WsMessage =
  | { type: 'property'; deviceId: number; ts: number; data: Record<string, JsonValue> }
  | { type: 'status'; deviceId: number; ts: number; onlineStatus: OnlineStatus }
  | {
      type: 'event'
      deviceId: number
      ts: number
      identifier: string
      level: string
      params: unknown
    }
  | { type: 'command'; deviceId: number; command: Command; ts?: number }
  | { type: 'alarm'; deviceId: number; alarm: Alarm; ts?: number }
  | { type: 'dashboard' }
  | { type: 'subscribed'; deviceId?: number; topic?: string }
  | { type: 'error'; message: string }

export type WsStatus = 'idle' | 'connecting' | 'open' | 'reconnecting'
