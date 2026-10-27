export interface SysUser {
  id: number
  username: string
  nickname?: string
  email?: string
  enabled: boolean
  roles: string[]
  createdAt: string
}

export interface UserCreateRequest {
  username: string
  password: string
  nickname: string
  email: string
  roleIds: number[]
}

export interface UserUpdateRequest {
  nickname: string
  email: string
  enabled: boolean
  roleIds: number[]
}

export interface Role {
  id: number
  code: string
  name: string
  description?: string
  permissions: string[]
}

export interface RoleForm {
  code: string
  name: string
  description: string
  permissions: string[]
}

export interface PermissionNode {
  code: string
  name: string
  type: 'MENU' | 'API'
  children?: PermissionNode[]
}

export interface OperationLog {
  id: number
  username: string
  module: string
  action: string
  method: string
  path: string
  ip: string
  success: boolean
  costMs: number
  detail?: string
  createdAt: string
}

export interface LoginLog {
  id: number
  username: string
  ip: string
  success: boolean
  message?: string
  createdAt: string
}

export interface SysConfig {
  key: string
  value: string
  description?: string
}
