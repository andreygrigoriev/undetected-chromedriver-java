package io.github.andreygrigoriev.uc;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.chromium.HasCdp;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HeadlessEvasionsTest {

    @Test
    void resourcesAreLoaded() {
        assertThat(HeadlessEvasions.NAVIGATOR_SCRIPT).contains("new Proxy(navigator").isNotBlank();
        assertThat(HeadlessEvasions.CHROME_RUNTIME_SCRIPT).contains("window.chrome").isNotBlank();
    }

    @Test
    void patchesWhenWebdriverIsTrue() {
        HasCdp cdp = mock(HasCdp.class);
        JavascriptExecutor js = mock(JavascriptExecutor.class);
        when(js.executeScript("return navigator.webdriver")).thenReturn(true);
        when(js.executeScript("return navigator.userAgent")).thenReturn("Mozilla/5.0 HeadlessChrome/131.0");

        HeadlessEvasions.applyIfNeeded(cdp, js);

        var order = inOrder(cdp);
        order.verify(cdp).executeCdpCommand("Page.addScriptToEvaluateOnNewDocument",
                Map.of("source", HeadlessEvasions.NAVIGATOR_SCRIPT));
        ArgumentCaptor<Map<String, Object>> ua = ArgumentCaptor.captor();
        order.verify(cdp).executeCdpCommand(eq("Network.setUserAgentOverride"), ua.capture());
        assertThat(ua.getValue()).containsEntry("userAgent", "Mozilla/5.0 Chrome/131.0");
        order.verify(cdp).executeCdpCommand("Page.addScriptToEvaluateOnNewDocument",
                Map.of("source", HeadlessEvasions.CHROME_RUNTIME_SCRIPT));
    }

    @Test
    void patchesWhenUserAgentSaysHeadlessEvenIfWebdriverIsFalse() {
        HasCdp cdp = mock(HasCdp.class);
        JavascriptExecutor js = mock(JavascriptExecutor.class);
        when(js.executeScript("return navigator.webdriver")).thenReturn(false);
        when(js.executeScript("return navigator.userAgent")).thenReturn("Mozilla/5.0 HeadlessChrome/131.0");

        HeadlessEvasions.applyIfNeeded(cdp, js);

        verify(cdp).executeCdpCommand(eq("Network.setUserAgentOverride"), eq(Map.of("userAgent", "Mozilla/5.0 Chrome/131.0")));
    }

    @Test
    void doesNothingWhenWebdriverIsNotTrue() {
        HasCdp cdp = mock(HasCdp.class);
        JavascriptExecutor js = mock(JavascriptExecutor.class);
        when(js.executeScript("return navigator.webdriver")).thenReturn(false);
        when(js.executeScript("return navigator.userAgent")).thenReturn("Mozilla/5.0 Chrome/131.0");

        HeadlessEvasions.applyIfNeeded(cdp, js);

        verifyNoInteractions(cdp);
    }
}
