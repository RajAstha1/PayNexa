package io.paynexa.platform;

/** Claim names and fixed values shared by the token issuer (identity-service) and every verifier. */
public final class TokenClaims {

    public static final String ISSUER = "paynexa-identity";
    public static final String AUDIENCE = "paynexa-api";
    public static final String ROLES = "roles";
    public static final String ROLE_USER = "USER";
    public static final String ROLE_ADMIN = "ADMIN";

    private TokenClaims() {}
}
