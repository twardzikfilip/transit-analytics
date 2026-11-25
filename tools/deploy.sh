#!/usr/bin/env bash
# On the server: puts a released version live. GitHub's deploy key may run only this (docs/server-setup.md), with
# "app 1.2.0" or "recorder 1.0.1" or "grafana 1.0.1" as its command. Older versions too: that is the way back.
#
#   tools/deploy.sh app 1.2.0
#   tools/deploy.sh recorder 1.0.1
#   tools/deploy.sh grafana 1.0.1
#
# App: backs the database up (the new version may migrate it), starts the new backend and frontend, and goes back to
# the version before when they aren't healthy. The database and the recorder aren't touched.
# Grafana: the same without a backup; its dashboards are in its image. Tempo starts with it.
# Recorder: backend/recorder/upgrade.sh, which leaves no gap.
set -euo pipefail

KEEP_BACKUPS=3
MIN_FREE_GB=6
compose() { docker compose -f docker-compose.prod.yml "$@"; }
fail() { echo "$*" >&2; exit 1; }
set_version() { sed -i "s/^$1=.*/$1=$2/" .env; }

healthy() {  # waits for the backend, then for the site through Caddy
  local i
  for i in $(seq 100); do
    [ "$(docker inspect transit-backend --format '{{.State.Health.Status}}' 2> /dev/null)" = healthy ] && break
    sleep 3
  done
  [ "$i" -lt 100 ] || return 1
  for i in $(seq 10); do
    curl -fsS -m 10 -o /dev/null --resolve "$SITE:443:127.0.0.1" "https://$SITE/" 2> /dev/null && return 0
    sleep 3
  done
  return 1
}

backup() {
  local dir=~/backups
  mkdir -p "$dir" && chmod 700 "$dir"
  echo "Backing up the database…"
  docker exec transit-postgres pg_dump -U transit -Fc -Z 1 transit > "$dir/.part" 2> "$dir/last.log" \
    || fail "The backup failed; nothing was changed"
  mv "$dir/.part" "$dir/before-app-$1-$(date -u +%Y%m%dT%H%M%SZ).dump"
  # shellcheck disable=SC2012
  ls -1t "$dir"/before-app-*.dump | tail -n +$((KEEP_BACKUPS + 1)) | xargs -r rm --
}

deploy_app() {
  local version=$1 before
  before=$(sed -n 's/^APP_VERSION=//p' .env)
  SITE=$(sed -n 's/^SITE_ADDRESS=//p' .env)
  echo "App $before → $version"
  APP_VERSION=$version compose pull -q backend frontend || fail "No images of $version; nothing was changed"
  [ "$(df --output=avail -BG / | tail -1 | tr -dc 0-9)" -ge $MIN_FREE_GB ] || fail "Less than $MIN_FREE_GB GB free; nothing was changed"
  backup "$version"
  set_version APP_VERSION "$version"
  if compose up -d --no-deps backend frontend > /dev/null 2>&1 && healthy; then
    echo "App $version is live."
    return
  fi
  # Not printed: GitHub's logs of a public repository are public.
  docker logs --tail 200 transit-backend > ~/backups/failed-app.log 2>&1 || true
  echo "App $version isn't healthy; its log is in ~/backups/failed-app.log on the server" >&2
  [ "$before" != "$version" ] || fail "It was this version already; left as it is"
  echo "Going back to $before…" >&2
  set_version APP_VERSION "$before"
  compose up -d --no-deps backend frontend > /dev/null 2>&1 || true
  healthy && fail "Back on $before."
  fail "$before isn't healthy either: look at the server"
}

grafana_healthy() {
  local i
  for i in $(seq 60); do
    [ "$(docker inspect transit-grafana --format '{{.State.Health.Status}}' 2> /dev/null)" = healthy ] && return 0
    sleep 3
  done
  return 1
}

deploy_grafana() {
  local version=$1 before
  before=$(sed -n 's/^GRAFANA_VERSION=//p' .env)
  echo "Grafana $before → $version"
  GRAFANA_VERSION=$version compose pull -q grafana tempo || fail "No image of $version; nothing was changed"
  mkdir -p ~/backups && chmod 700 ~/backups
  set_version GRAFANA_VERSION "$version"
  # Tempo again too: its settings are a file.
  if compose up -d --no-deps --force-recreate grafana tempo > /dev/null 2>&1 && grafana_healthy; then
    echo "Grafana $version is live."
    return
  fi
  docker logs --tail 200 transit-grafana > ~/backups/failed-grafana.log 2>&1 || true
  echo "Grafana $version isn't healthy; its log is in ~/backups/failed-grafana.log on the server" >&2
  [ "$before" != "$version" ] || fail "It was this version already; left as it is"
  echo "Going back to $before…" >&2
  set_version GRAFANA_VERSION "$before"
  compose up -d --no-deps grafana > /dev/null 2>&1 || true
  grafana_healthy && fail "Back on $before."
  fail "$before isn't healthy either: look at the server"
}

main() {
  local what=${1:-} version=${2:-}
  [[ $what =~ ^(app|recorder|grafana)$ && $version =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "Usage: app|recorder|grafana <version>"
  cd ~/transit-analytics
  exec 9> ~/.transit-deploy.lock
  flock -n 9 || fail "Another deployment is running"
  # The release's compose file and scripts; not an older release's, when going back.
  git fetch -q --tags --force origin
  git rev-parse -q --verify "refs/tags/$what-v$version" > /dev/null || fail "No release $what-v$version"
  if git merge-base --is-ancestor HEAD "$what-v$version"; then
    git checkout -q --detach "$what-v$version"
  fi
  if [ "$what" = app ]; then
    deploy_app "$version"
  elif [ "$what" = grafana ]; then
    deploy_grafana "$version"
  else
    mkdir -p ~/backups && chmod 700 ~/backups
    backend/recorder/upgrade.sh "$version" > ~/backups/recorder-upgrade.log 2>&1 \
      || fail "The recorder's upgrade failed: see ~/backups/recorder-upgrade.log on the server"
    echo "Recorder $version records."
  fi
  docker image prune -af --filter "until=720h" > /dev/null || true
}

# From GitHub, the command asked for is in SSH_ORIGINAL_COMMAND; read whole before running, as a checkout replaces this file.
if [ -n "${SSH_ORIGINAL_COMMAND:-}" ]; then
  read -r what version rest <<< "$SSH_ORIGINAL_COMMAND"
  [ -z "${rest:-}" ] || fail "Usage: app|recorder|grafana <version>"
  main "$what" "${version:-}"; exit
else
  main "$@"; exit
fi
