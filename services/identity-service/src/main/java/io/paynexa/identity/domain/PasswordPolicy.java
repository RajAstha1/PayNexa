package io.paynexa.identity.domain;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * Length-based policy in line with NIST SP 800-63B: no composition rules, a minimum length, a blocklist of the most
 * obvious choices, and the bcrypt input limit (72 bytes) enforced explicitly instead of silently truncating.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_BYTES = 72;

    private static final Set<String> COMMON = Set.of(
            "password1234", "123456789012", "qwertyuiop12", "iloveyou1234", "administrator", "passwordpassword",
            "letmein123456", "welcome12345", "changeme1234", "password12345", "1234567890123");

    private PasswordPolicy() {}

    public static void validate(String password, String email) {
        if (password == null || password.codePointCount(0, password.length()) < MIN_LENGTH) {
            throw invalid("WEAK_PASSWORD", "Password must be at least " + MIN_LENGTH + " characters long");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw invalid("WEAK_PASSWORD", "Password must be at most " + MAX_BYTES + " bytes long");
        }
        var lower = password.toLowerCase(Locale.ROOT);
        if (COMMON.contains(lower) || (email != null && lower.contains(localPart(email))) || allSameCharacter(password)) {
            throw invalid("WEAK_PASSWORD", "Password is too easy to guess");
        }
    }

    private static String localPart(String email) {
        var at = email.indexOf('@');
        var local = at > 0 ? email.substring(0, at) : email;
        return local.length() < 4 ? "\u0000" : local.toLowerCase(Locale.ROOT);
    }

    private static boolean allSameCharacter(String s) {
        return s.chars().distinct().count() == 1;
    }

    private static AuthException invalid(String code, String message) {
        return new AuthException(AuthException.Kind.INVALID_INPUT, code, message);
    }
}
