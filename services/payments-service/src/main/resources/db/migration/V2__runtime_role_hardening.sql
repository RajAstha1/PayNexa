-- Defence in depth for the ledger. The append-only triggers already refuse changes; this removes the privileges too,
-- so a compromised or buggy runtime role cannot even attempt them, and cannot disable the triggers (that needs table
-- ownership, which only the migrator role has).
--
-- The runtime role is `payments_app` unless the session sets `payments.runtime_role`. Where that role does not exist
-- (e.g. unit tests that connect as a superuser) this migration is a deliberate no-op.
DO $$
DECLARE
    runtime_role TEXT := coalesce(current_setting('payments.runtime_role', true), 'payments_app');
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = runtime_role) THEN
        -- Immutable records: insert and read only.
        EXECUTE format('REVOKE UPDATE, DELETE, TRUNCATE ON payments.journal_entries, payments.postings, payments.payment_status_history, payments.audit_log FROM %I', runtime_role);
        -- Accounts: balance_minor is maintained only by the SECURITY DEFINER trigger from the postings. A column-level
        -- revoke does nothing while table-level UPDATE is held, so drop the table privilege and re-grant per column.
        EXECUTE format('REVOKE UPDATE, DELETE, TRUNCATE ON payments.accounts FROM %I', runtime_role);
        EXECUTE format('GRANT UPDATE (status) ON payments.accounts TO %I', runtime_role);
        EXECUTE format('REVOKE DELETE, TRUNCATE ON payments.payments, payments.risk_decisions, payments.reconciliation_runs, payments.reconciliation_findings FROM %I', runtime_role);
        EXECUTE format('REVOKE TRUNCATE ON ALL TABLES IN SCHEMA payments FROM %I', runtime_role);
    END IF;
END $$;
