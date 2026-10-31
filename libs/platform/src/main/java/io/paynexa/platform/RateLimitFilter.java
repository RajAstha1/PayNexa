package io.paynexa.platform;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.function.Function;

/**
 * Applies the first matching rule to each request. Rules key by client address (for unauthenticated endpoints such as
 * login) or by authenticated principal (for payment endpoints). Rejections get 429 and Retry-After.
 *
 * <p>The client address is taken from the TCP peer. {@code X-Forwarded-For} is ignored unless
 * {@code trustForwardedHeader} is set, which must only be done behind a proxy that overwrites it.
 */
public final class RateLimitFilter extends OncePerRequestFilter {

    public enum KeyBy { IP, USER_OR_IP }

    public record Rule(String name, String method, String pathPrefix, KeyBy keyBy, TokenBucketRateLimiter limiter) {
        boolean matches(HttpServletRequest r) {
            return (method == null || method.equalsIgnoreCase(r.getMethod())) && r.getRequestURI().startsWith(pathPrefix);
        }
    }

    private final List<Rule> rules;
    private final Function<HttpServletRequest, String> principalResolver;
    private final boolean trustForwardedHeader;
    private final MeterRegistry meters;

    public RateLimitFilter(List<Rule> rules, Function<HttpServletRequest, String> principalResolver,
                           boolean trustForwardedHeader, MeterRegistry meters) {
        this.rules = List.copyOf(rules);
        this.principalResolver = principalResolver;
        this.trustForwardedHeader = trustForwardedHeader;
        this.meters = meters;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        for (var rule : rules) {
            if (!rule.matches(request)) {
                continue;
            }
            var principal = rule.keyBy() == KeyBy.USER_OR_IP ? principalResolver.apply(request) : null;
            var key = rule.name() + ":" + (principal != null ? "u:" + principal : "ip:" + clientAddress(request));
            var decision = rule.limiter().tryAcquire(key);
            if (!decision.allowed()) {
                meters.counter("paynexa.ratelimit.rejected", "rule", rule.name()).increment();
                response.setStatus(429);
                response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
                response.setContentType("application/problem+json");
                response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,"
                        + "\"code\":\"RATE_LIMITED\",\"detail\":\"Rate limit exceeded. Retry after "
                        + decision.retryAfterSeconds() + " seconds.\"}");
                return;
            }
            break; // first matching rule only
        }
        chain.doFilter(request, response);
    }

    private String clientAddress(HttpServletRequest request) {
        if (trustForwardedHeader) {
            var forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                var first = forwarded.split(",")[0].trim();
                if (first.matches("[0-9a-fA-F:.]{3,45}")) {
                    return first;
                }
            }
        }
        return request.getRemoteAddr();
    }
}
