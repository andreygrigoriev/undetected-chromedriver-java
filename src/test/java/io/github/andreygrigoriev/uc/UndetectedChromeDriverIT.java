package io.github.andreygrigoriev.uc;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("browser")
class UndetectedChromeDriverIT {

    static String page(String name) throws URISyntaxException {
        return UndetectedChromeDriverIT.class.getResource("/pages/" + name).toURI().toString();
    }

    @ParameterizedTest(name = "debug={0}")
    @ValueSource(booleans = {false, true})
    void launchesUndetectedAndCleansUpOnQuit(boolean debug) throws Exception {
        Optional<Path> chrome = new ChromeLocator().findExecutable();
        assumeTrue(chrome.isPresent(), "no Chrome installed");

        UndetectedChromeDriver driver = UndetectedChromeDriver.newBuilder()
                .headless(Boolean.getBoolean("headless"))
                .debug(debug)
                .build();
        long pid = driver.browserPid();
        Path profile = driver.userDataDir();
        try {
            driver.get(page("detect.html"));

            assertThat(driver.executeScript("return window.detection.webdriver")).isIn(false, null);
            assertThat((List<?>) driver.executeScript("return window.detection.cdcKeys")).isEmpty();
            assertThat((String) driver.executeScript("return window.detection.userAgent"))
                    .doesNotContain("HeadlessChrome");
            assertThat(ProcessHandle.of(pid)).isPresent();
        } finally {
            driver.quit();
        }

        driver.quit(); // idempotent
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        assertThat(Files.exists(profile)).isFalse();
    }
}
