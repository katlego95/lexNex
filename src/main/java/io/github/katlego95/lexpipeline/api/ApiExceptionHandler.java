package io.github.katlego95.lexpipeline.api;

import io.github.katlego95.lexpipeline.batch.InvalidBatchRequestException;
import io.github.katlego95.lexpipeline.store.StorageFailedException;
import java.io.UncheckedIOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns anything that still escapes a controller into {@code application/problem+json}.
 *
 * <p>The pipeline itself does not throw — every document failure is an outcome — so what lands
 * here is the layer above it: a malformed request, or a genuine service fault. Both still owe the
 * caller a machine-readable answer rather than an HTML error page or a stack trace, which is what
 * a Spring app returns by default.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(InvalidBatchRequestException.class)
    ProblemDetail invalidBatchRequest(InvalidBatchRequestException e) {
        return Problems.of(HttpStatus.BAD_REQUEST, "invalid-batch-request",
                "Invalid batch request", e.getMessage());
    }

    @ExceptionHandler(StorageFailedException.class)
    ProblemDetail storageFailed(StorageFailedException e) {
        log.error("Storage failure serving a request", e);
        return Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, "storage-failed", "Storage failed",
                "The artifact store could not be read or written. Retrying is safe.");
    }

    @ExceptionHandler(UncheckedIOException.class)
    ProblemDetail requestBodyUnreadable(UncheckedIOException e) {
        log.warn("Could not read a request body", e);
        return Problems.of(HttpStatus.BAD_REQUEST, "unreadable-request",
                "Request body could not be read", String.valueOf(e.getMessage()));
    }

    /**
     * The backstop. Deliberately says nothing about the failure beyond that it happened: an error
     * body is attacker-readable, and the detail belongs in the log where the operator is.
     */
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("Unhandled failure serving a request", e);
        return Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal error",
                "The request could not be completed.");
    }
}
