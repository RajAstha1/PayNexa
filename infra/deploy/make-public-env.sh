#!/usr/bin/env bash
# Adds the public-gateway settings to .env and prints what to put in Vercel.
#   scripts/generate-dev-secrets.sh                      # once, creates secrets/ and .env
#   infra/deploy/make-public-env.sh api-name.duckdns.org you@example.com
set -euo pipefail
cd "$(dirname "$0")/../.."
HOST="${1:-}"; MAIL="${2:-}"
[[ "$HOST" =~ ^[A-Za-z0-9.-]+\.[A-Za-z]{2,}$ ]] || { echo "usage: $0 <public-hostname> <email-for-certificate-notices>" >&2; exit 1; }
[[ "$MAIL" =~ ^[^@\ ]+@[^@\ ]+\.[A-Za-z]{2,}$ ]] || { echo "usage: $0 <public-hostname> <email-for-certificate-notices>" >&2; exit 1; }
[ -f .env ] || { echo ".env not found: run scripts/generate-dev-secrets.sh first" >&2; exit 1; }
grep -q '^GATEWAY_KEY=' .env && { echo ".env already has GATEWAY_KEY; delete the API_HOST/ACME_EMAIL/GATEWAY_KEY lines to regenerate" >&2; exit 1; }
command -v openssl >/dev/null || { echo "openssl is required" >&2; exit 1; }
KEY="$(openssl rand -hex 32)"
umask 077
{
  echo "API_HOST=$HOST"
  echo "ACME_EMAIL=$MAIL"
  echo "GATEWAY_KEY=$KEY"
} >> .env
cat <<OUT

Added API_HOST, ACME_EMAIL and GATEWAY_KEY to .env (git-ignored).

Set these in Vercel (Project -> Settings -> Environment Variables), then redeploy:

  IDENTITY_URL          https://$HOST/identity
  PAYMENTS_URL          https://$HOST/payments
  NOTIFICATIONS_URL     https://$HOST/notifications
  BACKEND_GATEWAY_KEY   $KEY
  TRUST_PROXY_HEADERS   true
  ALLOWED_ORIGINS       https://<your-project>.vercel.app      (comma-separate extra domains)

BACKEND_GATEWAY_KEY is a secret: paste it only into Vercel's environment variables, never into code or chat.
OUT
