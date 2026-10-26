package io.andreygrigoriev.uc;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.WebElement;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The flow of upstream {@code example/example.py}, run against local pages instead of live sites. */
@Tag("browser")
class ExampleIT {

    @Test
    void exampleFlow() throws Exception {
        assumeTrue(new ChromeLocator().findExecutable().isPresent(), "no Chrome installed");

        UndetectedChromeDriver driver = UndetectedChromeDriver.newBuilder()
                .headless(Boolean.getBoolean("headless"))
                .build();
        try {
            driver.get(UndetectedChromeDriverIT.page("detect.html"));
            assertThat(driver.getTitle()).isEqualTo("detect");
            assertThat(driver.getScreenshotAs(OutputType.BYTES)).isNotEmpty();

            driver.tabNew(UndetectedChromeDriverIT.page("elements.html"));
            driver.windowNew();
            assertThat(driver.getWindowHandles().size()).isGreaterThanOrEqualTo(2);

            driver.switchTo().window(driver.getWindowHandles().iterator().next());
            driver.get(UndetectedChromeDriverIT.page("frames.html"));
            List<WebElement> found = new ArrayList<>();
            driver.findElementsRecursive(By.cssSelector("*")).forEach(found::add);
            assertThat(found).isNotEmpty();

            driver.reconnect(Duration.ofMillis(500));
            assertThat(driver.getTitle()).isNotNull();
        } finally {
            driver.quit();
        }
    }
}
