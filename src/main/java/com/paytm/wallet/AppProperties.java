package com.paytm.wallet;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed view of the {@code app.*} configuration tree. */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String instanceId,
        Auth auth,
        Wallet wallet,
        Transfer transfer,
        Observability observability) {

    public record Auth(boolean autoProvision, String adminToken) { }

    public record Wallet(long openingBalancePaise) { }

    public record Transfer(long maxAmountPaise) { }

    public record Observability(int logBufferSize) { }
}
