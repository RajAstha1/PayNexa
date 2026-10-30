-- payments-service schema. Amounts are BIGINT minor units (cents). No floating point anywhere.
-- Convention: balance = SUM(credits) - SUM(debits) for every account.

CREATE TABLE accounts (
    id             UUID PRIMARY KEY,
    type           VARCHAR(20) NOT NULL
        CHECK (type IN ('CUSTOMER', 'FUNDING_SOURCE', 'PAYOUT_CLEARING', 'EXTERNAL_SETTLED')),
    owner_user_id  UUID,
    currency       CHAR(3)     NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    status         VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'FROZEN')),
    balance_minor  BIGINT      NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT customer_has_owner CHECK ((type = 'CUSTOMER') = (owner_user_id IS NOT NULL)),
    -- Database-level guarantee that a customer balance can never be overdrawn, whatever the application does.
    CONSTRAINT customer_balance_non_negative CHECK (type <> 'CUSTOMER' OR balance_minor >= 0),
    -- System accounts are shared by every payment. Keeping a stored balance on them would make all payouts
    -- serialize on one hot row, so they carry no projection (their balance is derived from postings).
    CONSTRAINT system_account_has_no_projection CHECK (type = 'CUSTOMER' OR balance_minor = 0)
);
CREATE UNIQUE INDEX uq_customer_account_per_currency ON accounts (owner_user_id, currency) WHERE type = 'CUSTOMER';
CREATE UNIQUE INDEX uq_system_account_per_currency   ON accounts (type, currency)           WHERE type <> 'CUSTOMER';

CREATE TABLE payments (
    id                    UUID PRIMARY KEY,
    kind                  VARCHAR(10) NOT NULL CHECK (kind IN ('TRANSFER', 'PAYOUT', 'FUNDING')),
    status                VARCHAR(20) NOT NULL
        CHECK (status IN ('PENDING_REVIEW', 'COMPLETED', 'SUBMITTED', 'SETTLED', 'FAILED', 'REJECTED', 'REVERSED')),
    initiator_user_id     UUID        NOT NULL,
    payer_account_id      UUID        NOT NULL REFERENCES accounts (id),
    payee_account_id      UUID REFERENCES accounts (id),
    destination_ref       VARCHAR(140),
    amount_minor          BIGINT      NOT NULL CHECK (amount_minor > 0 AND amount_minor <= 1000000000000),
    currency              CHAR(3)     NOT NULL,
    reference             VARCHAR(140),
    failure_reason        VARCHAR(200),
    sequence              INTEGER     NOT NULL DEFAULT 1,
    rail_reference        VARCHAR(100),
    rail_attempts         INTEGER     NOT NULL DEFAULT 0,
    rail_next_attempt_at  TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT payment_shape CHECK (
        (kind IN ('TRANSFER', 'FUNDING') AND payee_account_id IS NOT NULL AND destination_ref IS NULL)
        OR (kind = 'PAYOUT' AND payee_account_id IS NULL AND destination_ref IS NOT NULL)),
    CONSTRAINT payer_differs_from_payee CHECK (payee_account_id IS NULL OR payee_account_id <> payer_account_id),
    CONSTRAINT status_matches_kind CHECK (
        (kind = 'PAYOUT' AND status IN ('PENDING_REVIEW', 'SUBMITTED', 'SETTLED', 'FAILED', 'REJECTED', 'REVERSED'))
        OR (kind <> 'PAYOUT' AND status IN ('PENDING_REVIEW', 'COMPLETED', 'FAILED', 'REJECTED', 'REVERSED')))
);
CREATE INDEX idx_payments_payer   ON payments (payer_account_id, created_at DESC);
CREATE INDEX idx_payments_payee   ON payments (payee_account_id, created_at DESC);
CREATE INDEX idx_payments_review  ON payments (created_at) WHERE status = 'PENDING_REVIEW';
CREATE INDEX idx_payments_payouts ON payments (rail_next_attempt_at) WHERE status = 'SUBMITTED';

