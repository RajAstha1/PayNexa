package io.paynexa.notifications.api;

import io.paynexa.platform.Correlation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ProblemAdvice {

    private static final Logger log = LoggerFactory.getLogger(ProblemAdvice.class);

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        if (e instanceof org.springframework.web.ErrorResponse er && er.getStatusCode().is4xxClientError()) {
            var status = HttpStatus.valueOf(er.getStatusCode().value());
            return problem(status, "HTTP_" + status.value(), status.getReasonPhrase());
        }
        if (e instanceof org.springframework.security.access.AccessDeniedException) {
            return problem(HttpStatus.FORBIDDEN, "FORBIDDEN", "Forbidden");
        }
        log.error("unhandled exception", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred");
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        var p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setProperty("code", code);
        if (Correlation.current() != null) {
            p.setProperty("correlationId", Correlation.current());
        }
        return p;
    }
}
