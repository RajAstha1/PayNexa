package io.paynexa.payments.domain;

/** Base for expected, client-correctable failures. Carries a stable machine-readable code and an HTTP-level category. */
public class DomainException extends RuntimeException {

    public enum Category { INVALID, NOT_FOUND, CONFLICT, UNPROCESSABLE, FORBIDDEN }

    private final String code;
    private final Category category;

    public DomainException(String code, String message) {
        this(code, message, Category.INVALID);
    }

    public DomainException(String code, String message, Category category) {
        super(message);
        this.code = code;
        this.category = category;
    }

    public String code() {
        return code;
    }

    public Category category() {
        return category;
    }

    public static DomainException notFound(String code, String message) {
        return new DomainException(code, message, Category.NOT_FOUND);
    }

    public static DomainException unprocessable(String code, String message) {
        return new DomainException(code, message, Category.UNPROCESSABLE);
    }

    public static DomainException conflict(String code, String message) {
        return new DomainException(code, message, Category.CONFLICT);
    }
}
