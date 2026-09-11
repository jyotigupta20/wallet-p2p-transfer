package com.paytm.wallet.transfer;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * Every statement here is deliberate. Read this class alongside
 * docs/WRITEUP.md - it is the whole correctness argument.
 */
@Repository
public class TransferRepository {

    private final JdbcClient db;

    public TransferRepository(JdbcClient db) {
        this.db = db;
    }

    // ------------------------------------------------------------------
    // INVARIANT 3 - exactly-once
    // ------------------------------------------------------------------

    /**
     * Claim the idempotency key. Returns the new transfer id if we won, empty
     * if this key was already used.
     *
     * Three properties matter, and all three come from ON CONFLICT DO NOTHING
     * rather than from catching a unique-violation in Java:
     *
     *  1. It does NOT abort the transaction. A raw 23505 would poison the
     *     Postgres transaction, forcing a rollback and a second connection
     *     just to read the winner's row - which is how naive implementations
     *     leak a 500 on the replay path.
     *  2. It BLOCKS on a concurrent uncommitted inserter of the same key and
     *     resolves once that transaction ends. That is what turns a K-way
     *     retry storm into one winner and K-1 clean replays.
     *  3. It runs in the SAME transaction as the debit and credit below, so
     *     the key and the money commit together or not at all. There is no
     *     window in which a key exists without its money, or money without
     *     its key.
     */
    public Optional<UUID> claimIdempotencyKey(UUID id, UUID userId, String idempotencyKey,
                                              String requestHash, UUID fromWalletId,
                                              UUID toWalletId, long amountPaise) {
        return db.sql("""
                    INSERT INTO transfers
                        (id, user_id, idempotency_key, request_hash,
                         from_wallet_id, to_wallet_id, amount_paise, status)
                    VALUES
                        (:id, :userId, :key, :hash, :from, :to, :amount, 'PENDING')
                    ON CONFLICT (user_id, idempotency_key) DO NOTHING
                    RETURNING id
                    """)
                .param("id", id)
                .param("userId", userId)
                .param("key", idempotencyKey)
                .param("hash", requestHash)
                .param("from", fromWalletId)
                .param("to", toWalletId)
                .param("amount", amountPaise)
                .query(UUID.class)
                .optional();
    }

    public Optional<Transfer> findByIdempotencyKey(UUID userId, String key) {
        return db.sql(SELECT_TRANSFER + " WHERE user_id = :u AND idempotency_key = :k")
                .param("u", userId)
                .param("k", key)
                .query(TransferRepository::map)
                .optional();
    }

    public Optional<Transfer> findById(UUID id) {
        return db.sql(SELECT_TRANSFER + " WHERE id = :id")
                .param("id", id)
                .query(TransferRepository::map)
                .optional();
    }

    // ------------------------------------------------------------------
    // Deadlock avoidance
    // ------------------------------------------------------------------

    /**
     * Take the row lock on one wallet.
     *
     * TransferService calls this TWICE, in ascending wallet-id order, before
     * touching any balance. That ordering is the first half of the answer to
     * "two transfers lock the same two wallets in opposite orders": A->B and
     * B->A both queue for the lower id first, so one waits for the other
     * instead of forming a cycle.
     *
     * FOR NO KEY UPDATE, not FOR UPDATE, is the second half - and it is the
     * half that is easy to get wrong. This was a real deadlock in this
     * service, found by burst.sh, not a hypothetical:
     *
     *   transfers has foreign keys to wallets(id). Inserting the transfer row
     *   therefore takes a FOR KEY SHARE lock on BOTH referenced wallet rows,
     *   and it takes them in the order the constraints are checked - request
     *   order, which we do not control - and holds them for the rest of the
     *   transaction. FOR KEY SHARE does not conflict with itself, so A->B and
     *   B->A happily end up each holding a shared lock on both rows. Then both
     *   try to upgrade to FOR UPDATE, which DOES conflict with FOR KEY SHARE,
     *   and each waits for a lock the other holds. Sorting the upgrade order
     *   cannot help: the shared locks were already taken on both rows before
     *   either upgrade began. Postgres detects the cycle and kills one of them
     *   with 40P01.
     *
     *   FOR NO KEY UPDATE is the correct lock strength here because we only
     *   ever modify balance_paise, never a key column - it is precisely the
     *   lock the subsequent UPDATE takes anyway. Critically it does NOT
     *   conflict with FOR KEY SHARE, so the foreign-key locks stop
     *   participating in the wait graph, and the sorted ordering is once again
     *   sufficient on its own.
     *
     * The alternative fix - dropping the foreign keys to remove the KEY SHARE
     * locks entirely - was rejected: it trades referential integrity in a money
     * schema for something a weaker lock already gives us for free.
     *
     * Returns empty if the wallet does not exist.
     */
    public Optional<Long> lockWallet(UUID walletId) {
        return db.sql("SELECT balance_paise FROM wallets WHERE id = :id FOR NO KEY UPDATE")
                .param("id", walletId)
                .query(Long.class)
                .optional();
    }

