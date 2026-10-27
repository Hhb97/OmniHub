import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { authApi } from '@/api/auth'
import type { LoginRequest, UserInfo } from '@/types'
import { clearToken, getToken, setToken } from '@/utils/token'
import { wsManager } from '@/utils/ws'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(getToken())
  const user = ref<UserInfo | null>(null)

  const permissions = computed(() => new Set(user.value?.permissions ?? []))
  const isLoggedIn = computed(() => !!token.value)

  function hasPermission(code?: string | string[]): boolean {
    if (!code || (Array.isArray(code) && code.length === 0)) return true
    const codes = Array.isArray(code) ? code : [code]
    return codes.some((c) => permissions.value.has(c))
  }

  async function login(form: LoginRequest): Promise<void> {
    const res = await authApi.login(form)
    token.value = res.token
    setToken(res.token)
    user.value = res.user
  }

  async function fetchMe(): Promise<UserInfo> {
    const me = await authApi.me()
    user.value = me
    return me
  }

  /** 清除本地登录态（不调用接口） */
  function reset(): void {
    token.value = ''
    user.value = null
    clearToken()
    wsManager.close()
  }

  async function logout(): Promise<void> {
    try {
      await authApi.logout()
    } catch {
      /* 退出时忽略服务端错误 */
    }
    reset()
  }

  return { token, user, permissions, isLoggedIn, hasPermission, login, fetchMe, reset, logout }
})
