package io.paynexa.payments.api;

import io.paynexa.payments.domain.DomainException;
import io.paynexa.platform.Correlation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;

/**
 * RFC 9457 problem responses with stable {@code code}s. Messages are written for API clients: they never contain
 * stack traces, SQL, class names, or values taken from the request.
 */
@RestControllerAdvice
class ProblemAdvice {

    private static final Logger log = LoggerFactory.getLogger(ProblemAdvice.class);

    @ExceptionHandler(DomainException.class)
    ProblemDetail domain(DomainException e) {
        var status = switch (e.category()) {
            case INVALID -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case UNPROCESSABLE -> HttpStatus.UNPROCESSABLE_CONTENT;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
        };
        return problem(status, e.code(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException e) {
        var fields = e.getBindingResult().getFieldErrors().stream().map(f -> f.getField()).distinct().sorted().toList();
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Invalid request fields: " + String.join(", ", fields));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ProblemDetail unreadable(Exception e) {
        return problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "The request could not be parsed");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ProblemDetail missingHeader(MissingRequestHeaderException e) {
        return problem(HttpStatus.BAD_REQUEST, "MISSING_HEADER", "Required header is missing: " + e.getHeaderName());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail denied(AccessDeniedException e) {
        return problem(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to perform this action");
    }

    @ExceptionHandler({CannotGetJdbcConnectionException.class, DataAccessResourceFailureException.class,
            QueryTimeoutException.class, TransientDataAccessException.class})
    ProblemDetail databaseUnavailable(Exception e) {
        log.error("database unavailable or contended: {}", e.getClass().getSimpleName());
        var p = problem(HttpStatus.SERVICE_UNAVAILABLE, "TEMPORARILY_UNAVAILABLE", "The service is temporarily unavailable. Retry with the same Idempotency-Key.");
        p.setProperty("retryable", true);
        return p;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        // Spring's own HTTP errors (no such route, wrong method, unsupported media type...) keep their status.
        if (e instanceof org.springframework.web.ErrorResponse er && er.getStatusCode().is4xxClientError()) {
            var status = HttpStatus.valueOf(er.getStatusCode().value());
            return problem(status, "HTTP_" + status.value(), status.getReasonPhrase());
        }
        // The full exception goes to the log with the correlation id; the client gets neither message nor class.
        log.error("unhandled exception", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred. Quote the correlation id when reporting it.");
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        var p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setType(URI.create("https://docs.paynexa.invalid/problems/" + code.toLowerCase().replace('_', '-')));
        p.setTitle(status.getReasonPhrase());
        p.setProperty("code", code);
        var correlation = Correlation.current();
        if (correlation != null) {
            p.setProperty("correlationId", correlation);
        }
        return p;
    }
}
