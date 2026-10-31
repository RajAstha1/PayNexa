package io.paynexa.platform;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * How every service verifies access tokens: RS256 only (the algorithm is pinned, so "alg: none" and HMAC-confusion
 * attacks are impossible), signature checked against the issuer's <b>public</b> key, expiry, issuer and audience required.
 * Services never hold a signing secret.
 */
public final class JwtVerification {

    private JwtVerification() {}

    public static RSAPublicKey parsePublicKey(String pem) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalStateException("paynexa.security.jwt.public-key is not set");
        }
        try {
            var base64 = pem.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
            var key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
            var rsa = (RSAPublicKey) key;
            if (rsa.getModulus().bitLength() < 2048) {
                throw new IllegalStateException("JWT public key must be at least 2048 bits");
            }
            return rsa;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("paynexa.security.jwt.public-key is not a valid PEM-encoded RSA public key");
        }
    }

    public static JwtDecoder decoder(String publicKeyPem) {
        var decoder = NimbusJwtDecoder.withPublicKey(parsePublicKey(publicKeyPem))
                .signatureAlgorithm(SignatureAlgorithm.RS256).build();
        OAuth2TokenValidator<Jwt> audience = jwt -> jwt.getAudience() != null && jwt.getAudience().contains(TokenClaims.AUDIENCE)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Wrong audience", null));
        OAuth2TokenValidator<Jwt> subject = jwt -> isUuid(jwt.getSubject())
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid subject", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(TokenClaims.ISSUER), audience, subject));
        return decoder;
    }

    /** Maps the {@code roles} claim to {@code ROLE_*} authorities. Unknown roles are ignored. */
    public static Converter<Jwt, AbstractAuthenticationToken> authenticationConverter() {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<String> roles = jwt.getClaimAsStringList(TokenClaims.ROLES);
            if (roles == null) {
                return List.<GrantedAuthority>of();
            }
            return roles.stream()
                    .filter(r -> TokenClaims.ROLE_USER.equals(r) || TokenClaims.ROLE_ADMIN.equals(r))
                    .<GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                    .toList();
        });
        return converter;
    }

    public static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    public static boolean isAdmin(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList(TokenClaims.ROLES);
        return roles != null && roles.contains(TokenClaims.ROLE_ADMIN);
    }

    private static boolean isUuid(String s) {
        try {
            UUID.fromString(s);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
