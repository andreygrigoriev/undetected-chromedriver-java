package io.github.andreygrigoriev.uc;

import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;

import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Everything the builder prepared before a driver exists: the launched browser, its profile, the chromedriver
 * service and the options chromedriver uses to attach to the browser.
 *
 * @param serviceFactory creates a new service for the same patched driver (used by reconnect)
 */
public record LaunchContext(
        ServiceHolder service,
        Supplier<ChromeDriverService> serviceFactory,
        BrowserProcess browser,
        Profile profile,
        ChromeOptions attachOptions,
        String debuggerAddress,
        Path driverExecutable,
        boolean headless,
        boolean advancedElements,
        boolean enableCdpEvents,
        boolean debug) {

    private static final Duration GRACE = Duration.ofSeconds(5);

    /** Stops the service, terminates the browser and deletes a temporary profile. Never throws. */
    public void release() {
        service.stop();
        try {
            browser.terminate(GRACE);
        } catch (RuntimeException ignored) {
            // best effort
        }
        profile.delete();
    }
}
