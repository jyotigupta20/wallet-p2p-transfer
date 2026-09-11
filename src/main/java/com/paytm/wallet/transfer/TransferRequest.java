package com.paytm.wallet.transfer;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Wire shape: {"from": uuid, "to": uuid, "amount_paise": 1234,
 *              "idempotency_key": "..."}
 *
 * amount_paise is a boxed Long so that "absent" is distinguishable from 0 and
 * both get a precise error code rather than a generic bind failure. Jackson is
 * configured with accept-float-as-int=false, so 12.5 is rejected outright
 * rather than silently truncated to 12 - the money path never sees a float.
 */
public record TransferRequest(
        @NotNull(message = "is required") UUID from,
        @NotNull(message = "is required") UUID to,
        @NotNull(message = "is required") Long amountPaise,
        @NotNull(message = "is required") String idempotencyKey) { }
