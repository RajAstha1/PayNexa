#!/usr/bin/env bash
# Generates throw-away development secrets: an RSA key pair for token signing and random database passwords.
# Refuses to overwrite existing files. Output goes to ./secrets (git-ignored) and ./.env (git-ignored).
set -euo pipefail
cd "$(dirname "$0")/.."

command -v openssl >/dev/null || { echo "openssl is required" >&2; exit 1; }
mkdir -p secrets
for f in secrets/jwt-private.pem secrets/jwt-public.pem .env; do
  [ -e "$f" ] && { echo "refusing to overwrite existing $f (delete it first if you really want new secrets)" >&2; exit 1; }
done

umask 077
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out secrets/jwt-private.pem
openssl rsa -in secrets/jwt-private.pem -pubout -out secrets/jwt-public.pem 2>/dev/null

rnd() { openssl rand -base64 33 | tr -d '/+=\n' | cut -c1-32; }
{
  echo "POSTGRES_ADMIN_PASSWORD=$(rnd)"
  for r in IDENTITY_MIGRATOR IDENTITY_APP PAYMENTS_MIGRATOR PAYMENTS_APP NOTIFICATION_MIGRATOR NOTIFICATION_APP; do
    echo "${r}_PW=$(rnd)"
  done
  echo "PAYNEXA_SANDBOX_FUNDING=true"
  echo "PAYNEXA_BOOTSTRAP_ADMIN_EMAIL=admin@paynexa.example"
  echo "PAYNEXA_BOOTSTRAP_ADMIN_PASSWORD=$(rnd)-sandbox"
} > .env
echo "Created secrets/jwt-private.pem, secrets/jwt-public.pem and .env (all git-ignored)."
echo "The bootstrap admin password is in .env. These values are for local sandbox use only."
