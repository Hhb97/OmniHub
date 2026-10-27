// Standalone MQTT device simulator. Everything goes through the real MQTT broker and the real platform
// pipeline: authentication, status (last will), property reports, events, commands and replies.
//
// Usage: node src/simulator.js [config.json]
// Config can be overridden with env vars: IOT_BROKER_URL, IOT_PRODUCT_KEY, IOT_DEVICE_KEY, IOT_DEVICE_SECRET
// Interactive commands on stdin: "offline" (drop connection), "online" (reconnect), "spike" (temperature spike), "quit".
import fs from 'node:fs'
import readline from 'node:readline'
import { fileURLToPath } from 'node:url'
import mqtt from 'mqtt'

const configPath = process.argv[2] ?? fileURLToPath(new URL('../config.local.json', import.meta.url))
let fileCfg = {}
if (fs.existsSync(configPath)) {
  fileCfg = JSON.parse(fs.readFileSync(configPath, 'utf8'))
} else {
  console.warn(`[sim] config file ${configPath} not found, relying on environment variables`)
}
const cfg = {
  brokerUrl: process.env.IOT_BROKER_URL ?? fileCfg.brokerUrl ?? 'mqtt://localhost:1883',
  productKey: process.env.IOT_PRODUCT_KEY ?? fileCfg.productKey,
  deviceKey: process.env.IOT_DEVICE_KEY ?? fileCfg.deviceKey,
  deviceSecret: process.env.IOT_DEVICE_SECRET ?? fileCfg.deviceSecret,
  reportIntervalSeconds: fileCfg.reportIntervalSeconds ?? 5,
  commandExecutionDelayMs: fileCfg.commandExecutionDelayMs ?? 800,
  initial: { temperature: 24, humidity: 55, voltage: 220, power: 1200, switch: true, targetTemp: 22, ...fileCfg.initial },
  spike: { everySeconds: 0, temperature: 85, durationReports: 3, ...fileCfg.spike },
  offlineCycle: { afterSeconds: 0, durationSeconds: 0, ...fileCfg.offlineCycle },
}
if (!cfg.productKey || !cfg.deviceKey || !cfg.deviceSecret) {
  console.error('[sim] productKey, deviceKey and deviceSecret are required (config file or IOT_* env vars)')
  process.exit(1)
}

const clientId = `${cfg.productKey}.${cfg.deviceKey}`
const base = `iot/${cfg.productKey}/${cfg.deviceKey}`
const T = {
  property: `${base}/property/post`,
  event: `${base}/event/post`,
  status: `${base}/status`,
  reply: `${base}/command/reply`,
  down: `${base}/command/down`,
}
const log = (...a) => console.log(new Date().toISOString(), '[sim]', ...a)

// ---------------------------------------------------------------- device state
const state = { ...cfg.initial }
let spikeLeft = 0
let msgSeq = 0
const nextId = () => `${Date.now()}-${++msgSeq}`
const round = (n, d = 2) => Math.round(n * 10 ** d) / 10 ** d
const walk = (v, step, min, max) => Math.min(max, Math.max(min, v + (Math.random() - 0.5) * step))

function tick() {
  // temperature drifts towards the target while the switch is on, otherwise towards ambient (28)
  const goal = state.switch ? state.targetTemp : 28
  state.temperature = round(walk(state.temperature + (goal - state.temperature) * 0.1, 1.2, -10, 120))
  state.humidity = round(walk(state.humidity, 3, 10, 95))
  state.voltage = round(walk(state.voltage, 4, 200, 240))
  state.power = state.switch ? round(walk(state.power, 80, 800, 1800)) : 0
  if (spikeLeft > 0) {
    state.temperature = cfg.spike.temperature
    spikeLeft--
  }
}

// ---------------------------------------------------------------- mqtt
let client
let manuallyOffline = false

function connect() {
  log(`connecting to ${cfg.brokerUrl} as ${clientId}`)
  client = mqtt.connect(cfg.brokerUrl, {
    clientId,
    username: clientId,
    password: cfg.deviceSecret,
    clean: true,
    keepalive: 30,
    reconnectPeriod: 3000,
    connectTimeout: 10000,
    will: { topic: T.status, payload: JSON.stringify({ state: 'offline' }), qos: 1, retain: false },
  })
  client.on('connect', () => {
    log('connected (authenticated by the platform)')
    client.subscribe(T.down, { qos: 1 }, (err) => (err ? log('subscribe failed', err.message) : log(`subscribed ${T.down}`)))
    publish(T.status, { state: 'online' })
    report()
  })
  client.on('reconnect', () => log('reconnecting...'))
  client.on('close', () => log('connection closed'))
  client.on('offline', () => log('client offline'))
  client.on('error', (e) => log('error:', e.message))
  client.on('message', (topic, payload) => onMessage(topic, payload.toString()))
}

