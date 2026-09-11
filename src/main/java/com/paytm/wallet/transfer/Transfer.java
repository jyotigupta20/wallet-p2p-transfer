package com.paytm.wallet.transfer;

import java.time.Instant;
import java.util.UUID;

/** A transfer row. All amounts integer paise. */
public record Transfer(
        UUID id,
        UUID userId,
        String idempotencyKey,
        String requestHash,
        UUID fromWalletId,
        UUID toWalletId,
        long amountPaise,
        String status,
        String declineReason,
        Long fromBalanceAfter,
        Long toBalanceAfter,
        Instant createdAt) {

    public static final String COMPLETED = "COMPLETED";
    public static final String DECLINED = "DECLINED";
    public static final String PENDING = "PENDING";

    public boolean isCompleted() {
        return COMPLETED.equals(status);
    }
}
