#!/bin/sh
# Points EMQX's device authentication and topic ACL at the platform backend (HTTP authn / authz).
# Run on the Docker host after `docker compose up -d`, from the repository directory (it reads .env).
# Safe to run again: existing sources are detected and left alone.
#
# NOTE: written against the EMQX 5.8 REST API but not yet run against a real EMQX. On a non-2xx answer the script
# prints EMQX's response, which names the offending field.
set -eu
cd "$(dirname "$0")/.."
[ -f .env ] || { echo ".env not found (copy .env.example first)"; exit 1; }
# shellcheck disable=SC1091
. ./.env
: "${EMQX_DASHBOARD_PASSWORD:?missing in .env}"
: "${IOT_MQTT_HTTP_AUTH_TOKEN:?missing in .env}"

API="http://127.0.0.1:${EMQX_DASHBOARD_PORT:-18083}/api/v5"
BACKEND="http://backend:8080"      # how EMQX (inside the compose network) reaches the backend

echo "waiting for EMQX ..."
i=0
until curl -sf -o /dev/null "$API/status"; do
  i=$((i + 1)); [ "$i" -gt 60 ] && { echo "EMQX did not come up"; exit 1; }
  sleep 2
done

TOKEN=$(curl -sf -X POST "$API/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"${EMQX_DASHBOARD_PASSWORD}\"}" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
[ -n "$TOKEN" ] || { echo "EMQX dashboard login failed (is EMQX_DASHBOARD_PASSWORD the password EMQX was started with?)"; exit 1; }
AUTH="Authorization: Bearer $TOKEN"

call() { # method path body -> prints status and body on failure
  code=$(curl -s -o /tmp/emqx-resp.$$ -w '%{http_code}' -X "$1" "$API$2" -H "$AUTH" -H 'Content-Type: application/json' -d "$3")
  case "$code" in 2*) echo "  $1 $2 -> $code" ;; *) echo "  $1 $2 -> $code"; cat /tmp/emqx-resp.$$; echo; rm -f /tmp/emqx-resp.$$; return 1 ;; esac
  rm -f /tmp/emqx-resp.$$
}

HEADERS="{\"content-type\":\"application/json\",\"x-broker-token\":\"${IOT_MQTT_HTTP_AUTH_TOKEN}\"}"

echo "authentication (HTTP):"
if curl -sf "$API/authentication" -H "$AUTH" | grep -q '"backend":"http"'; then
  echo "  already configured"
else
  call POST /authentication "$(printf '{"mechanism":"password_based","backend":"http","enable":true,"method":"post","url":"%s/api/mqtt/auth","headers":%s,"body":{"clientid":"${clientid}","username":"${username}","password":"${password}"},"pool_size":32,"connect_timeout":"5s","request_timeout":"10s"}' "$BACKEND" "$HEADERS")"
fi

echo "authorization (HTTP):"
if curl -sf "$API/authorization/sources" -H "$AUTH" | grep -q '"type":"http"'; then
  echo "  already configured"
else
  call POST /authorization/sources "$(printf '{"type":"http","enable":true,"method":"post","url":"%s/api/mqtt/acl","headers":%s,"body":{"clientid":"${clientid}","username":"${username}","topic":"${topic}","action":"${action}"},"pool_size":32,"connect_timeout":"5s","request_timeout":"10s"}' "$BACKEND" "$HEADERS")"
fi

echo "authorization settings (deny when no source matches, cache results):"
call PUT /authorization/settings '{"no_match":"deny","deny_action":"ignore","cache":{"enable":true,"max_size":32,"ttl":"10m"}}' || true
echo "metrics endpoint for Prometheus:"
code=$(curl -s -o /dev/null -w '%{http_code}' "$API/prometheus/stats" || true)
if [ "$code" = "200" ]; then
  echo "  /prometheus/stats answers without credentials, nothing to do"
elif [ -n "${EMQX_API_KEY:-}" ]; then
  echo "  using the EMQX API key already stored in .env"
else
  echo "  /prometheus/stats answered $code: creating an API key for Prometheus"
  resp=$(curl -s -X POST "$API/api_key" -H "$AUTH" -H 'Content-Type: application/json' \
    -d '{"name":"prometheus","enable":true,"desc":"Prometheus scrape (created by emqx-bootstrap.sh)"}') || true
  key=$(printf '%s' "$resp" | sed -n 's/.*"api_key":"\([^"]*\)".*/\1/p')
  secret=$(printf '%s' "$resp" | sed -n 's/.*"api_secret":"\([^"]*\)".*/\1/p')
  if [ -n "$key" ] && [ -n "$secret" ]; then
    sed -i "s|^EMQX_API_KEY=.*|EMQX_API_KEY=${key}|; s|^EMQX_API_SECRET=.*|EMQX_API_SECRET=${secret}|" .env
    echo "  stored in .env, restarting prometheus"
    docker compose up -d prometheus
  else
    echo "  could not create the key: $resp"
  fi
fi
echo "done."
