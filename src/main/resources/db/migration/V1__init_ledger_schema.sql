-- ============================================================================
-- V1__init_ledger_schema.sql
-- Double-Entry Wallet Ledger Initial Schema
-- All monetary values stored as BIGINT (smallest currency unit, e.g., paise / cents)
-- ============================================================================

-- 1. Users Table
CREATE TABLE users (
    id UUID PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 2. Wallets Table
CREATE TABLE wallets (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL UNIQUE,
    currency VARCHAR(3) NOT NULL DEFAULT 'INR',
    balance BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_wallets_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT chk_wallets_balance CHECK (balance >= 0)
);

-- 3. Transactions Table
CREATE TABLE transactions (
    id UUID PRIMARY KEY,
    reference_id UUID NOT NULL UNIQUE,
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    amount BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    source_wallet_id UUID,
    destination_wallet_id UUID,
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_tx_source_wallet FOREIGN KEY (source_wallet_id) REFERENCES wallets(id),
    CONSTRAINT fk_tx_destination_wallet FOREIGN KEY (destination_wallet_id) REFERENCES wallets(id),
    CONSTRAINT chk_transactions_amount CHECK (amount > 0)
);

-- 4. Ledger Entries Table (Double-Entry Bookkeeping)
CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY,
    transaction_id UUID NOT NULL,
    wallet_id UUID NOT NULL,
    entry_type VARCHAR(10) NOT NULL,
    amount BIGINT NOT NULL,
    balance_after BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ledger_transaction FOREIGN KEY (transaction_id) REFERENCES transactions(id),
    CONSTRAINT fk_ledger_wallet FOREIGN KEY (wallet_id) REFERENCES wallets(id),
    CONSTRAINT chk_ledger_entries_amount CHECK (amount > 0),
    CONSTRAINT uq_ledger_tx_wallet_type UNIQUE (transaction_id, wallet_id, entry_type)
);

-- Indexes for performance
CREATE INDEX idx_wallets_user_id ON wallets(user_id);
CREATE INDEX idx_transactions_source_wallet ON transactions(source_wallet_id);
CREATE INDEX idx_transactions_destination_wallet ON transactions(destination_wallet_id);
CREATE INDEX idx_transactions_reference_id ON transactions(reference_id);
CREATE INDEX idx_ledger_entries_wallet_created_at ON ledger_entries (wallet_id, created_at DESC);
CREATE INDEX idx_ledger_entries_transaction_id ON ledger_entries (transaction_id);
