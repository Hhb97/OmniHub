/**
 * MQTT 接入信息展示，Topic 规范见 docs/mqtt-protocol.md。
 */
export interface MqttTopic {
  label: string
  direction: '上行（设备发布）' | '下行（设备订阅）'
  topic: string
}

export function mqttBroker(): string {
  return import.meta.env.VITE_MQTT_BROKER || '<Broker 地址>:1883'
}

export function mqttTopics(productKey: string, deviceKey: string): MqttTopic[] {
  const base = `iot/${productKey}/${deviceKey}`
  return [
    { label: '属性上报', direction: '上行（设备发布）', topic: `${base}/property/post` },
    { label: '事件上报', direction: '上行（设备发布）', topic: `${base}/event/post` },
    { label: '上下线状态（遗嘱）', direction: '上行（设备发布）', topic: `${base}/status` },
    { label: '指令应答', direction: '上行（设备发布）', topic: `${base}/command/reply` },
    { label: '指令下发', direction: '下行（设备订阅）', topic: `${base}/command/down` },
  ]
}
