CREATE TABLE users (
    id              UUID PRIMARY KEY,
    email           VARCHAR(254) NOT NULL,
    password_hash   VARCHAR(100) NOT NULL,
    roles           TEXT[]       NOT NULL DEFAULT ARRAY['USER'],
    status          VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    failed_attempts INTEGER      NOT NULL DEFAULT 0,
    locked_until    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT email_is_normalised CHECK (email = lower(btrim(email))),
    CONSTRAINT roles_known CHECK (roles <@ ARRAY['USER', 'ADMIN']::text[] AND cardinality(roles) > 0),
    CONSTRAINT hash_is_bcrypt CHECK (password_hash LIKE '$2%')
);
CREATE UNIQUE INDEX uq_users_email ON users (email);

-- Refresh tokens are random 256-bit values; only their SHA-256 is stored. A family is one login session; reuse of a
-- rotated token revokes the whole family.
CREATE TABLE refresh_tokens (
    id          UUID PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES users (id),
    family_id   UUID        NOT NULL,
    token_hash  CHAR(64)    NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    revoke_reason VARCHAR(30)
);
CREATE UNIQUE INDEX uq_refresh_token_hash ON refresh_tokens (token_hash);
CREATE INDEX idx_refresh_family ON refresh_tokens (family_id);
CREATE INDEX idx_refresh_user ON refresh_tokens (user_id);

CREATE TABLE audit_log (
    id             BIGSERIAL PRIMARY KEY,
    at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    action         VARCHAR(40) NOT NULL,
    user_id        UUID,
    outcome        VARCHAR(10) NOT NULL,
    detail         VARCHAR(200),
    correlation_id VARCHAR(64)
);
CREATE INDEX idx_identity_audit_user ON audit_log (user_id, id);

CREATE FUNCTION forbid_audit_mutation() RETURNS trigger LANGUAGE plpgsql
    SET search_path = identity, pg_temp AS $$
BEGIN
    RAISE EXCEPTION '% on % is not allowed: this table is append-only', TG_OP, TG_TABLE_NAME USING ERRCODE = '55000';
END $$;
CREATE TRIGGER audit_append_only BEFORE UPDATE OR DELETE ON audit_log FOR EACH ROW EXECUTE FUNCTION forbid_audit_mutation();
CREATE TRIGGER audit_no_truncate BEFORE TRUNCATE ON audit_log FOR EACH STATEMENT EXECUTE FUNCTION forbid_audit_mutation();
