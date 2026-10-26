package io.andreygrigoriev.uc;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("browser")
class FrameSearchIT {

    @Test
    void findsElementsInTopLevelAndEachFrame() throws Exception {
        assumeTrue(new ChromeLocator().findExecutable().isPresent(), "no Chrome installed");

        UndetectedChromeDriver driver = UndetectedChromeDriver.newBuilder()
                .headless(Boolean.getBoolean("headless"))
                .build();
        try {
            driver.get(UndetectedChromeDriverIT.page("frames.html"));

            List<String> ids = new ArrayList<>();
            for (WebElement element : driver.findElementsRecursive(By.className("item"))) {
                ids.add(element.getAttribute("id"));
                assertThat(element.getText()).isNotEmpty();
            }

            assertThat(ids).containsExactly("top", "one", "two");
        } finally {
            driver.quit();
        }
    }
}
