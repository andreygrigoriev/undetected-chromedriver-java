package io.github.andreygrigoriev.uc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openqa.selenium.logging.LogEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Polls the {@code performance} log and hands each CDP event to its listener. A listener under {@code "*"} gets
 * every event and wins over the per-method ones. Events are delivered one by one on the poll thread, in log order.
 */
final class Reactor {

    static final String WILDCARD = "*";

    private static final Logger LOG = LoggerFactory.getLogger(Reactor.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final Supplier<List<LogEntry>> logSource;
    private final Duration interval;
    private final Map<String, Consumer<Map<String, Object>>> handlers = new ConcurrentHashMap<>();
    private final Thread thread;
    private volatile boolean running = true;

    Reactor(Supplier<List<LogEntry>> logSource) {
        this(logSource, Duration.ofSeconds(1));
    }

    Reactor(Supplier<List<LogEntry>> logSource, Duration interval) {
        this.logSource = logSource;
        this.interval = interval;
        this.thread = Thread.ofVirtual().name("uc-reactor").unstarted(this::run);
    }

    Reactor start() {
        thread.start();
        return this;
    }

    void addListener(String event, Consumer<Map<String, Object>> handler) {
        handlers.put(event.toLowerCase(Locale.ROOT), handler);
    }

    void clearListeners() {
        handlers.clear();
    }

    void stop() {
        running = false;
        thread.interrupt();
        try {
            thread.join(Duration.ofSeconds(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    boolean isRunning() {
        return thread.isAlive();
    }

    private void run() {
        while (running) {
            try {
                dispatchAll(logSource.get());
            } catch (RuntimeException e) {
                if (!running || String.valueOf(e.getMessage()).contains("invalid session id")) {
                    return;
                }
                LOG.debug("Could not read the performance log", e);
            }
            try {
                Thread.sleep(interval);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void dispatchAll(List<LogEntry> entries) {
        for (LogEntry entry : entries) {
            Map<String, Object> event = parse(entry);
            if (event == null) {
                continue;
            }
            Consumer<Map<String, Object>> handler = handlers.get(WILDCARD);
            if (handler == null && event.get("method") instanceof String method) {
                handler = handlers.get(method.toLowerCase(Locale.ROOT));
            }
            if (handler == null) {
                continue;
            }
            try {
                handler.accept(event);
            } catch (RuntimeException e) {
                LOG.warn("CDP listener failed", e);
            }
        }
    }

    private static Map<String, Object> parse(LogEntry entry) {
        try {
            Map<String, Object> outer = JSON.readValue(entry.getMessage(), MAP);
            return outer.get("message") instanceof Map<?, ?> inner ? castMap(inner) : null;
        } catch (Exception e) {
            LOG.debug("Skipping malformed log entry", e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
