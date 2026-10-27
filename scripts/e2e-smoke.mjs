// End-to-end smoke test against a RUNNING platform with the simulator connected (see README "验证").
// It talks only to the public REST API: nothing is written to the database directly.
//
// Required env: IOT_ADMIN_PASSWORD. Optional: IOT_API (default http://localhost:8080), IOT_ADMIN_USER,
// IOT_PRODUCT_KEY (demo-sensor), IOT_DEVICE_KEY (sim-001)
const API = process.env.IOT_API ?? 'http://localhost:8080'
const USER = process.env.IOT_ADMIN_USER ?? 'admin'
const PASSWORD = process.env.IOT_ADMIN_PASSWORD
const DEVICE_KEY = process.env.IOT_DEVICE_KEY ?? 'sim-001'
if (!PASSWORD) {
  console.error('IOT_ADMIN_PASSWORD is required')
  process.exit(1)
}

let failures = 0
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
function check(name, ok, detail = '') {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`)
  if (!ok) failures++
}
async function call(method, path, body, token) {
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

// ---- auth
const bad = await call('POST', '/auth/login', { username: USER, password: 'definitely-wrong' })
check('login with wrong password is rejected (401)', bad.status === 401)
const anon = await call('GET', '/devices')
check('protected endpoint without token is 401', anon.status === 401)
const login = await call('POST', '/auth/login', { username: USER, password: PASSWORD })
check('admin login', login.status === 200 && !!login.data?.token)
const token = login.data.token
const me = await call('GET', '/auth/me', null, token)
check('/auth/me returns permissions', me.data?.permissions?.includes('device:control'))

// ---- device pipeline
const list = await call('GET', `/devices?keyword=${DEVICE_KEY}`, null, token)
const dev = list.data?.records?.[0]
check('demo device exists', !!dev)
check('device list never exposes the secret', !JSON.stringify(list.body).toLowerCase().includes('secret'))
const online = await waitFor(async () => {
  const d = (await call('GET', `/devices/${dev.id}`, null, token)).data
  return d.onlineStatus === 'ONLINE' ? d : null
})
check('device is ONLINE after simulator connected via MQTT', !!online, online ? `lastOnlineAt=${online.lastOnlineAt}` : 'simulator running?')
const latest = await waitFor(async () => {
  const l = (await call('GET', `/devices/${dev.id}/latest`, null, token)).data
  return l?.length >= 4 ? l : null
})
check('latest values stored from MQTT reports', !!latest, latest?.map((v) => `${v.identifier}=${v.value}`).join(' '))
await sleep(11000)
const trend = await call('GET', `/devices/${dev.id}/trend?identifiers=temperature,humidity&interval=RAW`, null, token)
const pts = trend.data?.series?.[0]?.points?.length ?? 0
check('history/trend has multiple points', pts >= 2, `${pts} points`)
const hist = await call('GET', `/devices/${dev.id}/history?identifiers=temperature&size=5`, null, token)
check('history paging works', hist.data?.records?.length > 0 && hist.data.total >= hist.data.records.length)
const tooLong = await call('GET', `/devices/${dev.id}/history?identifiers=temperature&start=2020-01-01T00:00:00Z&end=2026-01-01T00:00:00Z`, null, token)
check('unbounded history query is refused (400)', tooLong.status === 400)
const msgs = await call('GET', `/devices/${dev.id}/messages?size=5`, null, token)
check('communication log recorded', msgs.data?.total > 0)

// ---- control
async function sendAndAwait(req, expected) {
  const sent = await call('POST', `/devices/${dev.id}/commands`, req, token)
  if (sent.status !== 200) return { sent }
  const final = await waitFor(async () => {
    const c = (await call('GET', `/commands/${sent.data.id}`, null, token)).data
    return ['SUCCESS', 'FAILED', 'TIMEOUT'].includes(c.status) ? c : null
  }, 15000)
  return { sent, final }
}
let r = await sendAndAwait({ type: 'SERVICE', identifier: 'turnOff', params: {} })
check('service turnOff -> SUCCESS reported by the device', r.final?.status === 'SUCCESS', `${r.sent.data?.status} -> ${r.final?.status} (${r.final?.message})`)
check('command went through RECEIVED', !!r.final?.receivedAt)
r = await sendAndAwait({ type: 'SERVICE', identifier: 'setTargetTemp', params: { target: 26.5 } })
check('service setTargetTemp(26.5) -> SUCCESS', r.final?.status === 'SUCCESS', r.final?.message)
r = await sendAndAwait({ type: 'PROPERTY_SET', identifier: 'switch', params: { value: true } })
check('property set switch=true -> SUCCESS', r.final?.status === 'SUCCESS')
r = await sendAndAwait({ type: 'SERVICE', identifier: 'selfTestFail', params: {} })
check('device-side failure is reported as FAILED', r.final?.status === 'FAILED', r.final?.message)
r = await sendAndAwait({ type: 'PROPERTY_GET', params: { identifiers: ['temperature'] } })
check('property get -> SUCCESS with data', r.final?.status === 'SUCCESS' && r.final?.result?.temperature !== undefined)
let bad1 = await call('POST', `/devices/${dev.id}/commands`, { type: 'SERVICE', identifier: 'setTargetTemp', params: { target: 99 } }, token)
check('out-of-range parameter rejected by thing model (400)', bad1.status === 400, bad1.body.message)
bad1 = await call('POST', `/devices/${dev.id}/commands`, { type: 'PROPERTY_SET', identifier: 'temperature', params: { value: 1 } }, token)
check('writing a read-only property rejected (400)', bad1.status === 400, bad1.body.message)
bad1 = await call('POST', `/devices/${dev.id}/commands`, { type: 'SERVICE', identifier: 'noSuchService', params: {} }, token)
check('unknown service rejected (400)', bad1.status === 400)

// ---- dashboard
const ov = await call('GET', '/dashboard/overview', null, token)
check('dashboard overview', ov.status === 200 && ov.data.deviceCount >= 1 && ov.data.onlineCount >= 1, JSON.stringify(ov.data))
const rt = await call('GET', '/dashboard/report-trend?hours=24', null, token)
check('report trend has 24 buckets and some data', rt.data?.length === 24 && rt.data.some((p) => p.count > 0))
const as = await call('GET', '/dashboard/alarm-stats?days=7', null, token)
check('alarm stats', as.status === 200 && as.data.byDay.length === 7)

// ---- permissions
const vname = 'viewer_' + Date.now().toString(36)
const roles = (await call('GET', '/system/roles', null, token)).data
const viewerRole = roles.find((x) => x.code === 'VIEWER')
const vpw = 'V' + Math.random().toString(36).slice(2) + '!9aZ'
const created = await call('POST', '/system/users', { username: vname, password: vpw, nickname: 'tmp viewer', roleIds: [viewerRole.id] }, token)
check('admin can create a viewer user', created.status === 200)
const vlogin = await call('POST', '/auth/login', { username: vname, password: vpw })
const vt = vlogin.data?.token
check('viewer can read devices', (await call('GET', '/devices', null, vt)).status === 200)
check('viewer cannot send commands (403)', (await call('POST', `/devices/${dev.id}/commands`, { type: 'SERVICE', identifier: 'turnOn', params: {} }, vt)).status === 403)
check('viewer cannot delete devices (403)', (await call('DELETE', `/devices/${dev.id}`, null, vt)).status === 403)
check('viewer cannot rotate secrets (403)', (await call('POST', `/devices/${dev.id}/rotate-secret`, null, vt)).status === 403)
check('viewer cannot list users (403)', (await call('GET', '/system/users', null, vt)).status === 403)
await call('DELETE', `/system/users/${created.data.id}`, null, token)

console.log(failures === 0 ? '\nALL CHECKS PASSED' : `\n${failures} CHECK(S) FAILED`)
process.exit(failures === 0 ? 0 : 1)
