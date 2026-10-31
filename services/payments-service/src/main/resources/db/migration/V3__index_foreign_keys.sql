-- Foreign keys without a leading index make parent-row deletes/updates scan the child table under lock, and make the
-- joins used by reconciliation and the API slow. Found by MigrationsTest.
CREATE INDEX idx_idempotency_payment      ON idempotency_keys (payment_id);
CREATE INDEX idx_journal_reverses_entry   ON journal_entries (reverses_entry_id);
CREATE INDEX idx_recon_findings_run       ON reconciliation_findings (run_id);
