package io.paynexa.system;

import io.paynexa.contracts.PaymentEvent;
import io.paynexa.testsupport.PostgresTestDatabase;
import io.paynexa.testsupport.TestTokens;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole platform as separate OS processes: the real executable jars, real PostgreSQL roles created by the real
 * bootstrap script, a real Kafka broker, real HTTP. Nothing is mocked and no code is shared with the services.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FullSystemTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static final String PASSWORD = "correct horse battery staple";

    static EmbeddedKafkaBroker kafka;
    static Path logDir;
    static final Map<String, Service> SERVICES = new LinkedHashMap<>();

    static String adminToken;
    static String aliceToken;
    static String bobToken;
    static UUID aliceAccount;
    static UUID bobAccount;
    static UUID firstTransfer;

    record Service(String name, int port, Map<String, String> env, Process[] process) {
        String url(String path) {
            return "http://localhost:" + port + path;
        }
    }

    record Resp(int status, String raw, java.net.http.HttpHeaders headers) {
        JsonNode json() {
            return JSON.readTree(raw);
        }
    }

    // ------------------------------------------------------------------------------------------------ startup

    @BeforeAll
    static void startPlatform() throws Exception {
        logDir = Files.createTempDirectory("paynexa-system-");
        kafka = new EmbeddedKafkaKraftBroker(1, 3, PaymentEvent.TOPIC, PaymentEvent.TOPIC + PaymentEvent.DEAD_LETTER_SUFFIX);
        kafka.afterPropertiesSet();

        // PostgreSQL with the production role model, created by the production bootstrap script.
        var database = PostgresTestDatabase.createDatabase();
        var admin = PostgresTestDatabase.dataSourceFor(database, "postgres", null);
        var script = Files.readString(Path.of(System.getProperty("scripts.dir"), "01-roles-and-schemas.sql")).lines()
                .filter(l -> !l.startsWith("\\")).reduce("", (a, l) -> a + l + "\n")
                .replace(":'identity_migrator_pw'", "'im'").replace(":'identity_app_pw'", "'ia'")
                .replace(":'payments_migrator_pw'", "'pm'").replace(":'payments_app_pw'", "'pa'")
                .replace(":'notification_migrator_pw'", "'nm'").replace(":'notification_app_pw'", "'na'");
        try (var c = admin.getConnection(); var st = c.createStatement()) {
            st.execute(script);
        }
        String pg = "jdbc:postgresql://localhost:" + PostgresTestDatabase.port() + "/" + database + "?currentSchema=";

        var keys = new TestTokens();
        var common = new LinkedHashMap<String, String>();
        common.put("KAFKA_BOOTSTRAP_SERVERS", kafka.getBrokersAsString());
        common.put("PAYNEXA_JWT_PUBLIC_KEY", keys.publicKeyPem());

        register("identity-service", 0, merge(common, Map.of(
                "IDENTITY_DB_URL", pg + "identity", "IDENTITY_DB_USER", "identity_app", "IDENTITY_DB_PASSWORD", "ia",
                "IDENTITY_DB_MIGRATOR_USER", "identity_migrator", "IDENTITY_DB_MIGRATOR_PASSWORD", "im",
                "PAYNEXA_JWT_PRIVATE_KEY", keys.privateKeyPem(),
                "PAYNEXA_BOOTSTRAP_ADMIN_EMAIL", "ops.admin@example.com", "PAYNEXA_BOOTSTRAP_ADMIN_PASSWORD", "an-administrator-passphrase-1")));
        register("payments-service", 0, merge(common, Map.of(
                "PAYMENTS_DB_URL", pg + "payments", "PAYMENTS_DB_USER", "payments_app", "PAYMENTS_DB_PASSWORD", "pa",
                "PAYMENTS_DB_MIGRATOR_USER", "payments_migrator", "PAYMENTS_DB_MIGRATOR_PASSWORD", "pm",
                "PAYNEXA_SANDBOX_FUNDING", "true")));
        register("notification-service", 0, merge(common, Map.of(
                "NOTIFICATION_DB_URL", pg + "notifications", "NOTIFICATION_DB_USER", "notification_app", "NOTIFICATION_DB_PASSWORD", "na",
                "NOTIFICATION_DB_MIGRATOR_USER", "notification_migrator", "NOTIFICATION_DB_MIGRATOR_PASSWORD", "nm")));

        for (var s : SERVICES.values()) {
            launch(s);
        }
        for (var s : SERVICES.values()) {
            awaitReady(s);
        }
    }

    @AfterAll
    static void stopPlatform() {
        SERVICES.values().forEach(s -> stop(s, true));
        if (kafka != null) {
            kafka.destroy();
        }
        // Logs are kept on failure and in CI artifacts: print the location rather than deleting them.
        System.out.println("system test logs: " + logDir);
    }

    private static Map<String, String> merge(Map<String, String> a, Map<String, String> b) {
        var m = new LinkedHashMap<>(a);
        m.putAll(b);
        return m;
    }

    private static void register(String name, int ignored, Map<String, String> env) throws IOException {
        int port;
        try (var s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        var withPort = new LinkedHashMap<>(env);
        withPort.put("SERVER_PORT", Integer.toString(port));
        SERVICES.put(name, new Service(name, port, withPort, new Process[1]));
    }

    static void launch(Service s) throws IOException {
        var cmd = List.of(System.getProperty("java.bin"), "-Xmx320m", "-XX:+ExitOnOutOfMemoryError", "-jar", System.getProperty("jar." + s.name()));
        var pb = new ProcessBuilder(cmd);
        pb.environment().putAll(s.env());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logDir.resolve(s.name() + ".log").toFile()));
        s.process()[0] = pb.start();
    }

    static void stop(Service s, boolean force) {
        var p = s.process()[0];
        if (p != null && p.isAlive()) {
            if (force) {
                p.destroyForcibly();
            } else {
                p.destroy();
            }
            try {
                p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static void awaitReady(Service s) {
        try {
            Awaitility.await().atMost(Duration.ofSeconds(150)).pollInterval(Duration.ofSeconds(1)).until(() -> {
                if (!s.process()[0].isAlive()) {
                    throw new IllegalStateException(s.name() + " exited early with " + s.process()[0].exitValue() + "\n" + tail(s.name()));
                }
                try {
                    return call("GET", s.url("/actuator/health/readiness"), null, null, null).status() == 200;
                } catch (Exception e) {
                    return false;
                }
            });
        } catch (org.awaitility.core.ConditionTimeoutException e) {
            throw new IllegalStateException(s.name() + " did not become ready\n" + tail(s.name()), e);
        }
    }

    static String tail(String service) {
        try {
            var lines = Files.readAllLines(logDir.resolve(service + ".log"));
            return String.join("\n", lines.subList(Math.max(0, lines.size() - 40), lines.size()));
        } catch (IOException e) {
            return "(no log)";
        }
    }

    // --------------------------------------------------------------------------------------------------- HTTP

    static Resp call(String method, String url, String token, String idempotencyKey, Object body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20));
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (idempotencyKey != null) b.header("Idempotency-Key", idempotencyKey);
        if (body != null) b.header("Content-Type", "application/json");
        var publisher = body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body));
        var r = HTTP.send(b.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(r.statusCode(), r.body(), r.headers());
    }

    static Resp payments(String method, String path, String token, String key, Object body) throws Exception {
        return call(method, SERVICES.get("payments-service").url(path), token, key, body);
    }

    static Resp identity(String path, Object body) throws Exception {
        return call("POST", SERVICES.get("identity-service").url(path), null, null, body);
    }

    static Resp notifications(String token) throws Exception {
        return call("GET", SERVICES.get("notification-service").url("/notifications"), token, null, null);
    }

    static String newKey() {
        return "sys-" + UUID.randomUUID();
    }

    static Map<String, Object> creds(String email) {
        return Map.of("email", email, "password", PASSWORD);
    }

    // ------------------------------------------------------------------------------------------------ the flow

    @Test
    @Order(1)
    void usersRegisterAndTheBootstrapAdminCanLogIn() throws Exception {
        var a = identity("/auth/register", creds("alice.e2e@example.com"));
        var b = identity("/auth/register", creds("bob.e2e@example.com"));
        var ad = identity("/auth/login", Map.of("email", "ops.admin@example.com", "password", "an-administrator-passphrase-1"));
        assertThat(a.status()).as(a.raw()).isEqualTo(201);
        assertThat(b.status()).isEqualTo(201);
        assertThat(ad.status()).as(ad.raw()).isEqualTo(200);
        aliceToken = a.json().get("accessToken").asString();
        bobToken = b.json().get("accessToken").asString();
        adminToken = ad.json().get("accessToken").asString();
    }

    @Test
    @Order(2)
    void tokensIssuedByIdentityAreAcceptedByOtherServicesAndForgedOnesAreNot() throws Exception {
        assertThat(payments("GET", "/accounts", aliceToken, null, null).status()).isEqualTo(200);
        assertThat(payments("GET", "/accounts", null, null, null).status()).isEqualTo(401);
        assertThat(payments("GET", "/accounts", "x" + aliceToken, null, null).status()).isEqualTo(401);
        var foreign = TestTokens.token(TestTokens.generate(), UUID.randomUUID().toString(), List.of("ADMIN"), "paynexa-identity", "paynexa-api",
                java.time.Instant.now().plusSeconds(300));
        assertThat(payments("GET", "/admin/payments/pending-review", foreign, null, null).status()).isEqualTo(401);
        assertThat(payments("GET", "/admin/payments/pending-review", aliceToken, null, null).status()).isEqualTo(403);
        assertThat(payments("GET", "/admin/payments/pending-review", adminToken, null, null).status()).isEqualTo(200);
    }

    @Test
    @Order(3)
    void accountsAreOpenedAndFundedSynthetically() throws Exception {
        var a = payments("POST", "/accounts", aliceToken, null, Map.of("currency", "USD"));
        var b = payments("POST", "/accounts", bobToken, null, Map.of("currency", "USD"));
        assertThat(a.status()).isEqualTo(201);
        aliceAccount = UUID.fromString(a.json().get("id").asString());
        bobAccount = UUID.fromString(b.json().get("id").asString());

        var fund = payments("POST", "/admin/accounts/" + aliceAccount + "/fund", adminToken, newKey(), Map.of("amount", "200.00", "currency", "USD"));
        assertThat(fund.status()).as(fund.raw()).isEqualTo(201);
        assertThat(payments("GET", "/accounts/" + aliceAccount, aliceToken, null, null).json().get("balance").asString()).isEqualTo("200.00");
    }

    @Test
    @Order(4)
    void aTransferMovesMoneyExactlyAndRetriesAreHarmless() throws Exception {
        var key = newKey();
        var body = Map.of("fromAccountId", aliceAccount.toString(), "toAccountId", bobAccount.toString(), "amount", "25.50", "currency", "USD");
        var first = payments("POST", "/payments/transfers", aliceToken, key, body);
        var retry = payments("POST", "/payments/transfers", aliceToken, key, body);

        assertThat(first.status()).as(first.raw()).isEqualTo(201);
        assertThat(first.json().get("status").asString()).isEqualTo("COMPLETED");
        assertThat(retry.status()).isEqualTo(200);
        assertThat(retry.headers().firstValue("Idempotent-Replay")).hasValue("true");
        firstTransfer = UUID.fromString(first.json().get("id").asString());

        assertThat(payments("GET", "/accounts/" + aliceAccount, aliceToken, null, null).json().get("balance").asString()).isEqualTo("174.50");
        assertThat(payments("GET", "/accounts/" + bobAccount, bobToken, null, null).json().get("balance").asString()).isEqualTo("25.50");
    }

    @Test
    @Order(5)
    void bothPartiesAreNotifiedThroughKafkaExactlyOnce() throws Exception {
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(notifications(aliceToken).raw()).contains("PAYMENT_SENT");
            assertThat(notifications(bobToken).raw()).contains("PAYMENT_RECEIVED");
        });
        var sent = notifications(aliceToken).json();
        int count = 0;
        for (var n : sent) {
            if (firstTransfer.toString().equals(n.get("paymentId").asString()) && "PAYMENT_SENT".equals(n.get("kind").asString())) count++;
        }
        assertThat(count).as("duplicate request + at-least-once delivery must still yield one notification").isEqualTo(1);
    }

    @Test
    @Order(6)
    void usersCannotReachEachOthersDataAcrossServices() throws Exception {
        assertThat(payments("GET", "/accounts/" + aliceAccount, bobToken, null, null).status()).isEqualTo(404);
        assertThat(payments("GET", "/payments/" + firstTransfer, bobToken, null, null).status()).as("payee may view").isEqualTo(200);
        var stranger = identity("/auth/register", creds("mallory.e2e@example.com")).json().get("accessToken").asString();
        assertThat(payments("GET", "/payments/" + firstTransfer, stranger, null, null).status()).isEqualTo(404);
        assertThat(notifications(stranger).raw()).isEqualTo("[]");
        var theft = payments("POST", "/payments/transfers", stranger, newKey(),
                Map.of("fromAccountId", aliceAccount.toString(), "toAccountId", bobAccount.toString(), "amount", "1.00", "currency", "USD"));
        assertThat(theft.status()).isEqualTo(404);
    }

    @Test
    @Order(7)
    void aPayoutIsInFlightThenSettledOnlyAfterTheRailConfirms() throws Exception {
        var r = payments("POST", "/payments/payouts", aliceToken, newKey(),
                Map.of("fromAccountId", aliceAccount.toString(), "destination", "bank:e2e-ok", "amount", "40.00", "currency", "USD"));
        assertThat(r.status()).as(r.raw()).isEqualTo(201);
        assertThat(r.json().get("status").asString()).isIn("SUBMITTED", "SETTLED");   // worker may already have run
        var id = r.json().get("id").asString();

        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(payments("GET", "/payments/" + id, aliceToken, null, null).json().get("status").asString()).isEqualTo("SETTLED"));
        assertThat(payments("GET", "/accounts/" + aliceAccount, aliceToken, null, null).json().get("balance").asString()).isEqualTo("134.50");
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(notifications(aliceToken).raw()).contains("PAYOUT_SETTLED"));
    }

    @Test
    @Order(8)
    void aRailRejectionReturnsTheMoney() throws Exception {
        var r = payments("POST", "/payments/payouts", aliceToken, newKey(),
                Map.of("fromAccountId", aliceAccount.toString(), "destination", "bank:e2e-reject", "amount", "10.00", "currency", "USD"));
        var id = r.json().get("id").asString();
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(payments("GET", "/payments/" + id, aliceToken, null, null).json().get("status").asString()).isEqualTo("REVERSED"));
        assertThat(payments("GET", "/accounts/" + aliceAccount, aliceToken, null, null).json().get("balance").asString()).isEqualTo("134.50");
    }

    @Test
    @Order(9)
    void aLargePaymentIsHeldForReviewAndOnlyAnAdminCanReleaseIt() throws Exception {
        payments("POST", "/admin/accounts/" + aliceAccount + "/fund", adminToken, newKey(), Map.of("amount", "5000.00", "currency", "USD"));
        var r = payments("POST", "/payments/transfers", aliceToken, newKey(),
                Map.of("fromAccountId", aliceAccount.toString(), "toAccountId", bobAccount.toString(), "amount", "3000.00", "currency", "USD"));
        assertThat(r.json().get("status").asString()).isEqualTo("PENDING_REVIEW");
        var id = r.json().get("id").asString();
        assertThat(payments("GET", "/accounts/" + bobAccount, bobToken, null, null).json().get("balance").asString()).isEqualTo("25.50");

        assertThat(payments("POST", "/admin/payments/" + id + "/approve", aliceToken, null, null).status()).isEqualTo(403);
        var approved = payments("POST", "/admin/payments/" + id + "/approve", adminToken, null, null);
        assertThat(approved.status()).as(approved.raw()).isEqualTo(200);
        assertThat(approved.json().get("status").asString()).isEqualTo("COMPLETED");
        assertThat(payments("GET", "/accounts/" + bobAccount, bobToken, null, null).json().get("balance").asString()).isEqualTo("3025.50");
    }

    @Test
    @Order(10)
    void aDownedNotificationServiceLosesNothingAndCatchesUpOnRestart() throws Exception {
        var notif = SERVICES.get("notification-service");
        stop(notif, true);

        var r = payments("POST", "/payments/transfers", aliceToken, newKey(),
                Map.of("fromAccountId", aliceAccount.toString(), "toAccountId", bobAccount.toString(), "amount", "7.77", "currency", "USD"));
        assertThat(r.status()).as("payments must not depend on the consumer being up").isEqualTo(201);
        var id = r.json().get("id").asString();

        launch(notif);
        awaitReady(notif);
        Awaitility.await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
            var list = notifications(bobToken).json();
            boolean found = false;
            for (var n : list) if (id.equals(n.get("paymentId").asString())) found = true;
            assertThat(found).as("notification for the payment made while the consumer was down").isTrue();
        });
    }

    @Test
    @Order(11)
    void aKilledPaymentsServiceRestartsWithEveryBalanceIntactAndKeepsWorking() throws Exception {
        var before = payments("GET", "/accounts/" + aliceAccount, aliceToken, null, null).json().get("balance").asString();
        var pay = SERVICES.get("payments-service");
        stop(pay, true);                                                              // kill -9: no graceful shutdown
        assertThat(pay.process()[0].isAlive()).isFalse();

        launch(pay);
        awaitReady(pay);
        assertThat(payments("GET", "/accounts/" + aliceAccount, aliceToken, null, null).json().get("balance").asString()).isEqualTo(before);
        var again = payments("POST", "/payments/transfers", aliceToken, newKey(),
                Map.of("fromAccountId", aliceAccount.toString(), "toAccountId", bobAccount.toString(), "amount", "1.00", "currency", "USD"));
        assertThat(again.json().get("status").asString()).isEqualTo("COMPLETED");
    }

    @Test
    @Order(12)
    void reconciliationFindsTheLiveSystemConsistent() throws Exception {
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            var r = payments("POST", "/admin/reconciliation/run", adminToken, null, null);
            assertThat(r.status()).isEqualTo(200);
            assertThat(r.json().get("clean").asBoolean()).as(r.raw()).isTrue();
        });
    }

    @Test
    @Order(13)
    void refreshTokensRotateAndAReplayedOneKillsTheSession() throws Exception {
        var login = identity("/auth/login", creds("alice.e2e@example.com")).json();
        var r1 = login.get("refreshToken").asString();
        var rotated = identity("/auth/refresh", Map.of("refreshToken", r1));
        assertThat(rotated.status()).isEqualTo(200);
        assertThat(identity("/auth/refresh", Map.of("refreshToken", r1)).status()).as("replayed").isEqualTo(401);
        assertThat(identity("/auth/refresh", Map.of("refreshToken", rotated.json().get("refreshToken").asString())).status())
                .as("successor revoked by reuse detection").isEqualTo(401);
    }

    @Test
    @Order(14)
    void noSecretsOrCredentialsAppearInAnyServiceLog() throws Exception {
        var all = new ArrayList<String>();
        try (var files = Files.list(logDir)) {
            for (File f : files.map(Path::toFile).toList()) {
                all.add(Files.readString(f.toPath()));
            }
        }
        var logs = String.join("\n", all);
        assertThat(logs).isNotBlank()
                .doesNotContain(PASSWORD).doesNotContain("an-administrator-passphrase-1")
                .doesNotContain(aliceToken).doesNotContain(adminToken)
                .doesNotContain("BEGIN PRIVATE KEY").doesNotContain("bank:e2e-ok");
        assertThat(logs).as("structured JSON logs with correlation ids").contains("\"correlationId\"");
    }

    @Test
    @Order(15)
    void metricsAndHealthAreAvailableToOperators() throws Exception {
        var prom = payments("GET", "/actuator/prometheus", adminToken, null, null);
        assertThat(prom.status()).isEqualTo(200);
        assertThat(prom.raw()).contains("paynexa_payments_total{").contains("paynexa_outbox_pending")
                .contains("paynexa_rail_breaker_state").contains("paynexa_payouts_rail_total");
        assertThat(payments("GET", "/actuator/prometheus", aliceToken, null, null).status()).isEqualTo(403);
        for (var s : SERVICES.values()) {
            assertThat(call("GET", s.url("/actuator/health/liveness"), null, null, null).status()).isEqualTo(200);
        }
    }
}
