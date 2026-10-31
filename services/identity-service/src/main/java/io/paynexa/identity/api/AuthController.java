package io.paynexa.identity.api;

import io.paynexa.identity.service.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
class AuthController {

    record Credentials(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 128) String password) {
        /** Credentials must never reach a log line through string formatting. */
        @Override
        public String toString() {
            return "Credentials[redacted]";
        }
    }

    // length is checked by the service so every malformed token gets the same 401 as an unknown one
    record RefreshRequest(@NotBlank @Size(max = 4096) String refreshToken) {
        @Override
        public String toString() {
            return "RefreshRequest[redacted]";
        }
    }

    record TokenResponse(String accessToken, String tokenType, long expiresIn, String refreshToken) {
        static TokenResponse of(AuthService.Tokens t) {
            return new TokenResponse(t.accessToken(), "Bearer", t.expiresInSeconds(), t.refreshToken());
        }

        @Override
        public String toString() {
            return "TokenResponse[redacted]";
        }
    }

    private final AuthService auth;

    AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/register")
    ResponseEntity<TokenResponse> register(@Valid @RequestBody Credentials body) {
        return tokens(HttpStatus.CREATED, auth.register(body.email(), body.password()));
    }

    @PostMapping("/login")
    ResponseEntity<TokenResponse> login(@Valid @RequestBody Credentials body) {
        return tokens(HttpStatus.OK, auth.login(body.email(), body.password()));
    }

    @PostMapping("/refresh")
    ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshRequest body) {
        return tokens(HttpStatus.OK, auth.refresh(body.refreshToken()));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest body) {
        auth.logout(body.refreshToken());
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<TokenResponse> tokens(HttpStatus status, AuthService.Tokens t) {
        // Responses that carry credentials must never be cached by browsers or intermediaries.
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).header("Pragma", "no-cache").body(TokenResponse.of(t));
    }
}
