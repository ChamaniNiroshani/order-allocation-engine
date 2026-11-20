package com.chamani.order_allocation_engine;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.chamani.order_allocation_engine.logging.OrderEvents;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Records the business events (see OrderEvents) written while it is open, with the MDC they were written under.
 * try (LogCapture logs = new LogCapture()) { ... logs.events("order.allocated") ... }
 */
final class LogCapture implements AutoCloseable {

    /** An appender that copies the MDC into the event when it arrives: afterwards the MDC has already been cleaned up. */
    private static final class Recording extends ListAppender<ILoggingEvent> {
        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    }

    private final Logger logger = (Logger) LoggerFactory.getLogger(OrderEvents.class);
    private final Recording appender = new Recording();

    LogCapture() {
        appender.start();
        logger.addAppender(appender);
    }

    /** All events whose key/value {@code event} equals the name, oldest first. */
    List<ILoggingEvent> events(String name) {
        return appender.list.stream().filter(e -> name.equals(field(e, OrderEvents.EVENT))).toList();
    }

    List<String> eventNames() {
        return appender.list.stream().map(e -> field(e, OrderEvents.EVENT)).collect(Collectors.toList());
    }

    static String field(ILoggingEvent event, String key) {
        if (event.getKeyValuePairs() == null) {
            return null;
        }
        return event.getKeyValuePairs().stream().filter(kv -> kv.key.equals(key)).map(kv -> String.valueOf(kv.value)).findFirst().orElse(null);
    }

    static Map<String, String> mdc(ILoggingEvent event) {
        return event.getMDCPropertyMap();
    }

    /**
     * Detaches the appender, then checks what every captured event looked like to a JSON formatter: a key/value that has the
     * same name as an MDC entry makes Spring's structured formatter throw and the line is lost. The text format hides it.
     */
    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
        for (ILoggingEvent event : appender.list) {
            if (event.getKeyValuePairs() == null) {
                continue;
            }
            for (var kv : event.getKeyValuePairs()) {
                if (event.getMDCPropertyMap().containsKey(kv.key)) {
                    throw new AssertionError("key/value '" + kv.key + "' is also an MDC entry: the JSON log formatter would drop this line");
                }
            }
        }
    }
}
