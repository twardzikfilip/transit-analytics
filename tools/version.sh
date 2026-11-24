#!/usr/bin/env bash
# The two versions: the app's (backend and frontend together, in VERSION and frontend/package.json) and
# transit-recorder's (in backend/recorder/VERSION). Semantic versions: patch for a fix, minor for a feature, major for
# a change that needs something done by hand. Each is kept with its release date: the day it was set. A change in backend/zbiorkom-feed is in both apps.
#
#   tools/version.sh                     shows both
#   tools/version.sh app 1.2.0           sets the app's, released today
#   tools/version.sh recorder 1.0.1      sets the recorder's, released today
#   tools/version.sh check [tag]         fails unless the files agree (and the tag, e.g. app-v1.2.0, is the version)
set -euo pipefail
cd "$(dirname "$0")/.."

app() { cut -d' ' -f1 VERSION; }
recorder() { cut -d' ' -f1 backend/recorder/VERSION; }
released() { cut -d' ' -f2 "$1"; }
valid() { [[ $1 =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]; }
fail() { echo "$*" >&2; exit 1; }

case "${1:-show}" in
  show)
    echo "app      $(app), released $(released VERSION)"
    echo "recorder $(recorder), released $(released backend/recorder/VERSION)" ;;
  app | recorder)
    valid "${2:-}" || fail "Not a version: '${2:-}' (expected e.g. 1.2.0)"
    today=$(TZ=Europe/Warsaw date +%F)
    if [ "$1" = app ]; then
      echo "$2 $today" > VERSION
      (cd frontend && npm version --no-git-tag-version --allow-same-version "$2" > /dev/null && npm pkg set "released=$today")
    else
      echo "$2 $today" > backend/recorder/VERSION
    fi
    echo "$1 is now $2" ;;
  check)
    valid "$(app)" || fail "VERSION isn't a version: $(app)"
    valid "$(recorder)" || fail "backend/recorder/VERSION isn't a version: $(recorder)"
    for file in VERSION backend/recorder/VERSION; do
      [[ $(released "$file") =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || fail "$file has no release date (e.g. 1.2.0 2026-11-24)"
    done
    in_file=$(sed -n 's/^  "released": "\(.*\)",$/\1/p' frontend/package.json)
    [ "$in_file" = "$(released VERSION)" ] || fail "frontend/package.json was released $in_file, VERSION $(released VERSION)"
    for file in package.json package-lock.json; do
      in_file=$(sed -n 's/^  "version": "\(.*\)",$/\1/p' "frontend/$file" | head -1)
      [ "$in_file" = "$(app)" ] || fail "frontend/$file says $in_file, VERSION says $(app): run tools/version.sh app $(app)"
    done
    case "${2:-}" in
      "" | "app-v$(app)" | "recorder-v$(recorder)") ;;
      *) fail "Tag $2 is neither app-v$(app) nor recorder-v$(recorder)" ;;
    esac
    echo "Versions agree: app $(app), recorder $(recorder)" ;;
  *) fail "Usage: tools/version.sh [app|recorder <version> | check [tag]]" ;;
esac
