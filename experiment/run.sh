#!/bin/bash
# One run of the rollback-loss experiment.
#
#   ./run.sh <C0|C1a|C1b> <run-number>
#
# C0  : shared public schema (tag c0-shared-schema), service-era writes
# C1a : account_schema split (tag c1-schema-split), forward copy, NO service-era writes
# C1b : account_schema split (tag c1-schema-split), forward copy, service-era writes
#
# Env: WINDOW=<seconds> observation window after rollback (default 300)
set -u
cd "$(dirname "$0")"
export MSYS_NO_PATHCONV=1   # Git Bash on Windows: do not rewrite /results/... arguments

COND=${1:?condition C0|C1a|C1b}
RUN=${2:?run number}
WINDOW=${WINDOW:-300}
OUT=results/$COND/run$RUN
IN=/results/$COND/run$RUN            # same directory, as seen from the harness container

# The checked-out code must match the condition
if grep -q account_schema ../services/account-service/src/main/resources/application.yml; then CODE=C1; else CODE=C0; fi
case "$COND" in
  C0)       [ "$CODE" = C0 ] || { echo "C0 needs the c0-shared-schema code, found $CODE"; exit 2; } ;;
  C1a|C1b)  [ "$CODE" = C1 ] || { echo "$COND needs the c1-schema-split code, found $CODE"; exit 2; } ;;
  *)        echo "unknown condition $COND"; exit 2 ;;
esac

rm -rf "$OUT"; mkdir -p "$OUT"
META=$OUT/meta.env
{ echo "condition=$COND"; echo "run=$RUN"; echo "commit=$(git rev-parse --short HEAD)"; echo "window_s=$WINDOW"; } > "$META"
harness() { docker compose run --rm -T harness "$@" "$IN"; }
psql_q()  { docker compose exec -T db psql -U postgres -d banking_db -At -c "$1"; }

echo "== [$COND run $RUN] clean start (down -v)"
cp nginx/nginx.monolith.conf nginx/nginx.conf
docker compose down -v --remove-orphans >/dev/null 2>&1
docker compose build -q || exit 1
docker compose up -d db
until docker compose exec -T db pg_isready -U postgres -q; do sleep 1; done
# Monolith first so its DDL (with FKs) creates the public tables, then the services
docker compose up -d monolith
docker compose up -d account-service api-gateway nginx prometheus
harness wait || { docker compose logs --no-color > "$OUT/startup-failure.log"; exit 1; }

echo "== 1. seed through the monolith"
harness seed

if [ "$COND" != C0 ]; then
  echo "== 2. forward copy public.accounts -> account_schema.accounts (+ setval)"
  docker compose exec -T db psql -U postgres -d banking_db -v ON_ERROR_STOP=1 < sql/forward-copy.sql > "$OUT/forward-copy.txt" 2>&1
  echo "forward_copy_exit=$?" >> "$META"
fi

echo "== 3. switch to blue"
./scripts/switch-blue-green.sh blue > "$OUT/switch-to-blue.txt" 2>&1
echo "switch_blue_exit=$?" >> "$META"
harness forward

if [ "$COND" != C1a ]; then
  echo "== 4. service-era workload"
  harness workload
fi

echo "== 5. rollback to monolith"
SINCE=$(date -u +%Y-%m-%dT%H:%M:%SZ)
./scripts/switch-blue-green.sh monolith > "$OUT/switch-to-monolith.txt" 2>&1
echo "switch_monolith_exit=$?" >> "$META"
echo "rollback_at=$SINCE" >> "$META"
docker compose exec -T nginx nginx -T 2>/dev/null > "$OUT/nginx-after-rollback.conf"
if grep -q api-gateway "$OUT/nginx-after-rollback.conf"; then echo "nginx_after_rollback=blue"; else echo "nginx_after_rollback=monolith"; fi >> "$META"

# Ground truth at the moment of rollback, before any verification traffic
{
  echo "db_public_accounts=$(psql_q 'SELECT count(*) FROM public.accounts')"
  if [ "$CODE" = C1 ]; then
    echo "db_account_schema_accounts=$(psql_q 'SELECT count(*) FROM account_schema.accounts')"
    echo "db_rows_only_in_account_schema=$(psql_q 'SELECT count(*) FROM account_schema.accounts s WHERE NOT EXISTS (SELECT 1 FROM public.accounts p WHERE p.id=s.id AND p.account_number=s.account_number AND p.user_id=s.user_id)')"
    echo "db_status_differs=$(psql_q 'SELECT count(*) FROM account_schema.accounts s JOIN public.accounts p ON p.id=s.id WHERE p.status<>s.status')"
  fi
} >> "$META"
docker compose exec -T db psql -U postgres -d banking_db -c 'SELECT * FROM public.accounts ORDER BY id' > "$OUT/db-at-rollback.txt"
[ "$CODE" = C1 ] && docker compose exec -T db psql -U postgres -d banking_db -c 'SELECT * FROM account_schema.accounts ORDER BY id' >> "$OUT/db-at-rollback.txt"

echo "== 6. verify through the monolith"
harness verify

echo "== 7. observe for ${WINDOW}s"
sleep "$WINDOW"
harness signals
for s in monolith account-service api-gateway nginx; do
  docker compose logs --no-color --since "$SINCE" "$s" > "$OUT/logs-$s.txt" 2>&1
  key=${s//-/_}
  echo "error_$key=$(grep -cE '(^|[^A-Z])ERROR([^A-Z]|$)' "$OUT/logs-$s.txt")" >> "$META"
  echo "warn_$key=$(grep -cE '(^|[^A-Z])WARN([^A-Z]|$)' "$OUT/logs-$s.txt")" >> "$META"
done
docker compose logs --no-color > "$OUT/logs-all.txt" 2>&1

harness summarize
docker compose run --rm -T harness aggregate /results >/dev/null
echo "== done: $OUT"
