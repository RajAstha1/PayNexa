package io.paynexa.payments.api;

import io.paynexa.payments.domain.Account;
import io.paynexa.payments.domain.Money;
import io.paynexa.payments.domain.Payment;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/** Request/response shapes. Money crosses the API as a decimal string plus currency, never as a float. */
public final class Dtos {

    private Dtos() {}

    public record OpenAccountRequest(@NotBlank @Size(min = 3, max = 3) String currency) {}

    public record AccountResponse(UUID id, String currency, String status, String balance) {
        public static AccountResponse from(Account a) {
            return new AccountResponse(a.id(), a.currency(), a.status().name(), Money.format(a.balanceMinor(), a.currency()));
        }
    }

    public record TransferRequest(
            @NotNull UUID fromAccountId,
            @NotNull UUID toAccountId,
            @NotBlank @Size(max = 24) String amount,
            @NotBlank @Size(min = 3, max = 3) String currency,
            @Size(max = 140) String reference) {}

    public record PayoutRequest(
            @NotNull UUID fromAccountId,
            @NotBlank @Size(max = 140) String destination,
            @NotBlank @Size(max = 24) String amount,
            @NotBlank @Size(min = 3, max = 3) String currency,
            @Size(max = 140) String reference) {}

    public record FundRequest(@NotBlank @Size(max = 24) String amount, @NotBlank @Size(min = 3, max = 3) String currency) {}

    public record ReasonRequest(@Size(max = 200) String reason) {}

    public record StatusRequest(@NotBlank String status, @Size(max = 200) String reason) {}

    public record PaymentResponse(UUID id, String kind, String status, UUID fromAccountId, UUID toAccountId,
                                  String amount, String currency, String reference, String failureReason,
                                  Instant createdAt, Instant updatedAt) {
        /** Destination of a payout is intentionally never echoed back. */
        public static PaymentResponse from(Payment p) {
            return new PaymentResponse(p.id(), p.kind().name(), p.status().name(), p.payerAccountId(), p.payeeAccountId(),
                    Money.format(p.amountMinor(), p.currency()), p.currency(), p.reference(), p.failureReason(),
                    p.createdAt(), p.updatedAt());
        }
    }
}
