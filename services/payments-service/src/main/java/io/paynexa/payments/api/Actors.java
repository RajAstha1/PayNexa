package io.paynexa.payments.api;

import io.paynexa.payments.service.Actor;
import io.paynexa.platform.JwtVerification;
import org.springframework.security.oauth2.jwt.Jwt;

final class Actors {
    private Actors() {}

    static Actor from(Jwt jwt) {
        return Actor.user(JwtVerification.userId(jwt), JwtVerification.isAdmin(jwt));
    }
}
