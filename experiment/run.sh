#!/bin/bash
# One run of the rollback-loss experiment.
#
#   ./run.sh <C0|C1a|C1b|C2a|C2b> <run-number>
#
# C0  : shared public schema (tag c0-shared-schema), service-era writes
# C1a : account_schema split (tag c1-schema-split), forward copy, NO service-era writes
# C1b : account_schema split (tag c1-schema-split), forward copy, service-era writes
# C2a : + transaction-service in transaction_schema (tag c2-transaction-split), forward copy,
#       service-era writes incl. money moves, full rollback to the monolith
# C2b : as C2a, but only /api/transactions is rolled back; /api/accounts stays on account-service
#
# Env: WINDOW=<seconds> observation window after rollback (default 300)
set -u
cd "$(dirname "$0")"
export MSYS_NO_PATHCONV=1   # Git Bash on Windows: do not rewrite /results/... arguments

COND=${1:?condition C0|C1a|C1b|C2a|C2b}
RUN=${2:?run number}
WINDOW=${WINDOW:-300}
OUT=results/$COND/run$RUN
IN=/results/$COND/run$RUN            # same directory, as seen from the harness container

# The checked-out code must match the condition
if grep -qs transaction_schema ../services/transaction-service/src/main/resources/application.yml; then CODE=C2
elif grep -q account_schema ../services/account-service/src/main/resources/application.yml; then CODE=C1
else CODE=C0; fi
case "$COND" in
  C0)       [ "$CODE" = C0 ] || { echo "C0 needs the c0-shared-schema code, found $CODE"; exit 2; } ;;
  C1a|C1b)  [ "$CODE" = C1 ] || { echo "$COND needs the c1-schema-split code, found $CODE"; exit 2; } ;;
  C2a|C2b)  [ "$CODE" = C2 ] || { echo "$COND needs the c2-transaction-split code, found $CODE"; exit 2; } ;;
  *)        echo "unknown condition $COND"; exit 2 ;;
esac
ROLLBACK_TARGET=monolith
[ "$COND" = C2b ] && ROLLBACK_TARGET=partial
SERVICES="account-service api-gateway"
[ "$CODE" = C2 ] && SERVICES="rabbitmq account-service transaction-service api-gateway"

rm -rf "$OUT"; mkdir -p "$OUT"
META=$OUT/meta.env
{ echo "condition=$COND"; echo "run=$RUN"; echo "commit=$(git rev-parse --short HEAD)"; echo "window_s=$WINDOW"; } > "$META"
harness() { docker compose run --rm -T harness "$@" "$IN"; }
psql_q()  { docker compose exec -T db psql -U postgres -d banking_db -At -c "$1"; }
psql_t()  { docker compose exec -T db psql -U postgres -d banking_db -c "SELECT * FROM $1 ORDER BY id"; }

echo "== [$COND run $RUN] clean start (down -v)"
cp nginx/nginx.monolith.conf nginx/nginx.conf
docker compose down -v --remove-orphans >/dev/null 2>&1
docker compose build -q || exit 1
docker compose up -d db
until docker compose exec -T db pg_isready -U postgres -q; do sleep 1; done
# Monolith first so its DDL (with FKs) creates the public tables, then the services
docker compose up -d monolith
docker compose up -d $SERVICES nginx prometheus
harness wait || { docker compose logs --no-color > "$OUT/startup-failure.log"; exit 1; }

echo "== 1. seed through the monolith"
harness seed

if [ "$COND" != C0 ]; then
  echo "== 2. forward copy into the service schemas (+ setval)"
  C2VAR=""; [ "$CODE" = C2 ] && C2VAR="-v c2=1"
  docker compose exec -T db psql -U postgres -d banking_db -v ON_ERROR_STOP=1 $C2VAR < sql/forward-copy.sql > "$OUT/forward-copy.txt" 2>&1
  echo "forward_copy_exit=$?" >> "$META"
  # Did the copy itself lose anything? (C2 has no no-write control condition; this check stands in for it)
  echo "fwd_accounts_missing=$(psql_q 'SELECT count(*) FROM public.accounts p WHERE NOT EXISTS (SELECT 1 FROM account_schema.accounts s WHERE s.id=p.id AND s.account_number=p.account_number AND s.balance=p.balance AND s.status=p.status AND s.user_id=p.user_id)')" >> "$META"
  if [ "$CODE" = C2 ]; then
    echo "fwd_transactions_missing=$(psql_q 'SELECT count(*) FROM public.transactions p WHERE NOT EXISTS (SELECT 1 FROM transaction_schema.transactions s WHERE s.id=p.id AND s.amount=p.amount AND s.transaction_type=p.transaction_type AND s.source_account_id=p.source_account_id AND s.target_account_id IS NOT DISTINCT FROM p.target_account_id)')" >> "$META"
  fi
fi

echo "== 3. switch to blue"
./scripts/switch-blue-green.sh blue > "$OUT/switch-to-blue.txt" 2>&1
echo "switch_blue_exit=$?" >> "$META"
harness forward