CREATE TABLE payment_status_history (
    id              BIGSERIAL PRIMARY KEY,
    payment_id      UUID        NOT NULL REFERENCES payments (id),
    from_status     VARCHAR(20),
    to_status       VARCHAR(20) NOT NULL,
    actor           VARCHAR(80) NOT NULL,
    reason          VARCHAR(200),
    correlation_id  VARCHAR(64),
    at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_status_history_payment ON payment_status_history (payment_id, id);

-- Idempotency scope is (user, key). A row is reusable once expired. payment_id FK is deferred so the key can be
-- claimed before the payment row is inserted in the same transaction.
CREATE TABLE idempotency_keys (
    user_id      UUID        NOT NULL,
    idem_key     VARCHAR(64) NOT NULL,
    fingerprint  CHAR(64)    NOT NULL,
    payment_id   UUID        NOT NULL REFERENCES payments (id) DEFERRABLE INITIALLY DEFERRED,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, idem_key)
);
CREATE INDEX idx_idempotency_expiry ON idempotency_keys (expires_at);

CREATE TABLE journal_entries (
    id                UUID PRIMARY KEY,
    payment_id        UUID        NOT NULL REFERENCES payments (id),
    kind              VARCHAR(20) NOT NULL
        CHECK (kind IN ('FUNDING', 'TRANSFER', 'PAYOUT_SUBMIT', 'PAYOUT_SETTLE', 'PAYOUT_RETURN', 'REVERSAL')),
    reverses_entry_id UUID REFERENCES journal_entries (id),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Each financial effect can happen at most once per payment, even if code is retried or raced.
    CONSTRAINT uq_one_entry_per_kind UNIQUE (payment_id, kind),
    CONSTRAINT reversal_references CHECK ((kind = 'REVERSAL') = (reverses_entry_id IS NOT NULL))
);

CREATE TABLE postings (
    id            BIGSERIAL PRIMARY KEY,
    entry_id      UUID        NOT NULL REFERENCES journal_entries (id),
    account_id    UUID        NOT NULL REFERENCES accounts (id),
    direction     CHAR(1)     NOT NULL CHECK (direction IN ('D', 'C')),
    amount_minor  BIGINT      NOT NULL CHECK (amount_minor > 0 AND amount_minor <= 1000000000000),
    currency      CHAR(3)     NOT NULL
);
CREATE INDEX idx_postings_entry   ON postings (entry_id);
CREATE INDEX idx_postings_account ON postings (account_id, id);

