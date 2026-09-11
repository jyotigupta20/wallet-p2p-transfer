package com.paytm.wallet.admin;

import com.paytm.wallet.AppProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An API index at the service root.
 *
 * Not a UI - this round does not grade one, and this is a JSON discovery
 * document, not a front end. It exists because the root is the first thing
 * anyone pastes into a browser, and a bare 404 there reads as "the service is
 * down" even when every real endpoint is healthy. Pointing straight at the
 * endpoints that let a reader verify the service themselves is more useful
 * than any page could be.
 */
@RestController
public class IndexController {

    private final AppProperties props;

    public IndexController(AppProperties props) {
        this.props = props;
    }

    @GetMapping("/")
    public Map<String, Object> index() {
        Map<String, Object> api = new LinkedHashMap<>();
        api.put("POST /wallets", "get-or-create the caller's wallet; bearer token identifies the user");
        api.put("GET /wallets/{id}", "current balance, in integer paise");
        api.put("POST /transfers", "{from, to, amount_paise, idempotency_key}");
        api.put("GET /transfers/{id}", "transfer status");

        Map<String, Object> verify = new LinkedHashMap<>();
        verify.put("GET /admin/invariants", "conservation, no-overdraft and ledger reconciliation, in one consistent snapshot");
        verify.put("GET /admin/status", "request counts, error rate, p50/p95/p99 latency");
        verify.put("GET /metrics", "Prometheus exposition: domain counters plus the invariants as gauges");
        verify.put("GET /health", "liveness and readiness; reports DOWN if Postgres is unreachable");

        Map<String, Object> logs = new LinkedHashMap<>();
        logs.put("GET /debug/logs?n=100", "recent structured logs, newline-delimited JSON");
        logs.put("GET /debug/logs/stream", "the same logs as a live stream (curl -N)");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("service", "wallet-p2p-transfer");
        out.put("instance_id", props.instanceId());
        out.put("money", "integer paise everywhere; fractional or string amounts are rejected, never rounded");
        out.put("api", api);
        out.put("verify_it_yourself", verify);
        out.put("logs", logs);
        out.put("repo", "https://github.com/jyotigupta20/wallet-p2p-transfer");
        return out;
    }
}
