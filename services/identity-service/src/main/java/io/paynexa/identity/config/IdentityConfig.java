package io.paynexa.identity.config;

import io.paynexa.identity.service.AuthService;
import io.paynexa.identity.service.TokenIssuer;
import io.micrometer.core.instrument.MeterRegistry;
import io.paynexa.platform.CorrelationFilter;
import io.paynexa.platform.RateLimitFilter;
import io.paynexa.platform.TokenBucketRateLimiter;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

@Configuration
@EnableConfigurationProperties(IdentityProperties.class)
public class IdentityConfig {

    private static final Logger log = LoggerFactory.getLogger(IdentityConfig.class);

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager manager) {
        return new TransactionTemplate(manager);
    }

    @Bean
    PasswordEncoder passwordEncoder(IdentityProperties props) {
        if (props.bcryptCost() < 10 || props.bcryptCost() > 16) {
            throw new IllegalStateException("paynexa.identity.bcrypt-cost must be between 10 and 16");
        }
        return new BCryptPasswordEncoder(props.bcryptCost());
    }

    @Bean
    TokenIssuer tokenIssuer(IdentityProperties props, Clock clock) {
        return new TokenIssuer(props.jwt(), clock);   // fails fast when the key is missing or weak
    }

    @Bean
    RateLimitFilter authRateLimit(IdentityProperties props, Clock clock, MeterRegistry meters) {
        var rl = props.rateLimit();
        var limiter = new TokenBucketRateLimiter(rl.authBurst(), rl.authPerSecond(), 50_000, Duration.ofMinutes(15), clock);
        return new RateLimitFilter(List.of(new RateLimitFilter.Rule("auth", "POST", "/auth/", RateLimitFilter.KeyBy.IP, limiter)),
                r -> null, rl.trustForwardedHeader(), meters);
    }

    @Bean
    SecurityFilterChain chain(HttpSecurity http, RateLimitFilter authRateLimit) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h.frameOptions(f -> f.deny())
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/auth/register", "/auth/login", "/auth/refresh", "/auth/logout").permitAll()
                        .requestMatchers("/actuator/health/**").permitAll()
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> deny(res, 401))
                        .accessDeniedHandler((req, res, ex) -> deny(res, 403)))
                .addFilterBefore(new CorrelationFilter(), AnonymousAuthenticationFilter.class)
                .addFilterAfter(authRateLimit, CorrelationFilter.class);
        return http.build();
    }

    private static void deny(HttpServletResponse res, int status) throws java.io.IOException {
        res.setStatus(status);
        res.setContentType("application/problem+json");
        res.getWriter().write("{\"status\":%d,\"code\":\"%s\"}".formatted(status, status == 401 ? "UNAUTHENTICATED" : "FORBIDDEN"));
    }

    /** Optional sandbox administrator, created only when both values are configured and no admin exists yet. */
    @Bean
    ApplicationRunner bootstrapAdmin(IdentityProperties props, AuthService auth) {
        return args -> {
            var email = props.bootstrapAdminEmail();
            var password = props.bootstrapAdminPassword();
            if (email != null && !email.isBlank() && password != null && !password.isBlank()) {
                auth.createAdminIfNoneExists(email, password);
                log.info("bootstrap administrator ensured");
            }
        };
    }
}
