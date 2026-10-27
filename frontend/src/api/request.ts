import axios, { AxiosError, type AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import type { ApiResult } from '@/types'
import { clearToken, getToken } from '@/utils/token'

declare module 'axios' {
  interface AxiosRequestConfig {
    /** 为 true 时请求失败不弹出全局错误提示 */
    silent?: boolean
  }
}

export class ApiError extends Error {
  code: number
  data: unknown
  constructor(code: number, message: string, data?: unknown) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.data = data
  }
}

const instance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || '/api',
  timeout: 20_000,
})

instance.interceptors.request.use((config) => {
  const token = getToken()
  if (token) config.headers.set('Authorization', `Bearer ${token}`)
  return config
})

let redirecting = false

async function handleUnauthorized(): Promise<void> {
  clearToken()
  if (redirecting) return
  redirecting = true
  try {
    const { default: router } = await import('@/router')
    const { useAuthStore } = await import('@/stores/auth')
    useAuthStore().reset()
    const current = router.currentRoute.value
    if (current.path !== '/login') {
      await router.replace({ path: '/login', query: { redirect: current.fullPath } })
    }
  } finally {
    redirecting = false
  }
}

/** 把 400 的字段错误拼进提示 */
function detailOf(data: unknown): string {
  if (data && typeof data === 'object' && !Array.isArray(data)) {
    const parts = Object.entries(data as Record<string, unknown>)
      .filter(([, v]) => typeof v === 'string' && v)
      .map(([k, v]) => `${k}: ${v as string}`)
    if (parts.length) return `（${parts.join('；')}）`
  }
  return ''
}

function fail(err: ApiError, silent: boolean | undefined): never {
  if (err.code === 401) {
    void handleUnauthorized()
    if (!silent) ElMessage.error(err.message || '登录已过期，请重新登录')
  } else if (!silent) {
    const msg = err.code === 400 ? `${err.message}${detailOf(err.data)}` : err.message
    ElMessage.error(msg || '请求失败')
  }
  throw err
}

instance.interceptors.response.use(
  (resp) => {
    const body = resp.data as ApiResult | undefined
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code !== 0) {
        return fail(new ApiError(body.code, body.message, body.data), resp.config.silent)
      }
      return resp
    }
    return fail(new ApiError(500, '响应格式错误'), resp.config.silent)
  },
  (error: AxiosError<ApiResult>) => {
    const silent = error.config?.silent
    const status = error.response?.status
    const body = error.response?.data
    if (status) {
      const message =
        body && typeof body === 'object' && body.message
          ? body.message
          : (
              {
                400: '请求参数错误',
                401: '登录已过期，请重新登录',
                403: '没有权限执行此操作',
                404: '请求的资源不存在',
                409: '操作冲突',
              } as Record<number, string>
            )[status] || `服务器错误（${status}）`
      return fail(new ApiError(body?.code ?? status, message, body?.data), silent)
    }
    const message =
      error.code === 'ECONNABORTED' ? '请求超时，请稍后重试' : '网络异常，无法连接服务器'
    return fail(new ApiError(0, message), silent)
  },
)

/** 返回已解包的 data（{code,message,data} 的 data 部分） */
async function request<T>(config: AxiosRequestConfig): Promise<T> {
  const resp = await instance.request<ApiResult<T>>(config)
  return resp.data.data
}

/** 去掉空字符串 / null / undefined 的查询参数 */
function cleanParams(params?: object): object | undefined {
  if (!params) return undefined
  return Object.fromEntries(
    Object.entries(params).filter(([, v]) => v !== '' && v !== null && v !== undefined),
  )
}

export const http = {
  get: <T>(url: string, params?: object, config?: AxiosRequestConfig) =>
    request<T>({ ...config, method: 'GET', url, params: cleanParams(params) }),
  post: <T>(url: string, data?: unknown, config?: AxiosRequestConfig) =>
    request<T>({ ...config, method: 'POST', url, data }),
  put: <T>(url: string, data?: unknown, config?: AxiosRequestConfig) =>
    request<T>({ ...config, method: 'PUT', url, data }),
  delete: <T>(url: string, config?: AxiosRequestConfig) =>
    request<T>({ ...config, method: 'DELETE', url }),
}
