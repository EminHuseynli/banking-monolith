-- Database separation: one schema per microservice (same PostgreSQL server).
-- This is a pragmatic trade-off: full DB isolation would require separate PostgreSQL
-- instances but adds operational overhead. Documented as explicit trade-off in thesis.

CREATE SCHEMA IF NOT EXISTS user_schema;
CREATE SCHEMA IF NOT EXISTS account_schema;
CREATE SCHEMA IF NOT EXISTS transaction_schema;
CREATE SCHEMA IF NOT EXISTS notification_schema;
