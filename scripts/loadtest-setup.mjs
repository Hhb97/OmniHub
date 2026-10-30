// Creates the product, saves its thing model and batch-creates devices through the platform API, then writes the
// device file that the load test runner reads (same format as the "batch create devices" dialog export).
//
//   node scripts/loadtest-setup.mjs --base http://<host>:8088 --broker mqtt://<host>:1883 --count 10000
//
// The admin password is asked for on the terminal (hidden) or taken from OMNIHUB_PASSWORD; it is never written
// anywhere. Device secrets are shown by the API only once, so every chunk is appended to <out>.partial as soon as it
// has been created. Needs Node 18+.
import fs from 'node:fs'
import path from 'node:path'

const args = Object.fromEntries(process.argv.slice(2).reduce((acc, a, i, all) => {
  if (a.startsWith('--')) acc.push([a.slice(2), all[i + 1] && !all[i + 1].startsWith('--') ? all[i + 1] : 'true'])
  return acc
}, []))
const base = (args.base || process.env.OMNIHUB_URL || 'http://localhost:8088').replace(/\/$/, '')
const user = args.user || 'admin'
const count = parseInt(args.count || '10000', 10)
const prefix = args.prefix || 'LT'
const productKey = args['product-key'] || 'loadtest-sensor'
const productName = args['product-name'] || 'LoadTest Sensor'
const modelFile = args.model || 'backend/core/src/test/resources/loadtest/thing-model.json'
const broker = args.broker || ''
const out = args.out || `backend/devices-${count}.local.json`
const chunk = Math.max(1, parseInt(args.chunk || '500', 10))
const append = args.append === 'true'

if (!broker) fail('--broker is required, e.g. --broker mqtt://192.168.1.10:1883 (it is written into the device file)')
if (!Number.isInteger(count) || count < 1) fail('--count must be a positive integer')

function fail(msg) {
  console.error('ERROR: ' + msg)
  process.exit(1)
}

function askHidden(prompt) {
  return new Promise((resolve, reject) => {
    if (!process.stdin.isTTY) return reject(new Error('no terminal: set OMNIHUB_PASSWORD instead'))
    process.stdout.write(prompt)
    let buf = ''
    process.stdin.setRawMode(true)
    process.stdin.resume()
    process.stdin.setEncoding('utf8')
    const onData = (ch) => {
      for (const c of ch) {
        if (c === '\r' || c === '\n') {
          process.stdin.setRawMode(false)
          process.stdin.pause()
          process.stdin.off('data', onData)
          process.stdout.write('\n')
          return resolve(buf)
        }
        if (c === '\u0003') process.exit(130)
        if (c === '\u007f' || c === '\b') buf = buf.slice(0, -1)
        else buf += c
      }
    }
    process.stdin.on('data', onData)
  })
}

async function call(method, url, body, token, timeoutMs = 120000) {
  const res = await fetch(base + url, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(timeoutMs),
  })
  const text = await res.text()
  let json
  try { json = JSON.parse(text) } catch { throw new Error(`${method} ${url}: HTTP ${res.status}, not JSON: ${text.slice(0, 200)}`) }
  if (!res.ok || json.code !== 0) throw new Error(`${method} ${url}: HTTP ${res.status} ${json.message || ''} ${JSON.stringify(json.data || '')}`.trim())
  return json.data
}

// a failed API call ends the script with one readable line instead of a stack trace
const must = (p) => p.catch((e) => fail(e.message))

const password = process.env.OMNIHUB_PASSWORD || await askHidden(`password of "${user}": `)
console.log(`login ${base} as ${user} ...`)
const token = (await must(call('POST', '/api/auth/login', { username: user, password }))).token

// product: reuse when the key exists
const found = await must(call('GET', `/api/products?keyword=${encodeURIComponent(productKey)}&size=100`, undefined, token))
let product = found.records.find((p) => p.productKey === productKey)
if (product) {
  console.log(`product "${product.name}" (${productKey}) exists, id ${product.id}, ${product.deviceCount} devices`)
  if (product.deviceCount > 0 && !append) {
    fail(`the product already has ${product.deviceCount} devices. Use another --product-key, or --append to add more `
      + '(device names restart from the prefix, so use a different --prefix too)')
  }
} else {
  product = await must(call('POST', '/api/products', { name: productName, productKey, category: 'LoadTest', protocol: 'MQTT',
    status: 'ENABLED', description: 'Created by scripts/loadtest-setup.mjs' }, token))
  console.log(`product created: ${product.name} (${productKey}), id ${product.id}`)
}

const model = JSON.parse(fs.readFileSync(modelFile, 'utf8'))
const saved = await must(call('PUT', `/api/products/${product.id}/thing-model`, model, token))
console.log(`thing model saved from ${modelFile}: version ${saved.version}, ${saved.properties.length} properties, `
  + `${saved.events.length} events, ${saved.services.length} services`)

fs.mkdirSync(path.dirname(path.resolve(out)), { recursive: true })
const partial = out + '.partial'
fs.writeFileSync(partial, '')
const all = []
const started = Date.now()
const chunks = Math.ceil(count / chunk)
try {
  for (let i = 0; i < chunks; i++) {
    const n = Math.min(chunk, count - i * chunk)
    // each request numbers its devices from 1, so with several chunks the chunk number goes into the name prefix
    const pre = chunks === 1 ? prefix : `${prefix}-${String(i + 1).padStart(String(chunks).length, '0')}`
    const t0 = Date.now()
    const devs = await call('POST', '/api/devices/batch', { productId: product.id, namePrefix: pre, count: n,
      description: 'load test' }, token, 600000)
    const rows = devs.map((d) => ({ name: d.name, brokerUrl: broker, productKey: d.productKey, deviceKey: d.deviceKey,
      deviceSecret: d.secret, clientId: d.clientId, username: d.clientId, password: d.secret }))
    fs.appendFileSync(partial, rows.map((r) => JSON.stringify(r)).join('\n') + '\n')
    all.push(...rows)
    const secs = (Date.now() - t0) / 1000
    const eta = ((Date.now() - started) / (i + 1)) * (chunks - i - 1) / 1000
    console.log(`chunk ${i + 1}/${chunks}: ${n} devices in ${secs.toFixed(1)}s, total ${all.length}/${count}`
      + (i + 1 < chunks ? `, about ${Math.ceil(eta)}s left` : ''))
  }
} catch (e) {
  console.error(`\nFAILED after ${all.length} devices: ${e.message}`)
  console.error(`The credentials of the ${all.length} devices created so far are in ${partial} (one JSON object per line).`)
  console.error('A request that timed out may still have created devices on the server without us seeing their secrets:')
  console.error('check the product in the web UI and delete the surplus devices before running again.')
  process.exit(1)
}

fs.writeFileSync(out, JSON.stringify(all, null, 2))
fs.unlinkSync(partial)
const keys = new Set(all.map((d) => d.clientId))
console.log(`\nDone: ${all.length} devices (${keys.size} distinct client ids) written to ${path.resolve(out)}`)
console.log('The file contains the device secrets: keep it out of git (*.local.json is ignored) and do not share it.')
