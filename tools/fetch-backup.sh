#!/usr/bin/env bash
# On the home computer: fetches the server's database once a night (docs/server-setup.md, "Nightly backups").
# Started every hour by a timer; does nothing until a backup is due (one a day, from 03:30), so it is retried after a
# failure and made up for after the computer was off. Settings in ~/.config/transit-backup.env: SERVER=user@address.
#
#   fetch-backup.sh          when due
#   fetch-backup.sh --now    now
set -uo pipefail
DAILY=7 WEEKLY=4 MIN_FREE_GB=8
dir=${BACKUP_DIR:-$HOME/backups/server}
. "${BACKUP_SETTINGS:-$HOME/.config/transit-backup.env}"
key=$HOME/.ssh/transit-backup known=$HOME/.ssh/transit-backup.known_hosts
mkdir -p "$dir" && chmod 700 "$dir" && cd "$dir" || exit 1
exec 9> .lock; flock -n 9 || exit 0

# Tells the desktop, at most once a day.
tell() {
  echo "$1" >&2
  [ "$(cat .told 2> /dev/null)" != "$(date +%F)" ] || return 0
  notify-send -u critical "Transit Analytics backup" "$1" 2> /dev/null && date +%F > .told
}
newest() { ls -1 transit-*.dump 2> /dev/null | tail -1; }
failed() {
  rm -f .part
  local last; last=$(newest)
  if [ -z "$last" ] || [ $(($(date +%s) - $(stat -c %Y "$last"))) -gt $((2 * 86400)) ]; then
    tell "$1. No backup for over two days; the last: ${last:-none}"
  else
    tell "$1. Tried again every hour; the last: $last"
  fi
  exit 1
}

last=$(newest)
due=$(date -d '03:30' +%s); [ "$(date +%s)" -ge "$due" ] || due=$(date -d 'yesterday 03:30' +%s)
if [ "${1:-}" != --now ] && [ -n "$last" ] && [ "$(stat -c %Y "$last")" -ge "$due" ]; then exit 0; fi

free=$(df --output=avail -BG . | tail -1 | tr -dc 0-9)
[ "$free" -ge $MIN_FREE_GB ] || failed "Only $free GB free on this computer: no backup made"
(umask 077; timeout 1800 ssh -i "$key" -o IdentitiesOnly=yes -o IdentityAgent=none -o BatchMode=yes \
  -o StrictHostKeyChecking=yes -o UserKnownHostsFile="$known" -o ConnectTimeout=20 -o ServerAliveInterval=20 \
  "$SERVER" dump < /dev/null > .part 2> last.log) || failed "The server's database couldn't be fetched"
# Reads all of it, so a cut-off or damaged dump is found now. Nothing is restored.
docker exec -i transit-postgres nice -n 19 pg_restore -f /dev/null < .part 2> last.log || failed "The backup can't be read"
size=$(stat -c %s .part)
[ -z "$last" ] || [ "$size" -ge $(($(stat -c %s "$last") / 2)) ] || failed "The backup is less than half the last one's size"
name=transit-$(date +%Y%m%dT%H%M%S).dump
mv .part "$name" && echo "$name: $((size / 1000000)) MB"
rm -f .told

# Keeps the newest $DAILY days' and the newest of each of the last $WEEKLY weeks; only after a good backup.
declare -A days weeks; kept_days=0 kept_weeks=0
for f in $(ls -1r transit-*.dump); do
  day=${f:8:8} week=$(date -d "${f:8:8}" +%G-%V) keep=no
  if [ -z "${days[$day]:-}" ] && [ $kept_days -lt $DAILY ]; then keep=yes kept_days=$((kept_days + 1)); fi
  days[$day]=1
  if [ -z "${weeks[$week]:-}" ] && [ $kept_weeks -lt $WEEKLY ]; then keep=yes kept_weeks=$((kept_weeks + 1)); fi
  weeks[$week]=1
  [ $keep = yes ] || rm -f -- "$f"
done
