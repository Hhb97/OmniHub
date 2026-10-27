export type DataType = 'INT' | 'FLOAT' | 'BOOL' | 'STRING' | 'ENUM' | 'JSON'
export type AccessMode = 'R' | 'RW'
export type EventLevel = 'INFO' | 'WARNING' | 'ERROR'

export interface EnumValue {
  value: string | number
  label: string
}

export interface Spec {
  min?: number
  max?: number
  step?: number
  maxLength?: number
  enumValues?: EnumValue[]
}

export interface ThingProperty {
  identifier: string
  name: string
  dataType: DataType
  unit?: string
  description?: string
  accessMode: AccessMode
  spec?: Spec
}

export interface ThingParam {
  identifier: string
  name: string
  dataType: DataType
}

export interface ThingInput extends ThingParam {
  spec?: Spec
}

export interface ThingEvent {
  identifier: string
  name: string
  level: EventLevel
  description?: string
  outputs: ThingParam[]
}

export interface ThingService {
  identifier: string
  name: string
  description?: string
  inputs: ThingInput[]
  outputs: ThingParam[]
}

export interface ThingModelBody {
  properties: ThingProperty[]
  events: ThingEvent[]
  services: ThingService[]
}

export interface ThingModel extends ThingModelBody {
  productId: number
  version: number
  updatedAt?: string
}

export interface ThingModelVersion {
  version: number
  createdAt: string
  createdBy?: string
}