if [ "$COND" != C1a ]; then
  echo "== 4. service-era workload"
  harness workload
fi

echo "== 5. rollback to $ROLLBACK_TARGET"
SINCE=$(date -u +%Y-%m-%dT%H:%M:%SZ)
./scripts/switch-blue-green.sh $ROLLBACK_TARGET > "$OUT/switch-to-$ROLLBACK_TARGET.txt" 2>&1
echo "switch_monolith_exit=$?" >> "$META"   # exit code of the rollback switch (monolith or partial)
echo "rollback_target=$ROLLBACK_TARGET" >> "$META"
echo "rollback_at=$SINCE" >> "$META"
docker compose exec -T nginx nginx -T 2>/dev/null > "$OUT/nginx-after-rollback.conf"
# Locations still proxied to the gateway after the rollback ("monolith" = none)
GW=$(awk '/location/ {loc=$2} /proxy_pass.*api-gateway/ {print loc}' "$OUT/nginx-after-rollback.conf" | sort -u | paste -sd, -)
echo "nginx_after_rollback=${GW:-monolith}" >> "$META"

# Ground truth at the moment of rollback, before any verification traffic
{
  echo "db_public_accounts=$(psql_q 'SELECT count(*) FROM public.accounts')"
  if [ "$CODE" != C0 ]; then
    echo "db_account_schema_accounts=$(psql_q 'SELECT count(*) FROM account_schema.accounts')"
    echo "db_rows_only_in_account_schema=$(psql_q 'SELECT count(*) FROM account_schema.accounts s WHERE NOT EXISTS (SELECT 1 FROM public.accounts p WHERE p.id=s.id AND p.account_number=s.account_number AND p.user_id=s.user_id)')"
    echo "db_status_differs=$(psql_q 'SELECT count(*) FROM account_schema.accounts s JOIN public.accounts p ON p.id=s.id WHERE p.status<>s.status')"
  fi
  if [ "$CODE" = C2 ]; then
    echo "db_balance_differs=$(psql_q 'SELECT count(*) FROM account_schema.accounts s JOIN public.accounts p ON p.id=s.id WHERE p.balance<>s.balance')"
    echo "db_balance_sum_public=$(psql_q 'SELECT sum(balance) FROM public.accounts')"
    echo "db_balance_sum_account_schema=$(psql_q 'SELECT sum(balance) FROM account_schema.accounts')"
    echo "db_public_transactions=$(psql_q 'SELECT count(*) FROM public.transactions')"
    echo "db_transaction_schema_transactions=$(psql_q 'SELECT count(*) FROM transaction_schema.transactions')"
    echo "db_rows_only_in_transaction_schema=$(psql_q 'SELECT count(*) FROM transaction_schema.transactions s WHERE NOT EXISTS (SELECT 1 FROM public.transactions p WHERE p.id=s.id)')"
    # Service-era notification events nobody consumed (notification-service is not extracted)
    echo "rabbit_notification_queue=$(docker compose exec -T rabbitmq rabbitmqctl -q list_queues name messages 2>/dev/null | awk '$1 == "notification.queue" {print $2}')"
  fi
} >> "$META"
psql_t public.accounts > "$OUT/db-at-rollback.txt"
if [ "$CODE" != C0 ]; then psql_t account_schema.accounts >> "$OUT/db-at-rollback.txt"; fi
if [ "$CODE" = C2 ]; then
  psql_t public.transactions >> "$OUT/db-at-rollback.txt"
  psql_t transaction_schema.transactions >> "$OUT/db-at-rollback.txt"
fi

echo "== 6. verify through the monolith"
harness verify
if [ "$CODE" = C2 ]; then
  # Transaction ids the monolith handed out again after the rollback (same id, different transaction)
  echo "db_tx_ids_reused=$(psql_q 'SELECT count(*) FROM public.transactions p JOIN transaction_schema.transactions s ON s.id=p.id WHERE (p.transaction_type, p.amount, p.source_account_id) IS DISTINCT FROM (s.transaction_type, s.amount, s.source_account_id)')" >> "$META"
fi

echo "== 7. observe for ${WINDOW}s"
sleep "$WINDOW"
harness signals
LOGGED="monolith account-service api-gateway nginx"
[ "$CODE" = C2 ] && LOGGED="$LOGGED transaction-service rabbitmq"
for s in $LOGGED; do
  docker compose logs --no-color --since "$SINCE" "$s" > "$OUT/logs-$s.txt" 2>&1
  key=${s//-/_}
  echo "error_$key=$(grep -cE '(^|[^A-Z])ERROR([^A-Z]|$)' "$OUT/logs-$s.txt")" >> "$META"
  echo "warn_$key=$(grep -cE '(^|[^A-Z])WARN([^A-Z]|$)' "$OUT/logs-$s.txt")" >> "$META"
done
docker compose logs --no-color > "$OUT/logs-all.txt" 2>&1
docker stats --no-stream --format '{{.Name}} {{.MemUsage}}' > "$OUT/mem.txt" 2>&1

harness summarize
docker compose run --rm -T harness aggregate /results >/dev/null
echo "== done: $OUT"
