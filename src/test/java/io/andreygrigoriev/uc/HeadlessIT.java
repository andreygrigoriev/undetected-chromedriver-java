package io.andreygrigoriev.uc;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("browser")
class HeadlessIT {

    @Test
    void headlessBrowserLooksLikeHeaded() throws Exception {
        assumeTrue(new ChromeLocator().findExecutable().isPresent(), "no Chrome installed");

        UndetectedChromeDriver driver = UndetectedChromeDriver.newBuilder().headless(true).build();
        try {
            driver.get(UndetectedChromeDriverIT.page("detect.html"));

            assertThat((String) driver.executeScript("return navigator.userAgent")).doesNotContain("Headless");
            assertThat(driver.executeScript("return navigator.webdriver")).isIn(false, null);
            assertThat(driver.executeScript("return typeof window.chrome.runtime")).isEqualTo("object");
        } finally {
            driver.quit();
        }
    }
}
