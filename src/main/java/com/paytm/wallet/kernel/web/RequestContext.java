package com.paytm.wallet.kernel.web;

import org.slf4j.MDC;

/** Per-request identifiers, carried in the SLF4J MDC so every log line inherits them. */
public final class RequestContext {

    public static final String CORRELATION_ID = "correlation_id";
    public static final String INSTANCE_ID = "instance_id";
    public static final String HEADER_CORRELATION_ID = "X-Correlation-Id";
    public static final String HEADER_REQUEST_ID = "X-Request-Id";
    public static final String HEADER_INSTANCE_ID = "X-Instance-Id";

    private RequestContext() { }

    public static String correlationId() {
        String id = MDC.get(CORRELATION_ID);
        return id == null ? "-" : id;
    }
}
