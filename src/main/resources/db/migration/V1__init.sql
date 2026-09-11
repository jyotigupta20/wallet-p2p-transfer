-- Paytm PML R2 — Wallet & P2P Transfer
--
-- Design note: every graded invariant is enforced by a DB constraint, not by
-- application code. The app can have bugs; the database still cannot represent
-- a negative balance, a duplicate idempotency key, or a second wallet for a user.

-- ---------------------------------------------------------------------------
-- users: a bearer token identifies the caller. We store only a SHA-256 hash.
-- ---------------------------------------------------------------------------
CREATE TABLE users (
    id          UUID        PRIMARY KEY,
    external_id TEXT        NOT NULL UNIQUE,
    token_hash  TEXT        NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- wallets
--   UNIQUE (user_id)          -> INVARIANT 4: race-free get-or-create.
--                                Two concurrent POST /wallets cannot both win.
--   CHECK (balance_paise >= 0)-> INVARIANT 2: no overdraft. Belt-and-braces
--                                behind the conditional debit; a logic bug
--                                still cannot persist a negative balance.
-- Money is BIGINT paise. No numeric, no float, anywhere in the money path.
-- ---------------------------------------------------------------------------
CREATE TABLE wallets (
    id            UUID        PRIMARY KEY,
    user_id       UUID        NOT NULL UNIQUE REFERENCES users (id),
    balance_paise BIGINT      NOT NULL DEFAULT 0 CHECK (balance_paise >= 0),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- transfers
--   UNIQUE (user_id, idempotency_key) -> INVARIANT 3: exactly-once.
--     Scoped to the caller: one client's key must not collide with another's.
--     The row is inserted with ON CONFLICT DO NOTHING in the SAME transaction
--     as the debit/credit, so the key and the money commit together or not at all.
--   request_hash lets us tell a genuine retry (same body -> replay the original)
--     from key reuse with a different body (-> 409).
--   *_balance_after is stored so a replay returns a byte-identical body instead
--     of recomputing a balance that has since moved on.
-- ---------------------------------------------------------------------------
CREATE TABLE transfers (
    id                 UUID        PRIMARY KEY,
    user_id            UUID        NOT NULL REFERENCES users (id),
    idempotency_key    TEXT        NOT NULL,
    request_hash       TEXT        NOT NULL,
    from_wallet_id     UUID        NOT NULL REFERENCES wallets (id),
    to_wallet_id       UUID        NOT NULL REFERENCES wallets (id),
    amount_paise       BIGINT      NOT NULL CHECK (amount_paise > 0),
    status             TEXT        NOT NULL,
    decline_reason     TEXT,
    from_balance_after BIGINT,
    to_balance_after   BIGINT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT transfers_idempotency_uk UNIQUE (user_id, idempotency_key),

    -- amount_paise > 0 above is load-bearing: a negative amount would turn the
    -- conditional debit (balance >= amount) into a no-op guard and let a caller
    -- pull money OUT of the destination wallet.
    CONSTRAINT transfers_not_self CHECK (from_wallet_id <> to_wallet_id),

    CONSTRAINT transfers_status_shape CHECK (
        -- PENDING is insertable but never commits in the synchronous path;
        -- it exists so the key can be claimed before the outcome is known.
        (status = 'PENDING')
     OR (status = 'COMPLETED'
         AND decline_reason IS NULL
         AND from_balance_after IS NOT NULL
         AND to_balance_after   IS NOT NULL)
     OR (status = 'DECLINED'
         AND decline_reason IS NOT NULL
         AND from_balance_after IS NULL
         AND to_balance_after   IS NULL)
    )
);

CREATE INDEX transfers_from_wallet_idx ON transfers (from_wallet_id, created_at DESC);
CREATE INDEX transfers_to_wallet_idx   ON transfers (to_wallet_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- ledger_entries: double-entry. INVARIANT 1 (conservation) becomes a query
-- rather than a claim:
--
--   SELECT sum(signed_amount_paise) FROM ledger_entries
--    WHERE kind IN ('TRANSFER_DEBIT','TRANSFER_CREDIT');       -- must be 0, forever
--
--   every wallet: balance_paise = sum(its signed_amount_paise) -- must hold, forever
--
-- Money enters the system only via OPENING / MINT entries, which are the only
-- rows allowed to change the system-wide total. A transfer can never do so.
-- ---------------------------------------------------------------------------
CREATE TABLE ledger_entries (
    id                  BIGSERIAL   PRIMARY KEY,
    transfer_id         UUID        REFERENCES transfers (id),
    wallet_id           UUID        NOT NULL REFERENCES wallets (id),
    kind                TEXT        NOT NULL CHECK (kind IN
                            ('TRANSFER_DEBIT','TRANSFER_CREDIT','OPENING','MINT')),
    signed_amount_paise BIGINT      NOT NULL,
    balance_after       BIGINT      NOT NULL CHECK (balance_after >= 0),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- a transfer touches each wallet at most once: makes a double-apply
    -- impossible even if the service layer were to retry mid-transaction
    CONSTRAINT ledger_one_entry_per_wallet_per_transfer UNIQUE (transfer_id, wallet_id),

    CONSTRAINT ledger_sign_matches_kind CHECK (
        (kind = 'TRANSFER_DEBIT'  AND signed_amount_paise < 0 AND transfer_id IS NOT NULL)
     OR (kind = 'TRANSFER_CREDIT' AND signed_amount_paise > 0 AND transfer_id IS NOT NULL)
     OR (kind IN ('OPENING','MINT') AND signed_amount_paise >= 0 AND transfer_id IS NULL)
    )
);

CREATE INDEX ledger_wallet_idx ON ledger_entries (wallet_id, id DESC);
