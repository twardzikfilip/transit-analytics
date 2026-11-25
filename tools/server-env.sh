#!/usr/bin/env bash
# Makes the server's .env (for docker-compose.prod.yml): random secrets, and the passwords of admin and guest as typed.
#
#   tools/server-env.sh transit.example.org
set -euo pipefail
cd "$(dirname "$0")/.."
fail() { echo "$*" >&2; exit 1; }

[[ "${1:-}" =~ ^[a-z0-9.-]+\.[a-z]+$ ]] || fail "Usage: tools/server-env.sh <domain name>"
[ ! -e .env ] || fail ".env is there already; its secrets are in use, so it isn't replaced"

ask() {  # user: its password, typed twice
  local first second
  while true; do
    read -rsp "Password for $1 (at least 10 letters, digits, . _ -): " first; echo >&2
    [[ $first =~ ^[A-Za-z0-9._-]{10,72}$ ]] || { echo "Not that one." >&2; continue; }
    read -rsp "Again: " second; echo >&2
    [ "$first" = "$second" ] && break
    echo "Not the same." >&2
  done
  echo "$first"
}
admin=$(ask admin)
guest=$(ask guest)

umask 077
{
  echo "SITE_ADDRESS=$1"
  echo "APP_VERSION=$(cut -d' ' -f1 VERSION)"
  echo "RECORDER_VERSION=$(cut -d' ' -f1 backend/recorder/VERSION)"
  echo "GRAFANA_VERSION=$(cut -d' ' -f1 grafana/VERSION)"
  for name in DB_PASSWORD RECORDER_DB_PASSWORD GRAFANA_DB_PASSWORD GRAFANA_ADMIN_PASSWORD JWT_SECRET; do
    echo "$name=$(openssl rand -hex 32)"
  done
  echo "ADMIN_PASSWORD=$admin"
  echo "GUEST_PASSWORD=$guest"
  echo "RECORDER_UID=$(id -u)"
  echo "RECORDER_GID=$(id -g)"
} > .env
echo "Made .env (readable by you only)."
