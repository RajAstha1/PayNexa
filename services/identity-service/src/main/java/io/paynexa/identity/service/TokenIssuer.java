package io.paynexa.identity.service;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.paynexa.identity.config.IdentityProperties;
import io.paynexa.platform.TokenClaims;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/** Signs access tokens with RS256. The private key never leaves this process and is never logged. */
public final class TokenIssuer {

    private final RSASSASigner signer;
    private final Duration ttl;
    private final Clock clock;

    public TokenIssuer(IdentityProperties.Jwt config, Clock clock) {
        this.signer = new RSASSASigner(parsePrivateKey(config.privateKey()));
        this.ttl = config.accessTtl();
        this.clock = clock;
        if (ttl.compareTo(Duration.ofMinutes(60)) > 0 || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalStateException("paynexa.identity.jwt.access-ttl must be between 1 second and 60 minutes");
        }
    }

    static RSAPrivateCrtKey parsePrivateKey(String pem) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalStateException("paynexa.identity.jwt.private-key is not set");
        }
        try {
            var b64 = pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
            var key = (RSAPrivateCrtKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64)));
            if (key.getModulus().bitLength() < 2048) {
                throw new IllegalStateException("JWT private key must be at least 2048 bits");
            }
            return key;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("paynexa.identity.jwt.private-key is not a valid PKCS#8 PEM RSA private key");
        }
    }

    public Duration ttl() {
        return ttl;
    }

    public String issue(UUID userId, List<String> roles) {
        try {
            var now = clock.instant();
            var claims = new JWTClaimsSet.Builder()
                    .issuer(TokenClaims.ISSUER).audience(TokenClaims.AUDIENCE).subject(userId.toString())
                    .claim(TokenClaims.ROLES, roles).issueTime(Date.from(now)).notBeforeTime(Date.from(now.minusSeconds(5)))
                    .expirationTime(Date.from(now.plus(ttl))).jwtID(UUID.randomUUID().toString()).build();
            var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            jwt.sign(signer);
            return jwt.serialize();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException("could not sign token", e);
        }
    }
}
