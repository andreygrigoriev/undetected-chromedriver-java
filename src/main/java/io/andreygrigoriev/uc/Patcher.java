package io.andreygrigoriev.uc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/** Produces a patched chromedriver executable, downloading it when needed and caching it per version. */
public class Patcher {

    private static final Logger LOG = LoggerFactory.getLogger(Patcher.class);

    /** Serializes threads; the file lock only works between processes (it throws within one JVM). */
    private static final ReentrantLock THREAD_LOCK = new ReentrantLock();

    /**
     * @param driverExecutablePath user supplied driver, patched only if needed and never deleted; may be null
     * @param browserExecutable    browser used to detect the driver version; may be null
     * @param versionMain          Chrome milestone to match; null means detect, then fall back to latest Stable
     * @param userMultiProcs       reuse the newest already patched driver in the data directory
     * @param forceClose           kill running drivers that block writing the target, then retry once
     */
    public record Settings(Path driverExecutablePath, Path browserExecutable, Integer versionMain,
                           boolean userMultiProcs, boolean forceClose) {
        public static Settings defaults() {
            return new Settings(null, null, null, false, false);
        }
    }

    private final Settings settings;
    private final Platform platform;
    private final DriverReleases releases;
    private final DriverDownloader downloader;
    private final ChromeLocator chrome;

    public Patcher(Settings settings) {
        Platform platform = Platform.detect();
        this(settings, platform, new DriverReleases(), new DriverDownloader(), new ChromeLocator(platform));
    }

    public Patcher(Settings settings, Platform platform, DriverReleases releases, DriverDownloader downloader,
                   ChromeLocator chrome) {
        this.settings = settings;
        this.platform = platform;
        this.releases = releases;
        this.downloader = downloader;
        this.chrome = chrome;
    }

    /** Returns the path of a patched driver, creating it if necessary. */
    public Path auto() {
        Path dataDir = platform.dataDir();
        THREAD_LOCK.lock();
        try {
            Files.createDirectories(dataDir);
            try (FileChannel channel = FileChannel.open(dataDir.resolve(".lock"), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                return locate(dataDir);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot prepare driver in " + dataDir, e);
        } finally {
            THREAD_LOCK.unlock();
        }
    }

    private Path locate(Path dataDir) {
        if (settings.driverExecutablePath() != null) {
            return custom(settings.driverExecutablePath());
        }
        if (settings.userMultiProcs()) {
            Optional<Path> existing = newestPatched(dataDir);
            if (existing.isPresent()) {
                LOG.debug("Reusing patched driver {}", existing.get());
                return existing.get();
            }
        }
        ChromeVersion version = releases.resolve(effectiveVersionMain());
        Path target = dataDir.resolve("undetected_chromedriver_" + version + platform.exeSuffix());
        if (!BinaryPatcher.isPatched(target)) {
            withForceKill(target, () -> prepare(version, target));
        }
        return target;
    }

    private Path custom(Path exe) {
        if (!Files.isRegularFile(exe)) {
            throw new IllegalArgumentException("Driver executable not found: " + exe);
        }
        if (!BinaryPatcher.isPatched(exe)) {
            withForceKill(exe, () -> BinaryPatcher.patch(exe));
        }
        return exe;
    }

    private void prepare(ChromeVersion version, Path target) {
        if (!Files.isRegularFile(target)) {
            downloader.download(releases.downloadUrl(version, platform), platform, target);
        }
        BinaryPatcher.patch(target);
    }

    private Integer effectiveVersionMain() {
        if (settings.versionMain() != null) {
            return settings.versionMain();
        }
        Optional<Path> browser = Optional.ofNullable(settings.browserExecutable()).or(chrome::findExecutable);
        return browser.flatMap(chrome::detectVersion).map(ChromeVersion::major).orElse(null);
    }

    private Optional<Path> newestPatched(Path dataDir) {
        try (Stream<Path> files = Files.list(dataDir)) {
            return files
                    .filter(p -> p.getFileName().toString().contains("chromedriver"))
                    .filter(Files::isRegularFile)
                    .filter(BinaryPatcher::isPatched)
                    .max(Comparator.comparing(Patcher::lastModified));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static java.nio.file.attribute.FileTime lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p);
        } catch (IOException e) {
            return java.nio.file.attribute.FileTime.fromMillis(0);
        }
    }

    private void withForceKill(Path target, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            if (!settings.forceClose()) {
                throw e;
            }
            LOG.warn("Writing {} failed ({}), killing running drivers and retrying", target, e.toString());
            forceKillInstances(target.getFileName().toString());
            action.run();
        }
    }

    /** Kills every process whose executable has exactly this file name. Returns how many were killed. */
    public static int forceKillInstances(String exeName) {
        long self = ProcessHandle.current().pid();
        return (int) ProcessHandle.allProcesses()
                .filter(p -> p.pid() != self)
                .filter(p -> p.info().command()
                        .map(cmd -> Path.of(cmd).getFileName().toString().equals(exeName))
                        .orElse(false))
                .filter(p -> {
                    LOG.info("Killing process {} ({})", p.pid(), exeName);
                    p.destroyForcibly();
                    try {
                        p.onExit().get(5, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception ignored) {
                        // best effort
                    }
                    return true;
                })
                .count();
    }
}
