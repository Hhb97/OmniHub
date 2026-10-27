// Creates the demo product / thing model / device / alarm rules through the platform's REST API and writes
// simulator/config.local.json (git-ignored, contains the device secret). Safe to re-run: an existing device
// gets a new secret via rotate-secret.
//
// Required env: IOT_ADMIN_PASSWORD. Optional: IOT_API (default http://localhost:8080), IOT_ADMIN_USER (admin),
// IOT_BROKER_URL (mqtt://localhost:1883), IOT_PRODUCT_KEY (demo-sensor), IOT_DEVICE_KEY (sim-001)
import fs from 'node:fs'
import { fileURLToPath } from 'node:url'

const API = process.env.IOT_API ?? 'http://localhost:8080'
const USER = process.env.IOT_ADMIN_USER ?? 'admin'
const PASSWORD = process.env.IOT_ADMIN_PASSWORD
const PRODUCT_KEY = process.env.IOT_PRODUCT_KEY ?? 'demo-sensor'
const DEVICE_KEY = process.env.IOT_DEVICE_KEY ?? 'sim-001'
const BROKER = process.env.IOT_BROKER_URL ?? 'mqtt://localhost:1883'
if (!PASSWORD) {
  console.error('IOT_ADMIN_PASSWORD is required')
  process.exit(1)
}

let token = ''
async function call(method, path, body, okStatuses = [200]) {
  const res = await fetch(API + '/api' + path, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  })
  const json = await res.json().catch(() => ({}))
  if (!okStatuses.includes(res.status)) throw new Error(`${method} ${path} -> ${res.status} ${json.message ?? ''}`)
  return { status: res.status, ...json }
}

const num = (identifier, name, unit, min, max, rw = 'R') => ({
  identifier, name, dataType: 'FLOAT', unit, description: name, accessMode: rw, spec: { min, max, step: 0.01 },
})

const thingModel = {
  properties: [
    num('temperature', '温度', '℃', -40, 125),
    num('humidity', '湿度', '%', 0, 100),
    num('voltage', '电压', 'V', 0, 500),
    num('power', '功率', 'W', 0, 10000),
    { identifier: 'switch', name: '开关状态', dataType: 'BOOL', unit: '', description: '设备开关', accessMode: 'RW', spec: {} },
    num('targetTemp', '目标温度', '℃', 10, 40, 'RW'),
  ],
  events: [
    { identifier: 'overheat', name: '设备过热', level: 'WARNING', description: '温度超过 80℃', outputs: [{ identifier: 'temperature', name: '温度', dataType: 'FLOAT' }] },
    { identifier: 'deviceFault', name: '设备故障', level: 'ERROR', description: '设备自检失败', outputs: [] },
  ],
  services: [
    { identifier: 'turnOn', name: '打开开关', description: '', inputs: [], outputs: [] },
    { identifier: 'turnOff', name: '关闭开关', description: '', inputs: [], outputs: [] },
    {
      identifier: 'setTargetTemp', name: '设置目标温度', description: '',
      inputs: [{ identifier: 'target', name: '目标温度', dataType: 'FLOAT', spec: { min: 10, max: 40 } }], outputs: [],
    },
    { identifier: 'reboot', name: '重启设备', description: '设备断开连接 5 秒后重连', inputs: [], outputs: [] },
    { identifier: 'selfTestFail', name: '模拟自检失败', description: '演示执行失败', inputs: [], outputs: [] },
  ],
}

const login = await call('POST', '/auth/login', { username: USER, password: PASSWORD })
token = login.data.token
console.log('logged in as', USER)

let productId
const created = await call('POST', '/products', {
  name: '演示温湿度传感器', productKey: PRODUCT_KEY, category: '环境监测', protocol: 'MQTT', status: 'ENABLED',
  description: '由 simulator/provision.js 创建的演示产品',
}, [200, 409])
if (created.status === 200) {
  productId = created.data.id
  console.log('product created', productId)
} else {
  const list = await call('GET', `/products?keyword=${PRODUCT_KEY}&size=100`)
  productId = list.data.records.find((p) => p.productKey === PRODUCT_KEY).id
  console.log('product already exists', productId)
}
await call('PUT', `/products/${productId}/thing-model`, thingModel)
console.log('thing model saved')

let secret, deviceId
const dev = await call('POST', '/devices', { name: '模拟设备 001', productId, deviceKey: DEVICE_KEY, description: '模拟器' }, [200, 409])
if (dev.status === 200) {
  secret = dev.data.secret
  deviceId = dev.data.id
  console.log('device created', deviceId)
} else {
  const list = await call('GET', `/devices?keyword=${DEVICE_KEY}&productId=${productId}&size=100`)
  deviceId = list.data.records.find((d) => d.deviceKey === DEVICE_KEY).id
  secret = (await call('POST', `/devices/${deviceId}/rotate-secret`)).data.secret
  console.log('device exists, secret rotated', deviceId)
}

const rules = await call('GET', '/alarm-rules?size=100')
const have = new Set(rules.data.records.map((r) => r.name))
const wanted = [
  { name: '温度过高', scope: 'PRODUCT', productId, ruleType: 'THRESHOLD', identifier: 'temperature', operator: 'GT', threshold: 60, triggerCount: 2, silenceSeconds: 0, level: 'CRITICAL', enabled: true, description: '连续两次温度高于 60℃' },
  { name: '设备离线', scope: 'PRODUCT', productId, ruleType: 'OFFLINE', silenceSeconds: 0, level: 'MAJOR', enabled: true, description: '设备离线' },
  { name: '设备过热事件', scope: 'PRODUCT', productId, ruleType: 'EVENT', identifier: 'overheat', silenceSeconds: 0, level: 'WARNING', enabled: true, description: '收到过热事件' },
]
for (const r of wanted) {
  if (!have.has(r.name)) {
    await call('POST', '/alarm-rules', r)
    console.log('alarm rule created:', r.name)
  }
}

const out = fileURLToPath(new URL('../config.local.json', import.meta.url))
fs.writeFileSync(out, JSON.stringify({
  brokerUrl: BROKER, productKey: PRODUCT_KEY, deviceKey: DEVICE_KEY, deviceSecret: secret,
  reportIntervalSeconds: 5, commandExecutionDelayMs: 800,
  spike: { everySeconds: 0, temperature: 85, durationReports: 3 },
  offlineCycle: { afterSeconds: 0, durationSeconds: 0 },
}, null, 2))
console.log(`wrote ${out} (contains the device secret, git-ignored)`)
