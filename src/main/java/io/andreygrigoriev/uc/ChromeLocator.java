package io.andreygrigoriev.uc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds an installed Chrome executable and reads its version. */
public final class ChromeLocator {

    private static final List<String> PATH_NAMES =
            List.of("google-chrome", "chromium", "chromium-browser", "chrome", "google-chrome-stable");
    private static final List<String> MAC_PATHS = List.of(
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
            "/Applications/Chromium.app/Contents/MacOS/Chromium",
            "/Applications/Google Chrome Beta.app/Contents/MacOS/Google Chrome Beta",
            "/Applications/Google Chrome Canary.app/Contents/MacOS/Google Chrome Canary");
    private static final List<String> WINDOWS_ENV_DIRS =
            List.of("PROGRAMFILES", "PROGRAMFILES(X86)", "LOCALAPPDATA", "PROGRAMW6432");
    private static final List<String> WINDOWS_BROWSER_DIRS =
            List.of("Google/Chrome", "Google/Chrome Beta", "Google/Chrome SxS", "Chromium");
    private static final Pattern VERSION = Pattern.compile("\\d+(?:\\.\\d+){3}");
    private static final long VERSION_TIMEOUT_SECONDS = 10;

    /** Runs a command and returns its stdout, or empty on failure or timeout. */
    public interface CommandRunner extends Function<List<String>, Optional<String>> {
    }

    private final Platform platform;
    private final CommandRunner runner;

    public ChromeLocator() {
        this(Platform.detect());
    }

    public ChromeLocator(Platform platform) {
        this(platform, ChromeLocator::run);
    }

    public ChromeLocator(Platform platform, CommandRunner runner) {
        this.platform = platform;
        this.runner = runner;
    }

    /** Candidate paths in search order; none of them is checked for existence. */
    public List<Path> candidates() {
        List<Path> result = new ArrayList<>();
        String pathVar = platform.env().getOrDefault("PATH", platform.env().getOrDefault("Path", ""));
        String separator = platform.os() == Platform.Os.WINDOWS ? ";" : ":";
        for (String dir : pathVar.split(separator)) {
            if (dir.isBlank()) {
                continue;
            }
            for (String name : PATH_NAMES) {
                result.add(Path.of(dir).resolve(name + platform.exeSuffix()));
            }
        }
        switch (platform.os()) {
            case MAC -> MAC_PATHS.forEach(p -> result.add(Path.of(p)));
            case WINDOWS -> {
                for (String envName : WINDOWS_ENV_DIRS) {
                    String base = platform.env().get(envName);
                    if (base == null || base.isBlank()) {
                        continue;
                    }
                    for (String dir : WINDOWS_BROWSER_DIRS) {
                        result.add(Path.of(base).resolve(dir).resolve("Application").resolve("chrome.exe"));
                    }
                }
            }
            default -> {
            }
        }
        return result;
    }

    /** The first candidate that is a regular, executable file. */
    public Optional<Path> findExecutable() {
        return candidates().stream()
                .filter(p -> Files.isRegularFile(p) && Files.isExecutable(p))
                .findFirst();
    }

    public Optional<ChromeVersion> detectVersion(Path executable) {
        List<String> command = platform.os() == Platform.Os.WINDOWS
                ? List.of("reg", "query", "HKCU\\Software\\Google\\Chrome\\BLBeacon", "/v", "version")
                : List.of(executable.toString(), "--version");
        return runner.apply(command).flatMap(ChromeLocator::parseVersion);
    }

    /** Extracts the first four-part version from command output. */
    public static Optional<ChromeVersion> parseVersion(String output) {
        if (output == null) {
            return Optional.empty();
        }
        Matcher m = VERSION.matcher(output);
        return m.find() ? Optional.of(ChromeVersion.parse(m.group())) : Optional.empty();
    }

    private static Optional<String> run(List<String> command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getOutputStream().close();
            // Drain on a virtual thread so the timeout also covers a process that never exits.
            Process p = process;
            var reader = new java.util.concurrent.atomic.AtomicReference<String>();
            Thread t = Thread.ofVirtual().start(() -> {
                try {
                    reader.set(new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // leave unset
                }
            });
            if (!process.waitFor(VERSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return Optional.empty();
            }
            t.join(TimeUnit.SECONDS.toMillis(VERSION_TIMEOUT_SECONDS));
            return process.exitValue() == 0 ? Optional.ofNullable(reader.get()) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
