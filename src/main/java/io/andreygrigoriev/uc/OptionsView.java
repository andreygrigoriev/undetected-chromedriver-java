package io.andreygrigoriev.uc;

import org.openqa.selenium.chrome.ChromeOptions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Read-only view of the parts of {@link ChromeOptions} that the launcher needs. */
public final class OptionsView {

    static final String CHROME_OPTIONS = "goog:chromeOptions";

    private final Map<String, Object> top;
    private final Map<String, Object> chrome;

    private OptionsView(Map<String, Object> top, Map<String, Object> chrome) {
        this.top = top;
        this.chrome = chrome;
    }

    @SuppressWarnings("unchecked")
    public static OptionsView of(ChromeOptions options) {
        Map<String, Object> all = options == null ? Map.of() : options.asMap();
        Map<String, Object> top = new LinkedHashMap<>(all);
        Object raw = top.remove(CHROME_OPTIONS);
        Map<String, Object> chrome = raw instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        return new OptionsView(top, chrome);
    }

    public List<String> args() {
        List<String> out = new ArrayList<>();
        if (chrome.get("args") instanceof List<?> list) {
            for (Object o : list) {
                out.add(String.valueOf(o));
            }
        }
        return out;
    }

    public Optional<String> binary() {
        return Optional.ofNullable(chrome.get("binary")).map(Object::toString);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> prefs() {
        return chrome.get("prefs") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    public Optional<String> debuggerAddress() {
        return Optional.ofNullable(chrome.get("debuggerAddress")).map(Object::toString);
    }

    /** Every capability except {@code goog:chromeOptions}. */
    public Map<String, Object> topLevelCapabilities() {
        return top;
    }
}
