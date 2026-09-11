package com.paytm.wallet.kernel.web;

import com.paytm.wallet.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Stamps every request with a correlation id and the serving instance id, puts
 * both in the MDC (so the structured JSON log lines carry them), and echoes
 * them back as response headers.
 *
 * The instance id is what lets a reader of the public logs see that a burst was
 * genuinely spread across two deployed replicas - the point being that the
 * correctness guarantees come from Postgres, not from a single process.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    /** Callers may supply their own id; we only accept something sane to log. */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    private final String instanceId;

    public CorrelationIdFilter(AppProperties props) {
        this.instanceId = props.instanceId();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String correlationId = firstSafe(
                request.getHeader(RequestContext.HEADER_CORRELATION_ID),
                request.getHeader(RequestContext.HEADER_REQUEST_ID));

        MDC.put(RequestContext.CORRELATION_ID, correlationId);
        MDC.put(RequestContext.INSTANCE_ID, instanceId);
        response.setHeader(RequestContext.HEADER_CORRELATION_ID, correlationId);
        response.setHeader(RequestContext.HEADER_INSTANCE_ID, instanceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(RequestContext.CORRELATION_ID);
            MDC.remove(RequestContext.INSTANCE_ID);
        }
    }

    private static String firstSafe(String... candidates) {
        for (String c : candidates) {
            if (c != null && SAFE_ID.matcher(c).matches()) {
                return c;
            }
        }
        return UUID.randomUUID().toString();
    }
}
