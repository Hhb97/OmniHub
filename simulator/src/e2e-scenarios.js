// Scenario tests for the harder paths: broker authentication / ACL, invalid and duplicate data, alarm
// raise / dedupe / recover, offline detection (transport vs business), command timeout, disable and credential
// rotation. Uses a raw MQTT client acting as a device, plus the public REST API. Takes about 90 seconds.
//
// Required env: IOT_ADMIN_PASSWORD, and the demo product must exist (run provision.js first).
// Optional: IOT_API (http://localhost:8080), IOT_BROKER_URL (mqtt://localhost:1883), IOT_PRODUCT_KEY (demo-sensor)
import mqtt from 'mqtt'

const API = process.env.IOT_API ?? 'http://localhost:8080'
const BROKER = process.env.IOT_BROKER_URL ?? 'mqtt://localhost:1883'
const PK = process.env.IOT_PRODUCT_KEY ?? 'demo-sensor'
const PASSWORD = process.env.IOT_ADMIN_PASSWORD
if (!PASSWORD) {
  console.error('IOT_ADMIN_PASSWORD is required')
  process.exit(1)
}

let failures = 0
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const check = (name, ok, detail = '') => {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`)
  if (!ok) failures++
}
let token
async function call(method, path, body) {
  const res = await fetch(API + '/api' + path, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  })
  const json = await res.json().catch(() => ({}))
  return { status: res.status, body: json, data: json.data }
}
async function waitFor(fn, timeoutMs = 20000, stepMs = 500) {
  const end = Date.now() + timeoutMs
  while (Date.now() < end) {
    const v = await fn()
    if (v) return v
    await sleep(stepMs)
  }
  return null
}

function deviceClient(deviceKey, secret, opts = {}) {
  const clientId = `${PK}.${deviceKey}`
  const base = `iot/${PK}/${deviceKey}`
  return new Promise((resolve, reject) => {
    const c = mqtt.connect(BROKER, {
      clientId, username: clientId, password: secret, reconnectPeriod: 0, connectTimeout: 5000, clean: true,
      will: { topic: `${base}/status`, payload: JSON.stringify({ state: 'offline' }), qos: 1 }, ...opts,
    })
    const api = {
      c, base,
      pub: (kind, body) => new Promise((res) => c.publish(`${base}/${kind}`, JSON.stringify(body), { qos: 1 }, () => res())),
      prop: (params, id) => api.pub('property/post', { id: id ?? `${Date.now()}-${Math.random()}`, ts: Date.now(), params }),
      end: (force = false) => new Promise((res) => c.end(force, {}, res)),
    }
    c.once('connect', () => resolve(api))
    c.once('error', (e) => { c.end(true); reject(e) })
  })
}

const login = await call('POST', '/auth/login', { username: 'admin', password: PASSWORD })
token = login.data.token
const products = (await call('GET', `/products?keyword=${PK}&size=50`)).data.records
const product = products.find((p) => p.productKey === PK)
if (!product) throw new Error('demo product missing, run provision.js first')

const key = 'scn-' + Date.now().toString(36)
const created = await call('POST', '/devices', { name: 'scenario device', productId: product.id, deviceKey: key })
const dev = created.data
let secret = dev.secret
check('scenario device created, secret returned once', !!secret && dev.onlineStatus === 'INACTIVE')
check('new device is INACTIVE (never connected)', (await call('GET', `/devices/${dev.id}`)).data.onlineStatus === 'INACTIVE')
const getDev = async () => (await call('GET', `/devices/${dev.id}`)).data
const alarmsOf = async (type) => (await call('GET', `/alarms?deviceId=${dev.id}&size=50`)).data.records.filter((a) => !type || a.ruleType === type)

// ---- broker authentication
let rejected = false
try { await deviceClient(key, 'wrong-secret') } catch { rejected = true }
check('MQTT connect with wrong secret is rejected', rejected)
rejected = false
try { await deviceClient(key, secret, { username: 'someone.else' }) } catch { rejected = true }
check('MQTT connect with mismatching username is rejected', rejected)

let d = await deviceClient(key, secret)
check('MQTT connect with correct credentials succeeds', d.c.connected)
await d.pub('status', { state: 'online' })
check('status message makes device ONLINE', !!(await waitFor(async () => (await getDev()).onlineStatus === 'ONLINE')))

// ---- ACL: a device may not publish to another device's topics
const other = (await call('GET', '/devices?keyword=sim-001')).data.records[0]
if (other) {
  const before = (await call('GET', `/devices/${other.id}/messages?size=1`)).data.total
  // fire and forget: the broker may never acknowledge (or may drop the connection on) an unauthorized publish
  d.c.on('error', () => {})
  d.c.publish(`iot/${PK}/sim-001/property/post`, JSON.stringify({ id: 'evil', params: { temperature: 1 } }), { qos: 0 })
  await sleep(1500)
  const evil = (await call('GET', `/devices/${other.id}/history?identifiers=temperature&size=100`)).data.records.some((r) => r.value === 1)
  check("publishing to another device's topic has no effect (ACL)", !evil)
  if (!d.c.connected) {
    console.log('      (broker dropped the connection after the unauthorized publish; reconnecting)')
    d = await deviceClient(key, secret)
    await d.pub('status', { state: 'online' })
  }
}

// ---- invalid + duplicate data
await d.prop({ temperature: 25.5, humidity: 50 }, 'dup-1')
await d.prop({ temperature: 25.5, humidity: 50 }, 'dup-1') // same message id again
await d.prop({ temperature: 'hot' })
await d.prop({ temperature: 999 })
await d.prop({ nonexistent: 1 })
await new Promise((res) => d.c.publish(`${d.base}/property/post`, '{not json', { qos: 1 }, () => res()))
await sleep(2000)
const hist = (await call('GET', `/devices/${dev.id}/history?identifiers=temperature&size=50`)).data
check('duplicate message id stored once', hist.records.filter((r) => r.value === 25.5).length === 1)
check('invalid / out-of-range / unknown values are not stored', hist.records.every((r) => r.value === 25.5))
const msgs = (await call('GET', `/devices/${dev.id}/messages?size=50&direction=UP`)).data.records
check('rejected messages are visible in the communication log as INVALID', msgs.filter((m) => m.result === 'INVALID').length >= 4)

// ---- threshold alarm: rule needs 2 consecutive violations (>60)
await d.prop({ temperature: 90 })
await sleep(800)
check('one violation does not raise an alarm yet', (await alarmsOf('THRESHOLD')).length === 0)
await d.prop({ temperature: 91 })
const raised = await waitFor(async () => (await alarmsOf('THRESHOLD'))[0])
check('2 consecutive violations raise a THRESHOLD alarm', !!raised && raised.status === 'ACTIVE', raised?.message)
for (const t of [92, 93, 94]) await d.prop({ temperature: t })
await sleep(1500)
const afterMore = await alarmsOf('THRESHOLD')
check('continued violation does not create duplicate alarms', afterMore.length === 1 && afterMore[0].triggerCount >= 4, `count=${afterMore.length} triggers=${afterMore[0]?.triggerCount}`)
await d.prop({ temperature: 30 })
const recovered = await waitFor(async () => (await alarmsOf('THRESHOLD'))[0]?.status === 'RECOVERED')
check('value back to normal recovers the alarm', !!recovered)
const actions = (await call('GET', `/alarms/${raised.id}/actions`)).data.map((a) => a.action)
check('alarm history records TRIGGER and RECOVER', actions.includes('TRIGGER') && actions.includes('RECOVER'), actions.join(','))
const ack = await call('POST', `/alarms/${raised.id}/ack`, { remark: 'checked on site' })
check('alarm can be acknowledged', ack.status === 200 && ack.data.acknowledged === true && ack.data.acknowledgedBy === 'admin')
await d.prop({ temperature: 95 })
await d.prop({ temperature: 96 })
await waitFor(async () => (await alarmsOf('THRESHOLD')).length === 2)
check('after recovery a new violation raises a NEW alarm', (await alarmsOf('THRESHOLD')).length === 2)

// ---- event alarm
await d.pub('event/post', { id: 'ev-1', ts: Date.now(), event: 'overheat', params: { temperature: 95 } })
const evAlarm = await waitFor(async () => (await alarmsOf('EVENT'))[0])
check('device event raises an EVENT alarm', !!evAlarm)
const evAck = await call('POST', `/alarms/${evAlarm.id}/ack`, { remark: 'handled' })
check('acknowledging an EVENT alarm closes it', evAck.data?.status === 'RECOVERED')
await d.pub('event/post', { id: 'ev-2', event: 'notInThingModel', params: {} })

// ---- offline detection: clean disconnect
await d.end()
const off = await waitFor(async () => {
  const x = await getDev()
  return x.onlineStatus === 'OFFLINE' && x.mqttConnected === false ? x : null
})
check('clean disconnect -> OFFLINE, mqttConnected=false, lastOfflineAt set', !!off && !!off.lastOfflineAt)
check('offline alarm raised', !!(await waitFor(async () => (await alarmsOf('OFFLINE'))[0]?.status === 'ACTIVE')))
const cmdOffline = await call('POST', `/devices/${dev.id}/commands`, { type: 'SERVICE', identifier: 'turnOn', params: {} })
check('command to an offline device returns 409 with a clear message', cmdOffline.status === 409 && /离线/.test(cmdOffline.body.message), cmdOffline.body.message)

// ---- abrupt connection loss -> last will
d = await deviceClient(key, secret)
check('reconnect makes the device ONLINE again', !!(await waitFor(async () => (await getDev()).onlineStatus === 'ONLINE')))
check('offline alarm recovers when the device comes back', !!(await waitFor(async () => (await alarmsOf('OFFLINE'))[0]?.status === 'RECOVERED')))
d.c.stream.destroy() // drop the TCP connection without DISCONNECT
check('abrupt connection loss -> OFFLINE', !!(await waitFor(async () => (await getDev()).onlineStatus === 'OFFLINE')))

// ---- silent device: transport connected but no messages => business status OFFLINE
await call('PUT', '/system/configs', { key: 'device.offline.timeout.seconds', value: '10' })
d = await deviceClient(key, secret)
await d.prop({ humidity: 40 })
check('heartbeat message keeps device ONLINE', !!(await waitFor(async () => (await getDev()).onlineStatus === 'ONLINE')))
const silent = await waitFor(async () => {
  const x = await getDev()
  return x.onlineStatus === 'OFFLINE' && x.mqttConnected === true ? x : null
}, 45000, 1000)
check('connected but silent device becomes business-OFFLINE while MQTT is still connected', !!silent)
await d.prop({ humidity: 41 })
check('a new message brings it back ONLINE', !!(await waitFor(async () => (await getDev()).onlineStatus === 'ONLINE')))
await call('PUT', '/system/configs', { key: 'device.offline.timeout.seconds', value: '90' })

// ---- command timeout (device never answers)
await call('PUT', '/system/configs', { key: 'command.timeout.seconds', value: '5' })
d.c.subscribe(`${d.base}/command/down`)
await sleep(500)
const sent = await call('POST', `/devices/${dev.id}/commands`, { type: 'SERVICE', identifier: 'turnOn', params: {} })
check('command is SENT after publish', sent.status === 200 && sent.data.status === 'SENT')
const timedOut = await waitFor(async () => {
  const c = (await call('GET', `/commands/${sent.data.id}`)).data
  return c.status === 'TIMEOUT' ? c : null
}, 20000)
check('command without device reply ends as TIMEOUT', !!timedOut, timedOut?.message)
await call('PUT', '/system/configs', { key: 'command.timeout.seconds', value: '30' })

// ---- credential rotation and disabling
const rot = await call('POST', `/devices/${dev.id}/rotate-secret`)
const newSecret = rot.data.secret
check('rotate-secret returns a new secret', !!newSecret && newSecret !== secret)
await sleep(1000)
check('rotation drops the existing connection', !d.c.connected)
rejected = false
try { await deviceClient(key, secret) } catch { rejected = true }
check('old secret no longer authenticates', rejected)
secret = newSecret
d = await deviceClient(key, secret)
check('new secret authenticates', d.c.connected)
await call('PUT', `/devices/${dev.id}/enabled`, { enabled: false })
await sleep(1000)
check('disabling kicks the device and marks it OFFLINE', !d.c.connected && (await getDev()).onlineStatus === 'OFFLINE')
rejected = false
try { await deviceClient(key, secret) } catch { rejected = true }
check('disabled device cannot connect', rejected)
const cmdDisabled = await call('POST', `/devices/${dev.id}/commands`, { type: 'SERVICE', identifier: 'turnOn', params: {} })
check('command to a disabled device is refused (409)', cmdDisabled.status === 409)

// ---- cleanup + product deletion protection
const delProduct = await call('DELETE', `/products/${product.id}`)
check('deleting a product that still has devices is refused (409)', delProduct.status === 409, delProduct.body.message)
await call('DELETE', `/devices/${dev.id}`)
check('device can be deleted', (await call('GET', `/devices/${dev.id}`)).status === 404)

console.log(failures === 0 ? '\nALL SCENARIOS PASSED' : `\n${failures} CHECK(S) FAILED`)
process.exit(failures === 0 ? 0 : 1)
