CREATE TABLE IF NOT EXISTS wallet_user (
    user_id VARCHAR(32) PRIMARY KEY,
    full_name VARCHAR(100) NOT NULL,
    account_no VARCHAR(32) NOT NULL,
    pin_hash VARCHAR(60) NOT NULL,
    failed_pin_attempts INTEGER NOT NULL DEFAULT 0 CHECK (failed_pin_attempts BETWEEN 0 AND 3),
    pin_locked_until TIMESTAMP(6) WITH TIME ZONE,
    version BIGINT NOT NULL
);
