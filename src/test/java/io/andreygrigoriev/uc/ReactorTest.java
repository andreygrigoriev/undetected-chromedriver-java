package io.andreygrigoriev.uc;

import org.junit.jupiter.api.Test;
import org.openqa.selenium.logging.LogEntry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;

import static org.assertj.core.api.Assertions.assertThat;

class ReactorTest {

    private static final Duration FAST = Duration.ofMillis(10);

    private static LogEntry entry(String method) {
        return new LogEntry(Level.INFO, 0, "{\"message\":{\"method\":\"" + method + "\",\"params\":{}},\"webview\":\"x\"}");
    }

    /** Hands out the queued batch once, then empty lists. */
    private static java.util.function.Supplier<List<LogEntry>> once(List<LogEntry> batch) {
        AtomicBoolean served = new AtomicBoolean();
        return () -> served.getAndSet(true) ? List.of() : batch;
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
    }

    @Test
    void wildcardWinsOverSpecificListener() throws Exception {
        List<String> wild = new CopyOnWriteArrayList<>();
        List<String> specific = new CopyOnWriteArrayList<>();
        Reactor reactor = new Reactor(once(List.of(entry("Network.a"), entry("Network.b"))), FAST);
        reactor.addListener("Network.a", e -> specific.add((String) e.get("method")));
        reactor.addListener("*", e -> wild.add((String) e.get("method")));
        reactor.start();
        await(() -> wild.size() == 2);
        reactor.stop();

        assertThat(wild).containsExactly("Network.a", "Network.b");
        assertThat(specific).isEmpty();
    }

    @Test
    void matchingIsCaseInsensitiveAndKeepsLogOrder() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        Reactor reactor = new Reactor(once(List.of(entry("Network.One"), entry("Page.skip"), entry("NETWORK.one"),
                entry("network.ONE"))), FAST);
        reactor.addListener("NETWORK.ONE", e -> seen.add((String) e.get("method")));
        reactor.start();
        await(() -> seen.size() == 3);
        reactor.stop();

        assertThat(seen).containsExactly("Network.One", "NETWORK.one", "network.ONE");
    }

    @Test
    void malformedEntriesAreSkipped() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        List<LogEntry> batch = new ArrayList<>();
        batch.add(new LogEntry(Level.INFO, 0, "not json"));
        batch.add(new LogEntry(Level.INFO, 0, "{\"nomessage\":1}"));
        batch.add(new LogEntry(Level.INFO, 0, "{\"message\":\"text\"}"));
        batch.add(entry("Good.event"));
        Reactor reactor = new Reactor(once(batch), FAST);
        reactor.addListener("*", e -> seen.add((String) e.get("method")));
        reactor.start();
        await(() -> !seen.isEmpty());

        assertThat(seen).containsExactly("Good.event");
        assertThat(reactor.isRunning()).isTrue();
        reactor.stop();
    }

    @Test
    void throwingHandlerDoesNotStopDispatch() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        Reactor reactor = new Reactor(once(List.of(entry("A.x"), entry("A.y"))), FAST);
        reactor.addListener("*", e -> {
            seen.add((String) e.get("method"));
            throw new IllegalStateException("boom");
        });
        reactor.start();
        await(() -> seen.size() == 2);
        reactor.stop();

        assertThat(seen).containsExactly("A.x", "A.y");
    }

    @Test
    void sourceFailuresDoNotKillTheLoop() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        AtomicBoolean failed = new AtomicBoolean();
        Reactor reactor = new Reactor(() -> {
            if (!failed.getAndSet(true)) {
                throw new IllegalStateException("transient");
            }
            return seen.isEmpty() ? List.of(entry("A.x")) : List.of();
        }, FAST);
        reactor.addListener("*", e -> seen.add((String) e.get("method")));
        reactor.start();
        await(() -> !seen.isEmpty());
        reactor.stop();

        assertThat(seen).containsExactly("A.x");
    }

    @Test
    void invalidSessionEndsTheLoop() throws Exception {
        Reactor reactor = new Reactor(() -> {
            throw new IllegalStateException("invalid session id");
        }, FAST);
        reactor.start();
        await(() -> !reactor.isRunning());

        assertThat(reactor.isRunning()).isFalse();
    }

    @Test
    void clearListenersStopsDelivery() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        Reactor reactor = new Reactor(() -> List.of(entry("A.x")), Duration.ofMillis(50));
        reactor.addListener("*", e -> seen.add("x"));
        reactor.clearListeners();
        reactor.start();
        Thread.sleep(200);
        reactor.stop();

        assertThat(seen).isEmpty();
    }

    @Test
    void stopEndsTheThread() {
        Reactor reactor = new Reactor(List::of, Duration.ofSeconds(30)).start();
        assertThat(reactor.isRunning()).isTrue();
        reactor.stop();

        assertThat(reactor.isRunning()).isFalse();
    }

    @Test
    void passesParamsToTheListener() throws Exception {
        List<Map<String, Object>> seen = new CopyOnWriteArrayList<>();
        Reactor reactor = new Reactor(once(List.of(entry("A.x"))), FAST);
        reactor.addListener("a.x", seen::add);
        reactor.start();
        await(() -> !seen.isEmpty());
        reactor.stop();

        assertThat(seen.get(0)).containsKey("params");
    }
}
