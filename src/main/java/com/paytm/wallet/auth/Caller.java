package com.paytm.wallet.auth;

import java.util.UUID;

/** The authenticated user behind a bearer token. */
public record Caller(UUID userId, String externalId) { }
