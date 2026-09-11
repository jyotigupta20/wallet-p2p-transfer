package com.paytm.wallet.kernel.obs;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

import java.util.Map;

/**
 * Logback appender that mirrors every log event into {@link LogRingBuffer} as
 * compact JSON.
 *
 * It serialises the event itself rather than reusing Spring Boot's ECS encoder
 * so that the tail endpoint cannot be broken by a change to the console log
 * format, and so the shape stays small enough to stream comfortably.
 */
public class RingBufferAppender extends AppenderBase<ILoggingEvent> {

    @Override
    protected void append(ILoggingEvent event) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        field(sb, "ts", event.getInstant().toString()).append(',');
        field(sb, "level", event.getLevel().toString()).append(',');
        field(sb, "logger", shortLogger(event.getLoggerName())).append(',');
        field(sb, "thread", event.getThreadName()).append(',');
        field(sb, "message", event.getFormattedMessage());

        for (Map.Entry<String, String> e : event.getMDCPropertyMap().entrySet()) {
            sb.append(',');
            field(sb, e.getKey(), e.getValue());
        }
        if (event.getThrowableProxy() != null) {
            sb.append(',');
            field(sb, "exception", event.getThrowableProxy().getClassName());
        }
        sb.append('}');
        LogRingBuffer.get().append(sb.toString());
    }

    /** com.paytm.wallet.transfer.TransferService -> TransferService */
    private static String shortLogger(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? name : name.substring(i + 1);
    }

    private static StringBuilder field(StringBuilder sb, String key, String value) {
        sb.append('"');
        escape(sb, key);
        sb.append("\":\"");
        escape(sb, value == null ? "" : value);
        return sb.append('"');
    }

    private static void escape(StringBuilder sb, String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
    }
}
