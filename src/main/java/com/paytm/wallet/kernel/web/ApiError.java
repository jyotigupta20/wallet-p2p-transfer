package com.paytm.wallet.kernel.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * RFC 9457 problem detail, plus a machine-readable {@code code} and the
 * correlation id so a caller can quote one id when reporting a problem.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        String type,
        String title,
        int status,
        String detail,
        String code,
        String correlationId) {

    public static ApiError of(ErrorCode code, String detail, String correlationId) {
        return new ApiError(
                "https://paytm-wallet.invalid/errors/" + code.name().toLowerCase(),
                code.status().getReasonPhrase(),
                code.status().value(),
                detail,
                code.name(),
                correlationId);
    }
}
