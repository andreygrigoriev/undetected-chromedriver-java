package io.github.andreygrigoriev.uc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

/** A Chrome user data directory: either a temporary one or one supplied by the caller (kept on {@link #delete()}). */
public final class Profile {

    private static final Logger LOG = LoggerFactory.getLogger(Profile.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DELETE_ATTEMPTS = 5;
    private static final long DELETE_BACKOFF_MS = 200;

    private final Path dir;
    private final boolean keep;

    private Profile(Path dir, boolean keep) {
        this.dir = dir;
        this.keep = keep;
    }

    /** Creates a fresh temporary profile directory that {@link #delete()} removes. */
    public static Profile temporary() {
        try {
            return new Profile(Files.createTempDirectory("undetected_chromedriver_"), false);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create a temporary profile directory", e);
        }
    }

    /** Wraps a given directory. With {@code keep} set, {@link #delete()} leaves it alone. */
    public static Profile of(Path dir, boolean keep) {
        return new Profile(dir.toAbsolutePath().normalize(), keep);
    }

    public Path dir() {
        return dir;
    }

    public boolean keep() {
        return keep;
    }

    /** Sets a non-null {@code profile.exit_type} to null so Chrome doesn't show the "restore pages" bubble. */
    public void fixExitType() {
        Path file = dir.resolve("Default").resolve("Preferences");
        if (!Files.isRegularFile(file)) {
            LOG.debug("No Preferences file at {}", file);
            return;
        }
        try {
            String json = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
            Map<String, Object> prefs = MAPPER.readValue(json, new TypeReference<>() {
            });
            if (prefs != null && prefs.get("profile") instanceof Map<?, ?> profile
                    && profile.get("exit_type") != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> profileMap = (Map<String, Object>) profile;
                profileMap.put("exit_type", null);
                Files.write(file, MAPPER.writeValueAsString(prefs).getBytes(StandardCharsets.ISO_8859_1));
            }
        } catch (IOException | RuntimeException e) {
            LOG.debug("Cannot fix exit_type in {}", file, e);
        }
    }

    /** Removes the directory, retrying a few times because Chrome may still hold files. No-op when kept. */
    public void delete() {
        if (keep || !Files.exists(dir)) {
            return;
        }
        for (int attempt = 1; attempt <= DELETE_ATTEMPTS; attempt++) {
            try {
                deleteTree();
                return;
            } catch (IOException | RuntimeException e) {
                LOG.debug("Delete attempt {} of {} failed for {}", attempt, DELETE_ATTEMPTS, dir, e);
                if (attempt == DELETE_ATTEMPTS) {
                    return;
                }
                try {
                    Thread.sleep(DELETE_BACKOFF_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void deleteTree() throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(p);
            }
        }
    }
}
