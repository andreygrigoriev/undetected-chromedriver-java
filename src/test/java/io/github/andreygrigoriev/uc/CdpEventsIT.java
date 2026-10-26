package io.github.andreygrigoriev.uc;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("browser")
class CdpEventsIT {

    @Test
    void responseReceivedListenerFiresOnGet() throws Exception {
        assumeTrue(new ChromeLocator().findExecutable().isPresent(), "no Chrome installed");

        UndetectedChromeDriver driver = UndetectedChromeDriver.newBuilder()
                .headless(Boolean.getBoolean("headless"))
                .enableCdpEvents(true)
                .build();
        try {
            CountDownLatch fired = new CountDownLatch(1);
            boolean registered = driver.addCdpListener("Network.responseReceived", event -> {
                assertThat(event).containsKey("params");
                fired.countDown();
            });
            assertThat(registered).isTrue();

            driver.get(UndetectedChromeDriverIT.page("detect.html"));

            assertThat(fired.await(15, TimeUnit.SECONDS)).isTrue();
            driver.clearCdpListeners();
        } finally {
            driver.quit();
        }
    }

    @Test
    void addListenerReturnsFalseWhenEventsAreOff() {
        assumeTrue(new ChromeLocator().findExecutable().isPresent(), "no Chrome installed");

        UndetectedChromeDriver driver = UndetectedChromeDriver.newBuilder()
                .headless(Boolean.getBoolean("headless"))
                .build();
        try {
            assertThat(driver.addCdpListener("*", Map::size)).isFalse();
        } finally {
            driver.quit();
        }
    }
}
