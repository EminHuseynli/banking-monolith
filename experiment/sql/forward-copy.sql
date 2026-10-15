-- Forward data migration at cut-over (C1 and C2 runs): copy the monolith's rows
-- into each extracted service's schema, then advance the identity sequences so the
-- services do not hand out ids that already exist. This is the forward data move of a
-- real migration. Run with no traffic on the extracted routes.
-- C1: accounts only. C2: accounts and transactions (run.sh sets -v c2=1).

BEGIN;

INSERT INTO account_schema.accounts (id, account_number, account_type, balance, status, user_id, created_at)
SELECT id, account_number, account_type, balance, status, user_id, created_at
FROM public.accounts;

SELECT setval(pg_get_serial_sequence('account_schema.accounts', 'id'),
              (SELECT COALESCE(MAX(id), 1) FROM account_schema.accounts));

\if :{?c2}
INSERT INTO transaction_schema.transactions (id, transaction_type, amount, description, status,
                                             source_account_id, target_account_id, created_at)
SELECT id, transaction_type, amount, description, status, source_account_id, target_account_id, created_at
FROM public.transactions;

SELECT setval(pg_get_serial_sequence('transaction_schema.transactions', 'id'),
              (SELECT COALESCE(MAX(id), 1) FROM transaction_schema.transactions));
\endif

COMMIT;

SELECT 'public' AS schema, count(*) AS accounts FROM public.accounts
UNION ALL
SELECT 'account_schema', count(*) FROM account_schema.accounts;

\if :{?c2}
SELECT 'public' AS schema, count(*) AS transactions FROM public.transactions
UNION ALL
SELECT 'transaction_schema', count(*) FROM transaction_schema.transactions;
\endif
