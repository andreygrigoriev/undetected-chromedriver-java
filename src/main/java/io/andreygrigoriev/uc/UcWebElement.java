package io.andreygrigoriev.uc;

import org.openqa.selenium.WebElement;
import org.openqa.selenium.remote.RemoteWebElement;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A {@link RemoteWebElement} with a few conveniences; produced by {@link UndetectedChromeDriver}. */
public class UcWebElement extends RemoteWebElement {

    static final String CHILDREN_SCRIPT = "return Array.from(arguments[0].children);";
    static final String CHILDREN_BY_TAG_SCRIPT = "return Array.from(arguments[0].children)"
            + ".filter(e => e.tagName.toLowerCase() === arguments[1].toLowerCase());";
    static final String DESCENDANTS_SCRIPT = "return Array.from(arguments[0].querySelectorAll('*'));";
    static final String DESCENDANTS_BY_TAG_SCRIPT = "return Array.from(arguments[0].querySelectorAll(arguments[1]));";
    static final String ATTRS_SCRIPT = "return Object.fromEntries(Array.from(arguments[0].attributes)"
            + ".map(a => [a.name, a.value]));";

    private static final Duration RECONNECT_WAIT = Duration.ofMillis(100);

    private final boolean advancedElements;
    private Map<String, String> attrs;

    public UcWebElement() {
        this(false);
    }

    UcWebElement(boolean advancedElements) {
        this.advancedElements = advancedElements;
    }

    /** Clicks, then reconnects the driver, for clicks that make a detectable browser change (e.g. navigation). */
    public void clickSafe() {
        click();
        if (parent instanceof UndetectedChromeDriver driver) {
            driver.reconnect(RECONNECT_WAIT);
        }
    }

    /**
     * Child elements, optionally only those with the given tag name. When {@code recursive}, all descendants in
     * document order.
     */
    public List<WebElement> children(String tag, boolean recursive) {
        boolean tagged = tag != null && !tag.isEmpty();
        String script = recursive
                ? (tagged ? DESCENDANTS_BY_TAG_SCRIPT : DESCENDANTS_SCRIPT)
                : (tagged ? CHILDREN_BY_TAG_SCRIPT : CHILDREN_SCRIPT);
        Object result = tagged ? parent.executeScript(script, this, tag) : parent.executeScript(script, this);
        Set<WebElement> found = new LinkedHashSet<>();
        if (result instanceof List<?> list) {
            for (Object item : list) {
                found.add((WebElement) item);
            }
        }
        return List.copyOf(found);
    }

    /** The element's attributes by name; fetched once and cached. */
    public Map<String, String> attrs() {
        if (attrs == null) {
            Map<String, String> fetched = new LinkedHashMap<>();
            if (parent.executeScript(ATTRS_SCRIPT, this) instanceof Map<?, ?> map) {
                map.forEach((k, v) -> fetched.put(String.valueOf(k), String.valueOf(v)));
            }
            attrs = fetched;
        }
        return attrs;
    }

    @Override
    public String toString() {
        if (!advancedElements) {
            return super.toString();
        }
        StringBuilder text = new StringBuilder("UcWebElement <").append(getTagName());
        attrs().forEach((name, value) -> text.append(' ').append(name).append("=\"").append(value).append('"'));
        return text.append('>').toString();
    }
}
