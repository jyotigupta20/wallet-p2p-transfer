package com.paytm.wallet.wallet;

import java.util.UUID;

/** A wallet row. Balance is always integer paise. */
public record Wallet(UUID id, UUID userId, long balancePaise) { }
