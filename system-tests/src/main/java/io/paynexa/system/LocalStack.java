package io.paynexa.system;

import io.paynexa.contracts.PaymentEvent;
import io.paynexa.testsupport.PostgresTestDatabase;
import io.paynexa.testsupport.TestTokens;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runs the whole platform on this machine without Docker, for hands-on checking. SANDBOX ONLY: throw-away keys and
 * passwords generated per run, synthetic funding enabled, simulated payout rail. Stops everything on Ctrl-C.
 */
public final class LocalStack {

    private static final int IDENTITY_PORT = 8081;
    private static final int PAYMENTS_PORT = 8082;
    private static final int NOTIFICATIONS_PORT = 8083;

    public static void main(String[] args) throws Exception {
        var work = Files.createTempDirectory("paynexa-local-");
        var kafka = new EmbeddedKafkaKraftBroker(1, 3, PaymentEvent.TOPIC, PaymentEvent.TOPIC + PaymentEvent.DEAD_LETTER_SUFFIX);
        kafka.afterPropertiesSet();

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
        var pg = "jdbc:postgresql://localhost:" + PostgresTestDatabase.port() + "/" + database + "?currentSchema=";

        var keys = new TestTokens();
        var privateKey = Files.writeString(work.resolve("jwt-private.pem"), keys.privateKeyPem());
        var publicKey = Files.writeString(work.resolve("jwt-public.pem"), keys.publicKeyPem());
        var adminPassword = randomPassword();

        var common = new LinkedHashMap<String, String>();
        common.put("KAFKA_BOOTSTRAP_SERVERS", kafka.getBrokersAsString());
        common.put("PAYNEXA_JWT_PUBLIC_KEY_FILE", publicKey.toString());

        var identityEnv = new LinkedHashMap<>(common);
        putAll(identityEnv,"SERVER_PORT", "" + IDENTITY_PORT,
                "IDENTITY_DB_URL", pg + "identity", "IDENTITY_DB_USER", "identity_app", "IDENTITY_DB_PASSWORD", "ia",
                "IDENTITY_DB_MIGRATOR_USER", "identity_migrator", "IDENTITY_DB_MIGRATOR_PASSWORD", "im",
                "PAYNEXA_JWT_PRIVATE_KEY_FILE", privateKey.toString(),
                "PAYNEXA_BOOTSTRAP_ADMIN_EMAIL", "admin@paynexa.example", "PAYNEXA_BOOTSTRAP_ADMIN_PASSWORD", adminPassword,
                // One machine is one client address in a sandbox, so the strict production login limits would throttle
                // a single person clicking around. Production defaults stay 10 requests / 0.2 per second per address.
                "IDENTITY_AUTH_BURST", "300", "IDENTITY_AUTH_PER_SECOND", "20");
        var paymentsEnv = new LinkedHashMap<>(common);
        putAll(paymentsEnv,"SERVER_PORT", "" + PAYMENTS_PORT,
                "PAYMENTS_DB_URL", pg + "payments", "PAYMENTS_DB_USER", "payments_app", "PAYMENTS_DB_PASSWORD", "pa",
                "PAYMENTS_DB_MIGRATOR_USER", "payments_migrator", "PAYMENTS_DB_MIGRATOR_PASSWORD", "pm",
                "PAYNEXA_SANDBOX_FUNDING", "true");
        var notificationEnv = new LinkedHashMap<>(common);
        putAll(notificationEnv,"SERVER_PORT", "" + NOTIFICATIONS_PORT,
                "NOTIFICATION_DB_URL", pg + "notifications", "NOTIFICATION_DB_USER", "notification_app", "NOTIFICATION_DB_PASSWORD", "na",
                "NOTIFICATION_DB_MIGRATOR_USER", "notification_migrator", "NOTIFICATION_DB_MIGRATOR_PASSWORD", "nm");

        var processes = new java.util.ArrayList<Process>();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            processes.forEach(Process::destroy);
            kafka.destroy();
            System.out.println("\nstopped. logs were in " + work);
        }));

        processes.add(launch("identity-service", identityEnv, work));
        processes.add(launch("payments-service", paymentsEnv, work));
        processes.add(launch("notification-service", notificationEnv, work));

        System.out.println("starting services (first start takes about 20-40 seconds)...");
        awaitReady("identity", IDENTITY_PORT);
        awaitReady("payments", PAYMENTS_PORT);
        awaitReady("notifications", NOTIFICATIONS_PORT);

        System.out.println("""

                ==================== PayNexa is running (SANDBOX, synthetic money) ====================
                identity-service      http://localhost:%d
                payments-service      http://localhost:%d
                notification-service  http://localhost:%d

                admin login   email: admin@paynexa.example
                              password: %s
                logs          %s
                Press Ctrl-C to stop everything. Data is discarded on exit.
                =======================================================================================
                """.formatted(IDENTITY_PORT, PAYMENTS_PORT, NOTIFICATIONS_PORT, adminPassword, work));
        Thread.currentThread().join();
    }

    /** Map.of tops out at 10 pairs; environments here are longer, so use alternating key/value varargs instead. */
    private static void putAll(Map<String, String> target, String... keyValues) {
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            target.put(keyValues[i], keyValues[i + 1]);
        }
    }

    private static Process launch(String name, Map<String, String> env, Path work) throws Exception {
        var pb = new ProcessBuilder(System.getProperty("java.bin"), "-Xmx320m", "-jar", System.getProperty("jar." + name));
        pb.environment().putAll(env);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(work.resolve(name + ".log").toFile()));
        return pb.start();
    }

    private static void awaitReady(String label, int port) throws Exception {
        var http = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health/readiness")).timeout(Duration.ofSeconds(3)).build();
        for (int i = 0; i < 150; i++) {
            try {
                if (http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
                    System.out.println("  " + label + " ready");
                    return;
                }
            } catch (Exception ignored) {
                // not up yet
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException(label + " did not become ready; see its log");
    }

    private static String randomPassword() {
        var chars = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        var rnd = new SecureRandom();
        var sb = new StringBuilder("sandbox-");
        for (int i = 0; i < 16; i++) {
            sb.append(chars.charAt(rnd.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
