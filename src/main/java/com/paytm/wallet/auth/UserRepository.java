package com.paytm.wallet.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class UserRepository {

    private final JdbcClient db;

    public UserRepository(JdbcClient db) {
        this.db = db;
    }

    public Optional<Caller> findByTokenHash(String tokenHash) {
        return db.sql("SELECT id, external_id FROM users WHERE token_hash = :h")
                .param("h", tokenHash)
                .query((rs, n) -> new Caller(rs.getObject("id", UUID.class), rs.getString("external_id")))
                .optional();
    }

    /**
     * Race-free insert. Two concurrent first-requests for the same token both
     * reach here; ON CONFLICT DO NOTHING means one inserts and the other reads
     * back the winner. Same shape as the wallet get-or-create.
     */
    public int insertIfAbsent(UUID id, String externalId, String tokenHash) {
        return db.sql("""
                    INSERT INTO users (id, external_id, token_hash)
                    VALUES (:id, :ext, :h)
                    ON CONFLICT (token_hash) DO NOTHING
                    """)
                .param("id", id)
                .param("ext", externalId)
                .param("h", tokenHash)
                .update();
    }
}
