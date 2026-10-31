#!/bin/bash
# Runs once, inside the postgres container, on first start of an empty data volume.
set -euo pipefail
psql -v ON_ERROR_STOP=1 --username postgres --dbname paynexa \
  -v identity_migrator_pw="$IDENTITY_MIGRATOR_PW" -v identity_app_pw="$IDENTITY_APP_PW" \
  -v payments_migrator_pw="$PAYMENTS_MIGRATOR_PW" -v payments_app_pw="$PAYMENTS_APP_PW" \
  -v notification_migrator_pw="$NOTIFICATION_MIGRATOR_PW" -v notification_app_pw="$NOTIFICATION_APP_PW" \
  -f /sql/01-roles-and-schemas.sql
