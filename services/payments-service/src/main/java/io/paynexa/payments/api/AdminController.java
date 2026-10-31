package io.paynexa.payments.api;

import io.paynexa.payments.api.Dtos.AccountResponse;
import io.paynexa.payments.api.Dtos.FundRequest;
import io.paynexa.payments.api.Dtos.PaymentResponse;
import io.paynexa.payments.api.Dtos.ReasonRequest;
import io.paynexa.payments.api.Dtos.StatusRequest;
import io.paynexa.payments.domain.AccountStatus;
import io.paynexa.payments.domain.DomainException;
import io.paynexa.payments.domain.Money;
import io.paynexa.payments.persistence.OutboxRepository;
import io.paynexa.payments.persistence.PaymentRepository;
import io.paynexa.payments.recon.Finding;
import io.paynexa.payments.recon.ReconciliationService;
import io.paynexa.payments.service.AccountService;
import io.paynexa.payments.service.LifecycleService;
import io.paynexa.payments.service.PaymentService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Everything under /admin requires ROLE_ADMIN (enforced in the filter chain and again in the services). */
@RestController
@RequestMapping("/admin")
class AdminController {

    private final LifecycleService lifecycle;
    private final AccountService accounts;
    private final PaymentService payments;
    private final PaymentRepository paymentRepo;
    private final ReconciliationService reconciliation;
    private final OutboxRepository outbox;
    private final io.paynexa.payments.persistence.HistoryRepository audit;

    AdminController(LifecycleService lifecycle, AccountService accounts, PaymentService payments,
                    PaymentRepository paymentRepo, ReconciliationService reconciliation, OutboxRepository outbox,
                    io.paynexa.payments.persistence.HistoryRepository audit) {
        this.audit = audit;
        this.lifecycle = lifecycle;
        this.accounts = accounts;
        this.payments = payments;
        this.paymentRepo = paymentRepo;
        this.reconciliation = reconciliation;
        this.outbox = outbox;
    }

    @GetMapping("/payments/pending-review")
    List<PaymentResponse> pendingReview() {
        return paymentRepo.listPendingReview(100).stream().map(PaymentResponse::from).toList();
    }

    @PostMapping("/payments/{id}/approve")
    PaymentResponse approve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return PaymentResponse.from(lifecycle.approve(Actors.from(jwt), id));
    }

    @PostMapping("/payments/{id}/reject")
    PaymentResponse reject(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody(required = false) ReasonRequest body) {
        return PaymentResponse.from(lifecycle.reject(Actors.from(jwt), id, body == null ? null : body.reason()));
    }

    @PostMapping("/payments/{id}/reverse")
    PaymentResponse reverse(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody(required = false) ReasonRequest body) {
        return PaymentResponse.from(lifecycle.reverse(Actors.from(jwt), id, body == null ? null : body.reason()));
    }

    @PostMapping("/accounts/{id}/status")
    AccountResponse setStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody StatusRequest body) {
        AccountStatus status;
        try {
            status = AccountStatus.valueOf(body.status());
        } catch (IllegalArgumentException e) {
            throw new DomainException("INVALID_STATUS", "Status must be ACTIVE or FROZEN");
        }
        return AccountResponse.from(accounts.setStatus(Actors.from(jwt), id, status, body.reason()));
    }

    /** Synthetic top-up. Returns 404 unless sandbox funding is enabled by configuration. */
    @PostMapping("/accounts/{id}/fund")
    ResponseEntity<PaymentResponse> fund(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
                                         @PathVariable UUID id, @Valid @RequestBody FundRequest body) {
        var result = payments.fund(Actors.from(jwt), key, id, Money.parse(body.amount(), body.currency()));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(PaymentResponse.from(result.payment()));
    }

    @PostMapping("/reconciliation/run")
    Map<String, Object> reconcile(@AuthenticationPrincipal Jwt jwt) {
        var report = reconciliation.run("MANUAL");
        audit.audit(Actors.from(jwt).name(), "RECONCILIATION_RUN", "reconciliation", report.runId().toString(),
                "{\"findings\":%d}".formatted(report.findings().size()));
        return Map.of("runId", report.runId(), "clean", report.clean(),
                "findings", report.findings().stream().map(AdminController::view).toList());
    }

    @PostMapping("/outbox/requeue-dead")
    Map<String, Object> requeueDead(@AuthenticationPrincipal Jwt jwt) {
        int requeued = outbox.requeueDead();
        audit.audit(Actors.from(jwt).name(), "OUTBOX_REQUEUE_DEAD", "outbox", "all", "{\"requeued\":%d}".formatted(requeued));
        return Map.of("requeued", requeued);
    }

    private static Map<String, String> view(Finding f) {
        return Map.of("check", f.check(), "severity", f.severity().name(), "subject", f.subject(), "detail", f.detail());
    }
}
