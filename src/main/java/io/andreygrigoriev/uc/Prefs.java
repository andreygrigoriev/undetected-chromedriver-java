package io.andreygrigoriev.uc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Chrome profile preferences: dotted keys are expanded to nested JSON and merged into {@code Default/Preferences}. */
public final class Prefs {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Prefs() {
    }

    /** {@code undot("a.b.c", v)} gives {@code {a:{b:{c:v}}}}. */
    public static Map<String, Object> undot(String key, Object value) {
        String[] parts = key.split("\\.");
        Object current = value;
        for (int i = parts.length - 1; i >= 0; i--) {
            Map<String, Object> level = new LinkedHashMap<>();
            level.put(parts[i], current);
            current = level;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) current;
        return result;
    }

    /** Merges b into a copy of a. Leaves from b win, maps merge recursively. Inputs are not modified. */
    public static Map<String, Object> mergeNested(Map<String, Object> a, Map<String, Object> b) {
        Map<String, Object> out = new LinkedHashMap<>(a);
        for (Map.Entry<String, Object> e : b.entrySet()) {
            Object existing = out.get(e.getKey());
            if (existing instanceof Map<?, ?> left && e.getValue() instanceof Map<?, ?> right) {
                @SuppressWarnings("unchecked")
                Map<String, Object> merged = mergeNested((Map<String, Object>) left, (Map<String, Object>) right);
                out.put(e.getKey(), merged);
            } else {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    /** Expands every dotted key of the given prefs and merges the result into the profile's Preferences file. */
    public static void writeTo(Path profileDir, Map<String, Object> prefs) {
        Map<String, Object> expanded = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : prefs.entrySet()) {
            expanded = mergeNested(expanded, undot(e.getKey(), e.getValue()));
        }
        Path file = profileDir.resolve("Default").resolve("Preferences");
        Map<String, Object> existing = readExisting(file);
        Map<String, Object> merged = mergeNested(existing, expanded);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, MAPPER.writeValueAsString(merged).getBytes(StandardCharsets.ISO_8859_1));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        }
    }

    private static Map<String, Object> readExisting(Path file) {
        if (!Files.isRegularFile(file)) {
            return new LinkedHashMap<>();
        }
        try {
            String json = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
            Map<String, Object> parsed = MAPPER.readValue(json, new TypeReference<>() {
            });
            return parsed == null ? new LinkedHashMap<>() : parsed;
        } catch (IOException e) {
            return new LinkedHashMap<>();
        }
    }
}
