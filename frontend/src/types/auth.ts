export interface UserInfo {
  id: number
  username: string
  nickname: string
  tenantId: number
  roles: string[]
  permissions: string[]
}

export interface LoginRequest {
  username: string
  password: string
}

export interface LoginResult {
  token: string
  expiresIn: number
  user: UserInfo
}

export interface ChangePasswordRequest {
  oldPassword: string
  newPassword: string
}
