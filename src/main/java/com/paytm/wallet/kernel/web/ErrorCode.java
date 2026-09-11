package com.paytm.wallet.kernel.web;

import org.springframework.http.HttpStatus;

/**
 * Every failure the API can produce, each mapped to a specific 4xx/5xx.
 *
 * The exercise bank is explicit that a malformed or rejected request must yield
 * "a specific 4xx with a clear message - never a 500 or a stack trace", and that
 * 5xx must stay at zero under the bursts. Enumerating the failures here (rather
 * than throwing ad-hoc exceptions) is what makes that auditable.
 */
public enum ErrorCode {

    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Request body or parameters are invalid"),
    INVALID_AMOUNT(HttpStatus.BAD_REQUEST, "amount_paise must be a positive integer number of paise"),
    SELF_TRANSFER(HttpStatus.BAD_REQUEST, "from and to must be different wallets"),
    MISSING_IDEMPOTENCY_KEY(HttpStatus.BAD_REQUEST, "idempotency_key is required"),

    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "A valid bearer token is required"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "The caller does not own the source wallet"),

    WALLET_NOT_FOUND(HttpStatus.NOT_FOUND, "No such wallet"),
    TRANSFER_NOT_FOUND(HttpStatus.NOT_FOUND, "No such transfer"),

    IDEMPOTENCY_KEY_REUSE(HttpStatus.CONFLICT,
            "This idempotency_key was already used with a different request body"),

    INSUFFICIENT_FUNDS(HttpStatus.UNPROCESSABLE_ENTITY,
            "The source wallet does not have sufficient balance"),

    SERVICE_BUSY(HttpStatus.SERVICE_UNAVAILABLE,
            "Contention timeout - the request was not applied; retry with the same idempotency_key");

    private final HttpStatus status;
    private final String defaultDetail;

    ErrorCode(HttpStatus status, String defaultDetail) {
        this.status = status;
        this.defaultDetail = defaultDetail;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultDetail() {
        return defaultDetail;
    }
}