function publish(topic, body) {
  if (!client?.connected) return
  const payload = JSON.stringify(body)
  client.publish(topic, payload, { qos: 1 }, (err) => {
    if (err) log('publish failed', topic, err.message)
  })
  log('->', topic.replace(base + '/', ''), payload)
}

function report(only) {
  const keys = only?.length ? only : Object.keys(state)
  const params = {}
  for (const k of keys) if (k in state) params[k] = state[k]
  publish(T.property, { id: nextId(), ts: Date.now(), params })
}

function sendEvent(event, params) {
  publish(T.event, { id: nextId(), ts: Date.now(), event, params })
}

// ---------------------------------------------------------------- commands
const reply = (cmdId, status, message, data) => publish(T.reply, { cmdId, status, message, data })

function onMessage(topic, text) {
  log('<-', topic.replace(base + '/', ''), text)
  let cmd
  try {
    cmd = JSON.parse(text)
  } catch {
    log('ignoring malformed command')
    return
  }
  if (!cmd.cmdId) return
  reply(cmd.cmdId, 'RECEIVED', 'command accepted by device')
  setTimeout(() => execute(cmd), cfg.commandExecutionDelayMs)
}

function execute(cmd) {
  try {
    if (cmd.type === 'PROPERTY_SET') {
      if (!(cmd.identifier in state) || !['switch', 'targetTemp'].includes(cmd.identifier)) {
        return reply(cmd.cmdId, 'FAILED', `property ${cmd.identifier} is not writable on this device`)
      }
      state[cmd.identifier] = cmd.params.value
      reply(cmd.cmdId, 'SUCCESS', 'property set', { [cmd.identifier]: state[cmd.identifier] })
      return report([cmd.identifier])
    }
    if (cmd.type === 'PROPERTY_GET') {
      const ids = cmd.params?.identifiers ?? []
      const data = Object.fromEntries(ids.filter((k) => k in state).map((k) => [k, state[k]]))
      reply(cmd.cmdId, 'SUCCESS', 'property values', data)
      return report(ids)
    }
    if (cmd.type === 'SERVICE') {
      switch (cmd.identifier) {
        case 'turnOn':
          state.switch = true
          reply(cmd.cmdId, 'SUCCESS', 'switch is on', { switch: true })
          return report(['switch'])
        case 'turnOff':
          state.switch = false
          reply(cmd.cmdId, 'SUCCESS', 'switch is off', { switch: false })
          return report(['switch'])
        case 'setTargetTemp':
          state.targetTemp = cmd.params.target
          reply(cmd.cmdId, 'SUCCESS', `target temperature set to ${state.targetTemp}`, { targetTemp: state.targetTemp })
          return report(['targetTemp'])
        case 'reboot':
          reply(cmd.cmdId, 'SUCCESS', 'rebooting, back in 5s')
          return goOffline(5)
        case 'selfTestFail':
          return reply(cmd.cmdId, 'FAILED', 'self test failed: sensor bus error')
        default:
          return reply(cmd.cmdId, 'FAILED', `unknown service ${cmd.identifier}`)
      }
    }
    reply(cmd.cmdId, 'FAILED', `unsupported command type ${cmd.type}`)
  } catch (e) {
    reply(cmd.cmdId, 'FAILED', `execution error: ${e.message}`)
  }
}

// ---------------------------------------------------------------- lifecycle
function goOffline(seconds) {
  log(`going offline${seconds ? ` for ${seconds}s` : ''}`)
  manuallyOffline = true
  // proper DISCONNECT: the broker reports a clean disconnect
  client.end(false, {}, () => {
    if (seconds) setTimeout(goOnline, seconds * 1000)
  })
}

function goOnline() {
  if (!manuallyOffline) return
  manuallyOffline = false
  connect()
}

connect()

setInterval(() => {
  if (manuallyOffline || !client?.connected) return
  tick()
  report()
  if (state.temperature > 80) sendEvent('overheat', { temperature: state.temperature })
}, cfg.reportIntervalSeconds * 1000)

if (cfg.spike.everySeconds > 0) {
  setInterval(() => (spikeLeft = cfg.spike.durationReports), cfg.spike.everySeconds * 1000)
}
if (cfg.offlineCycle.afterSeconds > 0) {
  setInterval(() => !manuallyOffline && goOffline(cfg.offlineCycle.durationSeconds), cfg.offlineCycle.afterSeconds * 1000)
}

const rl = readline.createInterface({ input: process.stdin })
rl.on('line', (line) => {
  const c = line.trim()
  if (c === 'offline') goOffline(0)
  else if (c === 'online') goOnline()
  else if (c === 'spike') spikeLeft = cfg.spike.durationReports
  else if (c === 'quit') shutdown()
})

function shutdown() {
  log('shutting down')
  if (client?.connected) {
    client.publish(T.status, JSON.stringify({ state: 'offline' }), { qos: 1 }, () => client.end(false, {}, () => process.exit(0)))
  } else {
    process.exit(0)
  }
}
process.on('SIGINT', shutdown)
process.on('SIGTERM', shutdown)
