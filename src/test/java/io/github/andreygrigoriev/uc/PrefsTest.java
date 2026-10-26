package io.github.andreygrigoriev.uc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PrefsTest {

    @TempDir
    Path dir;

    @Test
    void undotBuildsNestedMaps() {
        assertThat(Prefs.undot("a.b.c", 1)).isEqualTo(Map.of("a", Map.of("b", Map.of("c", 1))));
        assertThat(Prefs.undot("plain", "x")).isEqualTo(Map.of("plain", "x"));
    }

    @Test
    void mergeOverridesLeavesAndKeepsSiblings() {
        Map<String, Object> a = Map.of("x", Map.of("keep", 1, "over", 1), "top", "a");
        Map<String, Object> b = Map.of("x", Map.of("over", 2, "new", 3), "other", true);

        Map<String, Object> merged = Prefs.mergeNested(a, b);

        assertThat(merged).isEqualTo(Map.of(
                "x", Map.of("keep", 1, "over", 2, "new", 3),
                "top", "a",
                "other", true));
        assertThat(a.get("x")).isEqualTo(Map.of("keep", 1, "over", 1));
    }

    @Test
    void leafReplacesMapAndViceVersa() {
        assertThat(Prefs.mergeNested(Map.of("k", Map.of("a", 1)), Map.of("k", 5))).isEqualTo(Map.of("k", 5));
        assertThat(Prefs.mergeNested(Map.of("k", 5), Map.of("k", Map.of("a", 1)))).isEqualTo(Map.of("k", Map.of("a", 1)));
    }

    @Test
    void writesNewPreferencesFile() throws Exception {
        Map<String, Object> prefs = new LinkedHashMap<>();
        prefs.put("profile.default_content_setting_values.notifications", 2);
        prefs.put("profile.password_manager_enabled", false);

        Prefs.writeTo(dir, prefs);

        Map<?, ?> read = read();
        assertThat(read).isEqualTo(Map.of("profile", Map.of(
                "default_content_setting_values", Map.of("notifications", 2),
                "password_manager_enabled", false)));
    }

    @Test
    void mergesIntoExistingPreferences() throws Exception {
        Path file = dir.resolve("Default/Preferences");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"profile\":{\"exit_type\":\"Crashed\",\"name\":\"café\"},\"a\":1}",
                StandardCharsets.ISO_8859_1);

        Prefs.writeTo(dir, Map.of("profile.exit_type", "Normal"));

        assertThat(read()).isEqualTo(Map.of("profile", Map.of("exit_type", "Normal", "name", "café"), "a", 1));
    }

    @Test
    void corruptExistingFileIsReplaced() throws Exception {
        Path file = dir.resolve("Default/Preferences");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{not json");

        Prefs.writeTo(dir, Map.of("a.b", 1));

        assertThat(read()).isEqualTo(Map.of("a", Map.of("b", 1)));
    }

    private Map<?, ?> read() throws Exception {
        String json = new String(Files.readAllBytes(dir.resolve("Default/Preferences")), StandardCharsets.ISO_8859_1);
        return new ObjectMapper().readValue(json, Map.class);
    }
}
