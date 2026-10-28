#!/bin/sh
# Creates .env from .env.example on the Docker host: every CHANGE_ME becomes a random value, except the admin
# password, which must be supplied (OMNIHUB_ADMIN_PASSWORD, at least 8 characters) so that nobody has to read it
# out of a file or a log. Does nothing when .env already exists. Secrets are never printed.
set -eu
cd "$(dirname "$0")/.."
if [ -f .env ]; then echo ".env already exists, keeping it"; exit 0; fi
: "${OMNIHUB_ADMIN_PASSWORD:?set OMNIHUB_ADMIN_PASSWORD (min 8 characters) in the environment}"
[ "${#OMNIHUB_ADMIN_PASSWORD}" -ge 8 ] || { echo "admin password must be at least 8 characters"; exit 1; }

rand() { head -c 48 /dev/urandom | base64 | tr -d '/+=\n' | head -c "${1:-32}"; }
cp .env.example .env
chmod 600 .env
for key in POSTGRES_PASSWORD INFLUX_PASSWORD EMQX_DASHBOARD_PASSWORD GRAFANA_ADMIN_PASSWORD IOT_MQTT_PASSWORD IOT_MQTT_HTTP_AUTH_TOKEN IOT_METRICS_TOKEN; do
  sed -i "s|^${key}=.*|${key}=$(rand 28)|" .env
done
sed -i "s|^IOT_JWT_SECRET=.*|IOT_JWT_SECRET=$(rand 48)|" .env
# the admin password may contain characters that are special for sed: write it with awk instead
awk -v pw="$OMNIHUB_ADMIN_PASSWORD" 'BEGIN{FS=OFS="="} $1=="IOT_ADMIN_PASSWORD"{print $1,pw; next} {print}' .env > .env.tmp && mv .env.tmp .env
chmod 600 .env
echo ".env created (permissions 600). Secrets are stored only in $(pwd)/.env"
