package com.paytm.wallet.kernel.web;

/** A failure that maps to a specific {@link ErrorCode} and therefore a specific 4xx/5xx. */
public class ApiException extends RuntimeException {

    private final ErrorCode code;

    public ApiException(ErrorCode code) {
        this(code, code.defaultDetail());
    }

    public ApiException(ErrorCode code, String detail) {
        super(detail);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        // These are expected control flow (declined, replayed, forbidden), not
        // faults. Under a 1000-way burst, filling in stack traces for tens of
        // thousands of clean rejections is pure overhead.
        return this;
    }
}
