package io.paynexa.payments.api;

import io.paynexa.payments.api.Dtos.PaymentResponse;
import io.paynexa.payments.api.Dtos.PayoutRequest;
import io.paynexa.payments.api.Dtos.TransferRequest;
import io.paynexa.payments.domain.DomainException;
import io.paynexa.payments.domain.Money;
import io.paynexa.payments.persistence.AccountRepository;
import io.paynexa.payments.persistence.HistoryRepository;
import io.paynexa.payments.persistence.PaymentRepository;
import io.paynexa.payments.service.PaymentService;
import io.paynexa.payments.service.PaymentService.PayoutCommand;
import io.paynexa.payments.service.PaymentService.TransferCommand;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/payments")
class PaymentController {

    private final PaymentService payments;
    private final PaymentRepository paymentRepo;
    private final AccountRepository accountRepo;
    private final HistoryRepository history;

    PaymentController(PaymentService payments, PaymentRepository paymentRepo, AccountRepository accountRepo, HistoryRepository history) {
        this.payments = payments;
        this.paymentRepo = paymentRepo;
        this.accountRepo = accountRepo;
        this.history = history;
    }

    /**
     * 201 for a newly created payment (including committed business outcomes such as FAILED/REJECTED/PENDING_REVIEW,
     * which are reported in {@code status}); 200 when the Idempotency-Key matched an earlier identical request.
     */
    @PostMapping("/transfers")
    ResponseEntity<PaymentResponse> transfer(@AuthenticationPrincipal Jwt jwt,
                                             @RequestHeader("Idempotency-Key") String key,
                                             @Valid @RequestBody TransferRequest body) {
        var amount = Money.parse(body.amount(), body.currency());
        var result = payments.transfer(Actors.from(jwt), key,
                new TransferCommand(body.fromAccountId(), body.toAccountId(), amount, body.reference()));
        return respond(result);
    }

    @PostMapping("/payouts")
    ResponseEntity<PaymentResponse> payout(@AuthenticationPrincipal Jwt jwt,
                                           @RequestHeader("Idempotency-Key") String key,
                                           @Valid @RequestBody PayoutRequest body) {
        var amount = Money.parse(body.amount(), body.currency());
        var result = payments.payout(Actors.from(jwt), key,
                new PayoutCommand(body.fromAccountId(), body.destination(), amount, body.reference()));
        return respond(result);
    }

    @GetMapping("/{id}")
    PaymentResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return PaymentResponse.from(visible(jwt, id));
    }

    @GetMapping("/{id}/history")
    List<HistoryEntry> history(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        visible(jwt, id);
        return history.history(id).stream()
                .map(h -> new HistoryEntry(h.from() == null ? null : h.from().name(), h.to().name(), h.at()))
                .toList();
    }

    @GetMapping
    List<PaymentResponse> list(@AuthenticationPrincipal Jwt jwt,
                               @RequestParam(defaultValue = "50") int limit,
                               @RequestParam(defaultValue = "0") int offset) {
        var actor = Actors.from(jwt);
        var mine = accountRepo.findByOwner(actor.userId()).stream().map(a -> a.id()).toList();
        if (mine.isEmpty()) {
            return List.of();
        }
        return paymentRepo.listForAccounts(mine, Math.min(Math.max(limit, 1), 100), Math.max(offset, 0))
                .stream().map(PaymentResponse::from).toList();
    }

    record HistoryEntry(String from, String to, java.time.Instant at) {}

    /** A payment is visible to the owners of its payer/payee account and to admins; everyone else gets 404. */
    private io.paynexa.payments.domain.Payment visible(Jwt jwt, UUID id) {
        var actor = Actors.from(jwt);
        var payment = paymentRepo.find(id).orElseThrow(() -> DomainException.notFound("PAYMENT_NOT_FOUND", "Payment not found"));
        if (actor.admin()) {
            return payment;
        }
        var involved = java.util.stream.Stream.of(payment.payerAccountId(), payment.payeeAccountId())
                .filter(java.util.Objects::nonNull)
                .map(accountRepo::find).flatMap(java.util.Optional::stream)
                .anyMatch(a -> actor.userId().equals(a.ownerUserId()));
        if (!involved) {
            throw DomainException.notFound("PAYMENT_NOT_FOUND", "Payment not found");
        }
        return payment;
    }

    private static ResponseEntity<PaymentResponse> respond(PaymentService.Result r) {
        var body = PaymentResponse.from(r.payment());
        var builder = ResponseEntity.status(r.replayed() ? HttpStatus.OK : HttpStatus.CREATED);
        if (r.replayed()) {
            builder.header("Idempotent-Replay", "true");
        }
        return builder.body(body);
    }
}
