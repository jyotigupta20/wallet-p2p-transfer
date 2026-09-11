package com.paytm.wallet.admin;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class InvariantRepository {

    private final JdbcClient db;

    public InvariantRepository(JdbcClient db) {
        this.db = db;
    }

    /**
     * One round trip, one consistent snapshot.
     *
     * The last subquery is the strongest check in the whole service: it
     * recomputes every wallet balance from its ledger entries and counts the
     * wallets that disagree. If the service ever applied a debit without its
     * matching ledger row, or double-applied a credit, this number goes
     * non-zero even though every individual balance still looks plausible.
     */
    public Invariants snapshot() {
        return db.sql("""
                SELECT
                  (SELECT count(*) FROM wallets)                        AS wallet_count,
                  (SELECT coalesce(sum(balance_paise), 0) FROM wallets)  AS total_balance,
                  (SELECT coalesce(sum(signed_amount_paise), 0)
                     FROM ledger_entries
                    WHERE kind IN ('OPENING', 'MINT'))                   AS total_issued,
                  (SELECT coalesce(sum(signed_amount_paise), 0)
                     FROM ledger_entries
                    WHERE kind IN ('TRANSFER_DEBIT', 'TRANSFER_CREDIT')) AS transfer_ledger_sum,
                  (SELECT count(*) FROM wallets WHERE balance_paise < 0) AS negative_balances,
                  (SELECT count(*) FROM (
                       SELECT w.id
                         FROM wallets w
                         LEFT JOIN ledger_entries l ON l.wallet_id = w.id
                        GROUP BY w.id, w.balance_paise
                       HAVING w.balance_paise <> coalesce(sum(l.signed_amount_paise), 0)
                   ) mismatched)                                         AS disagreeing,
                  (SELECT count(*) FROM transfers WHERE status = 'COMPLETED') AS completed,
                  (SELECT count(*) FROM transfers WHERE status = 'DECLINED')  AS declined
                """)
                .query((rs, n) -> Invariants.of(
                        rs.getLong("wallet_count"),
                        rs.getLong("total_balance"),
                        rs.getLong("total_issued"),
                        rs.getLong("transfer_ledger_sum"),
                        rs.getLong("negative_balances"),
                        rs.getLong("disagreeing"),
                        rs.getLong("completed"),
                        rs.getLong("declined")))
                .single();
    }
}
