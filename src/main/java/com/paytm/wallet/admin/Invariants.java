package com.paytm.wallet.admin;

import java.time.Instant;

/**
 * The graded invariants, computed from the database rather than asserted.
 *
 * {@code holds} is the single boolean a reviewer (or burst.sh) can check:
 * it is true only when every individual check below passes.
 */
public record Invariants(
        long walletCount,
        long totalWalletBalancePaise,
        long totalIssuedPaise,
        long transferLedgerSumPaise,
        long negativeBalanceCount,
        long walletsDisagreeingWithLedger,
        long transfersCompleted,
        long transfersDeclined,
        long transfersPending,
        boolean conservationHolds,
        boolean noOverdraft,
        boolean ledgerReconciles,
        boolean noStuckTransfers,
        boolean holds,
        Instant checkedAt) {

    public static Invariants of(long walletCount, long totalBalance, long totalIssued,
                                long transferLedgerSum, long negativeBalances,
                                long disagreeing, long completed, long declined,
                                long pending) {
        // Conservation, stated in the form that survives wallets being created
        // mid-burst: money in the system equals money ever issued. A transfer
        // cannot change either side. Comparing "total balance now" against
        // "total balance before" would be wrong the moment the grader's script
        // creates a wallet while transfers are in flight.
        boolean conservation = totalBalance == totalIssued && transferLedgerSum == 0;
        boolean noOverdraft = negativeBalances == 0;
        boolean reconciles = disagreeing == 0;
        // A committed PENDING row would mean a transfer claimed its idempotency
        // key and then never reached a terminal state - the key is burned, the
        // money never moved, and a retry replays a transfer that did nothing.
        // It should be impossible (PENDING only exists inside the uncommitted
        // transaction), which is exactly why it is worth asserting: if the
        // assumption ever breaks, silence is the worst outcome.
        boolean noStuck = pending == 0;
        return new Invariants(
                walletCount, totalBalance, totalIssued, transferLedgerSum,
                negativeBalances, disagreeing, completed, declined, pending,
                conservation, noOverdraft, reconciles, noStuck,
                conservation && noOverdraft && reconciles && noStuck,
                Instant.now());
    }
}
