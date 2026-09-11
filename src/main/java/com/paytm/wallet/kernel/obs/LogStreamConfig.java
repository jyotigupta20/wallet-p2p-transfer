package com.paytm.wallet.kernel.obs;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import jakarta.annotation.PostConstruct;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Attaches {@link RingBufferAppender} to the root logger at startup. */
@Component
public class LogStreamConfig {

    @PostConstruct
    void attach() {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
            return;   // not Logback (e.g. a test slice) - the tail endpoint simply stays empty
        }
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        if (root.getAppender("ring-buffer") != null) {
            return;
        }
        RingBufferAppender appender = new RingBufferAppender();
        appender.setName("ring-buffer");
        appender.setContext(context);
        appender.start();
        root.addAppender(appender);
    }
}
