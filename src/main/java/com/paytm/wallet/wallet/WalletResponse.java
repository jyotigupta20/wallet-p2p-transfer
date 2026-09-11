package com.paytm.wallet.wallet;

import java.util.UUID;

/**
 * Body of POST /wallets and GET /wallets/{id}.
 *
 * Whether this call created the wallet or found an existing one is reported in
 * the X-Wallet-Created response header, NOT here - so N concurrent
 * get-or-create calls for the same user return byte-identical bodies.
 */
public record WalletResponse(UUID walletId, UUID userId, long balancePaise) {

    public static WalletResponse from(Wallet w) {
        return new WalletResponse(w.id(), w.userId(), w.balancePaise());
    }
}
