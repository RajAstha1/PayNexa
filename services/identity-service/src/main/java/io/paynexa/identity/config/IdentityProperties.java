package io.paynexa.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "paynexa.identity")
public record IdentityProperties(
        @DefaultValue Jwt jwt,
        @DefaultValue("7d") Duration refreshTtl,
        @DefaultValue Lockout lockout,
        @DefaultValue("12") int bcryptCost,
        @DefaultValue RateLimit rateLimit,
        String bootstrapAdminEmail,
        String bootstrapAdminPassword) {

    public record Jwt(String privateKey, @DefaultValue("10m") Duration accessTtl) {}

    public record Lockout(@DefaultValue("5") int maxFailedAttempts, @DefaultValue("15m") Duration duration) {}

    public record RateLimit(@DefaultValue("10") int authBurst, @DefaultValue("0.2") double authPerSecond,
                            @DefaultValue("false") boolean trustForwardedHeader) {}
}
