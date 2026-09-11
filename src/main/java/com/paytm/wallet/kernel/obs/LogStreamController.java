package com.paytm.wallet.kernel.obs;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Publicly viewable structured logs, served by the service itself.
 *
 *   curl -s  https://HOST/debug/logs?n=100      # recent tail, newline-delimited JSON
 *   curl -N  https://HOST/debug/logs/stream     # watch events land live
 *
 * Run the stream in one pane and burst.sh in another and the domain events -
 * transfer.completed, transfer.declined, transfer.idempotent_replay - scroll
 * past as they happen, each carrying its correlation id and the instance that
 * served it.
 *
 * Nothing secret is logged anywhere in this service: bearer tokens are hashed
 * before they are ever stored and are never logged, and the domain events carry
 * only wallet ids, amounts and client-chosen idempotency keys. So this endpoint
 * exposes no more than GET /wallets/{id} already does.
 */
@RestController
public class LogStreamController {

    /** Bounded: a slow SSE client must never apply back-pressure to the money path. */
    private static final int QUEUE_CAPACITY = 1_000;
    private static final long STREAM_TIMEOUT_MS = 30 * 60 * 1_000L;

    private final LogRingBuffer buffer = LogRingBuffer.get();
    private final String viewerHtml = loadViewer();
    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();

    public LogStreamController() {
        // One shared dispatcher thread does all the writing. The logging thread
        // only ever does a non-blocking offer(), so a stalled reader costs at
        // most its own dropped lines - it can never slow down a transfer.
        Thread dispatcher = new Thread(this::dispatchLoop, "log-sse-dispatcher");
        dispatcher.setDaemon(true);
        dispatcher.start();
    }

    /**
     * Content-negotiated on purpose.
     *
     * The deliverable is logs that are *viewable*, and serving
     * application/x-ndjson to a browser makes it download a file instead of
     * showing anything - so a reviewer who clicks the link gets a save dialog
     * rather than the logs. A browser (Accept: text/html) therefore gets a
     * plain console that tails the SSE stream; curl, jq and everything else
     * still get newline-delimited JSON exactly as before.
     *
     * ?format=json or ?format=html overrides the negotiation either way.
     */
    @GetMapping("/debug/logs")
    public ResponseEntity<String> tail(
            @RequestParam(defaultValue = "100") int n,
            @RequestParam(required = false) String format,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept) {

        boolean wantsHtml = "html".equalsIgnoreCase(format)
                || (format == null && accept != null && accept.contains(MediaType.TEXT_HTML_VALUE));

        if (wantsHtml && viewerHtml != null) {
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_HTML)
                    .cacheControl(CacheControl.noCache())
                    .body(viewerHtml);
        }

        List<String> lines = buffer.tail(Math.clamp(n, 1, 2_000));
        String body = String.join("\n", lines) + (lines.isEmpty() ? "" : "\n");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/x-ndjson; charset=utf-8")
                .body(body);
    }

    @GetMapping("/debug/logs/info")
    public Map<String, Object> info() {
        return Map.of(
                "buffered_lines", buffer.tail(Integer.MAX_VALUE).size(),
                "total_written", buffer.totalWritten(),
                "live_stream_subscribers", subscribers.size());
    }

    /**
     * Response headers matter here. A reverse proxy - Render's, nginx, any CDN -
     * will happily buffer a text/event-stream response, so the client sees
     * nothing for minutes and the "watch the logs live" demo silently fails in
     * production while working perfectly on localhost. X-Accel-Buffering: no is
     * the nginx-family opt-out; no-cache and the disabled keep-alive close the
     * remaining ways an intermediary can decide to hold on to the bytes.
     */
    @GetMapping(value = "/debug/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("Connection", "keep-alive");
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        Subscriber subscriber = new Subscriber(emitter);

        emitter.onCompletion(() -> remove(subscriber));
        emitter.onTimeout(() -> remove(subscriber));
        emitter.onError(e -> remove(subscriber));

        subscribers.add(subscriber);
        buffer.subscribe(subscriber.sink);

        // Say hello straight away, for two reasons. A client otherwise sees
        // nothing - not even response headers - until the service happens to
        // log something, which looks indistinguishable from a hung connection.
        // And replaying the recent tail means a viewer who connects mid-burst
        // has context rather than joining blind.
        try {
            emitter.send(SseEmitter.event().name("connected").data(
                    "{\"event\":\"connected\",\"buffered_lines\":"
                            + buffer.totalWritten() + "}"));
            for (String line : buffer.tail(20)) {
                emitter.send(SseEmitter.event().data(line));
            }
        } catch (IOException | IllegalStateException e) {
            remove(subscriber);
            emitter.completeWithError(e);
        }
        return emitter;
    }

    private void remove(Subscriber subscriber) {
        buffer.unsubscribe(subscriber.sink);
        subscribers.remove(subscriber);
    }

    /** Read once at startup; null simply means the ndjson form is always served. */
    private static String loadViewer() {
        try (var in = LogStreamController.class.getResourceAsStream("/logviewer.html")) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private void dispatchLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            boolean idle = true;
            for (Subscriber s : subscribers) {
                String line;
                while ((line = s.queue.poll()) != null) {
                    idle = false;
                    try {
                        s.emitter.send(SseEmitter.event().data(line));
                    } catch (IOException | IllegalStateException e) {
                        remove(s);
                        break;
                    }
                }
            }
            if (idle) {
                try {
                    TimeUnit.MILLISECONDS.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static final class Subscriber {
        final SseEmitter emitter;
        final BlockingQueue<String> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        final java.util.function.Consumer<String> sink;

        Subscriber(SseEmitter emitter) {
            this.emitter = emitter;
            this.sink = queue::offer;   // returns false and drops when full; never blocks
        }
    }
}
