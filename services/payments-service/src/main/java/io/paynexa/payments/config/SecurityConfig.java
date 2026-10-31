package io.paynexa.payments.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.paynexa.platform.CorrelationFilter;
import io.paynexa.platform.JwtVerification;
import io.paynexa.platform.RateLimitFilter;
import io.paynexa.platform.TokenBucketRateLimiter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

@Configuration
public class SecurityConfig {

    @Bean
    JwtDecoder jwtDecoder(@Value("${paynexa.security.jwt.public-key:}") String publicKeyPem) {
        // Fails fast at startup when the key is missing or weak.
        return JwtVerification.decoder(publicKeyPem);
    }

    @Bean
    RateLimitFilter rateLimitFilter(PaymentsProperties props, Clock clock, MeterRegistry meters) {
        var rl = props.rateLimit();
        var payments = new TokenBucketRateLimiter(rl.paymentsBurst(), rl.paymentsPerSecond(), 50_000, Duration.ofMinutes(10), clock);
        var general = new TokenBucketRateLimiter(rl.generalBurst(), rl.generalPerSecond(), 50_000, Duration.ofMinutes(10), clock);
        return new RateLimitFilter(List.of(
                new RateLimitFilter.Rule("payments-write", "POST", "/payments", RateLimitFilter.KeyBy.USER_OR_IP, payments),
                new RateLimitFilter.Rule("general", null, "/", RateLimitFilter.KeyBy.USER_OR_IP, general)),
                request -> {
                    var auth = SecurityContextHolder.getContext().getAuthentication();
                    return auth instanceof JwtAuthenticationToken t ? t.getToken().getSubject() : null;
                },
                rl.trustForwardedHeader(), meters);
    }

    @Bean
    SecurityFilterChain api(HttpSecurity http, JwtDecoder decoder, RateLimitFilter rateLimit) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)   // stateless bearer-token API: no cookies, no ambient credentials
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(f -> f.deny())
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .cacheControl(Customizer.withDefaults()))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.GET, "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .requestMatchers("/accounts/**", "/payments/**").hasAnyRole("USER", "ADMIN")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.decoder(decoder).jwtAuthenticationConverter(JwtVerification.authenticationConverter()))
                        .authenticationEntryPoint((req, res, ex) -> problem(res, 401, "UNAUTHENTICATED", "Authentication is required"))
                        .accessDeniedHandler((req, res, ex) -> problem(res, 403, "FORBIDDEN", "You do not have permission to perform this action")))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> problem(res, 401, "UNAUTHENTICATED", "Authentication is required"))
                        .accessDeniedHandler((req, res, ex) -> problem(res, 403, "FORBIDDEN", "You do not have permission to perform this action")))
                .addFilterBefore(new CorrelationFilter(), BearerTokenAuthenticationFilter.class)
                .addFilterAfter(rateLimit, BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    private static void problem(HttpServletResponse res, int status, String code, String detail) throws java.io.IOException {
        res.setStatus(status);
        res.setContentType("application/problem+json");
        if (status == 401) {
            res.setHeader("WWW-Authenticate", "Bearer");
        }
        var correlation = io.paynexa.platform.Correlation.current();
        res.getWriter().write("{\"title\":\"%s\",\"status\":%d,\"code\":\"%s\",\"detail\":\"%s\"%s}".formatted(
                status == 401 ? "Unauthorized" : "Forbidden", status, code, detail,
                correlation == null ? "" : ",\"correlationId\":\"" + correlation + "\""));
    }
}
