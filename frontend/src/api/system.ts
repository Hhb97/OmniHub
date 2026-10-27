import { http } from './request'
import type {
  LoginLog,
  OperationLog,
  PageQuery,
  PageResult,
  PermissionNode,
  Role,
  RoleForm,
  SysConfig,
  SysUser,
  UserCreateRequest,
  UserUpdateRequest,
} from '@/types'

export const systemApi = {
  listUsers: (params: PageQuery & { keyword?: string }) =>
    http.get<PageResult<SysUser>>('/system/users', params),
  createUser: (body: UserCreateRequest) => http.post<SysUser>('/system/users', body),
  updateUser: (id: number, body: UserUpdateRequest) =>
    http.put<SysUser>(`/system/users/${id}`, body),
  removeUser: (id: number) => http.delete<void>(`/system/users/${id}`),
  resetPassword: (id: number, newPassword: string) =>
    http.put<void>(`/system/users/${id}/password`, { newPassword }),

  listRoles: () => http.get<Role[]>('/system/roles'),
  createRole: (body: RoleForm) => http.post<Role>('/system/roles', body),
  updateRole: (id: number, body: RoleForm) => http.put<Role>(`/system/roles/${id}`, body),
  removeRole: (id: number) => http.delete<void>(`/system/roles/${id}`),
  permissions: () => http.get<PermissionNode[]>('/system/permissions'),

  operationLogs: (params: PageQuery & { keyword?: string; username?: string }) =>
    http.get<PageResult<OperationLog>>('/system/logs/operation', params),
  loginLogs: (params: PageQuery & { username?: string }) =>
    http.get<PageResult<LoginLog>>('/system/logs/login', params),

  listConfigs: () => http.get<SysConfig[]>('/system/configs'),
  updateConfig: (key: string, value: string) => http.put<void>('/system/configs', { key, value }),
}
