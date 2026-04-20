CREATE TABLE IF NOT EXISTS users (
    id         BIGSERIAL PRIMARY KEY,
    first_name VARCHAR(255) NOT NULL,
    last_name  VARCHAR(255) NOT NULL,
    email      VARCHAR(255) NOT NULL UNIQUE,
    password   VARCHAR(255) NOT NULL,
    role       VARCHAR(50)  NOT NULL,
    created_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS accounts (
    id             BIGSERIAL PRIMARY KEY,
    account_number VARCHAR(255)    NOT NULL UNIQUE,
    account_type   VARCHAR(50)     NOT NULL,
    balance        NUMERIC(19, 4)  NOT NULL,
    status         VARCHAR(50)     NOT NULL,
    user_id        BIGINT          NOT NULL REFERENCES users (id),
    created_at     TIMESTAMP
);

CREATE TABLE IF NOT EXISTS transactions (
    id               BIGSERIAL PRIMARY KEY,
    transaction_type VARCHAR(50)    NOT NULL,
    amount           NUMERIC(19, 4) NOT NULL,
    description      VARCHAR(255),
    status           VARCHAR(50)    NOT NULL,
    source_account_id BIGINT        NOT NULL REFERENCES accounts (id),
    target_account_id BIGINT                 REFERENCES accounts (id),
    created_at       TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_source_account ON transactions (source_account_id);
CREATE INDEX IF NOT EXISTS idx_target_account ON transactions (target_account_id);

CREATE TABLE IF NOT EXISTS notifications (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users (id),
    message    VARCHAR(255) NOT NULL,
    type       VARCHAR(50)  NOT NULL,
    is_read    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP
);
