package io.github.andreygrigoriev.uc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.PageLoadStrategy;
import org.openqa.selenium.SessionNotCreatedException;
import org.openqa.selenium.chrome.ChromeOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class UndetectedChromeDriverBuilderTest {

    @TempDir
    Path tmp;

    private Path browser;
    private Path driver;
    private Platform platform;
    private ChromeLocator noChrome;

    @BeforeEach
    void setUp() throws IOException {
        assumeFalse(System.getProperty("os.name").toLowerCase().contains("win"), "needs a POSIX shell");
        browser = script("browser", """
                #!/bin/sh
                case "$1" in --version) echo "Google Chrome 131.0.6778.85"; exit 0;; esac
                exec sleep 600
                """);
        driver = script("chromedriver", "#!/bin/sh\nexit 0\n");
        platform = Platform.of("Linux", "amd64", Map.of("PATH", ""), tmp);
        noChrome = new ChromeLocator(platform, command -> Optional.empty());
    }

    private Path script(String name, String body) throws IOException {
        Path file = tmp.resolve(name);
        Files.writeString(file, body);
        assertThat(file.toFile().setExecutable(true)).isTrue();
        return file;
    }

    private UndetectedChromeDriver.Builder builder() {
        return UndetectedChromeDriver.newBuilder()
                .chromeLocator(noChrome)
                .patcher(settings -> driver)
                .browserExecutablePath(browser)
                .userDataDir(tmp.resolve("profile"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void attachOptionsKeepOnlyDebuggerAddressInsideChromeOptions() {
        ChromeOptions user = new ChromeOptions();
        user.setPageLoadStrategy(PageLoadStrategy.EAGER);
        user.addArguments("--window-size=800,600");
        LaunchContext context = builder().options(user).port(45678).prepare();
        try {
            assertThat(context.debuggerAddress()).isEqualTo("127.0.0.1:45678");
            Map<String, Object> caps = context.attachOptions().asMap();
            assertThat(caps).containsEntry("pageLoadStrategy", PageLoadStrategy.EAGER);
            assertThat(caps).doesNotContainKey("goog:loggingPrefs");
            // the user's --window-size goes to the browser command line, not to chromedriver
            assertThat((Map<String, Object>) caps.get("goog:chromeOptions"))
                    .containsEntry("debuggerAddress", "127.0.0.1:45678")
                    .containsEntry("args", java.util.List.of());
        } finally {
            context.release();
        }
    }

    @Test
    void loggingPrefsAreSetOnlyWhenCdpEventsAreEnabled() {
        LaunchContext context = builder().enableCdpEvents(true).prepare();
        try {
            assertThat(context.attachOptions().asMap()).containsKey("goog:loggingPrefs");
            assertThat(context.enableCdpEvents()).isTrue();
        } finally {
            context.release();
        }
    }

    @Test
    void debuggerAddressFromOptionsIsUsedForLaunchAndAttach() {
        ChromeOptions user = new ChromeOptions();
        user.setExperimentalOption("debuggerAddress", "127.0.0.1:45679");
        LaunchContext context = builder().options(user).prepare();
        try {
            assertThat(context.debuggerAddress()).isEqualTo("127.0.0.1:45679");
        } finally {
            context.release();
        }
    }

    @Test
    void launchesTheBrowserAndKeepsAGivenProfile() {
        long before = BrowserProcess.registeredPids().size();
        LaunchContext context = builder().prepare();
        try {
            assertThat(context.browser().isAlive()).isTrue();
            assertThat(BrowserProcess.registeredPids()).contains(context.browser().pid());
            assertThat(context.profile().keep()).isTrue();
            assertThat(context.profile().dir()).isEqualTo(tmp.resolve("profile").toAbsolutePath().normalize());
        } finally {
            context.release();
        }
        assertThat(context.browser().isAlive()).isFalse();
        assertThat(BrowserProcess.registeredPids()).hasSize((int) before);
        assertThat(tmp.resolve("profile")).exists();
    }

    @Test
    void passesPatcherSettingsIncludingDetectedVersion() {
        AtomicReference<Patcher.Settings> seen = new AtomicReference<>();
        ChromeLocator versions = new ChromeLocator(platform, command -> Optional.of("Google Chrome 131.0.6778.85"));
        LaunchContext context = builder().chromeLocator(versions).userMultiProcs(true).patcherForceClose(true)
                .patcher(settings -> {
                    seen.set(settings);
                    return driver;
                }).prepare();
        context.release();
        assertThat(seen.get().versionMain()).isEqualTo(131);
        assertThat(seen.get().browserExecutable()).isEqualTo(browser);
        assertThat(seen.get().userMultiProcs()).isTrue();
        assertThat(seen.get().forceClose()).isTrue();
    }

    @Test
    void missingBrowserFailsWithHint() {
        UndetectedChromeDriver.Builder builder = UndetectedChromeDriver.newBuilder()
                .chromeLocator(noChrome).patcher(settings -> driver);
        assertThatThrownBy(builder::prepare)
                .isInstanceOf(SessionNotCreatedException.class)
                .hasMessageContaining("browserExecutablePath");
    }

    @Test
    void optionsCannotBeReused() {
        UndetectedChromeOptions options = new UndetectedChromeOptions();
        builder().options(options).prepare().release();
        assertThatThrownBy(() -> builder().options(options).prepare())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failureAfterLaunchTerminatesTheBrowser() {
        Set<Long> before = BrowserProcess.registeredPids();
        UndetectedChromeDriver.Builder failing = builder().patcher(settings -> null); // building the service fails after the browser started
        assertThatThrownBy(failing::prepare).isInstanceOf(RuntimeException.class);
        assertThat(BrowserProcess.registeredPids()).isEqualTo(before);
    }
}
