package io.paynexa.platform;

import java.util.regex.Pattern;

/**
 * Best-effort scrubber for untrusted or semi-structured text before it is logged. It is a safety net: the primary
 * defence is never passing secrets or full request bodies to a logger in the first place.
 */
public final class Redactor {

    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*");
    private static final Pattern EMAIL = Pattern.compile("([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})");
    private static final Pattern KEYED_SECRET = Pattern.compile(
            "(?i)(\"?(?:password|passwd|secret|token|refresh_?token|access_?token|authorization|api[_-]?key|private[_-]?key)\"?\\s*[:=]\\s*)(\"[^\"]*\"|[^\\s,;&}]+)");
    private static final Pattern CARD = Pattern.compile("\\b(?:\\d[ -]?){13,19}\\b");

    private Redactor() {}

    public static String redact(String text) {
        if (text == null) {
            return null;
        }
        var out = BEARER.matcher(text).replaceAll("Bearer [REDACTED]");
        out = JWT.matcher(out).replaceAll("[REDACTED_JWT]");
        out = KEYED_SECRET.matcher(out).replaceAll("$1[REDACTED]");
        out = EMAIL.matcher(out).replaceAll("$1***@$2");
        out = CARD.matcher(out).replaceAll("[REDACTED_NUMBER]");
        return out;
    }

    /** Neutralises CR/LF and other control characters so user input cannot forge log lines. */
    public static String forLog(String text) {
        return text == null ? null : redact(text.replaceAll("[\\p{Cntrl}]", "_"));
    }
}
