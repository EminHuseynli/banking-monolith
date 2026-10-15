# Manual run: C2b, run 1 (2026-10-15)

Code: tag `c2-transaction-split`. account-service and transaction-service each have their own
schema, and money moves through the services in the service era, as in C2a. The difference is the
rollback: **only `/api/transactions` goes back to the monolith** (`switch-blue-green.sh partial`,
`nginx.partial.conf`). `/api/accounts` stays on account-service, so two systems answer at once:
the account view reads `account_schema`, money moves write `public`.

Times are UTC; the screenshots show local time (UTC+4).

Windows Terminal panes: top left `scripts/health-loop.ps1`, top right Git Bash
(`switch-blue-green.sh`), bottom left `scripts/client.ps1` (`Call`, `Login`), bottom right psql.

| Step | UTC |
|---|---|
| monolith started | 16:51:06 |
| seed through the monolith | 16:57:50 – 17:02:12 |
| forward copy, switch to blue | ~17:12, 17:14:13 |
| service-era writes (X, Y, Z, then money) | 17:19:03 – 17:21:24 |
| **partial rollback** (`/api/transactions` → monolith) | **17:30:29** (nginx reload) |
| verify | 17:35:40 – 17:36:34 |
| final state screenshot | 17:48 |

## Screenshots

### 1. Monolith, seed (`images/1-monolith-seed/`)

| File | Shows |
|---|---|
| `1-stack-up-baseline-error1-warn13.png` | 16:54: all 8 containers running, health UP. Baseline log counts 1 ERROR (a Spring Security WARN text that contains the word "ERROR") / 13 WARN, same as C2a. |
| `2-prometheus-target-up.png` | Prometheus target `banking-monolith` UP at the start. |
| `3-register-users.png` | u1, u2, u3 registered (user ids 1–3). |
| `4-login.png` | Three logins, 200. |
| `5-open-accounts-1-to-6.png` | CHECKING + SAVINGS per user: ids 1–6, ACC-000001 … ACC-000006. |
| `6-seed-deposits-withdrawal-transfer.png` | Transactions 1–5: deposits 1000 → 1, 500 → 3, 250 → 5; withdrawal 100 from 1; transfer 200 from 1 to 3. |
| `7-seed-balances-served-by-monolith-and-history.png` | Balances 700 / 700 / 250; `$Last[0].firstName` = U3, so the monolith answers. History of account 1: transactions 5, 4, 1. |

### 2. Cut-over and service era (`images/2-cutover-and-service-era/`)

| File | Shows |
|---|---|
| `1-forward-copy-lossless-4-panes.png` | 17:12: `sql/forward-copy.sql -v c2=1` (top right) copies 6 accounts and 5 transactions. The copied rows (bottom right) equal the monolith view (bottom left): the copy loses nothing. |
| `2-switch-to-blue-served-by-services.png` | `switch-blue-green.sh blue` at 17:14:13. `firstName` empty, so the account list comes from account-service; balances and history unchanged. |
| `3-service-era-open-X-Y-close-Z.png` | u1 opens X (id 7, ACC-000007), u2 opens Y (id 8, ACC-000008), u3 closes Z (id 6). |
| `4-service-era-money-moves.png` | Through transaction-service → Feign → account-service: transaction 6 deposit 300 → 1, 7 withdrawal 100 from 3, 8 transfer 150 from 1 to 5, 9 deposit 50 → X. All 201. |
| `5-service-era-balances-and-history.png` | End of the service era: 1 = 850, 3 = 600, 5 = 400, X = 50; Z CLOSED. History of account 1 includes service-era transactions 8 and 6. |
| `6-service-era-notifications-none-delivered.png` | u1's notifications (served by the monolith) end with the seed transfer; no service-era event arrived. |
| `7-health-and-rabbitmq-queue-before-rollback.png` | 17:27, before the rollback: health UP, 1 ERROR / 13 WARN, 5 notification events unconsumed. The 5 events come from the 4 service-era transactions (a transfer notifies sender and receiver). The psql pane still shows the forward-copy state. |

### 3. Partial rollback and verification (`images/3-partial-rollback-and-verify/`)

| File | Shows |
|---|---|
| `1-partial-rollback-nginx-db-at-rollback-user-views.png` | Top right: `switch-blue-green.sh partial` at 17:30:29; `nginx -T` has only `location /api/accounts` → api-gateway and `location /` → monolith. Bottom right: database at the rollback, same as C2a (balances differ on 1, 3, 5; Z ACTIVE vs CLOSED; X, Y only in `account_schema`; sums 1650 vs 1900; transactions 6–9 only in `transaction_schema`). Bottom left: users still see the service-era account view (850 / 600 / 400 / 50, Z CLOSED). |
| `2-verify-history-and-deposit-invisible-in-account-view.png` | `firstName` empty: accounts still come from account-service. History of account 1, now from the monolith: 5, 4, 1 only, while the account view shows 850. u1 deposits 20 into account 1 through the monolith: 201, **transaction id 6** (reused), but the account view still shows **850**, not 870. |
| `3-verify-X-404-closed-Z-deposit-overdraw.png` | Deposit into X, which u1 sees in the account list: **404** (the monolith does not know X). Deposit into Z, CLOSED in the account view: 201 (transaction 7). u2 withdraws 650 from account 3, which the view shows at 600: **201** (transaction 8); the view still shows 600. |
| `4-final-state-sides-keep-diverging.png` | 17:48, 18 min after the rollback. Health UP, 1 ERROR / 13 WARN, queue 5. Bottom right: `public` 720 / 50 / 250 / Z 10, `account_schema` 850 / 600 / 400 / Z 0. Both sides go on accepting writes and drift further apart (sum gap 250 at the rollback, 870 at the end, see `db-final.txt`). |

### 4. Signals (`images/4-signals/`)

| File | Shows |
|---|---|
| `1-prometheus-targets.png` | Prometheus target `banking-monolith` UP after the rollback (page reloaded). |
| `2-prometheus-requests-by-status.png` | Monolith requests by status: 200, 201, one 404 (deposit into X). No 403, no 5xx. |

## Files

| File | Content |
|---|---|
| `db-final.txt` | All four tables after verification, balances side by side, sums (1030 vs 1900), transaction ids used on both sides for different transactions (6, 7, 8), RabbitMQ queue depth (5). |
| `logs-all.txt` | All container logs. |
| `logs-{monolith,account-service,transaction-service,api-gateway}-after-rollback.txt` | Logs since 17:30:29: all four empty, although account-service kept serving the account view. |
| `nginx-after-rollback.conf` | `nginx -T`: `/api/accounts` → api-gateway, everything else → monolith. |
| `prometheus-*.json` | Targets, requests by status, 4xx/5xx detail (one 404 on `/api/transactions/deposit`). |
| `mem.txt` | Container memory at the end. |
