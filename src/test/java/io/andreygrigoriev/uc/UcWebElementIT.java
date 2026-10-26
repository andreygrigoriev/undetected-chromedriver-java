package io.andreygrigoriev.uc;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("browser")
class UcWebElementIT {

    @Test
    void findElementReturnsUcWebElementWithChildrenAndSafeClick() throws Exception {
        assumeTrue(new ChromeLocator().findExecutable().isPresent(), "no Chrome installed");

        UndetectedChromeDriver driver = UndetectedChromeDriver.newBuilder()
                .headless(Boolean.getBoolean("headless"))
                .advancedElements(true)
                .build();
        try {
            driver.get(UndetectedChromeDriverIT.page("elements.html"));

            WebElement found = driver.findElement(By.id("list"));
            assertThat(found).isInstanceOf(UcWebElement.class);
            UcWebElement list = (UcWebElement) found;

            assertThat(list.children(null, false)).hasSize(2);
            assertThat(list.children("li", false)).hasSize(2);
            assertThat(list.children("span", false)).isEmpty();
            assertThat(list.children(null, true)).hasSize(3);
            assertThat(list.children("span", true)).hasSize(1);
            assertThat(list.toString()).isEqualTo("UcWebElement <ul id=\"list\">");

            UcWebElement link = (UcWebElement) driver.findElement(By.id("link"));
            link.clickSafe();
            assertThat(driver.getCurrentUrl()).endsWith("#clicked");
            assertThat(driver.findElement(By.id("list"))).isInstanceOf(UcWebElement.class);
        } finally {
            driver.quit();
        }
    }
}
