package io.github.andreygrigoriev.uc;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.chromium.HasCdp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Patches the properties that give a headless browser away: {@code navigator.webdriver}, the user agent and {@code window.chrome}. */
final class HeadlessEvasions {

    private static final Logger LOG = LoggerFactory.getLogger(HeadlessEvasions.class);
    private static final String ADD_SCRIPT = "Page.addScriptToEvaluateOnNewDocument";

    static final String NAVIGATOR_SCRIPT = load("/uc/headless-navigator.js");
    static final String CHROME_RUNTIME_SCRIPT = load("/uc/headless-chrome-runtime.js");

    private HeadlessEvasions() {
    }

    /** Patches the session when {@code navigator.webdriver} is true or the user agent says {@code Headless}. */
    static void applyIfNeeded(HasCdp cdp, JavascriptExecutor js) {
        String userAgent = String.valueOf(js.executeScript("return navigator.userAgent"));
        if (!Boolean.TRUE.equals(js.executeScript("return navigator.webdriver")) && !userAgent.contains("Headless")) {
            return;
        }
        LOG.info("patch navigator.webdriver");
        cdp.executeCdpCommand(ADD_SCRIPT, Map.of("source", NAVIGATOR_SCRIPT));

        LOG.info("patch user-agent string");
        cdp.executeCdpCommand("Network.setUserAgentOverride", Map.of("userAgent", userAgent.replace("Headless", "")));
        cdp.executeCdpCommand(ADD_SCRIPT, Map.of("source", CHROME_RUNTIME_SCRIPT));
    }

    private static String load(String resource) {
        try (InputStream in = HeadlessEvasions.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
