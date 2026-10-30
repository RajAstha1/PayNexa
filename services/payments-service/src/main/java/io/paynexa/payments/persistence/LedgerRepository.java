package io.paynexa.payments.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Writes the double-entry journal. Only INSERTs exist here: posted entries are immutable (the database enforces
 * it too). Account balances are updated by a database trigger from the postings, never by this code.
 */
@Repository
public class LedgerRepository {

    public enum EntryKind { FUNDING, TRANSFER, PAYOUT_SUBMIT, PAYOUT_SETTLE, PAYOUT_RETURN, REVERSAL }

    public enum Direction {
        DEBIT("D"), CREDIT("C");
        final String code;
        Direction(String code) { this.code = code; }
    }

    public record Leg(UUID accountId, Direction direction, long amountMinor, String currency) {
        public static Leg debit(UUID account, long minor, String currency) {
            return new Leg(account, Direction.DEBIT, minor, currency);
        }

        public static Leg credit(UUID account, long minor, String currency) {
            return new Leg(account, Direction.CREDIT, minor, currency);
        }

        public Leg opposite() {
            return new Leg(accountId, direction == Direction.DEBIT ? Direction.CREDIT : Direction.DEBIT, amountMinor, currency);
        }
    }

    public record Entry(UUID id, UUID paymentId, EntryKind kind, List<Leg> legs) {}

    private final JdbcClient jdbc;
    private final Clock clock;

    public LedgerRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public UUID post(UUID paymentId, EntryKind kind, UUID reversesEntryId, List<Leg> legs) {
        var entryId = UUID.randomUUID();
        jdbc.sql("INSERT INTO journal_entries (id, payment_id, kind, reverses_entry_id, created_at) VALUES (:id, :payment, :kind, :reverses, :now)")
                .param("id", entryId).param("payment", paymentId).param("kind", kind.name())
                .param("reverses", reversesEntryId).param("now", Times.toDb(clock.instant())).update();
        for (var leg : legs) {
            jdbc.sql("INSERT INTO postings (entry_id, account_id, direction, amount_minor, currency) VALUES (:entry, :account, :dir, :amount, :currency)")
                    .param("entry", entryId).param("account", leg.accountId()).param("dir", leg.direction().code)
                    .param("amount", leg.amountMinor()).param("currency", leg.currency()).update();
        }
        return entryId;
    }

    public Optional<Entry> findEntry(UUID paymentId, EntryKind kind) {
        return jdbc.sql("SELECT id FROM journal_entries WHERE payment_id = :p AND kind = :k")
                .param("p", paymentId).param("k", kind.name()).query(UUID.class).optional()
                .map(id -> new Entry(id, paymentId, kind, legsOf(id)));
    }

    public List<Leg> legsOf(UUID entryId) {
        return jdbc.sql("SELECT account_id, direction, amount_minor, currency FROM postings WHERE entry_id = :e ORDER BY id")
                .param("e", entryId)
                .query((rs, n) -> new Leg(rs.getObject("account_id", UUID.class),
                        "D".equals(rs.getString("direction")) ? Direction.DEBIT : Direction.CREDIT,
                        rs.getLong("amount_minor"), rs.getString("currency")))
                .list();
    }

    /** Ledger-derived balance (credits minus debits), independent of the stored projection. */
    public long ledgerBalance(UUID accountId) {
        return jdbc.sql("SELECT COALESCE(SUM(CASE direction WHEN 'C' THEN amount_minor ELSE -amount_minor END), 0) FROM postings WHERE account_id = :a")
                .param("a", accountId).query(Long.class).single();
    }
}
