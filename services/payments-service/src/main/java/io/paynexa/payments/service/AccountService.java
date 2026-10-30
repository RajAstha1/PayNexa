package io.paynexa.payments.service;

import io.paynexa.payments.domain.Account;
import io.paynexa.payments.domain.AccountStatus;
import io.paynexa.payments.domain.AccountType;
import io.paynexa.payments.domain.DomainException;
import io.paynexa.payments.domain.Money;
import io.paynexa.payments.persistence.AccountRepository;
import io.paynexa.payments.persistence.HistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final HistoryRepository audit;
    private final TransactionTemplate tx;

    public AccountService(AccountRepository accounts, HistoryRepository audit, TransactionTemplate tx) {
        this.accounts = accounts;
        this.audit = audit;
        this.tx = tx;
    }

    public Account open(Actor actor, String currency) {
        if (!Money.SUPPORTED.contains(currency)) {
            throw new DomainException("UNSUPPORTED_CURRENCY", "Unsupported currency");
        }
        return tx.execute(s -> accounts.openCustomerAccount(actor.userId(), currency));
    }

    public List<Account> listFor(Actor actor) {
        return accounts.findByOwner(actor.userId());
    }

    /** Owner or admin only. Anything else looks exactly like a missing account. */
    public Account get(Actor actor, UUID id) {
        return accounts.find(id)
                .filter(a -> a.type() == AccountType.CUSTOMER)
                .filter(a -> actor.admin() || actor.userId().equals(a.ownerUserId()))
                .orElseThrow(() -> DomainException.notFound("ACCOUNT_NOT_FOUND", "Account not found"));
    }

    public Account setStatus(Actor admin, UUID accountId, AccountStatus status, String reason) {
        if (!admin.admin()) {
            throw new DomainException("FORBIDDEN", "Administrator role required", DomainException.Category.FORBIDDEN);
        }
        return tx.execute(s -> {
            var before = accounts.find(accountId)
                    .filter(a -> a.type() == AccountType.CUSTOMER)
                    .orElseThrow(() -> DomainException.notFound("ACCOUNT_NOT_FOUND", "Account not found"));
            accounts.setStatus(accountId, status);
            audit.audit(admin.name(), status == AccountStatus.FROZEN ? "ACCOUNT_FROZEN" : "ACCOUNT_UNFROZEN",
                    "account", accountId.toString(),
                    "{\"from\":\"%s\",\"to\":\"%s\",\"reason\":\"%s\"}".formatted(before.status(), status, safe(reason)));
            return accounts.find(accountId).orElseThrow();
        });
    }

    static String safe(String text) {
        if (text == null) {
            return "";
        }
        var t = text.length() > 200 ? text.substring(0, 200) : text;
        return t.replaceAll("[\"\\\\\\p{Cntrl}]", " ");
    }
}
