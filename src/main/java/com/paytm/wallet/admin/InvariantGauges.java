package com.paytm.wallet.admin;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Publishes the invariants as Prometheus gauges, so correctness is visible on
 * the dashboard rather than only in a JSON endpoint.
 *
 * The useful one is wallet_total_balance_paise: during the graded conservation
 * burst it must be a flat line. A chart of money in the system that does not
 * move while a thousand concurrent transfers land is a more convincing
 * argument than any paragraph in the write-up.
 *
 * The snapshot is cached for a few seconds so that a scrape storm cannot turn
 * the invariant check itself into load on the money path.
 */
@Component
public class InvariantGauges {

    private static final Logger log = LoggerFactory.getLogger(InvariantGauges.class);
    private static final long CACHE_MILLIS = 5_000;

    private final InvariantRepository repository;
    private final MeterRegistry registry;
    private final AtomicReference<Cached> cached = new AtomicReference<>(null);

    private record Cached(Invariants value, long atMillis) { }

    public InvariantGauges(InvariantRepository repository, MeterRegistry registry) {
        this.repository = repository;
        this.registry = registry;
    }

    @PostConstruct
    void register() {
        Gauge.builder("wallet_total_balance_paise", this, g -> g.snapshot().totalWalletBalancePaise())
                .description("Sum of all wallet balances. Must not move across a transfer.")
                .register(registry);
        Gauge.builder("wallet_total_issued_paise", this, g -> g.snapshot().totalIssuedPaise())
                .description("Money ever issued into the system (OPENING + MINT ledger rows).")
                .register(registry);
        Gauge.builder("wallet_negative_balance_count", this, g -> g.snapshot().negativeBalanceCount())
                .description("Wallets with a negative balance. Must be zero.")
                .register(registry);
        Gauge.builder("wallet_ledger_mismatch_count", this, g -> g.snapshot().walletsDisagreeingWithLedger())
                .description("Wallets whose balance disagrees with their ledger. Must be zero.")
                .register(registry);
        Gauge.builder("wallet_stuck_transfers", this, g -> g.snapshot().transfersPending())
                .description("Transfers claimed but never finalised. Must be zero.")
                .register(registry);
        Gauge.builder("wallet_invariants_hold", this, g -> g.snapshot().holds() ? 1 : 0)
                .description("1 when every graded invariant currently holds, 0 otherwise.")
                .register(registry);
    }

    private Invariants snapshot() {
        Cached current = cached.get();
        long now = System.currentTimeMillis();
        if (current != null && now - current.atMillis() < CACHE_MILLIS) {
            return current.value();
        }
        try {
            Invariants fresh = repository.snapshot();
            cached.set(new Cached(fresh, now));
            if (!fresh.holds()) {
                // If this ever fires, it is the most important line in the log.
                log.error("event=invariant_violation total_balance_paise={} total_issued_paise={} "
                                + "transfer_ledger_sum={} negative_balances={} ledger_mismatches={}",
                        fresh.totalWalletBalancePaise(), fresh.totalIssuedPaise(),
                        fresh.transferLedgerSumPaise(), fresh.negativeBalanceCount(),
                        fresh.walletsDisagreeingWithLedger());
            }
            return fresh;
        } catch (RuntimeException e) {
            // A scrape must never fail because the database hiccuped.
            log.warn("event=invariant_snapshot_failed msg={}", e.getMessage());
            return current != null ? current.value()
                    : Invariants.of(0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }
}
