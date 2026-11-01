package io.paynexa.payments.api;

import io.paynexa.payments.api.Dtos.PaymentResponse;
import io.paynexa.payments.api.Dtos.PayoutRequest;
import io.paynexa.payments.api.Dtos.TransferRequest;
import io.paynexa.payments.domain.DomainException;
import io.paynexa.payments.domain.Money;
import io.paynexa.payments.domain.PaymentKind;
import io.paynexa.payments.domain.PaymentStatus;
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

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/payments")
class PaymentController {

    private final PaymentService payments;
    private final PaymentRepository paymentRepo;
    private final AccountRepository accountRepo;
    private final HistoryRepository history;
    private final Clock clock;

    PaymentController(PaymentService payments, PaymentRepository paymentRepo, AccountRepository accountRepo, HistoryRepository history, Clock clock) {
        this.clock = clock;
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

    /**
     * The caller's payments, newest first. Optional filters: {@code status}, {@code kind}, {@code direction} (in|out relative
     * to the caller's accounts) and an inclusive UTC date range {@code from}/{@code to} (YYYY-MM-DD). Page with
     * {@code limit} (max 100) and {@code offset}; ask for limit+1 to learn whether another page exists.
     */
    @GetMapping
    List<PaymentResponse> list(@AuthenticationPrincipal Jwt jwt,
                               @RequestParam(defaultValue = "50") int limit,
                               @RequestParam(defaultValue = "0") int offset,
                               @RequestParam(required = false) String status,
                               @RequestParam(required = false) String kind,
                               @RequestParam(required = false) String direction,
                               @RequestParam(required = false) String from,
                               @RequestParam(required = false) String to,
                               @RequestParam(required = false) UUID accountId,
                               @RequestParam(required = false) String q) {
        var actor = Actors.from(jwt);
        var mine = accountRepo.findByOwner(actor.userId()).stream().map(a -> a.id()).toList();
        if (accountId != null) {
            // Narrowing to one account is only allowed for an account the caller owns.
            if (!mine.contains(accountId)) {
                throw DomainException.notFound("ACCOUNT_NOT_FOUND", "Account not found");
            }
            mine = List.of(accountId);
        }
        if (mine.isEmpty()) {
            return List.of();
        }
        var text = q == null || q.isBlank() ? null : q.trim();
        if (text != null && text.length() > 50) {
            throw new DomainException("INVALID_FILTER", "Search text must be at most 50 characters");
        }
        var filter = new PaymentRepository.Filter(
                parseEnum(PaymentStatus.class, status, "status"), parseEnum(PaymentKind.class, kind, "kind"),
                parseEnum(PaymentRepository.Direction.class, direction, "direction"),
                parseDate(from, "from") == null ? null : parseDate(from, "from").atStartOfDay(ZoneOffset.UTC).toInstant(),
                parseDate(to, "to") == null ? null : parseDate(to, "to").plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                text);
        if (filter.from() != null && filter.toExclusive() != null && !filter.from().isBefore(filter.toExclusive())) {
            throw new DomainException("INVALID_FILTER", "'from' must not be after 'to'");
        }
        return paymentRepo.listForAccounts(mine, filter, Math.min(Math.max(limit, 1), 101), Math.max(offset, 0))
                .stream().map(PaymentResponse::from).toList();
    }

    /**
     * Money movement for charts: per UTC day and currency over the last {@code days} (7, 30, 90 or 365), plus totals for that
     * period and for the equally long period before it. Only payments that moved money count; transfers between the
     * caller's own accounts are internal and excluded. Never converts currencies.
     */
    @GetMapping("/stats")
    StatsResponse stats(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "30") int days) {
        if (days != 7 && days != 30 && days != 90 && days != 365) {
            throw new DomainException("INVALID_FILTER", "days must be 7, 30, 90 or 365");
        }
        var mine = accountRepo.findByOwner(Actors.from(jwt).userId()).stream().map(a -> a.id()).toList();
        var today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        var start = today.minusDays(days - 1L);
        var end = today.plusDays(1);
        var prevStart = start.minusDays(days);
        if (mine.isEmpty()) {
            return new StatsResponse(days, start, today, List.of(), List.of(), List.of());
        }
        var buckets = paymentRepo.dailyVolume(mine, at(start), at(end)).stream()
                .map(b -> new Bucket(b.day(), b.currency(), Money.format(b.incomingMinor(), b.currency()),
                        Money.format(b.outgoingMinor(), b.currency()), b.count())).toList();
        var current = paymentRepo.totals(mine, at(start), at(end)).stream().map(StatsTotal::from).toList();
        var previous = paymentRepo.totals(mine, at(prevStart), at(start)).stream().map(StatsTotal::from).toList();
        return new StatsResponse(days, start, today, buckets, current, previous);
    }

    record Bucket(LocalDate date, String currency, String incoming, String outgoing, long count) {}

    record StatsTotal(String currency, String incoming, String outgoing, long count) {
        static StatsTotal from(PaymentRepository.Totals t) {
            return new StatsTotal(t.currency(), Money.format(t.incomingMinor(), t.currency()), Money.format(t.outgoingMinor(), t.currency()), t.count());
        }
    }

    record StatsResponse(int days, LocalDate from, LocalDate to, List<Bucket> buckets, List<StatsTotal> totals, List<StatsTotal> previousTotals) {}

    private static Instant at(LocalDate d) {
        return d.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String name) {
        if (value == null || value.isBlank()) return null;
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new DomainException("INVALID_FILTER", "Unknown value for '" + name + "'");
        }
    }

    private static LocalDate parseDate(String value, String name) {
        if (value == null || value.isBlank()) return null;
        try {
            var d = LocalDate.parse(value.trim());
            if (d.isBefore(LocalDate.of(2000, 1, 1)) || d.isAfter(LocalDate.of(2100, 1, 1))) throw new DateTimeParseException("range", value, 0);
            return d;
        } catch (DateTimeParseException e) {
            throw new DomainException("INVALID_FILTER", "'" + name + "' must be a date like 2026-10-31");
        }
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
