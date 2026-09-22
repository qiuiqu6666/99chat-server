#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
set -a
# shellcheck disable=SC1091
source .env
set +a

MYSQL=(mysql -h"${DB_HOST}" -P"${DB_PORT}" -u"${DB_USERNAME}" -p"${DB_PASSWORD}" "${DB_NAME}" --ssl-mode="${DB_SSL_MODE:-DISABLED}")

echo '=== before wallet_red_packet.status ==='
"${MYSQL[@]}" -N -e "SHOW COLUMNS FROM wallet_red_packet LIKE 'status';"

echo '=== running migrate-wallet-red-packet-status.sql ==='
"${MYSQL[@]}" < scripts/migrate-wallet-red-packet-status.sql

echo '=== after wallet_red_packet.status ==='
"${MYSQL[@]}" -N -e "SHOW COLUMNS FROM wallet_red_packet LIKE 'status';"
