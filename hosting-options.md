# Hosting options: free public hosting for transit-analytics

Analysis from 20 November 2026 of how to host the recorder, backend, frontend and Grafana telemetry publicly, for free. It's a hobby, non-commercial project used by a few people.

**Short answer:** you can host all of it for free. The only realistic way is one small server running everything: either a free Oracle Cloud ARM server, or your own computer made public through a free Cloudflare Tunnel. The usual free app hosts don't work for this app, because the recorder has to run 24/7 and the database grows by gigabytes a month.

## What the app needs (measured on the development machine)

| Part | Memory now | Notes |
|---|---|---|
| Postgres + TimescaleDB | 845 MB | Database is 888 MB after about 11 recorded days |
| transit-recorder | 330 MB | Must run all the time: every minute down is data lost for good |
| Backend | 2.4 GB | Java takes a quarter of RAM when uncapped; limited to 1 GB it should need about 1.3 GB |
| Grafana with metrics, logs and traces (`otel-lgtm`) | 1.1 GB | |
| Frontend | – | Plain static files |

- **Growth at 10 s polling:**
  - the database adds about 100 MB a day after compression, around 3 GB a month;
  - the raw archive adds about 85 MB a day, around 2.5 GB a month;
  - so roughly 5–6 GB a month, 65 GB a year.
- **Everything together** needs about 4–5 GB of memory, 2 CPU cores, and a disk that can take that growth.

## The options

| Option | Fits? | Verdict |
|---|---|---|
| **A. Oracle Cloud Always Free ARM server** | Yes: 2 CPU cores, 12 GB memory, 200 GB disk | **Best fully free cloud option** |
| **B. Your own computer + Cloudflare Tunnel** | Yes: it runs there today | **Simplest**; free apart from electricity |
| C. Google Cloud's free server | No: 1 GB memory, 30 GB disk, US regions only | Too small even for the database plus Java |
| D. Free app hosts (Render, Koyeb, Fly…) + free databases (Supabase, Neon) | No | Free servers sleep when idle, which kills the recorder. Free databases cap at 0.5–1 GB, about 10 days of data, and don't offer TimescaleDB |

### A. Oracle Cloud Always Free

- **The limit was halved this year.** Oracle cut the free ARM allowance from 4 cores and 24 GB to **2 cores and 12 GB** in mid-2026. That's still enough for the full stack, with room to spare.
- **Disk:** the 200 GB of free disk lasts about 3 years at current growth. Longer if the raw archive moves to free object storage, such as Cloudflare R2's 10 GB or Oracle's own 20 GB.
- **Things to watch:**
  - **Idle servers get reclaimed.** Oracle takes back free servers that sit nearly idle for a week, and the recorder uses only about 5% CPU. The usual workaround is upgrading the account to Pay As You Go: still free within the limits, but it needs a card, and anything beyond the limits gets billed.
  - **Capacity:** free ARM servers are often unavailable in popular regions, and the home region is picked once, at sign-up.
  - **Account risk:** free accounts are sometimes closed without warning. Keep nightly database backups somewhere else, for example R2.
  - **ARM processor:** the Postgres, Grafana and Java images should all run on ARM, but build and test them for ARM before moving.
- **Latency:** Oracle has European regions, so it's close to zbiorkom.live.

### B. Your own computer + Cloudflare Tunnel

- Keep running docker compose at home. Cloudflare Tunnel, free with the Cloudflare Zero Trust plan, gives public HTTPS addresses without opening router ports or needing a fixed IP.
- **Login screen for free:** Cloudflare Access can also put a login in front of Grafana for up to 50 users.
- **Downsides:**
  - home outages and reboots are recording gaps;
  - a computer at home has to stay on all the time;
  - upload speed matters a little, but the traffic for a few users is tiny.
- This is a good first step: go public today and move to Oracle later without changing the setup.

### C. Google Cloud free server

One e2-micro server with 1 GB of memory and 30 GB of disk, free only in the US regions. Too small for Postgres, two Java apps and Grafana.

### D. Free app hosts + free databases

Free servers on these hosts sleep when nobody visits, which stops the recorder. Free Postgres plans cap at 0.5–1 GB, about 10 days of this app's data, and none confirm TimescaleDB support. Not viable.

## Grafana: on the same server, or Grafana Cloud's free plan?

- **Grafana Cloud free plan:** 10,000 metric series, 50 GB of logs and 50 GB of traces a month, kept 14 days, 3 users. The app sends far less than that, and it saves about 1 GB of memory on the server.
- **The catch:** the Transit Fleet dashboard reads Postgres directly through the `grafana_reader` role (`grafana/provisioning/datasources.yaml`). Grafana Cloud would need a connection into the database, via its private data-source agent or by exposing Postgres, and exposing Postgres is best avoided.
- **Recommendation:** keep the self-hosted Grafana on the same server, behind Cloudflare Access, or with a read-only anonymous viewer to make it public.

## Recommended setup

1. **Server:** Oracle free ARM server, 2 cores and 12 GB, running the existing docker compose (Postgres, recorder, backend capped at 1 GB, Grafana).
2. **Public access:** a Cloudflare Tunnel on the server publishes the app and Grafana, with free HTTPS. Nothing is exposed to the internet directly, and Postgres stays private. A domain costs about €10 a year; with no domain at all, Cloudflare's own free `*.trycloudflare.com` addresses work but change on every restart.
3. **Frontend:** served by the backend or nginx through the tunnel, or as static files on Cloudflare Pages (free).
4. **Backups:** a nightly `pg_dump` plus the raw archive, copied to Cloudflare R2 (10 GB free).
5. **Before going public:**
   - change the guest and admin passwords and the JWT secret;
   - take the recorder's `/live` endpoint and port 8081 off the public side;
   - set the Grafana admin password;
   - turn on Postgres compression and a retention policy, or move the raw archive to R2.

**Fallback right away:** option B today, then move to Oracle when the account is ready. Moving is just a database restore plus copying the archive, and the recorder's spool covers the switchover.

## Sources

- [Oracle Always Free Resources (docs)](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm)
- [Oracle quietly cuts Free Tier Ampere A1 resources in half (Linuxiac)](https://linuxiac.com/oracle-quietly-cuts-free-tier-ampere-a1-resources-in-half/)
- [OCI Always Free: updated Ampere A1 allocation (Oracle Customer Connect)](https://community.oracle.com/customerconnect/discussion/970310/oci-always-free-updated-ampere-a1-compute-allocation)
- [Oracle Cloud Always Free limits 2026](https://space-node.net/blog/oracle-cloud-always-free-limits-2026)
- [Grafana Cloud pricing](https://www.grafana.com/pricing/)
- [Grafana Cloud free plan 2026](https://costbench.com/software/observability/grafana-cloud/free-plan/)
- [Google Cloud free tier](https://cloud.google.com/free?hl=bg)
- [GCP free tier guide 2026](https://agentdeals.dev/gcp-free-tier-2026)
- [Supabase free tier limits 2026](https://automationatlas.io/answers/supabase-free-tier-limits-2026/)
- [Top PostgreSQL free tiers 2026 (Koyeb)](https://www.koyeb.com/blog/top-postgresql-database-free-tiers-in-2026)
- [Cloudflare Zero Trust plans](https://www.cloudflare.com/plans/sase-zero-trust.md)
- [Cloudflare Zero Trust free plan 2026](https://costbench.com/software/business-vpn/cloudflare-zero-trust/free-plan/)
- [Cloudflare Pages limits](https://developers.cloudflare.com:8443/pages/platform/limits)
