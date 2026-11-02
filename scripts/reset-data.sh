#!/bin/sh
set -eu

case "${1:-}" in
    --help)
        echo "Usage: make reset-data (or sh scripts/reset-data.sh)"
        echo "Permanently clears income, expense, transfer, adjustment and opening balances in Compose Postgres."
        echo "Keeps categories, members, accounts, beneficiaries, sessions and audit history."
        exit 0
        ;;
    "") ;;
    *) echo "Usage: sh scripts/reset-data.sh [--help]" >&2; exit 2 ;;
esac
if [ "$#" -gt 1 ]; then
    echo "Usage: sh scripts/reset-data.sh [--help]" >&2
    exit 2
fi

cd "$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
docker compose exec -T postgres sh -c \
    'exec psql -X --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" --set=ON_ERROR_STOP=1 --single-transaction --file=-' \
    < scripts/reset-data.sql
echo "Financial history cleared; all account balances are zero. Categories, members, accounts and beneficiaries retained."
