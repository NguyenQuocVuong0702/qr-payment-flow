CREATE SEQUENCE IF NOT EXISTS merchant_order_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE IF NOT EXISTS orders (
    order_id VARCHAR(32) PRIMARY KEY,
    merchant_id VARCHAR(32) NOT NULL,
    amount BIGINT NOT NULL,
    description VARCHAR(19) NOT NULL,
    status VARCHAR(24) NOT NULL,
    txn_ref VARCHAR(32),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    paid_at TIMESTAMP(6) WITH TIME ZONE,
    version BIGINT NOT NULL,
    CONSTRAINT uk_orders_txn_ref UNIQUE (txn_ref),
    CONSTRAINT ck_orders_amount CHECK (amount BETWEEN 1000 AND 500000000),
    CONSTRAINT ck_orders_description CHECK (
        CHAR_LENGTH(TRIM(description)) > 0 AND NOT REGEXP_LIKE(description, '[^A-Za-z0-9 ]')),
    CONSTRAINT ck_orders_status CHECK (
        status IN ('CREATED', 'WAITING_PAYMENT', 'PAID', 'PAYMENT_FAILED', 'EXPIRED'))
);

CREATE INDEX IF NOT EXISTS ix_order_reconciliation ON orders(merchant_id,status,paid_at);
