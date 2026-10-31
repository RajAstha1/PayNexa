package io.paynexa.identity.api;

import io.paynexa.identity.domain.AuthException;
import io.paynexa.platform.Correlation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ProblemAdvice {

    private static final Logger log = LoggerFactory.getLogger(ProblemAdvice.class);

    @ExceptionHandler(AuthException.class)
    ProblemDetail auth(AuthException e) {
        var status = switch (e.kind()) {
            case INVALID_INPUT -> HttpStatus.BAD_REQUEST;
            case CONFLICT -> HttpStatus.CONFLICT;
            case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
        };
        return problem(status, e.code(), e.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ProblemDetail invalid(Exception e) {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The request is invalid");
    }

    @ExceptionHandler({CannotGetJdbcConnectionException.class, TransientDataAccessException.class})
    ProblemDetail unavailable(Exception e) {
        log.error("database unavailable: {}", e.getClass().getSimpleName());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "TEMPORARILY_UNAVAILABLE", "The service is temporarily unavailable");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        if (e instanceof org.springframework.web.ErrorResponse er && er.getStatusCode().is4xxClientError()) {
            var status = HttpStatus.valueOf(er.getStatusCode().value());
            return problem(status, "HTTP_" + status.value(), status.getReasonPhrase());
        }
        log.error("unhandled exception", e);   // never include request bodies: they contain credentials
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred");
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        var p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setTitle(status.getReasonPhrase());
        p.setProperty("code", code);
        if (Correlation.current() != null) {
            p.setProperty("correlationId", Correlation.current());
        }
        return p;
    }
}
