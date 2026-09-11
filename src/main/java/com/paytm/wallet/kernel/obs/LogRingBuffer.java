package com.paytm.wallet.kernel.obs;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The last N structured log lines, in memory.
 *
 * The brief asks for logs that are "publicly viewable". On every free host the
 * platform's own log viewer sits behind a login, so the honest way to satisfy
 * that is to serve the logs from the service itself: GET /debug/logs for the
 * recent tail and GET /debug/logs/stream to watch domain events land live while
 * a burst is running.
 *
 * Deliberately bounded and in-memory. This is an observability window, not a
 * log store - the durable record of what happened to money is the ledger.
 */
public final class LogRingBuffer {

    private static final LogRingBuffer INSTANCE = new LogRingBuffer(2_000);

    private final Object lock = new Object();
    private final String[] entries;
    private final List<Consumer<String>> subscribers = new java.util.concurrent.CopyOnWriteArrayList<>();
    private long written;

    private LogRingBuffer(int capacity) {
        this.entries = new String[capacity];
    }

    /** Static, because Logback instantiates the appender outside the Spring context. */
    public static LogRingBuffer get() {
        return INSTANCE;
    }

    public void append(String jsonLine) {
        synchronized (lock) {
            entries[(int) (written % entries.length)] = jsonLine;
            written++;
        }
        for (Consumer<String> s : subscribers) {
            try {
                s.accept(jsonLine);
            } catch (RuntimeException ignored) {
                // A broken subscriber must never break logging.
            }
        }
    }

    /** Most recent {@code n} lines, oldest first. */
    public List<String> tail(int n) {
        synchronized (lock) {
            int available = (int) Math.min(written, entries.length);
            int count = Math.min(n, available);
            List<String> out = new ArrayList<>(count);
            for (int i = count; i > 0; i--) {
                out.add(entries[(int) ((written - i) % entries.length)]);
            }
            return out;
        }
    }

    public long totalWritten() {
        synchronized (lock) {
            return written;
        }
    }

    public void subscribe(Consumer<String> subscriber) {
        subscribers.add(subscriber);
    }

    public void unsubscribe(Consumer<String> subscriber) {
        subscribers.remove(subscriber);
    }

    public int subscriberCount() {
        return subscribers.size();
    }
}
