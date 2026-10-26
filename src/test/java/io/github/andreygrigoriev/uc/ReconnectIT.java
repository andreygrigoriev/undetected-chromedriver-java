package io.github.andreygrigoriev.uc;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("browser")
class ReconnectIT {

    @Test
    void sessionSurvivesRepeatedReconnects() throws Exception {
        assumeTrue(new ChromeLocator().findExecutable().isPresent(), "no Chrome installed");

        UndetectedChromeDriver driver = UndetectedChromeDriver.newBuilder()
                .headless(Boolean.getBoolean("headless"))
                .build();
        try {
            long pid = driver.browserPid();
            String page = UndetectedChromeDriverIT.page("detect.html");
            driver.get(page);

            driver.reconnect(Duration.ofMillis(100));
            assertThat(driver.getCurrentUrl()).endsWith("/pages/detect.html");
            driver.reconnect(Duration.ofMillis(100));
            assertThat(driver.executeScript("return 1 + 1")).isEqualTo(2L);

            assertThat(driver.browserPid()).isEqualTo(pid);
            assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isTrue();
        } finally {
            driver.quit();
        }
    }
}
