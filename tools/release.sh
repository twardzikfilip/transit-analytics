#!/usr/bin/env bash
# Raises the version of what is released (used by .github/workflows/release.yml), and says so in release.out
# ("app=1.2.0", "recorder=1.0.1").
#
#   tools/release.sh auto|app|recorder|grafana|both|all patch|minor|major
set -euo pipefail
cd "$(dirname "$0")/.."
fail() { echo "$*" >&2; exit 1; }
what=${1:-} bump=${2:-}
[[ $what =~ ^(auto|app|recorder|grafana|both|all)$ && $bump =~ ^(patch|minor|major)$ ]] || fail "Usage: auto|app|recorder|grafana|both|all patch|minor|major"

# Whether any of the paths changed since the last release with this tag prefix (or there was none).
changed() {
  local last
  last=$(git describe --tags --abbrev=0 --match "$1-v*" 2> /dev/null) || return 0
  shift
  ! git diff --quiet "$last" HEAD -- "$@"
}
next() {
  local major minor patch
  IFS=. read -r major minor patch <<< "$1"
  case $bump in
    major) echo "$((major + 1)).0.0" ;;
    minor) echo "$major.$((minor + 1)).0" ;;
    patch) echo "$major.$minor.$((patch + 1))" ;;
  esac
}

app=no recorder=no grafana=no
case $what in
  app) app=yes ;;
  recorder) recorder=yes ;;
  grafana) grafana=yes ;;
  both) app=yes recorder=yes ;;
  all) app=yes recorder=yes grafana=yes ;;
  auto)
    # What the images are built from; not documents, tests or the version files themselves.
    skip=(':!**/src/test/**' ':!**/*.spec.ts' ':!**/*.md' ':!frontend/package.json' ':!frontend/package-lock.json' ':!backend/recorder/VERSION')
    ! changed recorder backend/recorder backend/zbiorkom-feed backend/build.gradle.kts "${skip[@]}" || recorder=yes
    # The home's own (dashboards.yaml) and the helper scripts aren't in the image.
    ! changed grafana grafana ':!grafana/VERSION' ':!grafana/*.sh' ':!grafana/provisioning/dashboards.yaml' "${skip[@]}" || grafana=yes
    if changed app backend frontend docker-compose.prod.yml "${skip[@]}" ':!backend/recorder' \
        || changed app frontend/package.json frontend/package-lock.json; then
      app=yes
    fi ;;
esac
: > release.out
if [ $app = yes ]; then
  version=$(next "$(cut -d' ' -f1 VERSION)"); tools/version.sh app "$version" > /dev/null; echo "app=$version" >> release.out
fi
if [ $recorder = yes ]; then
  version=$(next "$(cut -d' ' -f1 backend/recorder/VERSION)"); tools/version.sh recorder "$version" > /dev/null
  echo "recorder=$version" >> release.out
fi
if [ $grafana = yes ]; then
  version=$(next "$(cut -d' ' -f1 grafana/VERSION)")
  # The very first release is the version in the file.
  git tag --list 'grafana-v*' | grep -q . || version=$(cut -d' ' -f1 grafana/VERSION)
  tools/version.sh grafana "$version" > /dev/null; echo "grafana=$version" >> release.out
fi
[ -s release.out ] || fail "Nothing changed since the last releases"
cat release.out
