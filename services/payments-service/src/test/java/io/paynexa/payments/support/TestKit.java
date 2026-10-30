package io.paynexa.payments.support;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.paynexa.payments.config.PaymentsProperties;
import io.paynexa.payments.domain.Account;
import io.paynexa.payments.domain.AccountType;
import io.paynexa.payments.domain.Money;
import io.paynexa.payments.persistence.AccountRepository;
import io.paynexa.payments.persistence.HistoryRepository;
import io.paynexa.payments.persistence.IdempotencyRepository;
import io.paynexa.payments.persistence.LedgerRepository;
import io.paynexa.payments.persistence.OutboxRepository;
import io.paynexa.payments.persistence.PaymentRepository;
import io.paynexa.payments.persistence.RiskRepository;
import io.paynexa.payments.risk.RiskEngine;
import io.paynexa.payments.service.AccountService;
import io.paynexa.payments.service.Actor;
import io.paynexa.payments.service.LifecycleService;
import io.paynexa.payments.service.PaymentMetrics;
import io.paynexa.payments.service.PaymentRecorder;
import io.paynexa.payments.service.PaymentService;
import io.paynexa.testsupport.PostgresTestDatabase;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Real services, real PostgreSQL, real connection pool. No mocks on the money path. */
public final class TestKit implements AutoCloseable {

    public final HikariDataSource ds;
    public final JdbcClient jdbc;
    public final MutableClock clock = new MutableClock();
    public final PaymentsProperties props;
    public final AccountRepository accounts;
    public final PaymentRepository payments;
    public final LedgerRepository ledger;
    public final OutboxRepository outbox;
    public final HistoryRepository history;
    public final IdempotencyRepository idempotency;
    public final RiskRepository riskRepo;
    public final PaymentService paymentService;
    public final LifecycleService lifecycle;
    public final AccountService accountService;
    public final TransactionTemplate tx;
    public final PaymentRecorder recorder;
    public final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    public final JsonMapper json = JsonMapper.builder().build();
    private final AtomicInteger userCounter = new AtomicInteger();

    public static PaymentsProperties props(PaymentsProperties.Limits limits) {
        return new PaymentsProperties(
                new PaymentsProperties.Idempotency(Duration.ofDays(7)),
                new PaymentsProperties.Risk(limits, Map.of()),
                new PaymentsProperties.Outbox(Duration.ofMillis(50), 100, 3, Duration.ofMillis(10), Duration.ofSeconds(1), Duration.ofSeconds(2), false),
                new PaymentsProperties.Rail(Duration.ofMillis(50), 10, Duration.ofSeconds(30), Duration.ofMillis(10), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofHours(1), 5, false),
                new PaymentsProperties.Sandbox(true),
                new PaymentsProperties.Reconciliation(Duration.ofMinutes(5), Duration.ofMinutes(15), false));
    }

    /** Generous limits so ordinary tests are not affected by risk rules; risk tests pass their own. */
    public static PaymentsProperties.Limits relaxedLimits() {
        return new PaymentsProperties.Limits(new BigDecimal("1000000"), new BigDecimal("1000000"),
                new BigDecimal("10000000"), new BigDecimal("1000000"), 100_000, Duration.ofMinutes(10));
    }

    public static TestKit create() {
        return new TestKit(props(relaxedLimits()));
    }

    public static TestKit create(PaymentsProperties.Limits limits) {
        return new TestKit(props(limits));
    }

    private TestKit(PaymentsProperties props) {
        this.props = props;
        var raw = PostgresTestDatabase.migrated("payments", "classpath:db/migration");
        var cfg = new HikariConfig();
        cfg.setDataSource(raw);
        cfg.setMaximumPoolSize(40);
        cfg.setAutoCommit(true);
        this.ds = new HikariDataSource(cfg);
        this.jdbc = JdbcClient.create(ds);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        this.accounts = new AccountRepository(jdbc, clock);
        this.payments = new PaymentRepository(jdbc, clock);
        this.ledger = new LedgerRepository(jdbc, clock);
        this.outbox = new OutboxRepository(jdbc, clock);
        this.history = new HistoryRepository(jdbc, clock);
        this.idempotency = new IdempotencyRepository(jdbc, clock, props);
        this.riskRepo = new RiskRepository(jdbc, clock);
        this.recorder = new PaymentRecorder(payments, accounts, history, outbox, json, clock);
        var metrics = new PaymentMetrics(meters);
        this.paymentService = new PaymentService(tx, accounts, payments, ledger, idempotency, new RiskEngine(props),
                riskRepo, recorder, history, props, clock, metrics);
        this.lifecycle = new LifecycleService(tx, payments, accounts, ledger, recorder, history, metrics);
        this.accountService = new AccountService(accounts, history, tx);
    }

    public Actor newUser() {
        userCounter.incrementAndGet();
        return Actor.user(UUID.randomUUID(), false);
    }

    public Actor newAdmin() {
        return Actor.user(UUID.randomUUID(), true);
    }

    public Account openAccount(Actor owner, String currency) {
        return accountService.open(owner, currency);
    }

    /** Opens a USD account for a fresh user and funds it with {@code major} dollars via the sandbox funding path. */
    public Account fundedAccount(Actor owner, String currency, String major) {
        var acc = openAccount(owner, currency);
        if (!"0".equals(major)) {
            paymentService.fund(newAdmin(), "fund-" + UUID.randomUUID(), acc.id(), Money.parse(major, currency));
        }
        return accounts.find(acc.id()).orElseThrow();
    }

    public long balance(UUID accountId) {
        return accounts.find(accountId).orElseThrow().balanceMinor();
    }

    public long count(String sql, Object... params) {
        var spec = jdbc.sql(sql);
        return spec.params(params).query(Long.class).single();
    }

    /** Σ over all accounts of (credits - debits) for a currency; must always be exactly zero. */
    public long ledgerSum(String currency) {
        return count("SELECT COALESCE(SUM(CASE direction WHEN 'C' THEN amount_minor ELSE -amount_minor END), 0) FROM postings WHERE currency = ?", currency);
    }

    public long systemBalance(AccountType type, String currency) {
        return ledger.ledgerBalance(accounts.systemAccount(type, currency).id());
    }

    @Override
    public void close() {
        ds.close();
    }
}
