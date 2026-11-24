#!/usr/bin/env bash
# Starts transit-recorder in Docker, or upgrades the one running, without a gap in the recording: the new version
# starts next to the old one (on port 8082), and the old one stops only once the new one is recording. While both
# run, both poll; a report stored twice is kept once (position_sample is unique per vehicle and report time), so the
# overlap is harmless. A recorder running outside Docker (./gradlew :recorder:bootRun or java -jar) is the "old one"
# too: it is stopped the same way, which frees port 8081 for the container.
#
#   backend/recorder/upgrade.sh          (from anywhere in the repository; needs Docker access)
#   backend/recorder/upgrade.sh 1.0.1    on the server: that released version, from the registry (docker-compose.prod.yml;
#                                        then also kept as RECORDER_VERSION in .env)
set -euo pipefail
cd "$(dirname "$0")/../.."          # repository root, where docker-compose.yml is

health() { curl -fs "http://localhost:$1/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; }
wait_healthy() {                    # port, seconds
  for _ in $(seq 1 $(($2 / 2))); do health "$1" && return 0; sleep 2; done
  return 1
}

# The container runs as you (docker-compose.yml): create the data folders now, or Docker creates them owned by root.
mkdir -p backend/data/raw backend/data/spool
for dir in backend/data/raw backend/data/spool; do
  if [ "$(stat -c %u "$dir")" != "$(id -u)" ]; then
    echo "$dir isn't yours (Docker created it?); fix it with: sudo chown -R $(id -u):$(id -g) $dir" >&2
    exit 1
  fi
done

if [ $# -gt 0 ]; then
  export COMPOSE_FILE=docker-compose.prod.yml RECORDER_VERSION=$1
  grep -qs "^APP_VERSION=" .env || { echo "No .env of a server here: this isn't the server (README, \"Hosting\")." >&2; exit 1; }
  echo "Getting version $1…"
  docker compose pull -q recorder
else
  # On the server, building here would replace the released recorder with docker-compose.yml's.
  ! grep -qs "^APP_VERSION=" .env || { echo "This is the server (.env): give a released version, e.g. upgrade.sh 1.0.1" >&2; exit 1; }
  echo "Building the new version…"
  (cd backend && ./gradlew -q :recorder:bootJar)
  docker compose build -q recorder
fi

echo "Starting it next to the running one (port 8082)…"
docker rm -f transit-recorder-next > /dev/null 2>&1 || true
RECORDER_UID=$(id -u) RECORDER_GID=$(id -g) \
  docker compose run -d --no-deps --name transit-recorder-next -p 127.0.0.1:8082:8081 recorder > /dev/null
trap 'docker rm -f transit-recorder-next > /dev/null 2>&1 || true' EXIT
if ! wait_healthy 8082 60; then
  echo "The new version isn't healthy after 60 s; the old one keeps recording." >&2
  docker logs --tail 50 transit-recorder-next >&2
  exit 1
fi
# Make sure the new one has stored a poll of its own before the old one stops.
sleep 12
health 8082 || { echo "The new version stopped recording; the old one keeps recording." >&2; exit 1; }
echo "It records."

# A recorder outside Docker holds port 8081: stop it (gracefully: a poll under way finishes).
local_pids=$(pgrep -f 'transit-recorder\.jar|:recorder:bootRun|RecorderApplication' || true)
if [ -n "$local_pids" ]; then
  echo "Stopping the recorder running outside Docker (pid $local_pids)…"
  kill $local_pids
  for _ in $(seq 1 20); do ss -ltn | grep -q '127.0.0.1:8081 \|\*:8081 \|:::8081 ' || break; sleep 1; done
fi

echo "Replacing the old container…"
RECORDER_UID=$(id -u) RECORDER_GID=$(id -g) docker compose up -d --no-deps --force-recreate recorder
if ! wait_healthy 8081 60; then
  echo "The recorder on 8081 isn't healthy after 60 s; the temporary one on 8082 keeps recording." >&2
  trap - EXIT
  docker logs --tail 50 transit-recorder >&2
  exit 1
fi
[ $# -eq 0 ] || sed -i "s/^RECORDER_VERSION=.*/RECORDER_VERSION=$1/" .env
echo "Done: transit-recorder runs the new version on port 8081 (the temporary one is removed)."
