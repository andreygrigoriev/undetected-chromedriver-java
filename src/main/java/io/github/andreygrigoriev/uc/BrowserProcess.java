package io.github.andreygrigoriev.uc;

import org.openqa.selenium.SessionNotCreatedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** A browser started by this library, not by chromedriver. Survivors are terminated when the JVM exits. */
public final class BrowserProcess {

    private static final Logger LOG = LoggerFactory.getLogger(BrowserProcess.class);
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(2);

    /** CHILD is a plain child process. DETACHED additionally leaves the JVM's session where the OS allows it. */
    public enum Mode { CHILD, DETACHED }

    private static final Set<BrowserProcess> REGISTRY = ConcurrentHashMap.newKeySet();
    private static Thread shutdownHook;

    private final Process process;

    private BrowserProcess(Process process) {
        this.process = process;
    }

    public static BrowserProcess launch(Path binary, List<String> args, Mode mode) {
        return launch(binary, args, mode, Platform.detect());
    }

    static BrowserProcess launch(Path binary, List<String> args, Mode mode, Platform platform) {
        List<String> command = new ArrayList<>();
        // setsid exists only on Linux; macOS has none and on Windows children outlive the JVM anyway
        if (mode == Mode.DETACHED && platform.os() == Platform.Os.LINUX && onPath("setsid", platform)) {
            command.add("setsid");
        }
        command.add(binary.toString());
        command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command)
                .redirectInput(ProcessBuilder.Redirect.from(nullFile(platform)))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        Process started;
        try {
            started = builder.start();
        } catch (IOException e) {
            throw new SessionNotCreatedException("Could not start the browser " + binary + ": " + e.getMessage(), e);
        }
        BrowserProcess result = new BrowserProcess(started);
        register(result);
        LOG.debug("Started browser pid {} ({})", result.pid(), mode);
        return result;
    }

    public long pid() {
        return process.pid();
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    /** Destroys the process, forcibly after {@code grace}, and waits until it has exited. A second call does nothing. */
    public void terminate(Duration grace) {
        try {
            if (process.isAlive()) {
                process.destroy();
                if (!waitFor(grace)) {
                    process.destroyForcibly();
                    waitFor(grace);
                }
            }
        } finally {
            REGISTRY.remove(this);
        }
    }

    private boolean waitFor(Duration timeout) {
        try {
            process.onExit().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (java.util.concurrent.TimeoutException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return !process.isAlive();
        } catch (java.util.concurrent.ExecutionException e) {
            return !process.isAlive();
        }
    }

    /** PIDs of the processes that are still registered for cleanup at JVM exit. */
    static Set<Long> registeredPids() {
        Set<Long> pids = ConcurrentHashMap.newKeySet();
        REGISTRY.forEach(p -> pids.add(p.pid()));
        return pids;
    }

    private static synchronized void register(BrowserProcess p) {
        REGISTRY.add(p);
        if (shutdownHook == null) {
            shutdownHook = new Thread(BrowserProcess::terminateAll, "uc-browser-cleanup");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        }
    }

    private static void terminateAll() {
        for (BrowserProcess p : List.copyOf(REGISTRY)) {
            try {
                p.terminate(SHUTDOWN_GRACE);
            } catch (RuntimeException e) {
                LOG.debug("Could not terminate pid {}", p.pid(), e);
            }
        }
    }

    private static File nullFile(Platform platform) {
        return new File(platform.os() == Platform.Os.WINDOWS ? "NUL" : "/dev/null");
    }

    private static boolean onPath(String name, Platform platform) {
        String path = platform.env().getOrDefault("PATH", "");
        for (String dir : path.split(File.pathSeparator)) {
            if (!dir.isEmpty() && Files.isExecutable(Path.of(dir, name))) {
                return true;
            }
        }
        return false;
    }
}
