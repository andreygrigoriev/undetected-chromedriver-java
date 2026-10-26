package io.github.andreygrigoriev.uc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PatcherTest {

    private static final String CDC_BLOCK = "{window.cdc_" + "a".repeat(60) + ";}";
    private static final byte[] UNPATCHED = ("head" + CDC_BLOCK + "tail").getBytes(StandardCharsets.ISO_8859_1);
    private static final ChromeVersion V131 = ChromeVersion.parse("131.0.6778.85");

    @TempDir
    Path tmp;

    private Platform platform() {
        return Platform.of("Linux", "amd64", Map.of(), tmp);
    }

    private Path dataDir() {
        return platform().dataDir();
    }

    private Path cached() {
        return dataDir().resolve("undetected_chromedriver_" + V131);
    }

    /** Fake release source and downloader recording what they were asked. */
    private static class Fakes extends DriverReleases {
        final List<Integer> resolved = new ArrayList<>();
        final AtomicInteger downloads = new AtomicInteger();
        final AtomicInteger failures = new AtomicInteger();
        volatile RuntimeException resolveError;
        volatile long downloadDelayMillis;

        final DriverDownloader downloader = new DriverDownloader() {
            @Override
            public void download(String url, Platform platform, Path target) {
                downloads.incrementAndGet();
                if (downloadDelayMillis > 0) {
                    try {
                        Thread.sleep(downloadDelayMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (failures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                    throw new DriverDownloadException("target busy");
                }
                try {
                    Files.createDirectories(target.getParent());
                    Files.write(target, UNPATCHED);
                } catch (IOException e) {
                    throw new DriverDownloadException("write failed", e);
                }
            }
        };

        @Override
        public synchronized ChromeVersion resolve(Integer versionMain) {
            resolved.add(versionMain);
            if (resolveError != null) {
                throw resolveError;
            }
            return V131;
        }

        @Override
        public String downloadUrl(ChromeVersion version, Platform platform) {
            return "http://stub/" + version;
        }
    }

    private Patcher patcher(Patcher.Settings settings, Fakes fakes) {
        Platform p = platform();
        ChromeLocator locator = new ChromeLocator(p, cmd -> Optional.of("Google Chrome 120.0.6099.109"));
        return new Patcher(settings, p, fakes, fakes.downloader, locator);
    }

    private Patcher.Settings settings(Path driver, Path browser, Integer major, boolean multi, boolean force) {
        return new Patcher.Settings(driver, browser, major, multi, force);
    }

    @Test
    void macPlatformPatchesEvenWhenCodesignFails() throws IOException {
        Fakes fakes = new Fakes();
        Platform mac = Platform.of("Mac OS X", "aarch64", Map.of(), tmp);
        ChromeLocator locator = new ChromeLocator(mac, cmd -> Optional.of("Google Chrome 120.0.6099.109"));
        Patcher patcher = new Patcher(settings(null, null, 131, false, false), mac, fakes, fakes.downloader, locator);

        Path result = patcher.auto();

        assertThat(BinaryPatcher.isPatched(result)).isTrue();
    }

    @Test
    void publicConstructorUsesDetectedPlatform() {
        assertThat(new Patcher(settings(null, null, 131, false, false))).isNotNull();
    }

    @Test
    void cacheMissDownloadsAndPatches() {
        Fakes fakes = new Fakes();

        Path result = patcher(settings(null, null, 131, false, false), fakes).auto();

        assertThat(result).isEqualTo(cached());
        assertThat(fakes.downloads).hasValue(1);
        assertThat(BinaryPatcher.isPatched(result)).isTrue();
    }

    @Test
    void cacheHitSkipsDownload() {
        Fakes fakes = new Fakes();
        Patcher patcher = patcher(settings(null, null, 131, false, false), fakes);

        patcher.auto();
        Path second = patcher.auto();

        assertThat(second).isEqualTo(cached());
        assertThat(fakes.downloads).hasValue(1);
    }

    @Test
    void unpatchedCachedFileIsPatchedWithoutDownload() throws IOException {
        Files.createDirectories(dataDir());
        Files.write(cached(), UNPATCHED);
        Fakes fakes = new Fakes();

        patcher(settings(null, null, 131, false, false), fakes).auto();

        assertThat(fakes.downloads).hasValue(0);
        assertThat(BinaryPatcher.isPatched(cached())).isTrue();
    }

    @Test
    void versionMainDefaultsToDetectedBrowserThenLatest() {
        Fakes detected = new Fakes();
        patcher(settings(null, Path.of("/fake/chrome"), null, false, false), detected).auto();
        assertThat(detected.resolved).containsExactly(120);

        Fakes explicit = new Fakes();
        patcher(settings(null, Path.of("/fake/chrome"), 99, false, false), explicit).auto();
        assertThat(explicit.resolved).containsExactly(99);

        Fakes latest = new Fakes();
        Platform p = platform();
        new Patcher(settings(null, null, null, false, false), p, latest, latest.downloader,
                new ChromeLocator(p, cmd -> Optional.empty())).auto();
        assertThat(latest.resolved).containsExactly((Integer) null);
    }

    @Test
    void customPathIsPatchedOnceAndNeverReplaced() throws IOException {
        Path exe = tmp.resolve("my-driver");
        Files.write(exe, UNPATCHED);
        Fakes fakes = new Fakes();
        Patcher patcher = patcher(settings(exe, null, null, false, false), fakes);

        assertThat(patcher.auto()).isEqualTo(exe);
        assertThat(BinaryPatcher.isPatched(exe)).isTrue();

        byte[] afterFirst = Files.readAllBytes(exe);
        FileTime mtime = Files.getLastModifiedTime(exe);
        patcher.auto();

        assertThat(Files.readAllBytes(exe)).isEqualTo(afterFirst);
        assertThat(Files.getLastModifiedTime(exe)).isEqualTo(mtime);
        assertThat(fakes.resolved).isEmpty();
        assertThat(fakes.downloads).hasValue(0);
        assertThat(exe).exists();
    }

    @Test
    void missingCustomPathIsRejected() {
        Fakes fakes = new Fakes();

        assertThatThrownBy(() -> patcher(settings(tmp.resolve("nope"), null, null, false, false), fakes).auto())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void multiProcsReusesNewestPatchedFile() throws IOException {
        Files.createDirectories(dataDir());
        byte[] patched = UNPATCHED.clone();
        Path old = dataDir().resolve("undetected_chromedriver_100.0.0.0");
        Path newest = dataDir().resolve("undetected_chromedriver_130.0.0.0");
        Path unpatchedNewer = dataDir().resolve("undetected_chromedriver_140.0.0.0");
        Files.write(old, patched);
        Files.write(newest, patched);
        Files.write(unpatchedNewer, UNPATCHED);
        BinaryPatcher.patch(old);
        BinaryPatcher.patch(newest);
        Files.setLastModifiedTime(old, FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(newest, FileTime.fromMillis(2_000));
        Files.setLastModifiedTime(unpatchedNewer, FileTime.fromMillis(3_000));
        Fakes fakes = new Fakes();

        Path result = patcher(settings(null, null, null, true, false), fakes).auto();

        assertThat(result).isEqualTo(newest);
        assertThat(fakes.resolved).isEmpty();
    }

    @Test
    void multiProcsFallsThroughWhenNothingPatched() {
        Fakes fakes = new Fakes();

        Path result = patcher(settings(null, null, 131, true, false), fakes).auto();

        assertThat(result).isEqualTo(cached());
        assertThat(fakes.downloads).hasValue(1);
    }

    @Test
    void concurrentCallsDownloadOnce() throws Exception {
        Fakes fakes = new Fakes();
        fakes.downloadDelayMillis = 200;
        Patcher patcher = patcher(settings(null, null, 131, false, false), fakes);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<Path>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return patcher.auto();
                }));
            }
            start.countDown();
            for (Future<Path> f : results) {
                assertThat(f.get()).isEqualTo(cached());
            }
        }
        assertThat(fakes.downloads).hasValue(1);
    }

    @Test
    void resolutionFailurePropagatesAndLocksAreReleased() throws Exception {
        Fakes fakes = new Fakes();
        fakes.resolveError = new DriverResolutionException("offline");
        Patcher patcher = patcher(settings(null, null, 131, false, false), fakes);

        assertThatThrownBy(patcher::auto).isInstanceOf(DriverResolutionException.class).hasMessage("offline");

        fakes.resolveError = null;
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            // another thread succeeding proves the thread lock and the file lock are free
            assertThat(pool.submit(patcher::auto).get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(cached());
        }
    }

    @Test
    void failedWriteIsRetriedOnlyWithForceClose() throws IOException {
        Fakes failing = new Fakes();
        failing.failures.set(1);
        assertThatThrownBy(() -> patcher(settings(null, null, 131, false, false), failing).auto())
                .isInstanceOf(DriverDownloadException.class);
        assertThat(failing.downloads).hasValue(1);

        Fakes retried = new Fakes();
        retried.failures.set(1);
        Path result = patcher(settings(null, null, 131, false, true), retried).auto();
        assertThat(retried.downloads).hasValue(2);
        assertThat(BinaryPatcher.isPatched(result)).isTrue();
        Files.delete(result);

        Fakes alwaysFailing = new Fakes();
        alwaysFailing.failures.set(5);
        assertThatThrownBy(() -> patcher(settings(null, null, 131, false, true), alwaysFailing).auto())
                .isInstanceOf(DriverDownloadException.class);
        assertThat(alwaysFailing.downloads).hasValue(2);
    }

    @Test
    void forceKillInstancesMatchesExactBasename() throws Exception {
        Path sleep = Path.of("/bin/sleep");
        assumeTrue(Files.isExecutable(sleep) && !System.getProperty("os.name").toLowerCase().contains("win"));
        Path victim = tmp.resolve("uc-victim-driver");
        Path bystander = tmp.resolve("uc-victim-driver-other");
        Files.copy(sleep, victim);
        Files.copy(sleep, bystander);
        victim.toFile().setExecutable(true);
        bystander.toFile().setExecutable(true);
        if (System.getProperty("os.name").toLowerCase().contains("mac")) {
            // a copied system binary loses its signature and is killed on launch
            for (Path p : List.of(victim, bystander)) {
                assumeTrue(new ProcessBuilder("codesign", "-s", "-", "-f", p.toString()).start().waitFor() == 0);
            }
        }
        Process killed = new ProcessBuilder(victim.toString(), "60").start();
        Process spared = new ProcessBuilder(bystander.toString(), "60").start();
        try {
            int count = Patcher.forceKillInstances("uc-victim-driver");

            assertThat(count).isEqualTo(1);
            assertThat(killed.isAlive()).isFalse();
            assertThat(spared.isAlive()).isTrue();
        } finally {
            killed.destroyForcibly();
            spared.destroyForcibly();
        }
    }
}
