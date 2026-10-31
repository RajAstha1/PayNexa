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

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The rate limiter wired into the real filter chain, on the real payment endpoint. */
@SpringBootTest(properties = {
        "paynexa.payments.outbox.relay-enabled=false",
        "paynexa.payments.rail.worker-enabled=false",
        "paynexa.payments.reconciliation.scheduled=false",
        "paynexa.payments.rate-limit.payments-burst=3",
        "paynexa.payments.rate-limit.payments-per-second=0.001",
        "paynexa.payments.rate-limit.general-burst=100000",
        "spring.flyway.user=postgres", "spring.flyway.password=postgres",
        "spring.datasource.username=postgres", "spring.datasource.password=postgres"})
@AutoConfigureMockMvc
class RateLimitApiTest {

    static final TestTokens TOKENS = new TestTokens();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> PostgresTestDatabase.migratedJdbcUrl("payments", "classpath:db/migration"));
        r.add("paynexa.security.jwt.public-key", TOKENS::publicKeyPem);
    }

    @Autowired
    MockMvc mvc;

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder transfer(UUID user) {
        return post("/payments/transfers").header("Authorization", "Bearer " + TOKENS.user(user))
                .header("Idempotency-Key", "rl-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":\"1.00\",\"currency\":\"USD\"}"
                        .formatted(UUID.randomUUID(), UUID.randomUUID()));
    }

    @Test
    void paymentWritesAreLimitedPerUserWith429RetryAfterAndOtherUsersAreUnaffected() throws Exception {
        var heavy = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            mvc.perform(transfer(heavy)).andExpect(status().isNotFound());        // reaches the service (unknown account), not limited
        }
        mvc.perform(transfer(heavy))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        mvc.perform(transfer(UUID.randomUUID())).andExpect(status().isNotFound()); // a different user still gets through
    }

    @Test
    void readsAndHealthChecksAreNotSubjectToThePaymentWriteLimit() throws Exception {
        var user = UUID.randomUUID();
        for (int i = 0; i < 20; i++) {
            mvc.perform(get("/accounts").header("Authorization", "Bearer " + TOKENS.user(user))).andExpect(status().isOk());
            mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        }
    }

    @Test
    void unauthenticatedFloodsCannotConsumeAnotherUsersBudget() throws Exception {
        var victim = UUID.randomUUID();
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/payments/transfers").header("Idempotency-Key", "x-" + i).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().is(org.hamcrest.Matchers.anyOf(org.hamcrest.Matchers.is(401), org.hamcrest.Matchers.is(429))));
        }
        mvc.perform(transfer(victim)).andExpect(status().isNotFound());
    }
}
