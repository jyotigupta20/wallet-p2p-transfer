package com.paytm.wallet.wallet;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class WalletRepository {

    private final JdbcClient db;

    public WalletRepository(JdbcClient db) {
        this.db = db;
    }

    /**
     * INVARIANT 4 - race-free get-or-create, in one statement.
     *
     * UNIQUE (user_id) is what actually enforces it; ON CONFLICT DO NOTHING is
     * how we survive losing the race without raising an error. RETURNING id
     * yields the new id when we won the insert and nothing when we lost, so the
     * caller knows definitively which happened - important, because only the
     * winner may write the OPENING ledger row.
     *
     * A concurrent inserter that has not yet committed makes this statement
     * BLOCK on the unique index until they commit or abort, which is exactly
     * the serialisation we want. Crucially it does not abort our transaction
     * the way a raw unique-violation would.
     */
    public Optional<UUID> insertIfAbsent(UUID id, UUID userId, long openingBalancePaise) {
        return db.sql("""
                    INSERT INTO wallets (id, user_id, balance_paise)
                    VALUES (:id, :userId, :balance)
                    ON CONFLICT (user_id) DO NOTHING
                    RETURNING id
                    """)
                .param("id", id)
                .param("userId", userId)
                .param("balance", openingBalancePaise)
                .query(UUID.class)
                .optional();
    }

    public Optional<Wallet> findByUserId(UUID userId) {
        return db.sql("SELECT id, user_id, balance_paise FROM wallets WHERE user_id = :u")
                .param("u", userId)
                .query(WalletRepository::map)
                .optional();
    }

    public Optional<Wallet> findById(UUID id) {
        return db.sql("SELECT id, user_id, balance_paise FROM wallets WHERE id = :id")
                .param("id", id)
                .query(WalletRepository::map)
                .optional();
    }

    /** Records money entering the system, so conservation stays exactly checkable. */
    public void insertOpeningEntry(UUID walletId, long amountPaise) {
        db.sql("""
                    INSERT INTO ledger_entries
                        (transfer_id, wallet_id, kind, signed_amount_paise, balance_after)
                    VALUES (NULL, :w, 'OPENING', :amt, :amt)
                    """)
                .param("w", walletId)
                .param("amt", amountPaise)
                .update();
    }

    private static Wallet map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Wallet(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getLong("balance_paise"));
    }
}
