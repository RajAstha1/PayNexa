-- Inbox: one row per processed event id, written in the same transaction as the effect. A redelivered event finds its
-- row and is skipped, which turns Kafka's at-least-once delivery into effectively-once processing here.
CREATE TABLE inbox (
    event_id     UUID PRIMARY KEY,
    event_type   VARCHAR(60) NOT NULL,
    payment_id   UUID        NOT NULL,
    sequence     INTEGER     NOT NULL,
    outcome      VARCHAR(10) NOT NULL CHECK (outcome IN ('APPLIED', 'STALE')),
    received_at  TIMESTAMPTZ NOT NULL
);

-- Highest sequence applied per payment, to discard events that arrive out of order.
CREATE TABLE payment_progress (
    payment_id     UUID PRIMARY KEY,
    last_sequence  INTEGER     NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL
);

CREATE TABLE notifications (
    id          UUID PRIMARY KEY,
    user_id     UUID        NOT NULL,
    payment_id  UUID        NOT NULL,
    event_id    UUID        NOT NULL,
    kind        VARCHAR(30) NOT NULL,
    message     VARCHAR(300) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    read_at     TIMESTAMPTZ,
    UNIQUE (event_id, user_id)
);
CREATE INDEX idx_notifications_user ON notifications (user_id, created_at DESC);

CREATE TABLE dead_letters (
    id           BIGSERIAL PRIMARY KEY,
    topic        VARCHAR(200) NOT NULL,
    record_key   VARCHAR(100),
    payload      TEXT         NOT NULL,
    error        VARCHAR(500),
    status       VARCHAR(10)  NOT NULL DEFAULT 'NEW' CHECK (status IN ('NEW', 'REPLAYED')),
    created_at   TIMESTAMPTZ  NOT NULL,
    replayed_at  TIMESTAMPTZ
);
CREATE INDEX idx_dead_letters_status ON dead_letters (status, id);
