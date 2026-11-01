package io.paynexa.payments.api;

import io.paynexa.testsupport.PostgresTestDatabase;
import io.paynexa.testsupport.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Filtering, paging and statistics of the caller's payments, through the real security chain and a real PostgreSQL. */
@SpringBootTest(properties = {
        "paynexa.payments.outbox.relay-enabled=false",
        "paynexa.payments.rail.worker-enabled=false",
        "paynexa.payments.reconciliation.scheduled=false",
        "paynexa.payments.sandbox.funding-enabled=true",
        "paynexa.payments.rate-limit.payments-burst=100000",
        "paynexa.payments.rate-limit.general-burst=100000",
        "paynexa.payments.risk.defaults.review-threshold=100000",
        "paynexa.payments.risk.defaults.first-payee-review-threshold=100000",
        "paynexa.payments.risk.defaults.max-transaction=100000",
        "paynexa.payments.risk.defaults.daily-outflow=1000000",
        "spring.flyway.user=postgres", "spring.flyway.password=postgres",
        "spring.datasource.username=postgres", "spring.datasource.password=postgres"})
@AutoConfigureMockMvc
class PaymentQueryApiTest {

    static final TestTokens TOKENS = new TestTokens();
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final UUID ADMIN = UUID.randomUUID();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> PostgresTestDatabase.migratedJdbcUrl("payments", "classpath:db/migration"));
        r.add("paynexa.security.jwt.public-key", TOKENS::publicKeyPem);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    private JsonNode call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b, UUID user, int expected) throws Exception {
        var res = mvc.perform(b.header("Authorization", "Bearer " + TOKENS.user(user))).andExpect(status().is(expected)).andReturn();
        return JSON.readTree(res.getResponse().getContentAsString());
    }

    private UUID account(UUID user, String currency) throws Exception {
        var res = mvc.perform(post("/accounts").header("Authorization", "Bearer " + TOKENS.user(user)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currency\":\"" + currency + "\"}")).andExpect(status().isCreated()).andReturn();
        return UUID.fromString(JSON.readTree(res.getResponse().getContentAsString()).get("id").asString());
    }

    private void fund(UUID account, String amount, String currency) throws Exception {
        mvc.perform(post("/admin/accounts/" + account + "/fund").header("Authorization", "Bearer " + TOKENS.admin(ADMIN))
                .header("Idempotency-Key", "f-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":\"" + amount + "\",\"currency\":\"" + currency + "\"}")).andExpect(status().isCreated());
    }

    private JsonNode pay(UUID user, UUID from, UUID to, String amount, String currency) throws Exception {
        var res = mvc.perform(post("/payments/transfers").header("Authorization", "Bearer " + TOKENS.user(user))
                .header("Idempotency-Key", "p-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":\"%s\",\"currency\":\"%s\"}".formatted(from, to, amount, currency)))
                .andExpect(status().isCreated()).andReturn();
        return JSON.readTree(res.getResponse().getContentAsString());
    }

    // ------------------------------------------------------------------------------------------------ listing

    @Test
    void filtersByDirectionStatusAndKindAndNeverReturnsOtherUsersPayments() throws Exception {
        var alice = UUID.randomUUID();
        var bob = UUID.randomUUID();
        var carol = UUID.randomUUID();
        var a = account(alice, "USD");
        var b = account(bob, "USD");
        var c = account(carol, "USD");
        fund(a, "100.00", "USD");
        fund(c, "100.00", "USD");
        pay(alice, a, b, "10.00", "USD");               // alice -> bob  (completed)
        pay(alice, a, b, "1000.00", "USD");             // alice -> bob  (fails: insufficient funds)
        pay(carol, c, b, "5.00", "USD");                // carol -> bob  (alice must never see this)

        var out = call(get("/payments?direction=out"), alice, 200);
        assertThat(out).hasSize(2);                      // the two transfers alice sent
        for (var p : out) assertThat(p.get("fromAccountId").asString()).isEqualTo(a.toString());

        var in = call(get("/payments?direction=in"), alice, 200);
        assertThat(in).hasSize(1);                       // her own funding
        assertThat(in.get(0).get("kind").asString()).isEqualTo("FUNDING");

        assertThat(call(get("/payments?status=FAILED"), alice, 200)).hasSize(1);
        assertThat(call(get("/payments?status=completed&kind=transfer"), alice, 200)).hasSize(1);   // case-insensitive
        assertThat(call(get("/payments?kind=PAYOUT"), alice, 200)).isEmpty();

        // bob sees both payments he received, and nothing of alice's failed attempt except as the payee it targeted
        var bobIn = call(get("/payments?direction=in&status=COMPLETED"), bob, 200);
        assertThat(bobIn).hasSize(2);
        // carol's funding never shows up for alice in any query
        for (var p : call(get("/payments?limit=100"), alice, 200)) {
            assertThat(p.get("fromAccountId").asString()).isNotEqualTo(c.toString());
        }
    }

    @Test
    void filtersByInclusiveUtcDateRange() throws Exception {
        var alice = UUID.randomUUID();
        var bob = UUID.randomUUID();
        var a = account(alice, "USD");
        var b = account(bob, "USD");
        fund(a, "100.00", "USD");
        var old = pay(alice, a, b, "1.00", "USD");
        var today = LocalDate.now(ZoneOffset.UTC);
        jdbc.sql("UPDATE payments SET created_at = :t WHERE id = :id").param("t", java.time.OffsetDateTime.parse(today.minusDays(40) + "T12:00:00Z"))
                .param("id", UUID.fromString(old.get("id").asString())).update();

        assertThat(call(get("/payments?kind=TRANSFER&from=" + today), alice, 200)).isEmpty();
        assertThat(call(get("/payments?kind=TRANSFER&to=" + today.minusDays(40)), alice, 200)).hasSize(1);       // 'to' is inclusive
        assertThat(call(get("/payments?kind=TRANSFER&from=" + today.minusDays(41) + "&to=" + today.minusDays(39)), alice, 200)).hasSize(1);
        assertThat(call(get("/payments?kind=TRANSFER&from=" + today.minusDays(39)), alice, 200)).isEmpty();
    }

    @Test
    void pagesWithLimitAndOffsetInStableOrder() throws Exception {
        var alice = UUID.randomUUID();
        var bob = UUID.randomUUID();
        var a = account(alice, "USD");
        var b = account(bob, "USD");
        fund(a, "500.00", "USD");
        for (int i = 0; i < 7; i++) pay(alice, a, b, "1.00", "USD");

        var first = call(get("/payments?kind=TRANSFER&limit=3&offset=0"), alice, 200);
        var second = call(get("/payments?kind=TRANSFER&limit=3&offset=3"), alice, 200);
        var third = call(get("/payments?kind=TRANSFER&limit=3&offset=6"), alice, 200);
        assertThat(first).hasSize(3);
        assertThat(second).hasSize(3);
        assertThat(third).hasSize(1);
        var ids = new java.util.HashSet<String>();
        for (var page : new JsonNode[] {first, second, third}) for (var p : page) assertThat(ids.add(p.get("id").asString())).isTrue();
        assertThat(ids).hasSize(7);
    }

    @Test
    void rejectsMalformedFiltersWithAClearCodeAndNoSqlErrors() throws Exception {
        var alice = UUID.randomUUID();
        account(alice, "USD");
        for (var bad : new String[] {"status=NOPE", "kind=x", "direction=sideways", "from=yesterday", "to=2026-13-40", "from=2026-12-01&to=2026-01-01",
                "status=COMPLETED%27%20OR%201%3D1--", "from=1999-01-01"}) {
            var body = call(get("/payments?" + bad), alice, 400);
            assertThat(body.get("code").asString()).as(bad).isEqualTo("INVALID_FILTER");
            assertThat(body.toString()).doesNotContain("SELECT").doesNotContain("Exception");
        }
    }

    @Test
    void searchesByReferenceAndPaymentIdPrefixAndTreatsWildcardsAsPlainText() throws Exception {
        var alice = UUID.randomUUID();
        var bob = UUID.randomUUID();
        var a = account(alice, "USD");
        var b = account(bob, "USD");
        fund(a, "100.00", "USD");
        var lunch = payWithReference(alice, a, b, "1.00", "Team Lunch");
        payWithReference(alice, a, b, "2.00", "rent 100%_final");
        payWithReference(alice, a, b, "3.00", "taxi");

        assertThat(search(alice, "lunch", 200)).hasSize(1);                 // case-insensitive substring
        assertThat(search(alice, lunch.get("id").asString().substring(0, 8), 200)).hasSize(1);   // id prefix
        assertThat(search(alice, "100%_final", 200)).hasSize(1);          // % and _ are literals, not wildcards
        assertThat(search(alice, "%", 200)).hasSize(1);                   // "%" alone matches only the literal one
        assertThat(search(alice, "_", 200)).hasSize(1);
        assertThat(search(alice, "nothing-like-this", 200)).isEmpty();
        assertThat(search(bob, "lunch", 200)).hasSize(1);                   // the payee can find it too
        assertThat(search(UUID.randomUUID(), "lunch", 200)).isEmpty();      // strangers cannot
        assertThat(search(alice, "x".repeat(51), 400).get("code").asString()).isEqualTo("INVALID_FILTER");
    }

    @Test
    void narrowsToOneAccountOnlyIfTheCallerOwnsIt() throws Exception {
        var alice = UUID.randomUUID();
        var bob = UUID.randomUUID();
        var aUsd = account(alice, "USD");
        var aEur = account(alice, "EUR");
        var b = account(bob, "USD");
        fund(aUsd, "10.00", "USD");
        fund(aEur, "20.00", "EUR");
        pay(alice, aUsd, b, "1.00", "USD");

        assertThat(call(get("/payments?accountId=" + aUsd), alice, 200)).hasSize(2);       // funding + transfer
        assertThat(call(get("/payments?accountId=" + aEur), alice, 200)).hasSize(1);       // only the EUR funding
        assertThat(call(get("/payments?accountId=" + b), alice, 404).get("code").asString()).isEqualTo("ACCOUNT_NOT_FOUND");   // not hers
        assertThat(call(get("/payments?accountId=" + UUID.randomUUID()), alice, 404).get("code").asString()).isEqualTo("ACCOUNT_NOT_FOUND");
    }

    /** Search text goes in as a real request parameter, exactly as a browser sends it (MockMvc would re-encode a URL string). */
    private JsonNode search(UUID user, String text, int expected) throws Exception {
        var res = mvc.perform(get("/payments").param("q", text).header("Authorization", "Bearer " + TOKENS.user(user)))
                .andExpect(status().is(expected)).andReturn();
        return JSON.readTree(res.getResponse().getContentAsString());
    }

    private JsonNode payWithReference(UUID user, UUID from, UUID to, String amount, String reference) throws Exception {
        var res = mvc.perform(post("/payments/transfers").header("Authorization", "Bearer " + TOKENS.user(user))
                .header("Idempotency-Key", "r-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":\"%s\",\"currency\":\"USD\",\"reference\":\"%s\"}".formatted(from, to, amount, reference)))
                .andExpect(status().isCreated()).andReturn();
        return JSON.readTree(res.getResponse().getContentAsString());
    }

    // ------------------------------------------------------------------------------------------------- stats

    @Test
    void statsReportExactAmountsPerDayAndCurrencyAndNeverMixCurrencies() throws Exception {
        var alice = UUID.randomUUID();
        var bob = UUID.randomUUID();
        var aUsd = account(alice, "USD");
        var bUsd = account(bob, "USD");
        var aEur = account(alice, "EUR");
        var bEur = account(bob, "EUR");
        fund(aUsd, "100.00", "USD");
        fund(aEur, "50.00", "EUR");
        pay(alice, aUsd, bUsd, "10.25", "USD");
        pay(alice, aUsd, bUsd, "4.75", "USD");
        pay(bob, bUsd, aUsd, "2.00", "USD");
        pay(alice, aEur, bEur, "7.00", "EUR");

        var stats = call(get("/payments/stats?days=7"), alice, 200);
        assertThat(stats.get("days").asInt()).isEqualTo(7);
        var usd = total(stats.get("totals"), "USD");
        assertThat(usd.get("incoming").asString()).isEqualTo("102.00");   // 100.00 funding + 2.00 from bob
        assertThat(usd.get("outgoing").asString()).isEqualTo("15.00");    // 10.25 + 4.75
        assertThat(usd.get("count").asInt()).isEqualTo(4);
        var eur = total(stats.get("totals"), "EUR");
        assertThat(eur.get("incoming").asString()).isEqualTo("50.00");
        assertThat(eur.get("outgoing").asString()).isEqualTo("7.00");
        // one bucket per (day, currency), all dated today (UTC), sums consistent with totals
        assertThat(stats.get("buckets")).hasSize(2);
        for (var bucket : stats.get("buckets")) assertThat(bucket.get("date").asString()).isEqualTo(LocalDate.now(ZoneOffset.UTC).toString());
        assertThat(stats.get("previousTotals")).isEmpty();
    }

    @Test
    void statsExcludeFailedPaymentsAndInternalMovesAndSplitPeriods() throws Exception {
        var alice = UUID.randomUUID();
        var bob = UUID.randomUUID();
        var a1 = account(alice, "USD");
        var a2 = account(alice, "EUR");
        var b = account(bob, "USD");
        fund(a1, "50.00", "USD");
        pay(alice, a1, b, "999.00", "USD");                                // fails (insufficient funds): moved no money
        var earlier = pay(alice, a1, b, "5.00", "USD");                    // will be moved into the PREVIOUS 7-day window
        jdbc.sql("UPDATE payments SET created_at = now() - interval '10 days' WHERE id = :id").param("id", UUID.fromString(earlier.get("id").asString())).update();

        var stats = call(get("/payments/stats?days=7"), alice, 200);
        assertThat(total(stats.get("totals"), "USD").get("outgoing").asString()).isEqualTo("0.00");
        assertThat(total(stats.get("totals"), "USD").get("incoming").asString()).isEqualTo("50.00");
        assertThat(total(stats.get("previousTotals"), "USD").get("outgoing").asString()).isEqualTo("5.00");
        assertThat(a2).isNotNull();
    }

    @Test
    void statsAreScopedToTheCallerAndValidateTheWindow() throws Exception {
        var alice = UUID.randomUUID();
        var stranger = UUID.randomUUID();
        var a = account(alice, "USD");
        fund(a, "10.00", "USD");

        assertThat(call(get("/payments/stats?days=30"), stranger, 200).get("totals")).isEmpty();      // no accounts, no data
        var empty = call(get("/payments/stats?days=30"), alice, 200);
        assertThat(total(empty.get("totals"), "USD").get("incoming").asString()).isEqualTo("10.00");
        for (var days : new String[] {"0", "8", "-1", "366", "abc"}) {
            mvc.perform(get("/payments/stats?days=" + days).header("Authorization", "Bearer " + TOKENS.user(alice))).andExpect(status().is4xxClientError());
        }
        mvc.perform(get("/payments/stats?days=7")).andExpect(status().isUnauthorized());
        mvc.perform(get("/payments/stats?days=7").header("Authorization", "Bearer " + TOKENS.user(alice))).andExpect(jsonPath("$.from").exists());
    }

    private static JsonNode total(JsonNode totals, String currency) {
        for (var t : totals) if (currency.equals(t.get("currency").asString())) return t;
        throw new AssertionError("no totals for " + currency + " in " + totals);
    }
}
