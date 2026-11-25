# Server setup

What was done on the server over SSH, to do it again. For a fresh Ubuntu 24.04 on ARM (Oracle `VM.Standard.A1.Flex`,
2 OCPU, 12 GB, 50 GB disk) with a public IP, and the cloud's own firewall open for TCP 22, 80, 443 and UDP 443.
Put in your own `<server>` (its IP) and `<domain>`.

## 1. Log in

```sh
ssh -i ~/.ssh/transit-oracle ubuntu@<server>
uname -m        # aarch64
```

Everything below runs on the server, unless it says otherwise.

## 2. Update Ubuntu

```sh
sudo apt-get update && sudo apt-get -y upgrade
ls /var/run/reboot-required     # if it exists: sudo reboot
```

## 3. Open the firewall (before Docker, which adds rules of its own)

Oracle's Ubuntu blocks everything but SSH. Add the three rules above the `REJECT` line, and load them:

```sh
sudo cp /etc/iptables/rules.v4 /etc/iptables/rules.v4.orig
sudo sed -i '/-A INPUT -j REJECT/i -A INPUT -p tcp -m state --state NEW -m tcp --dport 80 -j ACCEPT' /etc/iptables/rules.v4
sudo sed -i '/-A INPUT -j REJECT/i -A INPUT -p tcp -m state --state NEW -m tcp --dport 443 -j ACCEPT' /etc/iptables/rules.v4
sudo sed -i '/-A INPUT -j REJECT/i -A INPUT -p udp -m state --state NEW -m udp --dport 443 -j ACCEPT' /etc/iptables/rules.v4
sudo iptables-restore --test /etc/iptables/rules.v4 && sudo iptables-restore < /etc/iptables/rules.v4
```

## 4. Install Docker

```sh
sudo apt-get -y install git ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu noble stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list
sudo apt-get update
sudo apt-get -y install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# Logs of at most 5 x 20 MB per container
echo '{"log-driver":"json-file","log-opts":{"max-size":"20m","max-file":"5"}}' | sudo tee /etc/docker/daemon.json
sudo systemctl restart docker && sudo systemctl enable docker
sudo usermod -aG docker ubuntu  # log out and in again
```

## 5. Swap, as a reserve

```sh
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
sudo swapon -a
```

## 6. The repository and its data folders

```sh
git clone https://github.com/twardzikfilip/transit-analytics.git ~/transit-analytics
cd ~/transit-analytics && mkdir -p backend/data/raw backend/data/spool
```

## 7. Check the ports

A listener for 40 seconds, in an empty folder:

```sh
mkdir -p /tmp/empty && cd /tmp/empty && sudo timeout 40 python3 -m http.server 80     # then the same with 443
```

Meanwhile on your own computer: `curl -I http://<domain>` (and `http://<domain>:443`) answers 200.
5432, 8080 and 8081 must not answer.

## 8. Download the images

```sh
docker pull timescale/timescaledb-ha:pg17.11-ts2.31.0
docker pull ghcr.io/twardzikfilip/transit-recorder:1.0.0
docker pull ghcr.io/twardzikfilip/transit-backend:1.0.0
docker pull ghcr.io/twardzikfilip/transit-frontend:1.0.0
```

## 9. Make the secrets (typed by you)

On your own computer:

```sh
ssh -t -i ~/.ssh/transit-oracle ubuntu@<server> 'cd transit-analytics && tools/server-env.sh <domain>'
```

## 10. Copy the data (the home recorder keeps running)

On the home computer: a dump (it only reads), noting the time, and to the server with it:

```sh
mkdir -p ~/backups/move && cd ~/backups/move && date -u +%FT%TZ | tee T0
docker exec transit-postgres nice -n 19 pg_dump -U transit -Fc -Z 1 transit > transit.dump
ssh ubuntu@<server> 'mkdir -p ~/move && chmod 700 ~/move' && scp transit.dump ubuntu@<server>:move/
```

On the server, **before any recorder runs** (else bus ids clash): start only PostgreSQL, make the two roles (they aren't
in the dump) with the passwords from `.env`, and restore:

```sh
cd ~/transit-analytics && docker compose -f docker-compose.prod.yml up -d --no-deps postgres
p="docker exec -i transit-postgres psql -q -v ON_ERROR_STOP=1 -U transit transit"
( set -a; . ./.env; printf "CREATE ROLE transit_recorder LOGIN PASSWORD '%s';\nCREATE ROLE grafana_reader LOGIN PASSWORD '%s';\n" \
    "$RECORDER_DB_PASSWORD" "$GRAFANA_DB_PASSWORD" ) | $p
$p -c "CREATE EXTENSION IF NOT EXISTS timescaledb VERSION '2.31.0'" -c "SELECT timescaledb_pre_restore()"
docker exec -i transit-postgres pg_restore -U transit -d transit --no-comments < ~/move/transit.dump 2> ~/move/restore.log
$p -c "SELECT timescaledb_post_restore()" -c "ANALYZE"
grep -c 'error:' ~/move/restore.log      # 0
```

Then compare counts per day on both sides (positions before T0 must be the same).

