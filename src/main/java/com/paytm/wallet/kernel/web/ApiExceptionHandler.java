package com.paytm.wallet.kernel.web;

import com.paytm.wallet.kernel.obs.DomainMetrics;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.sql.SQLException;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turns every failure into a specific problem+json response. Nothing reaches a
 * client as a stack trace, and the only path that can produce a 5xx is the
 * catch-all at the bottom - which is counted, so "zero 5xx under the burst"
 * is a number we publish rather than a claim we make.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final DomainMetrics metrics;

    public ApiExceptionHandler(DomainMetrics metrics) {
        this.metrics = metrics;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex) {
        return respond(ex.code(), ex.getMessage());
    }

    // ---- malformed input -----------------------------------------------

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return respond(ErrorCode.INVALID_REQUEST, detail.isBlank() ? "Invalid request body" : detail);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex) {
        // Deliberately does not echo ex.getMessage(): it can contain the raw
        // parser trace including a fragment of the caller's body.
        return respond(ErrorCode.INVALID_REQUEST, "Request body is not valid JSON, or a field has the wrong type");
    }

    @ExceptionHandler({MissingRequestValueException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> handleMissing(Exception ex) {
        return respond(ErrorCode.INVALID_REQUEST, "A required parameter is missing or malformed");
    }

    /**
     * An unknown path is not a missing wallet. Reusing WALLET_NOT_FOUND here
     * meant that browsing to the service root reported a wallet lookup
     * failure, which is both wrong and confusing to anyone exploring the API.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex) {
        return respond(ErrorCode.ENDPOINT_NOT_FOUND, ErrorCode.ENDPOINT_NOT_FOUND.defaultDetail());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethod(HttpRequestMethodNotSupportedException ex) {
        return respond(ErrorCode.INVALID_REQUEST, "Method not supported for this endpoint");
    }

    // ---- contention ------------------------------------------------------

    /**
     * Every SQLSTATE below means the same operationally useful thing: the
     * transaction rolled back and NOTHING was applied, so the caller may retry
     * safely with the same idempotency_key.
     *
     * That is why these become a 503 with Retry-After rather than an opaque
     * 500. For a money API the difference between "retryable, nothing
     * happened" and "unknown" is the difference between a safe client retry
     * and a support ticket.
     *
     * Catching the concrete leaf types here was not enough - found by
     * burst.sh, which turned up 500s under a 300-way burst:
     *   - lock_timeout surfaces as PessimisticLockingFailureException, or as
     *     an UncategorizedSQLException when the translator does not recognise
     *     55P03 at all;
     *   - pool exhaustion surfaces as CannotCreateTransactionException, which
     *     is a TransactionException and not a DataAccessException at all.
     * So we classify on SQLSTATE from the cause chain, with the Spring type as
     * a fallback, rather than trying to enumerate translator leaf classes.
     */
    private static final Set<String> RETRYABLE_SQL_STATES = Set.of(
            "40001",   // serialization_failure
            "40P01",   // deadlock_detected
            "55P03",   // lock_not_available  (our lock_timeout)
            "57014",   // query_canceled      (our statement_timeout)
            "53300",   // too_many_connections
            "08000", "08003", "08006"  // connection exceptions
    );

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<ApiError> handleDataAccess(Exception ex, HttpServletRequest request) {
        String sqlState = sqlStateOf(ex);
        boolean retryable = RETRYABLE_SQL_STATES.contains(sqlState)
                || ex instanceof org.springframework.dao.TransientDataAccessException
                || ex instanceof org.springframework.dao.ConcurrencyFailureException
                || ex instanceof org.springframework.transaction.CannotCreateTransactionException;

        if (!retryable) {
            return handleUnexpected(ex, request);
        }

        // A deadlock reaching here means the lock-ordering argument in
        // TransferService has a hole, so it is logged loudly enough to notice
        // in the public logs rather than being lost among ordinary timeouts.
        if ("40P01".equals(sqlState)) {
            log.error("event=deadlock_detected path={} - lock ordering invariant violated",
                    request.getRequestURI());
        } else {
            log.warn("event=contention_timeout sqlstate={} class={} path={}",
                    sqlState, ex.getClass().getSimpleName(), request.getRequestURI());
        }

        return ResponseEntity.status(ErrorCode.SERVICE_BUSY.status())
                .header(HttpHeaders.RETRY_AFTER, "1")
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ApiError.of(ErrorCode.SERVICE_BUSY,
                        ErrorCode.SERVICE_BUSY.defaultDetail(), RequestContext.correlationId()));
    }

    /** Walks the cause chain for the first SQLException carrying a SQLSTATE. */
    private static String sqlStateOf(Throwable t) {
        for (Throwable c = t; c != null && c.getCause() != c; c = c.getCause()) {
            if (c instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return "";
    }

    // ---- client went away ------------------------------------------------

    /**
     * A client disconnecting is not a server error.
     *
     * Someone watching GET /debug/logs/stream and pressing Ctrl-C produces a
     * broken pipe on the next event. Left to the catch-all below, that would
     * log a full stack trace at ERROR and increment the 5xx counter - so the
     * act of watching the logs would falsify the "zero 5xx" claim those very
     * logs are meant to support. Found by the API coverage suite.
     *
     * There is also nothing to respond with: the socket is already gone. Hence
     * void, and DEBUG rather than ERROR.
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleClientDisconnect(AsyncRequestNotUsableException ex) {
        log.debug("event=client_disconnected msg={}", ex.getMessage());
    }

    // ---- last resort -----------------------------------------------------

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        metrics.serverError();
        // Full detail to the logs (which carry the correlation id), never to the wire.
        log.error("event=unhandled_exception method={} path={} class={}",
                request.getMethod(), request.getRequestURI(), ex.getClass().getName(), ex);
        ApiError body = new ApiError(
                "https://paytm-wallet.invalid/errors/internal",
                "Internal Server Error",
                500,
                "Unexpected error. Quote the correlation id when reporting this.",
                "INTERNAL_ERROR",
                RequestContext.correlationId());
        return ResponseEntity.status(500).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    private ResponseEntity<ApiError> respond(ErrorCode code, String detail) {
        if (code.status().is5xxServerError()) {
            metrics.serverError();
        }
        // A rejected request must be as traceable as a successful one: without
        // this, a caller quoting a correlation id for a 404 or a 409 would find
        // nothing in the logs at all.
        log.info("event=request.rejected code={} status={} detail={}",
                code.name(), code.status().value(), detail);
        return ResponseEntity.status(code.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ApiError.of(code, detail, RequestContext.correlationId()));
    }

    private static String rootMessage(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null && r.getCause() != r) {
            r = r.getCause();
        }
        return r.getMessage();
    }
}
