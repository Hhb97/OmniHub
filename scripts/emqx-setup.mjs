// Configures an EXISTING EMQX 5.x so that device authentication and topic ACLs are decided by this platform
// (HTTP authn / authz pointing at the backend). Idempotent-ish: if a source already exists EMQX answers 4xx and
// the script reports it; remove the old entry in the dashboard and re-run.
//
// NOT verified against a real EMQX in this repository's test run. EMQX 4.x uses a different mechanism
// (emqx_auth_http plugin), see docs/deployment.md.
//
// Env (required): EMQX_DASHBOARD_USER, EMQX_DASHBOARD_PASSWORD, IOT_MQTT_HTTP_AUTH_TOKEN
// Env (optional): EMQX_API (default http://localhost:18083), IOT_BACKEND_URL (URL EMQX uses to reach the backend,
//                 default http://127.0.0.1:8080)
const EMQX = process.env.EMQX_API ?? 'http://localhost:18083'
const BACKEND = process.env.IOT_BACKEND_URL ?? 'http://127.0.0.1:8080'
const { EMQX_DASHBOARD_USER: USER, EMQX_DASHBOARD_PASSWORD: PASSWORD, IOT_MQTT_HTTP_AUTH_TOKEN: TOKEN } = process.env
if (!USER || !PASSWORD || !TOKEN) {
  console.error('EMQX_DASHBOARD_USER, EMQX_DASHBOARD_PASSWORD and IOT_MQTT_HTTP_AUTH_TOKEN are required')
  process.exit(1)
}

async function api(method, path, body, token) {
  const res = await fetch(EMQX + '/api/v5' + path, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  })
  const text = await res.text()
  return { status: res.status, body: text ? JSON.parse(text) : null }
}

const login = await api('POST', '/login', { username: USER, password: PASSWORD })
if (login.status !== 200) {
  console.error('EMQX login failed:', login.status)
  process.exit(1)
}
const token = login.body.token
const headers = { 'content-type': 'application/json', 'x-broker-token': TOKEN }

const authn = await api('POST', '/authentication', {
  mechanism: 'password_based',
  backend: 'http',
  enable: true,
  method: 'post',
  url: `${BACKEND}/api/mqtt/auth`,
  headers,
  body: { clientid: '${clientid}', username: '${username}', password: '${password}' },
}, token)
console.log('authentication:', authn.status, authn.status >= 300 ? JSON.stringify(authn.body) : 'ok')

const authz = await api('POST', '/authorization/sources', {
  type: 'http',
  enable: true,
  method: 'post',
  url: `${BACKEND}/api/mqtt/acl`,
  headers,
  body: { clientid: '${clientid}', username: '${username}', topic: '${topic}', action: '${action}' },
}, token)
console.log('authorization source:', authz.status, authz.status >= 300 ? JSON.stringify(authz.body) : 'ok')

const settings = await api('PUT', '/authorization/settings', { no_match: 'deny', deny_action: 'ignore' }, token)
console.log('authorization settings (no_match=deny):', settings.status, settings.status >= 300 ? JSON.stringify(settings.body) : 'ok')
