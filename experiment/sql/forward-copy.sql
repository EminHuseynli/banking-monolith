-- Forward data migration at cut-over (C1 runs only): copy the monolith's accounts
-- into account-service's schema, then advance the identity sequence so the service
-- does not hand out ids that already exist. This is the forward data move of a real migration.
-- Run with no traffic on /api/accounts.

BEGIN;

INSERT INTO account_schema.accounts (id, account_number, account_type, balance, status, user_id, created_at)
SELECT id, account_number, account_type, balance, status, user_id, created_at
FROM public.accounts;

SELECT setval(pg_get_serial_sequence('account_schema.accounts', 'id'),
              (SELECT COALESCE(MAX(id), 1) FROM account_schema.accounts));

COMMIT;

SELECT 'public' AS schema, count(*) AS accounts FROM public.accounts
UNION ALL
SELECT 'account_schema', count(*) FROM account_schema.accounts;
