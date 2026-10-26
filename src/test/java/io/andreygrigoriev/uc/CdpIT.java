package io.andreygrigoriev.uc;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("browser")
class CdpIT {

    @Test
    void tabNewAndSendWork() throws Exception {
        assumeTrue(new ChromeLocator().findExecutable().isPresent(), "no Chrome installed");

        UndetectedChromeDriver driver = UndetectedChromeDriver.newBuilder()
                .headless(Boolean.getBoolean("headless"))
                .build();
        try {
            String page = UndetectedChromeDriverIT.page("detect.html");
            JsonNode opened = driver.tabNew(page);
            assertThat(opened.path("id").asText()).isNotEmpty();

            boolean listed = false;
            for (JsonNode tab : driver.cdp().tabList()) {
                listed |= tab.path("id").asText().equals(opened.path("id").asText());
            }
            assertThat(listed).isTrue();

            JsonNode result = driver.cdp().send("Runtime.evaluate", Map.of("expression", "1 + 2"))
                    .get(10, TimeUnit.SECONDS);
            assertThat(result.path("result").path("value").asInt()).isEqualTo(3);
        } finally {
            driver.quit();
        }
    }
}
