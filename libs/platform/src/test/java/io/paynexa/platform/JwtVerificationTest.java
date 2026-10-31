package io.paynexa.platform;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtVerificationTest {

    static final KeyPair ISSUER_KEYS = rsa(2048);
    static final KeyPair ATTACKER_KEYS = rsa(2048);
    static final String PEM = pem(ISSUER_KEYS);
    static final Instant FUTURE = Instant.now().plusSeconds(300);

    static KeyPair rsa(int bits) {
        try {
            var gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(bits);
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String pem(KeyPair pair) {
        return "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder().encodeToString(pair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";
    }

    static String sign(KeyPair keys, String subject, List<String> roles, String issuer, String audience, Instant exp) throws Exception {
        var claims = new JWTClaimsSet.Builder().subject(subject).claim("roles", roles).expirationTime(Date.from(exp));
        if (issuer != null) claims.issuer(issuer);
        if (audience != null) claims.audience(audience);
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
        jwt.sign(new com.nimbusds.jose.crypto.RSASSASigner(keys.getPrivate()));
        return jwt.serialize();
    }

    private static final String SUB = UUID.randomUUID().toString();

    /** Spring Security 7 also adds a FACTOR_BEARER marker authority; only the role authorities matter here. */
    private static List<String> roles(org.springframework.security.oauth2.jwt.Jwt jwt) {
        return JwtVerification.authenticationConverter().convert(jwt).getAuthorities().stream()
                .map(a -> a.getAuthority()).filter(a -> a.startsWith("ROLE_")).toList();
    }

    @Test
    void acceptsAValidTokenAndMapsRolesToAuthorities() throws Exception {
        var jwt = JwtVerification.decoder(PEM).decode(sign(ISSUER_KEYS, SUB, List.of("ADMIN"), "paynexa-identity", "paynexa-api", FUTURE));
        assertThat(JwtVerification.isAdmin(jwt)).isTrue();
        assertThat(JwtVerification.userId(jwt)).hasToString(SUB);
        assertThat(roles(jwt)).containsExactly("ROLE_ADMIN");
    }

    @Test
    void unknownRolesGrantNothing() throws Exception {
        var jwt = JwtVerification.decoder(PEM).decode(sign(ISSUER_KEYS, SUB, List.of("SUPERUSER", "root"), "paynexa-identity", "paynexa-api", FUTURE));
        assertThat(roles(jwt)).isEmpty();
        assertThat(JwtVerification.isAdmin(jwt)).isFalse();
    }

    @Test
    void rejectsATokenSignedByAnotherKey() throws Exception {
        var forged = sign(ATTACKER_KEYS, SUB, List.of("ADMIN"), "paynexa-identity", "paynexa-api", FUTURE);
        assertThatThrownBy(() -> JwtVerification.decoder(PEM).decode(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsExpiredWrongIssuerWrongOrMissingAudience() throws Exception {
        var decoder = JwtVerification.decoder(PEM);
        assertThatThrownBy(() -> decoder.decode(sign(ISSUER_KEYS, SUB, List.of("USER"), "paynexa-identity", "paynexa-api", Instant.now().minusSeconds(600))))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(sign(ISSUER_KEYS, SUB, List.of("USER"), "evil", "paynexa-api", FUTURE)))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(sign(ISSUER_KEYS, SUB, List.of("USER"), null, "paynexa-api", FUTURE)))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(sign(ISSUER_KEYS, SUB, List.of("USER"), "paynexa-identity", "other-api", FUTURE)))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(sign(ISSUER_KEYS, SUB, List.of("USER"), "paynexa-identity", null, FUTURE)))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsNonUuidSubjects() throws Exception {
        assertThatThrownBy(() -> JwtVerification.decoder(PEM)
                .decode(sign(ISSUER_KEYS, "admin", List.of("USER"), "paynexa-identity", "paynexa-api", FUTURE)))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsAlgNoneUnsignedTokens() {
        var b64 = Base64.getUrlEncoder().withoutPadding();
        var unsigned = b64.encodeToString("{\"alg\":\"none\"}".getBytes()) + "."
                + b64.encodeToString(("{\"sub\":\"" + SUB + "\",\"iss\":\"paynexa-identity\",\"aud\":\"paynexa-api\",\"roles\":[\"ADMIN\"]}").getBytes()) + ".";
        assertThatThrownBy(() -> JwtVerification.decoder(PEM).decode(unsigned)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsHmacConfusionAttackUsingThePublicKeyAsTheHmacSecret() throws Exception {
        // classic attack: sign with HS256 using the (public) key bytes as the secret and hope the verifier accepts HMAC
        var secret = pem(ISSUER_KEYS).getBytes();
        var claims = new JWTClaimsSet.Builder().subject(SUB).issuer("paynexa-identity").audience("paynexa-api")
                .expirationTime(Date.from(FUTURE)).claim("roles", List.of("ADMIN")).build();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(secret));
        assertThatThrownBy(() -> JwtVerification.decoder(PEM).decode(jwt.serialize())).isInstanceOf(JwtException.class);
    }

    @Test
    void refusesToStartWithMissingMalformedOrWeakKeys() {
        assertThatThrownBy(() -> JwtVerification.decoder(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JwtVerification.decoder(" ")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JwtVerification.decoder("not a key")).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("not a key");
        assertThatThrownBy(() -> JwtVerification.decoder(pem(rsa(1024)))).hasMessageContaining("2048");
    }
}