## 11. Start the recorder, and fill the gap

```sh
docker compose -f docker-compose.prod.yml up -d --no-deps recorder
curl -s localhost:8081/actuator/health       # lastStored and lastArchived are seconds old
```

What was recorded at home between T0 and now: copy those files of `backend/data/raw/…/<day>/` to `~/move/gap` on the
server, and import them (duplicates are skipped; the archive isn't touched):

```sh
( set -a; . ./.env
  docker run --rm --network transit_default -v ~/move/gap:/import:ro --tmpfs /data/scratch:uid=1000 \
    -e DB_URL=jdbc:postgresql://postgres:5432/transit -e DB_USER=transit -e DB_PASSWORD -e RECORDING_ENABLED=false \
    -e RECORDING_ARCHIVE_DIR=/data/scratch -e RECORDING_SPOOL_DIR=/data/scratch -e SERVER_PORT=0 \
    -e MANAGEMENT_TRACING_EXPORT_ENABLED=false -e MANAGEMENT_LOGGING_EXPORT_ENABLED=false \
    -e MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED=false \
    ghcr.io/twardzikfilip/transit-recorder:1.0.0 --app.recording.import-dir=/import )
```

## 12. Start the app

```sh
docker compose -f docker-compose.prod.yml up -d --no-deps backend      # wait until `docker ps` says healthy
docker compose -f docker-compose.prod.yml up -d --no-deps frontend     # gets its HTTPS certificate by itself
```

Check `https://<domain>`, and that `admin`/`admin` and `guest`/`guest` don't log in.

## 13. Let GitHub deploy

A key that may only run `tools/deploy.sh`. On your own computer:

```sh
ssh-keygen -t ed25519 -N '' -C github-deploy -f ./deploy-key
ssh ubuntu@<server> "echo 'restrict,command=\"/home/ubuntu/transit-analytics/tools/deploy.sh\" $(cat deploy-key.pub)' >> ~/.ssh/authorized_keys"
# only main's workflows may use its secrets
gh api -X PUT repos/<owner>/<repo>/environments/production -F 'deployment_branch_policy[protected_branches]=false' \
  -F 'deployment_branch_policy[custom_branch_policies]=true'
gh api -X POST repos/<owner>/<repo>/environments/production/deployment-branch-policies -f name=main
gh secret set DEPLOY_KEY --env production < deploy-key
gh secret set DEPLOY_HOST --env production --body ubuntu@<server>
ssh-keyscan -t ed25519 <server> | gh secret set DEPLOY_KNOWN_HOSTS --env production     # compare its fingerprint first
shred -u deploy-key
```

Port 22 has to stay open to GitHub's machines (their addresses change).

## 14. Grafana

A new server's `.env` has what it needs (`tools/server-env.sh`). To an older one, add once:

```sh
cd ~/transit-analytics && echo GRAFANA_VERSION=1.0.0 >> .env && echo "GRAFANA_ADMIN_PASSWORD=$(openssl rand -hex 16)" >> .env
```

Then run the Release workflow (README, "Grafana"). Read the password with
`grep GRAFANA_ADMIN_PASSWORD ~/transit-analytics/.env`. It is for user `admin` at `https://<domain>/grafana/`.

## 15. Nightly backups

`tools/deploy.sh` keeps copies on the server; these are kept away from it, on the home computer, which fetches them.

1. At home: `ssh-keygen -t ed25519 -N '' -f ~/.ssh/transit-backup`. Save the server's host key (checked to be the right one)
   as `~/.ssh/transit-backup.known_hosts`, and `SERVER=user@address` as `~/.config/transit-backup.env` (mode 600).
2. On the server, the key's line in `~/.ssh/authorized_keys` starts with
   `restrict,command="nice -n 19 docker exec transit-postgres nice -n 19 pg_dump -U transit -Fc -Z 1 transit"`: it gives
   the dump, whatever it asks.
3. At home:

```sh
install -m 755 tools/fetch-backup.sh ~/.local/bin/transit-fetch-backup
install -m 644 tools/backup-timer/* ~/.config/systemd/user/
systemctl --user daemon-reload && systemctl --user enable --now transit-backup.timer
~/.local/bin/transit-fetch-backup --now          # the first one, into ~/backups/server
```

The timer asks every hour whether a backup is due (one a day, from 03:30), so a failure is retried and a computer that
was off makes up for it. The dump is read through once before it counts. The last 7 days and 4 weeks are kept; nothing is
fetched with under 8 GB free. A failure shows as a desktop notification, once a day. Installed is a copy: install it
again after changing the script.

To restore into an empty database (never over a running one; try it in a throwaway container first), use TimescaleDB's
own two steps around it:

```sh
psql -U transit -d transit -c "CREATE EXTENSION IF NOT EXISTS timescaledb VERSION '2.31.0'" -c "SELECT timescaledb_pre_restore()"
pg_restore -U transit -d transit --no-owner < transit-….dump
psql -U transit -d transit -c "SELECT timescaledb_post_restore()"
```

## Not done yet

A check from outside that the recorder records.
