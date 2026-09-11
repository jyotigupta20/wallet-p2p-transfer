package com.paytm.wallet.wallet;

import com.paytm.wallet.AppProperties;
import com.paytm.wallet.auth.Caller;
import com.paytm.wallet.kernel.obs.DomainMetrics;
import com.paytm.wallet.kernel.web.ApiException;
import com.paytm.wallet.kernel.web.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    private final WalletRepository wallets;
    private final DomainMetrics metrics;
    private final long openingBalancePaise;

    public WalletService(WalletRepository wallets, DomainMetrics metrics, AppProperties props) {
        this.wallets = wallets;
        this.metrics = metrics;
        this.openingBalancePaise = props.wallet().openingBalancePaise();
    }

    /** Outcome of get-or-create: the wallet, plus whether this call created it. */
    public record GetOrCreate(Wallet wallet, boolean created) { }

    /**
     * INVARIANT 4. The wallet row and its OPENING ledger row commit in one
     * transaction, so money can never appear in a balance without a matching
     * ledger entry to account for it.
     */
    @Transactional
    public GetOrCreate getOrCreate(Caller caller) {
        UUID candidateId = UUID.randomUUID();
        var inserted = wallets.insertIfAbsent(candidateId, caller.userId(), openingBalancePaise);

        if (inserted.isPresent()) {
            UUID walletId = inserted.get();
            if (openingBalancePaise > 0) {
                wallets.insertOpeningEntry(walletId, openingBalancePaise);
            }
            metrics.walletCreated();
            log.info("event=wallet.created wallet_id={} user_id={} opening_balance_paise={}",
                    walletId, caller.userId(), openingBalancePaise);
            return new GetOrCreate(
                    new Wallet(walletId, caller.userId(), openingBalancePaise), true);
        }

        // We lost the insert race (or the wallet simply already existed). The
        // winner's row is the real one; ours was never written.
        Wallet existing = wallets.findByUserId(caller.userId())
                .orElseThrow(() -> new IllegalStateException(
                        "wallet insert conflicted but no row found for user " + caller.userId()));
        metrics.walletGetOrCreateRaceLost();
        log.info("event=wallet.get_or_create.existing wallet_id={} user_id={}",
                existing.id(), caller.userId());
        return new GetOrCreate(existing, false);
    }

    @Transactional(readOnly = true)
    public Wallet byId(UUID id) {
        return wallets.findById(id).orElseThrow(() -> new ApiException(ErrorCode.WALLET_NOT_FOUND));
    }
}
