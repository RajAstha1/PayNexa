package io.paynexa.identity;

import io.paynexa.platform.JwtVerification;
import io.paynexa.testsupport.PostgresTestDatabase;
import io.paynexa.testsupport.TestTokens;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "paynexa.identity.bcrypt-cost=10",
        "paynexa.identity.lockout.max-failed-attempts=3",
        "paynexa.identity.lockout.duration=2s",
        "paynexa.identity.rate-limit.auth-burst=100000",
        "spring.flyway.user=postgres", "spring.flyway.password=postgres",
        "spring.datasource.username=postgres", "spring.datasource.password=postgres"})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class AuthFlowTest {

    static final TestTokens KEYS = new TestTokens();
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String GOOD_PASSWORD = "correct horse battery staple";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> PostgresTestDatabase.migratedJdbcUrl("identity", "classpath:db/migration"));
        r.add("paynexa.identity.jwt.private-key", KEYS::privateKeyPem);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    private static String email() {
        return "user-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    private JsonNode json(MvcResult r) throws Exception {
        return JSON.readTree(r.getResponse().getContentAsString());
    }

    private MvcResult send(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private String creds(String email, String password) {
        return "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password);
    }

    private JsonNode register(String email) throws Exception {
        var r = send("/auth/register", creds(email, GOOD_PASSWORD));
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        return json(r);
    }

    // ------------------------------------------------------------------------------------------ registration

    @Test
    void registrationReturnsAVerifiableRs256TokenForTheNewUser() throws Exception {
        var email = email();
        var tokens = register(email);

        var jwt = JwtVerification.decoder(KEYS.publicKeyPem()).decode(tokens.get("accessToken").asString());
        var storedId = jdbc.sql("SELECT id FROM users WHERE email = :e").param("e", email).query(UUID.class).single();
        assertThat(jwt.getSubject()).isEqualTo(storedId.toString());
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("paynexa-identity");
        assertThat(jwt.getAudience()).containsExactly("paynexa-api");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("USER");
        assertThat(jwt.getExpiresAt()).isAfter(jwt.getIssuedAt()).isBefore(jwt.getIssuedAt().plusSeconds(3600));
        assertThat(tokens.get("tokenType").asString()).isEqualTo("Bearer");
    }

    @Test
    void passwordsAreStoredOnlyAsSaltedBcryptHashes() throws Exception {
        var a = email();
        var b = email();
        register(a);
        register(b);
        var hashA = jdbc.sql("SELECT password_hash FROM users WHERE email = :e").param("e", a).query(String.class).single();
        var hashB = jdbc.sql("SELECT password_hash FROM users WHERE email = :e").param("e", b).query(String.class).single();
        assertThat(hashA).startsWith("$2").doesNotContain(GOOD_PASSWORD).hasSize(60);
        assertThat(hashA).as("same password, different salt").isNotEqualTo(hashB);
    }

    @Test
    void refreshTokensAreStoredOnlyAsHashes() throws Exception {
        var tokens = register(email());
        var raw = tokens.get("refreshToken").asString();
        assertThat(jdbc.sql("SELECT count(*) FROM refresh_tokens WHERE token_hash = :t").param("t", raw).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM refresh_tokens WHERE token_hash = :t").param("t", AuthFlowTest.sha256(raw)).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void emailsAreCaseAndWhitespaceInsensitiveSoDuplicatesCannotSneakIn() throws Exception {
        var e = email();
        register(e);
        var again = send("/auth/register", creds("  " + e.toUpperCase() + " ", GOOD_PASSWORD));
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(again).get("code").asString()).isEqualTo("EMAIL_TAKEN");
        assertThat(send("/auth/login", creds(e.toUpperCase(), GOOD_PASSWORD)).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void weakOrMalformedCredentialsAreRejectedBeforeAnythingIsStored() throws Exception {
        long before = jdbc.sql("SELECT count(*) FROM users").query(Long.class).single();
        for (var bad : new String[] {"short", "aaaaaaaaaaaaaaaa", "password12345", "x".repeat(73), ""}) {
            var r = send("/auth/register", creds(email(), bad));
            assertThat(r.getResponse().getStatus()).as(bad).isEqualTo(400);
        }
        var local = "carolinesmith";
        assertThat(send("/auth/register", creds(local + "@example.com", "my-" + local + "-secret!")).getResponse().getStatus())
                .as("password containing the email local part").isEqualTo(400);
        for (var badEmail : new String[] {"not-an-email", "a@b", "@example.com", "x y@example.com", "a@@example.com", "<script>@example.com"}) {
            assertThat(send("/auth/register", creds(badEmail, GOOD_PASSWORD)).getResponse().getStatus()).as(badEmail).isEqualTo(400);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM users").query(Long.class).single()).isEqualTo(before);
    }

    @Test
    void concurrentRegistrationOfTheSameEmailCreatesExactlyOneAccount() throws Exception {
        var e = email();
        var pool = Executors.newFixedThreadPool(8);
        var go = new CountDownLatch(1);
        var results = new ArrayList<java.util.concurrent.Future<Integer>>();
        for (int i = 0; i < 8; i++) {
            Callable<Integer> c = () -> {
                go.await();
                return send("/auth/register", creds(e, GOOD_PASSWORD)).getResponse().getStatus();
            };
            results.add(pool.submit(c));
        }
        go.countDown();
        int created = 0;
        int conflict = 0;
        for (var f : results) {
            int status = f.get(60, TimeUnit.SECONDS);
            if (status == 201) created++;
            if (status == 409) conflict++;
        }
        pool.shutdown();
        assertThat(created).isEqualTo(1);
        assertThat(conflict).isEqualTo(7);
        assertThat(jdbc.sql("SELECT count(*) FROM users WHERE email = :e").param("e", e).query(Long.class).single()).isEqualTo(1);
    }

    // ----------------------------------------------------------------------------------------------- login

    @Test
    void unknownEmailAndWrongPasswordGiveIdenticalResponses() throws Exception {
        var e = email();
        register(e);
        var wrongPassword = send("/auth/login", creds(e, "definitely the wrong one"));
        var unknown = send("/auth/login", creds(email(), "definitely the wrong one"));
        assertThat(wrongPassword.getResponse().getStatus()).isEqualTo(401);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(401);
        assertThat(json(wrongPassword).get("code")).isEqualTo(json(unknown).get("code"));
        assertThat(json(wrongPassword).get("detail")).isEqualTo(json(unknown).get("detail"));
    }

    @Test
    void repeatedFailuresLockTheAccountEvenAgainstTheCorrectPasswordThenItRecovers() throws Exception {
        var e = email();
        register(e);
        for (int i = 0; i < 3; i++) {
            assertThat(send("/auth/login", creds(e, "wrong-wrong-wrong")).getResponse().getStatus()).isEqualTo(401);
        }
        var locked = send("/auth/login", creds(e, GOOD_PASSWORD));
        assertThat(locked.getResponse().getStatus()).as("locked: correct password refused").isEqualTo(401);
        assertThat(json(locked).get("code").asString()).isEqualTo("INVALID_CREDENTIALS");   // lock state not revealed

        Thread.sleep(2_300);                                                              // lock duration is 2s in this test
        assertThat(send("/auth/login", creds(e, GOOD_PASSWORD)).getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT failed_attempts FROM users WHERE email = :e").param("e", e).query(Integer.class).single()).isZero();
    }

    @Test
    void concurrentWrongGuessesCannotExceedTheLockThresholdUnnoticed() throws Exception {
        var e = email();
        register(e);
        var pool = Executors.newFixedThreadPool(10);
        var go = new CountDownLatch(1);
        var futures = new ArrayList<java.util.concurrent.Future<Integer>>();
        for (int i = 0; i < 10; i++) {
            futures.add(pool.submit(() -> { go.await(); return send("/auth/login", creds(e, "guess-guess-guess")).getResponse().getStatus(); }));
        }
        go.countDown();
        for (var f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(jdbc.sql("SELECT locked_until IS NOT NULL FROM users WHERE email = :e").param("e", e).query(Boolean.class).single()).isTrue();
    }

    @Test
    void authResponsesAreNeverCacheable() throws Exception {
        var e = email();
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(creds(e, GOOD_PASSWORD)))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store")).andExpect(header().string("Pragma", "no-cache"));
    }

    // ---------------------------------------------------------------------------------------------- refresh

    @Test
    void refreshRotatesTheTokenAndTheOldOneStopsWorking() throws Exception {
        var first = register(email());
        var r1 = first.get("refreshToken").asString();

        var second = send("/auth/refresh", "{\"refreshToken\":\"%s\"}".formatted(r1));
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        var r2 = json(second).get("refreshToken").asString();
        assertThat(r2).isNotEqualTo(r1);
        JwtVerification.decoder(KEYS.publicKeyPem()).decode(json(second).get("accessToken").asString());

        assertThat(send("/auth/refresh", "{\"refreshToken\":\"%s\"}".formatted(r2)).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void reusingARotatedTokenRevokesTheWholeSessionIncludingTheLegitimateSuccessor() throws Exception {
        var first = register(email());
        var stolen = first.get("refreshToken").asString();
        var legit = json(send("/auth/refresh", "{\"refreshToken\":\"%s\"}".formatted(stolen))).get("refreshToken").asString();

        var replay = send("/auth/refresh", "{\"refreshToken\":\"%s\"}".formatted(stolen));      // attacker replays the old token
        assertThat(replay.getResponse().getStatus()).isEqualTo(401);

        var victim = send("/auth/refresh", "{\"refreshToken\":\"%s\"}".formatted(legit));        // legit successor is now dead too
        assertThat(victim.getResponse().getStatus()).as("family revoked by reuse detection").isEqualTo(401);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'REFRESH_REUSE'").query(Long.class).single()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void theSameRefreshTokenUsedConcurrentlyYieldsAtMostOneSuccess() throws Exception {
        var token = register(email()).get("refreshToken").asString();
        var pool = Executors.newFixedThreadPool(6);
        var go = new CountDownLatch(1);
        var futures = new ArrayList<java.util.concurrent.Future<Integer>>();
        for (int i = 0; i < 6; i++) {
            futures.add(pool.submit(() -> { go.await(); return send("/auth/refresh", "{\"refreshToken\":\"%s\"}".formatted(token)).getResponse().getStatus(); }));
        }
        go.countDown();
        int ok = 0;
        for (var f : futures) {
            if (f.get(60, TimeUnit.SECONDS) == 200) ok++;
        }
        pool.shutdown();
        assertThat(ok).isEqualTo(1);
    }

    @Test
    void expiredUnknownAndMalformedRefreshTokensAreAllRejectedTheSameWay() throws Exception {
        var token = register(email()).get("refreshToken").asString();
        jdbc.sql("UPDATE refresh_tokens SET expires_at = now() - interval '1 minute' WHERE token_hash = :h").param("h", sha256(token)).update();
        for (var bad : new String[] {token, "A".repeat(43), "short", "x".repeat(200)}) {
            var r = send("/auth/refresh", "{\"refreshToken\":\"%s\"}".formatted(bad));
            assertThat(r.getResponse().getStatus()).as(bad).isEqualTo(401);
            assertThat(json(r).get("code").asString()).isEqualTo("INVALID_REFRESH_TOKEN");
        }
    }

    @Test
    void logoutRevokesTheSessionAndIsIdempotent() throws Exception {
        var token = register(email()).get("refreshToken").asString();
        assertThat(send("/auth/logout", "{\"refreshToken\":\"%s\"}".formatted(token)).getResponse().getStatus()).isEqualTo(204);
        assertThat(send("/auth/logout", "{\"refreshToken\":\"%s\"}".formatted(token)).getResponse().getStatus()).isEqualTo(204);
        assertThat(send("/auth/logout", "{\"refreshToken\":\"unknown-unknown-unknown-unknown-unknown\"}").getResponse().getStatus()).isEqualTo(204);
        assertThat(send("/auth/refresh", "{\"refreshToken\":\"%s\"}".formatted(token)).getResponse().getStatus()).isEqualTo(401);
    }

    // ----------------------------------------------------------------------------------- hygiene and surface

    @Test
    void credentialsAndTokensNeverAppearInLogs(CapturedOutput output) throws Exception {
        var e = email();
        var distinctivePassword = "Zq9-distinctive-password-" + UUID.randomUUID();
        var tokens = json(send("/auth/register", creds(e, distinctivePassword)));
        send("/auth/login", creds(e, "wrong-distinctive-guess-" + UUID.randomUUID()));
        send("/auth/login", creds(e, distinctivePassword));
        send("/auth/refresh", "{\"refreshToken\":\"%s\"}".formatted(tokens.get("refreshToken").asString()));
        send("/auth/refresh", "{\"refreshToken\":\"%s\"}".formatted(tokens.get("refreshToken").asString()));   // reuse path

        var logs = output.getAll();
        assertThat(logs).doesNotContain(distinctivePassword).doesNotContain("wrong-distinctive-guess")
                .doesNotContain(tokens.get("refreshToken").asString()).doesNotContain(tokens.get("accessToken").asString());
    }

    @Test
    void auditTrailRecordsSecurityEventsWithoutEmailsOrSecrets() throws Exception {
        var e = email();
        register(e);
        send("/auth/login", creds(e, "wrong-wrong-wrong"));
        var rows = jdbc.sql("SELECT action || ' ' || outcome || ' ' || COALESCE(detail, '') FROM audit_log").query(String.class).list();
        assertThat(rows).anyMatch(r -> r.startsWith("REGISTER SUCCESS")).anyMatch(r -> r.startsWith("LOGIN FAILURE"));
        assertThat(String.join("\n", rows)).doesNotContain("@example.com").doesNotContain(GOOD_PASSWORD);
    }

    @Test
    void auditLogIsAppendOnly() {
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> jdbc.sql("DELETE FROM audit_log").update());
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> jdbc.sql("UPDATE audit_log SET outcome = 'X'").update());
    }

    @Test
    void onlyTheAuthEndpointsAndHealthAreExposed() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(get("/users")).andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content("{")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    static String sha256(String v) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(v.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
