package com.paytm.wallet.admin;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * A single curl that answers "is this healthy and how fast is it" without
 * needing a Prometheus to query.
 *
 * The latency numbers are computed here rather than exported as quantile=
 * series, because those two are mutually exclusive in Micrometer's Prometheus
 * exposition and exporting buckets is the correct choice for a multi-replica
 * deployment: percentiles cannot be averaged, so a p99 per instance cannot be
 * combined into a p99 for the service. Exporting buckets lets Grafana compute a
 * true service-wide p99 with histogram_quantile(); this endpoint runs the same
 * interpolation locally so the number is also readable with curl alone.
 */
@RestController
public class StatusController {

    private final MeterRegistry registry;
    private final String instanceId;

    public StatusController(MeterRegistry registry,
                            com.paytm.wallet.AppProperties props) {
        this.registry = registry;
        this.instanceId = props.instanceId();
    }

    @GetMapping("/admin/status")
    public Map<String, Object> status() {
        Collection<Timer> timers = registry.find("http.server.requests").timers();

        long total = 0;
        long serverErrors = 0;
        long clientErrors = 0;
        double totalSeconds = 0;
        TreeMap<Double, Double> merged = new TreeMap<>();

        for (Timer t : timers) {
            long count = t.count();
            total += count;
            totalSeconds += t.totalTime(java.util.concurrent.TimeUnit.SECONDS);

            String status = t.getId().getTag("status");
            if (status != null && status.length() == 3) {
                if (status.charAt(0) == '5') {
                    serverErrors += count;
                } else if (status.charAt(0) == '4') {
                    clientErrors += count;
                }
            }
            HistogramSnapshot snapshot = t.takeSnapshot();
            for (CountAtBucket b : snapshot.histogramCounts()) {
                merged.merge(b.bucket(java.util.concurrent.TimeUnit.SECONDS), b.count(), Double::sum);
            }
        }

        Map<String, Object> latency = new LinkedHashMap<>();
        latency.put("p50_ms", quantileMillis(merged, 0.50));
        latency.put("p95_ms", quantileMillis(merged, 0.95));
        latency.put("p99_ms", quantileMillis(merged, 0.99));
        latency.put("mean_ms", total == 0 ? 0.0 : round(totalSeconds / total * 1000));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("instance_id", instanceId);
        out.put("uptime_seconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        out.put("requests_total", total);
        out.put("client_errors_4xx", clientErrors);
        out.put("server_errors_5xx", serverErrors);
        out.put("error_rate_5xx", total == 0 ? 0.0 : round((double) serverErrors / total));
        out.put("latency", latency);
        out.put("domain", domainCounters());
        return out;
    }

    private Map<String, Object> domainCounters() {
        Map<String, Object> domain = new LinkedHashMap<>();
        registry.find("wallet_transfers_total").counters().forEach(c ->
                domain.put("transfers_" + c.getId().getTag("result"), (long) c.count()));
        registry.find("wallet_wallets_created_total").counters().forEach(c ->
                domain.put("wallets_created", (long) c.count()));
        registry.find("wallet_get_or_create_race_lost_total").counters().forEach(c ->
                domain.put("get_or_create_race_lost", (long) c.count()));
        return domain;
    }

    /**
     * Prometheus-style histogram_quantile: find the bucket containing the
     * target rank, then interpolate linearly within it. Cumulative counts, so
     * the final bucket carries the total.
     */
    private static double quantileMillis(TreeMap<Double, Double> cumulative, double q) {
        if (cumulative.isEmpty()) {
            return 0.0;
        }
        double total = cumulative.lastEntry().getValue();
        if (total <= 0) {
            return 0.0;
        }
        double target = q * total;
        double previousBound = 0;
        double previousCount = 0;
        for (Map.Entry<Double, Double> e : cumulative.entrySet()) {
            if (e.getValue() >= target) {
                double bucketCount = e.getValue() - previousCount;
                double withinBucket = bucketCount <= 0 ? 0
                        : (target - previousCount) / bucketCount;
                double seconds = previousBound + (e.getKey() - previousBound) * withinBucket;
                return round(seconds * 1000);
            }
            previousBound = e.getKey();
            previousCount = e.getValue();
        }
        return round(cumulative.lastKey() * 1000);
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
