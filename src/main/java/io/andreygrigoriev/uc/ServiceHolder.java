package io.andreygrigoriev.uc;

import org.openqa.selenium.chrome.ChromeDriverService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The chromedriver service currently used by a driver. A stopped service can't be restarted, so reconnecting
 * swaps in a new one; the holder is shared with the cleanup action, which must not reference the driver.
 */
public final class ServiceHolder {

    private static final Logger LOG = LoggerFactory.getLogger(ServiceHolder.class);

    private ChromeDriverService service;

    public ServiceHolder(ChromeDriverService service) {
        this.service = service;
    }

    public synchronized ChromeDriverService get() {
        return service;
    }

    public synchronized void set(ChromeDriverService replacement) {
        this.service = replacement;
    }

    /** Stops the current service; failures are logged and swallowed. */
    public synchronized void stop() {
        if (service == null) {
            return;
        }
        try {
            service.stop();
        } catch (RuntimeException e) {
            LOG.debug("Could not stop the chromedriver service", e);
        }
    }
}
