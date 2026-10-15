#!/bin/bash
# Traffic switch, kept as close as possible to the thesis script
# (banking-microservice@9929265 scripts/switch-blue-green.sh).
# Only change: "monolith" (thesis nginx.monolith.conf) and "partial" (C2b) are accepted targets.
#
#   ./scripts/switch-blue-green.sh blue       # /api/accounts (+ /api/transactions in C2) -> services
#   ./scripts/switch-blue-green.sh monolith   # rollback
#   ./scripts/switch-blue-green.sh partial    # C2b: roll back only /api/transactions

set -e

TARGET=$1
NGINX_CONF="./nginx/nginx.conf"

if [[ "$TARGET" != "blue" && "$TARGET" != "monolith" && "$TARGET" != "partial" ]]; then
    echo "Usage: $0 [blue|monolith|partial]"
    exit 1
fi

echo "Switching traffic to: $TARGET"

cp "./nginx/nginx.${TARGET}.conf" "$NGINX_CONF"

# Graceful reload — does not drop active connections, ~50ms switchover
docker-compose exec nginx nginx -s reload

echo "Done. Traffic is now routed to: $TARGET"
echo "Verify: curl -s http://localhost/actuator/health | jq"
