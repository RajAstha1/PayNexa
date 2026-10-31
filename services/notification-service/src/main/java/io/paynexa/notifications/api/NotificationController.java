package io.paynexa.notifications.api;

import io.paynexa.notifications.persistence.NotificationStore;
import io.paynexa.platform.JwtVerification;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/notifications")
class NotificationController {

    private final NotificationStore store;

    NotificationController(NotificationStore store) {
        this.store = store;
    }

    /** A user only ever sees their own notifications: the id comes from the token, never from the request. */
    @GetMapping
    List<NotificationStore.Notification> mine(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "50") int limit) {
        return store.forUser(JwtVerification.userId(jwt), Math.min(Math.max(limit, 1), 100));
    }

    @PostMapping("/{id}/read")
    ResponseEntity<?> markRead(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        if (!store.markRead(JwtVerification.userId(jwt), id)) {
            var p = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Notification not found");
            p.setProperty("code", "NOTIFICATION_NOT_FOUND");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(p);
        }
        return ResponseEntity.noContent().build();
    }
}
