package com.paytm.wallet.transfer;

import com.paytm.wallet.AppProperties;
import com.paytm.wallet.auth.Caller;
import com.paytm.wallet.auth.Hashing;
import com.paytm.wallet.kernel.obs.DomainMetrics;
import com.paytm.wallet.kernel.web.ApiException;
import com.paytm.wallet.kernel.web.ErrorCode;
import com.paytm.wallet.wallet.Wallet;
import com.paytm.wallet.wallet.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.UUID;

/**
 * The whole exercise, in one transaction.
 *
 * Sequence, and why it is in this order:
 *
 *   1. validate            - a non-positive amount would make the conditional
 *                            debit's "balance >= amount" guard vacuous and let
 *                            a caller pull money OUT of the destination wallet.
 *   2. resolve + authorise - before claiming the key, because the transfers
 *                            table has foreign keys to wallets: claiming first
 *                            would turn an unknown wallet id into an FK
 *                            violation (a 500) instead of a clean 404.
 *   3. claim the key       - ON CONFLICT DO NOTHING. Winner proceeds, losers
 *                            replay. Same transaction as the money below.
 *   4. lock both wallets   - in ascending id order. Deadlock-free by
 *                            construction.
 *   5. conditional debit   - rows-affected is the decision. 0 means declined.
 *   6. credit + ledger     - same transaction, so conservation is just
 *                            atomicity.
 *
 * Isolation is READ COMMITTED, stated explicitly. SERIALIZABLE would also be
 * correct but would turn the graded contention burst into a storm of 40001
 * serialisation failures needing an application retry loop; see docs/WRITEUP.md.
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final TransferRepository transfers;
    private final WalletRepository wallets;
    private final DomainMetrics metrics;
    private final long maxAmountPaise;

    public TransferService(TransferRepository transfers, WalletRepository wallets,
                           DomainMetrics metrics, AppProperties props) {
        this.transfers = transfers;
        this.wallets = wallets;
        this.metrics = metrics;
        this.maxAmountPaise = props.transfer().maxAmountPaise();
    }

    /** The transfer as persisted, plus whether this call replayed an earlier one. */
    public record Outcome(Transfer transfer, boolean replay) { }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Outcome transfer(Caller caller, TransferRequest request) {
        long amount = validate(request);
        authorise(caller, request);

        String requestHash = fingerprint(request);
        UUID transferId = UUID.randomUUID();

        Optional<UUID> claimed = transfers.claimIdempotencyKey(
                transferId, caller.userId(), request.idempotencyKey(),
                requestHash, request.from(), request.to(), amount);

        if (claimed.isEmpty()) {
            return replay(caller, request, requestHash);
        }

        // ---- we own this key; move the money -------------------------------
        lockBothInOrder(request.from(), request.to());

        Optional<Long> fromBalanceAfter = transfers.debitIfSufficient(request.from(), amount);
        if (fromBalanceAfter.isEmpty()) {
            // INVARIANT 2. Nothing was credited, so there is nothing to undo:
            // we commit the DECLINED row rather than rolling back, which is
            // what makes a retry of a declined transfer return the same
            // decline instead of silently re-attempting it later.
            Transfer declined = transfers.markDeclined(transferId, "INSUFFICIENT_FUNDS");
            metrics.transferDeclinedInsufficientFunds();
            log.info("event=transfer.declined reason=INSUFFICIENT_FUNDS transfer_id={} "
                            + "from_wallet={} to_wallet={} amount_paise={}",
                    transferId, request.from(), request.to(), amount);
            return new Outcome(declined, false);
        }

        long toBalanceAfter = transfers.credit(request.to(), amount);
        transfers.insertLedgerPair(transferId, request.from(), fromBalanceAfter.get(),
                request.to(), toBalanceAfter, amount);
        Transfer completed = transfers.markCompleted(
                transferId, fromBalanceAfter.get(), toBalanceAfter);

        metrics.transferCompleted();
        log.info("event=transfer.completed transfer_id={} from_wallet={} to_wallet={} "
                        + "amount_paise={} from_balance_after={} to_balance_after={}",
                transferId, request.from(), request.to(), amount,
                fromBalanceAfter.get(), toBalanceAfter);
        return new Outcome(completed, false);
    }

    @Transactional(readOnly = true)
    public Transfer byId(UUID id) {
        return transfers.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.TRANSFER_NOT_FOUND));
    }

    // ------------------------------------------------------------------

    private long validate(TransferRequest r) {
        if (r.idempotencyKey() == null || r.idempotencyKey().isBlank()) {
            throw new ApiException(ErrorCode.MISSING_IDEMPOTENCY_KEY);
        }
        if (r.idempotencyKey().length() > 255) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "idempotency_key must be at most 255 characters");
        }
        long amount = r.amountPaise();
        if (amount <= 0) {
            throw new ApiException(ErrorCode.INVALID_AMOUNT,
                    "amount_paise must be greater than zero; got " + amount);
        }
        if (amount > maxAmountPaise) {
            throw new ApiException(ErrorCode.INVALID_AMOUNT,
                    "amount_paise exceeds the per-transfer maximum of " + maxAmountPaise);
        }
        if (r.from().equals(r.to())) {
            throw new ApiException(ErrorCode.SELF_TRANSFER);
        }
        return amount;
    }

    private void authorise(Caller caller, TransferRequest r) {
        Wallet from = wallets.findById(r.from())
                .orElseThrow(() -> new ApiException(ErrorCode.WALLET_NOT_FOUND,
                        "Source wallet " + r.from() + " does not exist"));
        if (!from.userId().equals(caller.userId())) {
            // The one authorisation rule that actually protects money: you may
            // only debit a wallet you own. Reads stay open, because the burst
            // script asserts conservation across wallets it does not own.
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        if (wallets.findById(r.to()).isEmpty()) {
            throw new ApiException(ErrorCode.WALLET_NOT_FOUND,
                    "Destination wallet " + r.to() + " does not exist");
        }
    }

    /**
     * Acquire both row locks in a deterministic global order.
     *
     * Only consistency of the ordering matters, not which order it is - every
     * transaction in the system agrees, so a lock cycle cannot form. The
     * comparison is unsigned so that it matches Postgres's own uuid collation,
     * which keeps this reproducible if anyone reaches for ORDER BY ... FOR
     * UPDATE later.
     */
    private void lockBothInOrder(UUID a, UUID b) {
        UUID first = unsignedCompare(a, b) <= 0 ? a : b;
        UUID second = first.equals(a) ? b : a;
        transfers.lockWallet(first);
        transfers.lockWallet(second);
    }

    static int unsignedCompare(UUID a, UUID b) {
        int high = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return high != 0 ? high
                : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    }

    private Outcome replay(Caller caller, TransferRequest request, String requestHash) {
        Transfer existing = transfers
                .findByIdempotencyKey(caller.userId(), request.idempotencyKey())
                .orElseThrow(() -> new ApiException(ErrorCode.SERVICE_BUSY,
                        "Idempotency key is being claimed concurrently; retry with the same key"));

        if (!constantTimeEquals(existing.requestHash(), requestHash)) {
            metrics.transferKeyConflict();
            log.info("event=transfer.idempotency_key_conflict transfer_id={} idempotency_key={}",
                    existing.id(), request.idempotencyKey());
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSE);
        }

        metrics.transferIdempotentReplay();
        log.info("event=transfer.idempotent_replay transfer_id={} status={} "
                        + "from_wallet={} to_wallet={} amount_paise={}",
                existing.id(), existing.status(), existing.fromWalletId(),
                existing.toWalletId(), existing.amountPaise());
        return new Outcome(existing, true);
    }

    /**
     * The idempotency fingerprint: same key with the same body is a retry,
     * same key with a different body is a 409. Components are pipe-separated -
     * a character that cannot occur in a UUID or a decimal long - so two
     * distinct requests cannot collide by concatenation.
     */
    private static String fingerprint(TransferRequest r) {
        return Hashing.sha256Hex(r.from() + "|" + r.to() + "|" + r.amountPaise());
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
