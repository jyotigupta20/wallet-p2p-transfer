package com.paytm.wallet.transfer;

import java.time.Instant;
import java.util.UUID;

/**
 * Body of POST /transfers and GET /transfers/{id}.
 *
 * Every field is read from the persisted transfer row - notably the
 * *_balance_after values, which are the balances as of THIS transfer rather
 * than a live re-read. That is what makes a replay byte-identical to the
 * original response even after other transfers have since moved the wallet.
 */
public record TransferResponse(
        UUID transferId,
        UUID from,
        UUID to,
        long amountPaise,
        String status,
        String declineReason,
        Long fromBalanceAfter,
        Long toBalanceAfter,
        Instant createdAt) {

    public static TransferResponse from(Transfer t) {
        return new TransferResponse(
                t.id(), t.fromWalletId(), t.toWalletId(), t.amountPaise(),
                t.status(), t.declineReason(),
                t.fromBalanceAfter(), t.toBalanceAfter(), t.createdAt());
    }
}
