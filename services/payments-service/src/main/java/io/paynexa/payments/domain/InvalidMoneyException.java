package io.paynexa.payments.domain;

public class InvalidMoneyException extends DomainException {
    public InvalidMoneyException(String code, String message) {
        super(code, message);
    }
}
