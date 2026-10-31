package io.paynexa.payments.api;

import io.paynexa.payments.api.Dtos.AccountResponse;
import io.paynexa.payments.api.Dtos.OpenAccountRequest;
import io.paynexa.payments.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/accounts")
class AccountController {

    private final AccountService accounts;

    AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    /** Idempotent: opening an account for a currency you already hold returns the existing account. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    AccountResponse open(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody OpenAccountRequest body) {
        return AccountResponse.from(accounts.open(Actors.from(jwt), body.currency()));
    }

    @GetMapping
    List<AccountResponse> mine(@AuthenticationPrincipal Jwt jwt) {
        return accounts.listFor(Actors.from(jwt)).stream().map(AccountResponse::from).toList();
    }

    @GetMapping("/{id}")
    AccountResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return AccountResponse.from(accounts.get(Actors.from(jwt), id));
    }
}
