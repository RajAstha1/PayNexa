package io.paynexa.testsupport;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/** Issues RS256 tokens exactly as identity-service would, for tests of services that only verify tokens. */
public final class TestTokens {

    public static final String ISSUER = "paynexa-identity";
    public static final String AUDIENCE = "paynexa-api";

    private final KeyPair pair;

    public TestTokens() {
        this(generate());
    }

    public TestTokens(KeyPair pair) {
        this.pair = pair;
    }

    public static KeyPair generate() {
        try {
            var gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String publicKeyPem() {
        var b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(pair.getPublic().getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + b64 + "\n-----END PUBLIC KEY-----";
    }

    public String privateKeyPem() {
        var b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(pair.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + b64 + "\n-----END PRIVATE KEY-----";
    }

    public String user(UUID userId) {
        return token(userId, List.of("USER"), ISSUER, AUDIENCE, Instant.now().plusSeconds(300));
    }

    public String admin(UUID userId) {
        return token(userId, List.of("ADMIN"), ISSUER, AUDIENCE, Instant.now().plusSeconds(300));
    }

    public String token(UUID userId, List<String> roles, String issuer, String audience, Instant expiry) {
        return token(pair, userId.toString(), roles, issuer, audience, expiry);
    }

    public static String token(KeyPair signingPair, String subject, List<String> roles, String issuer, String audience, Instant expiry) {
        try {
            var claims = new JWTClaimsSet.Builder().subject(subject).issuer(issuer).audience(audience)
                    .issueTime(new Date()).expirationTime(Date.from(expiry)).jwtID(UUID.randomUUID().toString())
                    .claim("roles", roles).build();
            var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            jwt.sign(new RSASSASigner(signingPair.getPrivate()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
