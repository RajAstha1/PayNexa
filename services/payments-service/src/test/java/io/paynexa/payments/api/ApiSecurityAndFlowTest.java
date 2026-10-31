package io.paynexa.payments.api;

import io.paynexa.testsupport.PostgresTestDatabase;
import io.paynexa.testsupport.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full application: security filter chain, rate limiting, controllers, Flyway migrations and a real PostgreSQL.
 * The relay and workers are disabled here (covered by their own tests) so no broker is needed.
 */
@SpringBootTest(properties = {
        "paynexa.payments.outbox.relay-enabled=false",
        "paynexa.payments.rail.worker-enabled=false",
        "paynexa.payments.reconciliation.scheduled=false",
        "paynexa.payments.sandbox.funding-enabled=true",
        "paynexa.payments.rate-limit.payments-burst=1000",
        "paynexa.payments.rate-limit.general-burst=100000",
        "paynexa.payments.risk.defaults.review-threshold=100000",
        "paynexa.payments.risk.defaults.first-payee-review-threshold=100000",
        "paynexa.payments.risk.defaults.max-transaction=100000",
        "paynexa.payments.risk.defaults.daily-outflow=1000000",
        "spring.flyway.user=postgres",
        "spring.flyway.password=postgres",
        "spring.datasource.username=postgres",
        "spring.datasource.password=postgres"})
@AutoConfigureMockMvc
@org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
class ApiSecurityAndFlowTest {

