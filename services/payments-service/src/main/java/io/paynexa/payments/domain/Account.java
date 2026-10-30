package io.paynexa.payments.domain;

import java.util.UUID;

public record Account(UUID id, AccountType type, UUID ownerUserId, String currency, AccountStatus status, long balanceMinor) {
    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }
}
