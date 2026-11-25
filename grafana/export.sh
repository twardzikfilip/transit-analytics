#!/usr/bin/env bash
# Takes the dashboards of the folder "Transit Analytics" as they are in Grafana at home into grafana/dashboards, after
# changing or making them there (README, "Grafana"). Then look at `git diff`, and commit.
#
#   grafana/export.sh [http://localhost:3000]
set -euo pipefail
cd "$(dirname "$0")/dashboards"
python3 - "${1:-http://localhost:3000}" <<'P'
import glob, json, re, sys, urllib.parse, urllib.request
base = sys.argv[1].rstrip('/')
get = lambda path: json.load(urllib.request.urlopen(f'{base}/api/{path}', timeout=20))
files = {json.load(open(f))['uid']: f for f in glob.glob('*.json')}
found = set()
for hit in get('search?type=dash-db&limit=1000'):
    if hit.get('folderTitle') != 'Transit Analytics':
        continue
    d = get('dashboards/uid/' + urllib.parse.quote(hit['uid']))['dashboard']
    d.pop('id', None)
    found.add(d['uid'])
    file = files.get(d['uid']) or re.sub('[^a-z0-9]+', '-', d['title'].lower()).strip('-') + '.json'
    old = json.load(open(file)) if d['uid'] in files else {}
    if {**old, 'version': 0, 'id': 0} == {**d, 'version': 0, 'id': 0}:
        continue
    with open(file, 'w') as out:
        json.dump(d, out, indent=2, ensure_ascii=False)
        out.write('\n')
    print('Written:', file)
for uid in files.keys() - found:
    print('Not in Grafana (left as it is):', files[uid])
P
