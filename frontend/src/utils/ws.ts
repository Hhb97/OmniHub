import { ref } from 'vue'
import type { WsMessage, WsStatus } from '@/types'
import { getToken } from './token'

type Listener = (msg: WsMessage) => void

const WS_PATH = import.meta.env.VITE_WS_PATH || '/ws'
const MAX_BACKOFF = 30_000
const IDLE_CLOSE_DELAY = 5_000

/** 当前连接状态（响应式，供界面展示连接指示器） */
export const wsStatus = ref<WsStatus>('idle')

function buildUrl(token: string): string {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws'
  return `${proto}://${location.host}${WS_PATH}?token=${encodeURIComponent(token)}`
}

/**
 * 全局唯一 WebSocket 管理器：
 * - 按 deviceId / topic 引用计数订阅，计数归零时才真正取消订阅
 * - 断线指数退避重连，重连成功后自动重新订阅
 * - 没有任何订阅时延迟关闭连接
 */
class WsManager {
  private socket: WebSocket | null = null
  private listeners = new Set<Listener>()
  private deviceRefs = new Map<number, number>()
  private topicRefs = new Map<string, number>()
  private retry = 0
  private reconnectTimer: ReturnType<typeof setTimeout> | undefined
  private idleTimer: ReturnType<typeof setTimeout> | undefined

  /** 监听所有消息，返回取消函数 */
  onMessage(listener: Listener): () => void {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  subscribeDevice(deviceId: number): () => void {
    return this.addRef(
      this.deviceRefs,
      deviceId,
      () => ({ action: 'subscribe', deviceId }),
      () => ({
        action: 'unsubscribe',
        deviceId,
      }),
    )
  }

  subscribeTopic(topic: string): () => void {
    return this.addRef(
      this.topicRefs,
      topic,
      () => ({ action: 'subscribe', topic }),
      () => ({
        action: 'unsubscribe',
        topic,
      }),
    )
  }

  /** 登出时调用：清空订阅并关闭连接 */
  close(): void {
    this.deviceRefs.clear()
    this.topicRefs.clear()
    this.teardown()
    wsStatus.value = 'idle'
  }

  private addRef<K>(
    refs: Map<K, number>,
    key: K,
    subMsg: () => object,
    unsubMsg: () => object,
  ): () => void {
    this.clearIdle()
    const n = refs.get(key) ?? 0
    refs.set(key, n + 1)
    if (n === 0) this.send(subMsg())
    this.ensureConnected()
    let released = false
    return () => {
      if (released) return
      released = true
      const cur = refs.get(key) ?? 0
      if (cur <= 1) {
        refs.delete(key)
        this.send(unsubMsg())
        this.scheduleIdleClose()
      } else {
        refs.set(key, cur - 1)
      }
    }
  }

  private hasSubscriptions(): boolean {
    return this.deviceRefs.size > 0 || this.topicRefs.size > 0
  }

  private send(payload: object): void {
    if (this.socket && this.socket.readyState === WebSocket.OPEN) {
      this.socket.send(JSON.stringify(payload))
    }
    // 未连接时无需发送：onopen 会按引用表重新订阅
  }

  private ensureConnected(): void {
    if (this.socket || this.reconnectTimer) return
    this.connect()
  }

  private connect(): void {
    const token = getToken()
    if (!token) {
      wsStatus.value = 'idle'
      return
    }
    wsStatus.value = this.retry > 0 ? 'reconnecting' : 'connecting'
    let socket: WebSocket
    try {
      socket = new WebSocket(buildUrl(token))
    } catch {
      this.scheduleReconnect()
      return
    }
    this.socket = socket
    socket.onopen = () => {
      this.retry = 0
      wsStatus.value = 'open'
      this.deviceRefs.forEach((_, deviceId) => this.send({ action: 'subscribe', deviceId }))
      this.topicRefs.forEach((_, topic) => this.send({ action: 'subscribe', topic }))
    }
    socket.onmessage = (ev: MessageEvent) => {
      let msg: WsMessage
      try {
        msg = JSON.parse(String(ev.data)) as WsMessage
      } catch {
        return
      }
      this.listeners.forEach((l) => {
        try {
          l(msg)
        } catch (e) {
          console.error('[ws] listener error', e)
        }
      })
    }
    socket.onclose = () => {
      if (this.socket !== socket) return
      this.socket = null
      if (this.hasSubscriptions()) this.scheduleReconnect()
      else wsStatus.value = 'idle'
    }
    socket.onerror = () => {
      // onclose 随后触发，由其负责重连
    }
  }

  private scheduleReconnect(): void {
    if (this.reconnectTimer) return
    this.retry += 1
    wsStatus.value = 'reconnecting'
    const delay = Math.min(1000 * 2 ** (this.retry - 1), MAX_BACKOFF) + Math.random() * 500
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = undefined
      if (this.hasSubscriptions()) this.connect()
      else wsStatus.value = 'idle'
    }, delay)
  }

  private scheduleIdleClose(): void {
    if (this.hasSubscriptions() || this.idleTimer) return
    this.idleTimer = setTimeout(() => {
      this.idleTimer = undefined
      if (!this.hasSubscriptions()) {
        this.teardown()
        wsStatus.value = 'idle'
      }
    }, IDLE_CLOSE_DELAY)
  }

  private clearIdle(): void {
    if (this.idleTimer) clearTimeout(this.idleTimer)
    this.idleTimer = undefined
  }

  private teardown(): void {
    this.clearIdle()
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer)
    this.reconnectTimer = undefined
    this.retry = 0
    const s = this.socket
    this.socket = null
    if (s) {
      s.onclose = null
      s.onerror = null
      s.onmessage = null
      s.onopen = null
      s.close()
    }
  }
}

export const wsManager = new WsManager()