    // ------------------------------------------------------------------
    // INVARIANTS 1 and 2 - conservation and no overdraft
    // ------------------------------------------------------------------

    /**
     * The atomic conditional debit. The decision is made by the WHERE clause
     * and reported by rows-affected; there is no read-then-write in Java.
     *
     * Empty result => the balance was below the amount => cleanly declined.
     * RETURNING gives us the post-debit balance in the same round trip, which
     * is what gets persisted for byte-identical replays.
     *
     * The balance_paise >= 0 CHECK constraint on the table is the backstop: if
     * this predicate were ever wrong, the database itself would still refuse to
     * store a negative balance.
     */
    public Optional<Long> debitIfSufficient(UUID walletId, long amountPaise) {
        return db.sql("""
                    UPDATE wallets
                       SET balance_paise = balance_paise - :amt,
                           updated_at    = now()
                     WHERE id = :id
                       AND balance_paise >= :amt
                    RETURNING balance_paise
                    """)
                .param("id", walletId)
                .param("amt", amountPaise)
                .query(Long.class)
                .optional();
    }

    /** The matching credit. Same transaction as the debit: conservation is atomicity. */
    public long credit(UUID walletId, long amountPaise) {
        return db.sql("""
                    UPDATE wallets
                       SET balance_paise = balance_paise + :amt,
                           updated_at    = now()
                     WHERE id = :id
                    RETURNING balance_paise
                    """)
                .param("id", walletId)
                .param("amt", amountPaise)
                .query(Long.class)
                .single();
    }

    /**
     * Double-entry rows. UNIQUE (transfer_id, wallet_id) means a transfer can
     * touch each wallet at most once, so sum(signed_amount_paise) over all
     * TRANSFER_* rows is provably zero and conservation becomes a query rather
     * than a claim. See GET /admin/invariants.
     */
    public void insertLedgerPair(UUID transferId, UUID fromWalletId, long fromBalanceAfter,
                                 UUID toWalletId, long toBalanceAfter, long amountPaise) {
        db.sql("""
                    INSERT INTO ledger_entries
                        (transfer_id, wallet_id, kind, signed_amount_paise, balance_after)
                    VALUES
                        (:t, :from, 'TRANSFER_DEBIT',  :negAmt, :fromAfter),
                        (:t, :to,   'TRANSFER_CREDIT', :posAmt, :toAfter)
                    """)
                .param("t", transferId)
                .param("from", fromWalletId)
                .param("to", toWalletId)
                .param("negAmt", -amountPaise)
                .param("posAmt", amountPaise)
                .param("fromAfter", fromBalanceAfter)
                .param("toAfter", toBalanceAfter)
                .update();
    }

    // ------------------------------------------------------------------
    // Terminal writes
    // ------------------------------------------------------------------

    /*
     * Both terminal writes use RETURNING and hand back the persisted row.
     *
     * That is not a micro-optimisation for its own sake: the row we return is
     * literally the row a later replay will read back, so the first response
     * and every replay of it are guaranteed identical by construction rather
     * than by two code paths agreeing to build the same object.
     */

    public Transfer markCompleted(UUID transferId, long fromBalanceAfter, long toBalanceAfter) {
        return db.sql("""
                    UPDATE transfers
                       SET status = 'COMPLETED',
                           from_balance_after = :fromAfter,
                           to_balance_after   = :toAfter
                     WHERE id = :id
                    RETURNING id, user_id, idempotency_key, request_hash,
                              from_wallet_id, to_wallet_id, amount_paise,
                              status, decline_reason,
                              from_balance_after, to_balance_after, created_at
                    """)
                .param("id", transferId)
                .param("fromAfter", fromBalanceAfter)
                .param("toAfter", toBalanceAfter)
                .query(TransferRepository::map)
                .single();
    }

    public Transfer markDeclined(UUID transferId, String reason) {
        return db.sql("""
                    UPDATE transfers
                       SET status = 'DECLINED', decline_reason = :r
                     WHERE id = :id
                    RETURNING id, user_id, idempotency_key, request_hash,
                              from_wallet_id, to_wallet_id, amount_paise,
                              status, decline_reason,
                              from_balance_after, to_balance_after, created_at
                    """)
                .param("id", transferId)
                .param("r", reason)
                .query(TransferRepository::map)
                .single();
    }

    // ------------------------------------------------------------------

    private static final String SELECT_TRANSFER = """
            SELECT id, user_id, idempotency_key, request_hash,
                   from_wallet_id, to_wallet_id, amount_paise,
                   status, decline_reason, from_balance_after, to_balance_after, created_at
              FROM transfers
            """;

    private static Transfer map(ResultSet rs, int rowNum) throws SQLException {
        return new Transfer(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("idempotency_key"),
                rs.getString("request_hash"),
                rs.getObject("from_wallet_id", UUID.class),
                rs.getObject("to_wallet_id", UUID.class),
                rs.getLong("amount_paise"),
                rs.getString("status"),
                rs.getString("decline_reason"),
                (Long) rs.getObject("from_balance_after"),
                (Long) rs.getObject("to_balance_after"),
                rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant());
    }
}
