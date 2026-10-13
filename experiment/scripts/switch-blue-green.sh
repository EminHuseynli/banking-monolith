#!/bin/bash
# Traffic switch, kept as close as possible to the thesis script
# (banking-microservice@9929265 scripts/switch-blue-green.sh).
# Only change: "monolith" is an accepted target (thesis nginx.monolith.conf).
#
#   ./scripts/switch-blue-green.sh blue       # /api/accounts -> account-service
#   ./scripts/switch-blue-green.sh monolith   # rollback

set -e

TARGET=$1
NGINX_CONF="./nginx/nginx.conf"

if [[ "$TARGET" != "blue" && "$TARGET" != "monolith" ]]; then
    echo "Usage: $0 [blue|monolith]"
    exit 1
fi

echo "Switching traffic to: $TARGET"

cp "./nginx/nginx.${TARGET}.conf" "$NGINX_CONF"

# Graceful reload — does not drop active connections, ~50ms switchover
docker-compose exec nginx nginx -s reload

echo "Done. Traffic is now routed to: $TARGET"
echo "Verify: curl -s http://localhost/actuator/health | jq"
