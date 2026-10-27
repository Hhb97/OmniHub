import { onBeforeUnmount, onMounted, unref, watch, type Ref } from 'vue'
import type { WsMessage } from '@/types'
import { wsManager } from '@/utils/ws'

export interface WsSubscriptionOptions {
  /** 订阅设备（可为 ref，变化时自动换订阅） */
  deviceId?: Ref<number | undefined | null> | number
  /** 订阅主题，如 dashboard */
  topic?: string
  /** 处理消息；设备订阅时只回调该设备的消息，topic 订阅只回调 dashboard 类消息 */
  onMessage: (msg: WsMessage) => void
}

/** 组件生命周期内订阅设备/主题，卸载时自动取消 */
export function useWsSubscription(options: WsSubscriptionOptions): void {
  let offDevice: (() => void) | null = null
  let offTopic: (() => void) | null = null
  let offListener: (() => void) | null = null

  function subscribeDevice(id: number | undefined | null) {
    offDevice?.()
    offDevice = null
    if (id !== undefined && id !== null) offDevice = wsManager.subscribeDevice(id)
  }

  onMounted(() => {
    offListener = wsManager.onMessage((msg) => {
      if (msg.type === 'dashboard') {
        if (options.topic === 'dashboard') options.onMessage(msg)
        return
      }
      if (msg.type === 'subscribed' || msg.type === 'error') {
        options.onMessage(msg)
        return
      }
      const id = unref(options.deviceId)
      if (id !== undefined && id !== null && msg.deviceId === id) options.onMessage(msg)
    })
    if (options.topic) offTopic = wsManager.subscribeTopic(options.topic)
    if (options.deviceId !== undefined) {
      subscribeDevice(unref(options.deviceId))
    }
  })

  if (options.deviceId !== undefined && typeof options.deviceId === 'object') {
    watch(options.deviceId, (id) => subscribeDevice(id))
  }

  onBeforeUnmount(() => {
    offListener?.()
    offDevice?.()
    offTopic?.()
    offListener = offDevice = offTopic = null
  })
}
