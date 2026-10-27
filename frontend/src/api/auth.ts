import { http } from './request'
import type { ChangePasswordRequest, LoginRequest, LoginResult, UserInfo } from '@/types'

export const authApi = {
  login: (body: LoginRequest) => http.post<LoginResult>('/auth/login', body),
  logout: () => http.post<void>('/auth/logout', undefined, { silent: true }),
  me: () => http.get<UserInfo>('/auth/me'),
  changePassword: (body: ChangePasswordRequest) => http.put<void>('/auth/password', body),
}
