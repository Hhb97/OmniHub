/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** API 基础路径，默认 /api */
  readonly VITE_API_BASE?: string
  /** WebSocket 路径，默认 /ws */
  readonly VITE_WS_PATH?: string
  /** 展示在设备接入信息里的 MQTT Broker 地址 */
  readonly VITE_MQTT_BROKER?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
