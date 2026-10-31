package io.paynexa.platform;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FiltersTest {

    @Test
    void correlationIdIsGeneratedEchoedAndClearedAfterTheRequest() throws Exception {
        var req = new MockHttpServletRequest("GET", "/x");
        var res = new MockHttpServletResponse();
        var seen = new String[1];
        new CorrelationFilter().doFilter(req, res, (rq, rs) -> seen[0] = MDC.get(Correlation.MDC_KEY));
        assertThat(seen[0]).isNotBlank();
        assertThat(res.getHeader(Correlation.HEADER)).isEqualTo(seen[0]);
        assertThat(MDC.get(Correlation.MDC_KEY)).isNull();
    }

    @Test
    void safeClientSuppliedIdIsKeptButUnsafeOneIsReplaced() throws Exception {
        var good = "client-req-12345";
        var req = new MockHttpServletRequest("GET", "/x");
        req.addHeader(Correlation.HEADER, good);
        var res = new MockHttpServletResponse();
        new CorrelationFilter().doFilter(req, res, new MockFilterChain());
        assertThat(res.getHeader(Correlation.HEADER)).isEqualTo(good);

        var evil = new MockHttpServletRequest("GET", "/x");
        evil.addHeader(Correlation.HEADER, "x\r\nSet-Cookie: a=b");
        var res2 = new MockHttpServletResponse();
        new CorrelationFilter().doFilter(evil, res2, new MockFilterChain());
        assertThat(res2.getHeader(Correlation.HEADER)).doesNotContain("Set-Cookie").hasSizeGreaterThanOrEqualTo(8);
    }

    private RateLimitFilter filter(int capacity, boolean trustForwarded) {
        var limiter = new TokenBucketRateLimiter(capacity, 0.001, 1000, Duration.ofMinutes(5), Clock.systemUTC());
        return new RateLimitFilter(
                List.of(new RateLimitFilter.Rule("login", "POST", "/login", RateLimitFilter.KeyBy.IP, limiter),
                        new RateLimitFilter.Rule("pay", "POST", "/payments", RateLimitFilter.KeyBy.USER_OR_IP, limiter)),
                r -> r.getHeader("X-Test-User"), trustForwarded, new SimpleMeterRegistry());
    }

    private MockHttpServletResponse call(RateLimitFilter f, String method, String uri, String ip, String xff, String user) throws Exception {
        var req = new MockHttpServletRequest(method, uri);
        req.setRemoteAddr(ip);
        if (xff != null) req.addHeader("X-Forwarded-For", xff);
        if (user != null) req.addHeader("X-Test-User", user);
        var res = new MockHttpServletResponse();
        f.doFilter(req, res, new MockFilterChain());
        return res;
    }

    @Test
    void rejectsWith429RetryAfterAndProblemBodyOnceLimitIsExceeded() throws Exception {
        var f = filter(2, false);
        assertThat(call(f, "POST", "/login", "10.0.0.1", null, null).getStatus()).isEqualTo(200);
        assertThat(call(f, "POST", "/login", "10.0.0.1", null, null).getStatus()).isEqualTo(200);
        var res = call(f, "POST", "/login", "10.0.0.1", null, null);
        assertThat(res.getStatus()).isEqualTo(429);
        assertThat(res.getHeader("Retry-After")).isNotBlank();
        assertThat(res.getContentAsString()).contains("RATE_LIMITED").doesNotContain("10.0.0.1");
        assertThat(call(f, "POST", "/login", "10.0.0.2", null, null).getStatus()).isEqualTo(200); // other client unaffected
    }

    @Test
    void spoofedForwardedHeaderCannotEvadeTheLimitUnlessExplicitlyTrusted() throws Exception {
        var untrusting = filter(1, false);
        call(untrusting, "POST", "/login", "10.0.0.9", "1.1.1.1", null);
        assertThat(call(untrusting, "POST", "/login", "10.0.0.9", "2.2.2.2", null).getStatus()).isEqualTo(429);
    }

    @Test
    void authenticatedRulesKeyByUserSoSharedNatAddressesDoNotInterfere() throws Exception {
        var f = filter(1, false);
        assertThat(call(f, "POST", "/payments", "10.0.0.5", null, "alice").getStatus()).isEqualTo(200);
        assertThat(call(f, "POST", "/payments", "10.0.0.5", null, "alice").getStatus()).isEqualTo(429);
        assertThat(call(f, "POST", "/payments", "10.0.0.5", null, "bob").getStatus()).isEqualTo(200);
    }

    @Test
    void unmatchedAndHealthRequestsAreNeverLimited() throws Exception {
        var f = filter(1, false);
        for (int i = 0; i < 5; i++) {
            assertThat(call(f, "GET", "/accounts", "10.0.0.7", null, null).getStatus()).isEqualTo(200);
            assertThat(call(f, "GET", "/actuator/health/liveness", "10.0.0.7", null, null).getStatus()).isEqualTo(200);
        }
    }
}
