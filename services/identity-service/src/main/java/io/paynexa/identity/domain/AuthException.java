package io.paynexa.identity.domain;

/** Expected authentication/registration failures with a stable code. Messages never reveal which factor was wrong. */
public class AuthException extends RuntimeException {

    public enum Kind { INVALID_INPUT, CONFLICT, UNAUTHENTICATED }

    private final String code;
    private final Kind kind;

    public AuthException(Kind kind, String code, String message) {
        super(message);
        this.kind = kind;
        this.code = code;
    }

    public String code() {
        return code;
    }

    public Kind kind() {
        return kind;
    }

    public static AuthException invalidCredentials() {
        return new AuthException(Kind.UNAUTHENTICATED, "INVALID_CREDENTIALS", "Invalid email or password");
    }

    public static AuthException invalidRefreshToken() {
        return new AuthException(Kind.UNAUTHENTICATED, "INVALID_REFRESH_TOKEN", "The refresh token is invalid or expired");
    }
}