    static final TestTokens TOKENS = new TestTokens();
    static final JsonMapper JSON = JsonMapper.builder().build();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> PostgresTestDatabase.migratedJdbcUrl("payments", "classpath:db/migration"));
        r.add("paynexa.security.jwt.public-key", TOKENS::publicKeyPem);
    }

    @Autowired
    MockMvc mvc;

    static final UUID ALICE = UUID.randomUUID();
    static final UUID BOB = UUID.randomUUID();
    static final UUID ADMIN = UUID.randomUUID();

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private JsonNode body(MvcResult r) throws Exception {
        return JSON.readTree(r.getResponse().getContentAsString());
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, UUID user) {
        return b.header("Authorization", bearer(TOKENS.user(user))).contentType(MediaType.APPLICATION_JSON);
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder b) {
        return b.header("Authorization", bearer(TOKENS.admin(ADMIN))).contentType(MediaType.APPLICATION_JSON);
    }

    private UUID openAccount(UUID user, String currency) throws Exception {
        var r = mvc.perform(as(post("/accounts"), user).content("{\"currency\":\"" + currency + "\"}")).andExpect(status().isCreated()).andReturn();
        return UUID.fromString(body(r).get("id").asString());
    }

    private void fund(UUID account, String amount, String currency) throws Exception {
        mvc.perform(asAdmin(post("/admin/accounts/" + account + "/fund")).header("Idempotency-Key", "fund-" + UUID.randomUUID())
                .content("{\"amount\":\"" + amount + "\",\"currency\":\"" + currency + "\"}")).andExpect(status().isCreated());
    }

    private static String transferJson(UUID from, UUID to, String amount) {
        return "{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":\"%s\",\"currency\":\"USD\"}".formatted(from, to, amount);
    }

    // ------------------------------------------------------------------------- authentication

    @Test
    void requestsWithoutAValidTokenAreRejectedWith401AndNoDataLeak() throws Exception {
        mvc.perform(get("/accounts")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/accounts").header("Authorization", "Bearer garbage")).andExpect(status().isUnauthorized());
        mvc.perform(get("/accounts").header("Authorization", "Basic YWxpY2U6cGFzcw==")).andExpect(status().isUnauthorized());
        var expired = TOKENS.token(ALICE, List.of("USER"), TestTokens.ISSUER, TestTokens.AUDIENCE, Instant.now().minusSeconds(600));
        mvc.perform(get("/accounts").header("Authorization", bearer(expired))).andExpect(status().isUnauthorized());
        var wrongAudience = TOKENS.token(ALICE, List.of("USER"), TestTokens.ISSUER, "other", Instant.now().plusSeconds(60));
        mvc.perform(get("/accounts").header("Authorization", bearer(wrongAudience))).andExpect(status().isUnauthorized());
        var forged = TestTokens.token(TestTokens.generate(), ALICE.toString(), List.of("ADMIN"), TestTokens.ISSUER, TestTokens.AUDIENCE, Instant.now().plusSeconds(60));
        mvc.perform(get("/admin/payments/pending-review").header("Authorization", bearer(forged))).andExpect(status().isUnauthorized());
    }

    @Test
    void healthIsPublicButMetricsAndEverythingUnmappedAreNot() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus").header("Authorization", bearer(TOKENS.user(ALICE)))).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/prometheus").header("Authorization", bearer(TOKENS.admin(ADMIN)))).andExpect(status().isOk());
        mvc.perform(get("/does-not-exist").header("Authorization", bearer(TOKENS.user(ALICE)))).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/env").header("Authorization", bearer(TOKENS.admin(ADMIN)))).andExpect(status().is4xxClientError());
    }

    // ------------------------------------------------------------------------- authorization

    @Test
    void ordinaryUsersCannotUseAnyAdminEndpoint() throws Exception {
        var victim = openAccount(BOB, "USD");
        for (var path : List.of("/admin/payments/pending-review")) {
            mvc.perform(as(get(path), ALICE)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        mvc.perform(as(post("/admin/accounts/" + victim + "/fund"), ALICE).header("Idempotency-Key", "abcdefgh-1")
                .content("{\"amount\":\"1000000\",\"currency\":\"USD\"}")).andExpect(status().isForbidden());
        mvc.perform(as(post("/admin/accounts/" + victim + "/status"), ALICE).content("{\"status\":\"FROZEN\"}")).andExpect(status().isForbidden());
        mvc.perform(as(post("/admin/payments/" + UUID.randomUUID() + "/reverse"), ALICE)).andExpect(status().isForbidden());
        mvc.perform(as(post("/admin/reconciliation/run"), ALICE)).andExpect(status().isForbidden());
        mvc.perform(as(post("/admin/outbox/requeue-dead"), ALICE)).andExpect(status().isForbidden());
    }

    @Test
    void aTokenWithNoRolesOrAnUnknownRoleIsDeniedEverywhere() throws Exception {
        var noRoles = TOKENS.token(ALICE, List.of(), TestTokens.ISSUER, TestTokens.AUDIENCE, Instant.now().plusSeconds(60));
        var odd = TOKENS.token(ALICE, List.of("SUPERUSER"), TestTokens.ISSUER, TestTokens.AUDIENCE, Instant.now().plusSeconds(60));
        mvc.perform(get("/accounts").header("Authorization", bearer(noRoles))).andExpect(status().isForbidden());
        mvc.perform(get("/accounts").header("Authorization", bearer(odd))).andExpect(status().isForbidden());
    }

    @Test
    void usersCannotSeeOrSpendFromEachOthersAccountsAndGet404NotAnOracle() throws Exception {
        var a = openAccount(ALICE, "USD");
        var m = openAccount(UUID.randomUUID(), "USD");
        fund(a, "100.00", "USD");

        mvc.perform(as(get("/accounts/" + a), UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(as(get("/accounts/" + UUID.randomUUID()), ALICE)).andExpect(status().isNotFound());
        var stolen = UUID.randomUUID();
        mvc.perform(as(post("/payments/transfers"), stolen).header("Idempotency-Key", "steal-" + UUID.randomUUID())
                        .content(transferJson(a, m, "10.00")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
        mvc.perform(as(get("/accounts/" + a), ALICE)).andExpect(status().isOk()).andExpect(jsonPath("$.balance").value("100.00"));
    }

    @Test
    void paymentsAreVisibleOnlyToTheirPartiesAndAdmins() throws Exception {
        var alice = UUID.randomUUID();
        var bob = UUID.randomUUID();
        var a = openAccount(alice, "USD");
        var b = openAccount(bob, "USD");
        fund(a, "50.00", "USD");
        var r = mvc.perform(as(post("/payments/transfers"), alice).header("Idempotency-Key", "vis-" + UUID.randomUUID())
                .content(transferJson(a, b, "5.00"))).andExpect(status().isCreated()).andReturn();
        var id = body(r).get("id").asString();

        mvc.perform(as(get("/payments/" + id), alice)).andExpect(status().isOk());
        mvc.perform(as(get("/payments/" + id), bob)).andExpect(status().isOk());              // payee may see it
        mvc.perform(as(get("/payments/" + id), UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(as(get("/payments/" + id + "/history"), UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(asAdmin(get("/payments/" + id))).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------------- money flow over HTTP

    @Test
    void transferEndToEndWithIdempotentReplayOverHttp() throws Exception {
        var alice = UUID.randomUUID();
        var bob = UUID.randomUUID();
        var a = openAccount(alice, "USD");
        var b = openAccount(bob, "USD");
        fund(a, "100.00", "USD");
        var key = "http-" + UUID.randomUUID();

        var first = mvc.perform(as(post("/payments/transfers"), alice).header("Idempotency-Key", key).content(transferJson(a, b, "12.34")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.amount").value("12.34")).andReturn();
        var replay = mvc.perform(as(post("/payments/transfers"), alice).header("Idempotency-Key", key).content(transferJson(a, b, "12.34")))
                .andExpect(status().isOk()).andExpect(header().string("Idempotent-Replay", "true")).andReturn();

        assertThat(body(replay).get("id").asString()).isEqualTo(body(first).get("id").asString());
        mvc.perform(as(get("/accounts/" + a), alice)).andExpect(jsonPath("$.balance").value("87.66"));
        mvc.perform(as(get("/accounts/" + b), bob)).andExpect(jsonPath("$.balance").value("12.34"));
        mvc.perform(as(post("/payments/transfers"), alice).header("Idempotency-Key", key).content(transferJson(a, b, "99.00")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void insufficientFundsIsAnOutcomeNotAnHttpError() throws Exception {
        var alice = UUID.randomUUID();
        var a = openAccount(alice, "USD");
        var b = openAccount(UUID.randomUUID(), "USD");
        mvc.perform(as(post("/payments/transfers"), alice).header("Idempotency-Key", "poor-" + UUID.randomUUID()).content(transferJson(a, b, "1.00")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    void payoutShowsSubmittedNotSettledAndNeverEchoesTheDestination() throws Exception {
        var alice = UUID.randomUUID();
        var a = openAccount(alice, "USD");
        fund(a, "50.00", "USD");
        var r = mvc.perform(as(post("/payments/payouts"), alice).header("Idempotency-Key", "po-" + UUID.randomUUID())
                .content("{\"fromAccountId\":\"%s\",\"destination\":\"bank:secret-1234\",\"amount\":\"20.00\",\"currency\":\"USD\"}".formatted(a)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("SUBMITTED")).andReturn();
        assertThat(r.getResponse().getContentAsString()).doesNotContain("secret-1234").doesNotContain("destination");
    }

    // ------------------------------------------------------------------------- validation and safe errors

    @Test
    void invalidInputGetsClearProblemResponsesThatDoNotEchoValues() throws Exception {
        var alice = UUID.randomUUID();
        var a = openAccount(alice, "USD");
        var b = openAccount(UUID.randomUUID(), "USD");
        var k = "val-" + UUID.randomUUID();

        mvc.perform(as(post("/payments/transfers"), alice).header("Idempotency-Key", k).content(transferJson(a, b, "-5")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
        mvc.perform(as(post("/payments/transfers"), alice).header("Idempotency-Key", k).content(transferJson(a, b, "1e3")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
        mvc.perform(as(post("/payments/transfers"), alice).header("Idempotency-Key", k).content(transferJson(a, b, "1.999")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PRECISION"));
        mvc.perform(as(post("/payments/transfers"), alice).header("Idempotency-Key", k).content("{\"fromAccountId\":\"not-a-uuid\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        mvc.perform(as(post("/payments/transfers"), alice).header("Idempotency-Key", k).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        mvc.perform(as(post("/payments/transfers"), alice).content(transferJson(a, b, "1.00")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MISSING_HEADER"));
        mvc.perform(as(post("/payments/transfers"), alice).header("Idempotency-Key", "x").content(transferJson(a, b, "1.00")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
        mvc.perform(as(post("/payments/transfers"), alice).header("Idempotency-Key", k)
                        .content("{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":\"1\",\"currency\":\"USD\",\"reference\":\"%s\"}".formatted(a, b, "x".repeat(141))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void errorResponsesCarryACorrelationIdAndNeverLeakInternals() throws Exception {
        var r = mvc.perform(as(get("/payments/" + UUID.randomUUID()), ALICE).header("X-Correlation-Id", "trace-abc-12345"))
                .andExpect(status().isNotFound()).andExpect(header().string("X-Correlation-Id", "trace-abc-12345"))
                .andExpect(jsonPath("$.correlationId").value("trace-abc-12345")).andReturn();
        var text = r.getResponse().getContentAsString();
        assertThat(text).doesNotContain("Exception").doesNotContain("at io.paynexa").doesNotContain("SELECT").doesNotContain("postgres");
    }

    @Test
    void unknownRoutesAndWrongMethodsAreClientErrorsNotServerErrors() throws Exception {
        mvc.perform(asAdmin(get("/admin/nope"))).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("HTTP_404"));
        mvc.perform(as(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/accounts"), ALICE).content("{}"))
                .andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.code").value("HTTP_405"));
        mvc.perform(as(post("/accounts"), ALICE).contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("HTTP_415"));
    }

    @Test
    void openingAnAccountTwiceReturnsTheSameAccountAndUnsupportedCurrencyIsRejected() throws Exception {
        var user = UUID.randomUUID();
        var first = openAccount(user, "EUR");
        var second = openAccount(user, "EUR");
        assertThat(second).isEqualTo(first);
        mvc.perform(as(post("/accounts"), user).content("{\"currency\":\"XYZ\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_CURRENCY"));
    }

    @Test
    void adminCanRunReconciliationAndItIsCleanAfterTheseFlows() throws Exception {
        var r = mvc.perform(asAdmin(post("/admin/reconciliation/run"))).andExpect(status().isOk()).andReturn();
        var json = body(r);
        assertThat(json.get("clean").asBoolean()).as(json.toString()).isTrue();
    }
}
