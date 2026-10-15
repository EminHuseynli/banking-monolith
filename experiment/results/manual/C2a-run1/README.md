# Manual run: C2a, run 1 (2026-10-15)

Code: tag `c2-transaction-split`. account-service and transaction-service each have their own
schema. Money moves through the services in the service era, then all traffic is rolled back
to the monolith. Times are UTC; the screenshots show local time (UTC+4).

Windows Terminal panes: top left `scripts/health-loop.ps1`, top right Git Bash
(`switch-blue-green.sh`), bottom left `scripts/client.ps1` (`Call`, `Login`), bottom right psql.

| Step | UTC |
|---|---|
| monolith started | 08:12:01 |
| seed through the monolith | 08:18:37 – 08:22:09 |
| forward copy, switch to blue | ~08:33, 08:37:12 |
| service-era writes (X, Y, Z, then money) | 08:43:35 – 08:45:38 |
| **rollback to the monolith** | **09:00:33** (nginx reload) |
| verify through the monolith | 09:09:11 – 09:11:19 |

## Screenshots

### 1. Monolith, seed (`images/1-monolith-seed/`)

| File | Shows |
|---|---|
| `1-stack-up-baseline-error1-warn13.png` | All 8 containers running, health UP. Baseline log counts: 1 ERROR (a Spring Security WARN text that contains the word "ERROR"), 13 WARN. |
| `2-register-users.png` | u1, u2, u3 registered (user ids 1–3). |
| `3-login.png` | Three logins, 200. |
| `4-open-accounts-1-to-6.png` | CHECKING + SAVINGS per user: ids 1–6, ACC-000001 … ACC-000006. |
| `5-seed-deposits-withdrawal-transfer.png` | Transactions 1–5: deposits 1000 → 1, 500 → 3, 250 → 5; withdrawal 100 from 1; transfer 200 from 1 to 3. |
| `6-seed-balances-and-history.png` | Balances 700 / 700 / 250, others 0. History of account 1: transactions 5, 4, 1. |

### 2. Cut-over and service era (`images/2-cutover-and-service-era/`)

| File | Shows |
|---|---|
| `1-forward-copy.png` | `sql/forward-copy.sql -v c2=1`: 6 accounts and 5 transactions copied, sequences set to 6 and 5. |
| `2-forward-copy-lossless-4-panes.png` | Monolith view (bottom left) and the copied rows in `account_schema` / `transaction_schema` (bottom right) are identical. The copy loses nothing; this stands in for a no-write control condition. |
| `3-switch-to-blue-served-by-services.png` | `switch-blue-green.sh blue`. `$Last[0].firstName` is empty, so the account list comes from account-service; balances unchanged. |
| `4-service-era-open-X-Y-close-Z.png` | u1 opens X (id 7, ACC-000007), u2 opens Y (id 8, ACC-000008), u3 closes Z (id 6). |
| `5-service-era-money-moves.png` | Through transaction-service → Feign → account-service: transaction 6 deposit 300 → 1, 7 withdrawal 100 from 3, 8 transfer 150 from 1 to 5, 9 deposit 50 → X. All 201. |
| `6-service-era-balances.png` | What users see at the end of the service era: 1 = 850, 3 = 600, 5 = 400, X = 50; Z CLOSED. |
| `7-service-era-history.png` | History of account 1 now includes service-era transactions 8 and 6. |
| `8-service-era-notifications-none-delivered.png` | u1's notifications (served by the monolith) end with the seed transfer. None of the service-era events arrived: transaction-service publishes them to RabbitMQ and nothing consumes the queue. |
| `9-health-and-rabbitmq-queue-before-rollback.png` | Taken 08:59 UTC, just before the rollback: all containers running, health UP, 1 ERROR / 13 WARN (baseline), 5 notification events unconsumed. The same counts held after the rollback: the monolith wrote no log line at all after 09:00:33 (`logs-monolith-after-rollback.txt` is empty) and the queue still held 5 at the end (`db-final.txt`). |

### 3. Rollback and verification (`images/3-rollback-and-verify/`)

| File | Shows |
|---|---|
| `1-rollback-switch.png` | `switch-blue-green.sh monolith` at 09:00:33 UTC, exit without error. |
| `2-db-at-rollback-balances-sums-service-only-tx.png` | Ground truth at the rollback. Balances differ on accounts 1, 3, 5; Z ACTIVE in `public`, CLOSED in `account_schema`; X and Y missing from `public`. Sums: public 1650, account_schema 1900. Transactions 6–9 exist only in `transaction_schema`. |
| `3-verify-user-views-served-by-monolith.png` | After the rollback users see the pre-service-era state: 700 / 700 / 250, no X, no Y, Z ACTIVE. `firstName` = U3, so the monolith answers. |
| `4-verify-history-X-balance-closed-Z-deposit.png` | History of account 1 back to 5, 4, 1 (0 of 2 service-era transactions visible). X's balance: 404. Deposit into closed Z: 201, and it gets **transaction id 6**, an id already used by a different service-era transaction. |
| `5-verify-id7-reuse-misdirected-deposit-overdraw.png` | The monolith opens id 7 / ACC-000007 for u2 (X's id and number). u1 deposits 5 to id 7: it lands in u2's account. u2 withdraws all 700 the monolith shows from account 3, which held 600 at the end of the service era: 201, **100 more than the account held**. |

### 4. Signals after the rollback (`images/4-signals/`)

| File | Shows |
|---|---|
| `1-prometheus-targets.png` | Prometheus target `banking-monolith` UP (page reloaded). |
| `2-prometheus-requests-by-status.png` | Monolith requests by status: 200, 201, one 404 (X's balance), one 403, no 5xx. The 403 is `POST /get_message_for_webview` from a browser extension (Grammarly) hitting `localhost` at 09:09:54; nginx logs it as 499, `uri="UNKNOWN"` in `prometheus-4xx-detail.json`. Not part of the experiment. |

## Files

| File | Content |
|---|---|
| `db-final.txt` | All four tables after verification, balances side by side, transaction ids used on both sides for different transactions (6, 7, 8), RabbitMQ queue depth (5). |
| `logs-all.txt` | All container logs. |
| `logs-monolith-after-rollback.txt` | Monolith log since 09:00:33: empty. |
| `nginx-after-rollback.conf` | `nginx -T`: only `location /` → monolith. |
| `prometheus-*.json` | Targets, requests by status, 4xx detail. |
| `mem.txt` | Container memory at the end (about 1.8 GB total). |
