# Deploying PayNexa: Oracle Cloud (free) + Vercel

Sandbox only: synthetic money, simulated payout rail. Not a production configuration (see `docs/PRODUCTION_GAP.md`).

```
browser ──► Vercel (web console + its server routes)
                 │  HTTPS, with a shared secret in X-Gateway-Key
                 ▼
          Oracle VM:  Caddy (HTTPS, checks the secret)
                 ├─► identity-service
                 ├─► payments-service ─► PostgreSQL, Kafka
                 └─► notification-service
```

Only Caddy is reachable from the internet. Without the secret a request gets 403, so nobody can call the services
directly; the services and database listen on the VM's private network only.

> The steps below were assembled from the repo's own Docker setup and Oracle's and Caddy's documentation. They have
> **not** been run on an Oracle VM yet. Expect to fix a small thing or two on the first pass, and tell me what you see.

## Memory and the free tier

Oracle's Always Free Arm allowance is currently 2 OCPU / 12 GB RAM (it was 4 / 24 until June 2026). The stack is limited
to roughly 6 GB in `docker-compose.public.yml`, which leaves room for the OS and for image builds. Oracle may reclaim
Always Free instances that sit idle for 7 days (low CPU, network *and*, on Arm shapes, memory). The JVMs and Kafka hold
a few GB of memory, which should keep you above the memory threshold; check `free -m` after a day. Upgrading the
account to Pay As You Go (you stay at $0 within the free limits) is the usual way to remove that risk.

## 1. Create the VM (Oracle console)

1. Sign up at cloud.oracle.com. A card is needed for identity verification. **Pick your home region carefully; it
   cannot be changed.** Choose one close to your users.
2. Compute → Instances → Create instance:
   - Image: **Ubuntu 24.04** (aarch64)
   - Shape: **VM.Standard.A1.Flex**, 2 OCPU, 12 GB memory
   - Networking: assign a public IPv4 address
   - Add your SSH public key
   - Boot volume: 50-100 GB
3. "Out of host capacity" is a known response for the free Arm shape. Try another availability domain or retry later.
4. Networking → your VCN → Security list → add **ingress** rules for TCP **80** and **443** from `0.0.0.0/0`
   (and UDP 443 if you want HTTP/3). Do **not** open 22 to the world if you can restrict it to your own address.

## 2. Open the VM's own firewall

Oracle's Ubuntu images block ports at the VM level too. SSH in (`ssh ubuntu@<public-ip>`), then:

```bash
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80  -j ACCEPT
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
sudo iptables -I INPUT 6 -m state --state NEW -p udp --dport 443 -j ACCEPT
sudo apt-get update && sudo apt-get install -y iptables-persistent   # answer Yes to save current rules
sudo netfilter-persistent save
```

## 3. A hostname (free)

Caddy needs a name that points at the VM to get a certificate.

1. Sign in at duckdns.org, create a subdomain (for example `paynexa-api`) and set its IP to the VM's public IP.
2. Your API host is then `paynexa-api.duckdns.org`. Wait a minute, then `nslookup paynexa-api.duckdns.org` should show the VM IP.

## 4. Install Docker and a little swap (on the VM)

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER && newgrp docker
sudo fallocate -l 4G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
docker version && docker compose version
```

The swap is a safety net for the Gradle builds, not something the running stack should use.

## 5. Get the code and generate secrets (on the VM)

```bash
git clone https://github.com/RajAstha1/PayNexa.git && cd PayNexa
scripts/generate-dev-secrets.sh                                   # JWT keys, database passwords, bootstrap admin
infra/deploy/make-public-env.sh paynexa-api.duckdns.org you@example.com
```

The second script appends the gateway settings to `.env` and prints the values for Vercel. Keep that output; the
gateway key is shown once here and also lives in `.env`. The admin sign-in is `admin@paynexa.example` and its password is
in `.env` (`PAYNEXA_BOOTSTRAP_ADMIN_PASSWORD`).

`PAYNEXA_SANDBOX_FUNDING=true` (set by the generator) lets the admin add synthetic funds so the demo has balances. Turn
it off in `.env` if you do not want anyone to be able to mint sandbox money. Registration is open to anyone who finds the
console URL.

## 6. Start it (on the VM)

```bash
docker compose -f infra/docker/docker-compose.yml -f infra/deploy/docker-compose.public.yml --env-file .env up -d --build
docker compose -f infra/docker/docker-compose.yml -f infra/deploy/docker-compose.public.yml ps
```

The first build compiles three services and takes several minutes on 2 cores. When `ps` shows everything healthy or
running, check from the VM:

```bash
source .env
curl -s -o /dev/null -w "no key:   %{http_code}\n" https://$API_HOST/identity/auth/login      # expect 403
curl -s -o /dev/null -w "with key: %{http_code}\n" -H "X-Gateway-Key: $GATEWAY_KEY" -X POST \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$PAYNEXA_BOOTSTRAP_ADMIN_EMAIL\",\"password\":\"$PAYNEXA_BOOTSTRAP_ADMIN_PASSWORD\"}" \
  https://$API_HOST/identity/auth/login                                                          # expect 200
```

Logs: `docker compose -f infra/docker/docker-compose.yml -f infra/deploy/docker-compose.public.yml logs -f --tail=100 caddy payments-service`.

## 7. Deploy the console (Vercel)

1. vercel.com → Add New → Project → import `RajAstha1/PayNexa`.
2. **Root Directory: `web`**. Framework preset: Next.js (detected).
3. Environment Variables (Production) — the values printed by `make-public-env.sh`:

| Name | Value |
|---|---|
| `IDENTITY_URL` | `https://<api-host>/identity` |
| `PAYMENTS_URL` | `https://<api-host>/payments` |
| `NOTIFICATIONS_URL` | `https://<api-host>/notifications` |
| `BACKEND_GATEWAY_KEY` | the key from `.env` (mark as Sensitive) |
| `TRUST_PROXY_HEADERS` | `true` |
| `ALLOWED_ORIGINS` | `https://<your-project>.vercel.app` |

4. Deploy. If you change a variable later, redeploy; Vercel applies variables per deployment.
5. Open the Vercel URL, register, and sign in. As admin (`/admin`, Accounts tab) you can add synthetic funds.

## Things to know

- **Sessions on Vercel.** The console de-duplicates simultaneous token refreshes in memory of one server instance.
  Vercel can run several instances, so two refreshes at the same moment can occasionally trip the backend's
  refresh-token reuse protection and sign that user out. Rare; the fix is to sign in again.
- **Gateway key comparison** is a plain string match in Caddy, not constant-time. The key is 256 random bits and the
  endpoint is remote, so this is not practically exploitable, but rotate the key if it leaks: delete the three
  gateway lines in `.env`, rerun `make-public-env.sh`, restart Caddy, update Vercel, redeploy.
- **Backups.** Data lives in the `pgdata` Docker volume on the VM. Nothing backs it up. Oracle can reclaim an idle or
  deleted instance, and this is a sandbox, so treat the data as disposable.
- **Updating.** `git pull`, then rerun the `up -d --build` command.
- **Costs.** Vercel Hobby and the Oracle Always Free limits cost nothing; check both providers' current terms, which
  change.
