package io.paynexa.payments.persistence;

import io.paynexa.payments.domain.Account;
import io.paynexa.payments.domain.AccountStatus;
import io.paynexa.payments.domain.AccountType;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AccountRepository {

    private static final String COLUMNS = "id, type, owner_user_id, currency, status, balance_minor";

    private static final RowMapper<Account> MAPPER = (rs, n) -> new Account(
            rs.getObject("id", UUID.class),
            AccountType.valueOf(rs.getString("type")),
            rs.getObject("owner_user_id", UUID.class),
            rs.getString("currency"),
            AccountStatus.valueOf(rs.getString("status")),
            rs.getLong("balance_minor"));

    private final JdbcClient jdbc;
    private final Clock clock;

    public AccountRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Locks the given accounts {@code FOR UPDATE} in one statement ordered by id. Every transaction that needs
     * several accounts goes through this method, so they all acquire row locks in the same order and cannot deadlock
     * with each other. Must be called inside a transaction.
     */
    public Map<UUID, Account> lockInOrder(Collection<UUID> ids) {
        List<UUID> distinct = ids.stream().distinct().toList();
        var result = new LinkedHashMap<UUID, Account>();
        jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE id IN (:ids) ORDER BY id FOR UPDATE")
                .param("ids", distinct)
                .query(MAPPER).list()
                .forEach(a -> result.put(a.id(), a));
        return result;
    }

    public Optional<Account> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE id = :id")
                .param("id", id).query(MAPPER).optional();
    }

    public List<Account> findByOwner(UUID ownerUserId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE owner_user_id = :owner ORDER BY currency")
                .param("owner", ownerUserId).query(MAPPER).list();
    }

    /** Opens the user's account for the currency, or returns the existing one. Safe under concurrency. */
    public Account openCustomerAccount(UUID ownerUserId, String currency) {
        jdbc.sql("""
                INSERT INTO accounts (id, type, owner_user_id, currency, created_at)
                VALUES (:id, 'CUSTOMER', :owner, :currency, :now)
                ON CONFLICT (owner_user_id, currency) WHERE type = 'CUSTOMER' DO NOTHING""")
                .param("id", UUID.randomUUID()).param("owner", ownerUserId).param("currency", currency)
                .param("now", Times.toDb(clock.instant())).update();
        return jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE owner_user_id = :owner AND currency = :currency AND type = 'CUSTOMER'")
                .param("owner", ownerUserId).param("currency", currency).query(MAPPER).single();
    }

    /** Returns the per-currency system account of the given type, creating it on first use. */
    public Account systemAccount(AccountType type, String currency) {
        jdbc.sql("""
                INSERT INTO accounts (id, type, currency, created_at) VALUES (:id, :type, :currency, :now)
                ON CONFLICT (type, currency) WHERE type <> 'CUSTOMER' DO NOTHING""")
                .param("id", UUID.randomUUID()).param("type", type.name()).param("currency", currency)
                .param("now", Times.toDb(clock.instant())).update();
        return jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE type = :type AND currency = :currency")
                .param("type", type.name()).param("currency", currency).query(MAPPER).single();
    }

    public boolean setStatus(UUID id, AccountStatus status) {
        return jdbc.sql("UPDATE accounts SET status = :status WHERE id = :id AND type = 'CUSTOMER'")
                .param("status", status.name()).param("id", id).update() == 1;
    }
}
