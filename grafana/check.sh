#!/usr/bin/env bash
# Checks the transit-grafana image: it starts, has every dashboard of grafana/dashboards and the data sources they use,
# and lets nobody in without a login. Needs Docker; used by CI.
#
#   docker build -t transit-grafana grafana && grafana/check.sh transit-grafana
set -euo pipefail
cd "$(dirname "$0")"
fail() { echo "$*" >&2; docker logs --tail 30 "$name" >&2 2> /dev/null || true; exit 1; }
name=transit-grafana-check
port=${PORT:-13000}
docker rm -f $name > /dev/null 2>&1 || true
trap 'docker rm -f -v $name > /dev/null 2>&1' EXIT
docker run -d --name $name --memory 1g --cpus "${CPUS:-1}" -p "127.0.0.1:$port:3000" -e GF_SECURITY_ADMIN_PASSWORD=check-only \
  -e GRAFANA_DB_PASSWORD=none "$1" > /dev/null
url=http://127.0.0.1:$port/grafana
for _ in $(seq 60); do curl -fsS "$url/api/health" > /dev/null 2>&1 && break; sleep 2; done
curl -fsS "$url/api/health" > /dev/null || fail "It doesn't start"
[ "$(curl -s -o /dev/null -w '%{http_code}' "$url/api/search")" = 401 ] || fail "It answers without a login"
[ "$(curl -s -o /dev/null -w '%{http_code}' -u admin:admin "$url/api/search")" = 401 ] || fail "admin/admin logs in"
[ "$(curl -s -o /dev/null -w '%{http_code}' "$url/metrics")" != 200 ] || fail "Its metrics are open"
get() { curl -fsS -u admin:check-only "$url/api/$1"; }
sleep 5
python3 - "$(get 'search?type=dash-db')" "$(get datasources)" <<'P' || fail "The dashboards aren't right"
import glob, json, sys
shown = {d['uid'] for d in json.loads(sys.argv[1])}
sources = {d['uid'] for d in json.loads(sys.argv[2])}
def used(node):
    if isinstance(node, dict):
        source = node.get('datasource')
        if isinstance(source, dict) and source.get('uid'):
            yield source['uid']
        for value in node.values():
            yield from used(value)
    elif isinstance(node, list):
        for value in node:
            yield from used(value)
problems, seen = [], {}
for file in sorted(glob.glob('dashboards/*.json')):
    d = json.load(open(file))
    if d.get('uid') in seen: problems.append(f"{file}: same uid as {seen[d['uid']]}")
    seen[d.get('uid')] = file
    if d.get('uid') not in shown: problems.append(f'{file}: not loaded')
    for uid in sorted(set(used(d)) - sources):
        if not uid.startswith(('-- ', '$', 'grafana')): problems.append(f'{file}: no data source {uid}')
print(len(seen), 'dashboards,', len(sources), 'data sources', *problems, sep='\n' if problems else ' ')
sys.exit(bool(problems) or not seen)
P
for uid in transit-postgres tempo; do
  type=$(get "datasources/uid/$uid" | python3 -c 'import json,sys; print(json.load(sys.stdin)["type"])')
  get "plugins/$type/settings" > /dev/null || fail "No plugin for $uid ($type)"
done
echo "transit-grafana is fine"
