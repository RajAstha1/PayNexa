#!/usr/bin/env bash
# Walks through a complete payment against a running local stack (./gradlew :system-tests:runLocal).
# Usage: ADMIN_PASSWORD='<printed by runLocal>' scripts/demo.sh
# Everything here is synthetic sandbox money.
set -euo pipefail

I=${IDENTITY_URL:-http://localhost:8081}
P=${PAYMENTS_URL:-http://localhost:8082}
N=${NOTIFICATIONS_URL:-http://localhost:8083}
: "${ADMIN_PASSWORD:?set ADMIN_PASSWORD to the admin password printed by runLocal}"
PW='correct horse battery staple'
SUFFIX=$(date +%s)

json() { python3 -c "import sys,json; d=json.load(sys.stdin); print($1)"; }
post() { curl -sS -X POST "$1" -H 'Content-Type: application/json' "${@:2}"; }
step() { printf '\n\033[1m== %s\033[0m\n' "$1"; }

step "1. Register two users and log the administrator in"
ALICE=$(post "$I/auth/register" -d "{\"email\":\"alice$SUFFIX@example.com\",\"password\":\"$PW\"}" | json "d['accessToken']")
BOB=$(post "$I/auth/register" -d "{\"email\":\"bob$SUFFIX@example.com\",\"password\":\"$PW\"}" | json "d['accessToken']")
ADMIN=$(post "$I/auth/login" -d "{\"email\":\"admin@paynexa.example\",\"password\":\"$ADMIN_PASSWORD\"}" | json "d['accessToken']")
echo "tokens issued (RS256, 10 minute lifetime)"

step "2. Each user opens a USD account; the admin adds synthetic funds to Alice"
A_ACC=$(post "$P/accounts" -H "Authorization: Bearer $ALICE" -d '{"currency":"USD"}' | json "d['id']")
B_ACC=$(post "$P/accounts" -H "Authorization: Bearer $BOB" -d '{"currency":"USD"}' | json "d['id']")
post "$P/admin/accounts/$A_ACC/fund" -H "Authorization: Bearer $ADMIN" -H "Idempotency-Key: fund-$SUFFIX-alice" \
  -d '{"amount":"500.00","currency":"USD"}' | json "'funded: ' + d['amount'] + ' ' + d['currency'] + ' -> ' + d['status']"

step "3. Alice pays Bob 25.50 (the request is sent twice with the same Idempotency-Key)"
KEY="pay-$SUFFIX-1"
BODY="{\"fromAccountId\":\"$A_ACC\",\"toAccountId\":\"$B_ACC\",\"amount\":\"25.50\",\"currency\":\"USD\",\"reference\":\"demo\"}"
curl -sS -i -X POST "$P/payments/transfers" -H "Authorization: Bearer $ALICE" -H "Idempotency-Key: $KEY" -H 'Content-Type: application/json' -d "$BODY" \
  | grep -E '^HTTP|^Idempotent-Replay|"status"' | sed 's/^/  first : /'
curl -sS -i -X POST "$P/payments/transfers" -H "Authorization: Bearer $ALICE" -H "Idempotency-Key: $KEY" -H 'Content-Type: application/json' -d "$BODY" \
  | grep -E '^HTTP|^Idempotent-Replay' | sed 's/^/  second: /'

step "4. Balances (money moved exactly once)"
echo "  alice: $(curl -sS "$P/accounts/$A_ACC" -H "Authorization: Bearer $ALICE" | json "d['balance']")"
echo "  bob  : $(curl -sS "$P/accounts/$B_ACC" -H "Authorization: Bearer $BOB" | json "d['balance']")"

step "5. Bob cannot see Alice's account"
curl -sS -o /dev/null -w "  Bob GET Alice's account -> HTTP %{http_code}\n" "$P/accounts/$A_ACC" -H "Authorization: Bearer $BOB"
curl -sS -o /dev/null -w "  no token                -> HTTP %{http_code}\n" "$P/accounts"

step "6. A payout is in flight, then settled only after the (simulated) rail confirms"
PAYOUT=$(post "$P/payments/payouts" -H "Authorization: Bearer $ALICE" -H "Idempotency-Key: out-$SUFFIX-1" \
  -d "{\"fromAccountId\":\"$A_ACC\",\"destination\":\"bank:demo-ok\",\"amount\":\"40.00\",\"currency\":\"USD\"}" | json "d['id']")
echo "  right after submit: $(curl -sS "$P/payments/$PAYOUT" -H "Authorization: Bearer $ALICE" | json "d['status']")"
sleep 3
echo "  a few seconds on  : $(curl -sS "$P/payments/$PAYOUT" -H "Authorization: Bearer $ALICE" | json "d['status']")"

step "7. A large transfer is held for review; only an admin can release it"
HELD=$(post "$P/admin/accounts/$A_ACC/fund" -H "Authorization: Bearer $ADMIN" -H "Idempotency-Key: fund-$SUFFIX-2" -d '{"amount":"5000.00","currency":"USD"}' >/dev/null; \
  post "$P/payments/transfers" -H "Authorization: Bearer $ALICE" -H "Idempotency-Key: big-$SUFFIX" \
  -d "{\"fromAccountId\":\"$A_ACC\",\"toAccountId\":\"$B_ACC\",\"amount\":\"3000.00\",\"currency\":\"USD\"}" | json "d['id'] + ' ' + d['status']")
echo "  $HELD"
HELD_ID=${HELD%% *}
curl -sS -o /dev/null -w "  Alice tries to approve her own payment -> HTTP %{http_code}\n" -X POST "$P/admin/payments/$HELD_ID/approve" -H "Authorization: Bearer $ALICE"
echo "  admin approves -> $(post "$P/admin/payments/$HELD_ID/approve" -H "Authorization: Bearer $ADMIN" | json "d['status']")"

step "8. Notifications built from Kafka events"
sleep 2
curl -sS "$N/notifications" -H "Authorization: Bearer $BOB" | json "'\n'.join('  ' + n['kind'] + ': ' + n['message'] for n in d)"

step "9. Reconciliation re-derives the books from the immutable ledger"
post "$P/admin/reconciliation/run" -H "Authorization: Bearer $ADMIN" | json "'clean=' + str(d['clean']) + ' findings=' + str(len(d['findings']))"
