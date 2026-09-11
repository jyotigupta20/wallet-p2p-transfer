package com.paytm.wallet.kernel.obs;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * The domain counters the brief asks for, plus a 5xx counter.
 *
 * The 5xx counter is deliberate. "This service never returns a 5xx under load"
 * is a claim worth publishing as a number rather than leaving a reader to
 * infer it from logs - so it sits at /metrics, and burst.sh asserts it is
 * zero after every run.
 */
@Component
public class DomainMetrics {

    private final Counter transfersCompleted;
    private final Counter transfersDeclinedInsufficientFunds;
    private final Counter transfersIdempotentReplays;
    private final Counter transfersKeyConflicts;
    private final Counter walletsCreated;
    private final Counter walletsGetOrCreateRaceLost;
    private final Counter serverErrors;

    public DomainMetrics(MeterRegistry registry) {
        this.transfersCompleted = Counter.builder("wallet_transfers_total")
                .tag("result", "completed")
                .description("Transfers that moved money")
                .register(registry);
        this.transfersDeclinedInsufficientFunds = Counter.builder("wallet_transfers_total")
                .tag("result", "declined_insufficient_funds")
                .description("Transfers cleanly declined for insufficient balance")
                .register(registry);
        this.transfersIdempotentReplays = Counter.builder("wallet_transfers_total")
                .tag("result", "idempotent_replay")
                .description("Repeat requests served from the original stored result")
                .register(registry);
        this.transfersKeyConflicts = Counter.builder("wallet_transfers_total")
                .tag("result", "idempotency_key_conflict")
                .description("Same idempotency_key replayed with a different body (409)")
                .register(registry);
        this.walletsCreated = Counter.builder("wallet_wallets_created_total")
                .description("Wallets actually inserted")
                .register(registry);
        this.walletsGetOrCreateRaceLost = Counter.builder("wallet_get_or_create_race_lost_total")
                .description("Concurrent POST /wallets that lost the insert race and read back the winner")
                .register(registry);
        this.serverErrors = Counter.builder("wallet_server_errors_total")
                .description("Responses served with a 5xx status - expected to stay at zero")
                .register(registry);
    }

    public void transferCompleted() {
        transfersCompleted.increment();
    }

    public void transferDeclinedInsufficientFunds() {
        transfersDeclinedInsufficientFunds.increment();
    }

    public void transferIdempotentReplay() {
        transfersIdempotentReplays.increment();
    }

    public void transferKeyConflict() {
        transfersKeyConflicts.increment();
    }

    public void walletCreated() {
        walletsCreated.increment();
    }

    public void walletGetOrCreateRaceLost() {
        walletsGetOrCreateRaceLost.increment();
    }

    public void serverError() {
        serverErrors.increment();
    }
}
