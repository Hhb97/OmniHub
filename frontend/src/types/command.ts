export type CommandType = 'SERVICE' | 'PROPERTY_SET' | 'PROPERTY_GET'
export type CommandStatus = 'PENDING' | 'SENT' | 'RECEIVED' | 'SUCCESS' | 'FAILED' | 'TIMEOUT'

export interface Command {
  id: number
  commandNo: string
  deviceId: number
  type: CommandType
  identifier?: string
  params?: unknown
  status: CommandStatus
  result?: unknown
  message?: string
  createdBy?: string
  createdAt: string
  sentAt?: string
  receivedAt?: string
  finishedAt?: string
}

export interface CommandRequest {
  type: CommandType
  identifier?: string
  params: Record<string, unknown>
}