CREATE TABLE risk_decisions (
    id          BIGSERIAL PRIMARY KEY,
    payment_id  UUID        NOT NULL REFERENCES payments (id),
    decision    VARCHAR(10) NOT NULL CHECK (decision IN ('ALLOW', 'REVIEW', 'BLOCK')),
    hits        JSONB       NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_risk_decisions_payment ON risk_decisions (payment_id);

CREATE TABLE outbox_events (
    id               BIGSERIAL PRIMARY KEY,
    event_id         UUID         NOT NULL UNIQUE,
    aggregate_id     UUID         NOT NULL,
    event_type       VARCHAR(60)  NOT NULL,
    sequence         INTEGER      NOT NULL,
    payload          JSONB        NOT NULL,
    status           VARCHAR(10)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PUBLISHED', 'DEAD')),
    attempts         INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_error       VARCHAR(500),
    correlation_id   VARCHAR(64),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at     TIMESTAMPTZ
);
CREATE INDEX idx_outbox_due ON outbox_events (id) WHERE status = 'PENDING';

CREATE TABLE audit_log (
    id              BIGSERIAL PRIMARY KEY,
    at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor           VARCHAR(80) NOT NULL,
    action          VARCHAR(60) NOT NULL,
    target_type     VARCHAR(30) NOT NULL,
    target_id       VARCHAR(64) NOT NULL,
    details         JSONB       NOT NULL DEFAULT '{}'::jsonb,
    correlation_id  VARCHAR(64)
);
CREATE INDEX idx_audit_target ON audit_log (target_type, target_id, id);

CREATE TABLE reconciliation_runs (
    id            UUID PRIMARY KEY,
    started_at    TIMESTAMPTZ NOT NULL,
    finished_at   TIMESTAMPTZ NOT NULL,
    trigger       VARCHAR(20) NOT NULL,
    finding_count INTEGER     NOT NULL
);
CREATE TABLE reconciliation_findings (
    id        BIGSERIAL PRIMARY KEY,
    run_id    UUID         NOT NULL REFERENCES reconciliation_runs (id),
    check_name VARCHAR(60) NOT NULL,
    severity  VARCHAR(10)  NOT NULL CHECK (severity IN ('CRITICAL', 'WARNING')),
    subject   VARCHAR(80)  NOT NULL,
    detail    VARCHAR(500) NOT NULL
);

-- ---------------------------------------------------------------------------------------------------------------
-- Ledger invariants enforced by the database
-- ---------------------------------------------------------------------------------------------------------------

-- 1. Append-only: posted entries, postings, history and audit rows can never be changed or removed.
CREATE FUNCTION forbid_mutation() RETURNS trigger LANGUAGE plpgsql
    SET search_path = payments, pg_temp AS $$
BEGIN
    RAISE EXCEPTION '% on % is not allowed: this table is append-only', TG_OP, TG_TABLE_NAME
        USING ERRCODE = '55000';
END $$;

CREATE TRIGGER journal_entries_append_only BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER postings_append_only BEFORE UPDATE OR DELETE ON postings
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER status_history_append_only BEFORE UPDATE OR DELETE ON payment_status_history
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER audit_log_append_only BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER journal_entries_no_truncate BEFORE TRUNCATE ON journal_entries
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER postings_no_truncate BEFORE TRUNCATE ON postings
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER audit_log_no_truncate BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();

-- 2. A posting must be in the same currency as its account.
CREATE FUNCTION check_posting_currency() RETURNS trigger LANGUAGE plpgsql
    SET search_path = payments, pg_temp AS $$
DECLARE
    account_currency CHAR(3);
BEGIN
    SELECT currency INTO account_currency FROM payments.accounts WHERE id = NEW.account_id;
    IF account_currency IS DISTINCT FROM NEW.currency THEN
        RAISE EXCEPTION 'posting currency % does not match account % currency %',
            NEW.currency, NEW.account_id, account_currency USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER postings_currency BEFORE INSERT ON postings
    FOR EACH ROW EXECUTE FUNCTION check_posting_currency();

-- 3. The customer balance projection is maintained by the database from the postings themselves, so it cannot
--    drift from the ledger through any code path. SECURITY DEFINER lets the runtime role (which may not UPDATE balance_minor
--    directly) cause this single, controlled update.
CREATE FUNCTION apply_posting_to_balance() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = payments, pg_temp AS $$
BEGIN
    UPDATE payments.accounts
       SET balance_minor = balance_minor + CASE NEW.direction WHEN 'C' THEN NEW.amount_minor ELSE -NEW.amount_minor END
     WHERE id = NEW.account_id AND type = 'CUSTOMER';
    RETURN NEW;
END $$;
CREATE TRIGGER postings_apply_balance AFTER INSERT ON postings
    FOR EACH ROW EXECUTE FUNCTION apply_posting_to_balance();

-- 4. Every journal entry must have at least two postings and balance (debits = credits) per currency.
--    Deferred: checked at COMMIT, after all postings of the entry exist.
CREATE FUNCTION check_entry_balanced() RETURNS trigger LANGUAGE plpgsql
    SET search_path = payments, pg_temp AS $$
DECLARE
    target_entry UUID;
    posting_count INTEGER;
    unbalanced_currency CHAR(3);
BEGIN
    IF TG_TABLE_NAME = 'journal_entries' THEN
        target_entry := NEW.id;
    ELSE
        target_entry := NEW.entry_id;
    END IF;

    SELECT count(*) INTO posting_count FROM payments.postings WHERE entry_id = target_entry;
    IF posting_count < 2 THEN
        RAISE EXCEPTION 'journal entry % has % postings, at least 2 required', target_entry, posting_count
            USING ERRCODE = '23514';
    END IF;

    SELECT currency INTO unbalanced_currency
      FROM payments.postings WHERE entry_id = target_entry
     GROUP BY currency
    HAVING SUM(CASE direction WHEN 'D' THEN amount_minor ELSE -amount_minor END) <> 0
     LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'journal entry % is not balanced in %', target_entry, unbalanced_currency
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER journal_entries_balanced AFTER INSERT ON journal_entries
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_entry_balanced();
CREATE CONSTRAINT TRIGGER postings_balanced AFTER INSERT ON postings
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_entry_balanced();
