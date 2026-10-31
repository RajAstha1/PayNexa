package io.paynexa.notifications.config;

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
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

@Configuration
public class NotificationConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager manager) {
        return new TransactionTemplate(manager);
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${paynexa.security.jwt.public-key:}") String pem) {
        return JwtVerification.decoder(pem);
    }

    @Bean
    RateLimitFilter rateLimit(Clock clock, MeterRegistry meters) {
        var limiter = new TokenBucketRateLimiter(60, 10, 50_000, Duration.ofMinutes(10), clock);
        return new RateLimitFilter(List.of(new RateLimitFilter.Rule("general", null, "/", RateLimitFilter.KeyBy.USER_OR_IP, limiter)),
                r -> {
                    var auth = SecurityContextHolder.getContext().getAuthentication();
                    return auth instanceof JwtAuthenticationToken t ? t.getToken().getSubject() : null;
                }, false, meters);
    }

    @Bean
    SecurityFilterChain chain(HttpSecurity http, JwtDecoder decoder, RateLimitFilter rateLimit) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.GET, "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/**", "/admin/**").hasRole("ADMIN")
                        .requestMatchers("/notifications/**").hasAnyRole("USER", "ADMIN")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.decoder(decoder).jwtAuthenticationConverter(JwtVerification.authenticationConverter()))
                        .authenticationEntryPoint((req, res, ex) -> deny(res, 401, "UNAUTHENTICATED"))
                        .accessDeniedHandler((req, res, ex) -> deny(res, 403, "FORBIDDEN")))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> deny(res, 401, "UNAUTHENTICATED"))
                        .accessDeniedHandler((req, res, ex) -> deny(res, 403, "FORBIDDEN")))
                .addFilterBefore(new CorrelationFilter(), BearerTokenAuthenticationFilter.class)
                .addFilterAfter(rateLimit, BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    private static void deny(HttpServletResponse res, int status, String code) throws java.io.IOException {
        res.setStatus(status);
        res.setContentType("application/problem+json");
        res.getWriter().write("{\"status\":%d,\"code\":\"%s\"}".formatted(status, code));
    }
}
