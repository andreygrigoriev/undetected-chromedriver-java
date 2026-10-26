package io.andreygrigoriev.uc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Removes the {@code cdc_} injection from a chromedriver binary by overwriting it with a same-length marker. */
public final class BinaryPatcher {

    private static final Logger LOG = LoggerFactory.getLogger(BinaryPatcher.class);

    private static final String MARKER = "{console.log(\"undetected chromedriver 1337!\")}";
    private static final String PATCHED_TEXT = "undetected chromedriver";

    /** UNIX_LINES makes {@code .} skip only {@code \n}, like a Python bytes regex. */
    private static final Pattern CDC_BLOCK = Pattern.compile("\\{window\\.cdc.*?;\\}", Pattern.UNIX_LINES);
    private static final Pattern EXE_VERSION = Pattern.compile("platform_handle\u0000content\u0000([0-9.]*)");

    private BinaryPatcher() {
    }

    /**
     * Patches the binary in place. The file size never changes.
     *
     * @return true if the file was rewritten, false if there was nothing to patch or the block was too short
     */
    public static boolean patch(Path exe) {
        String content = read(exe);
        Matcher m = CDC_BLOCK.matcher(content);
        if (!m.find()) {
            LOG.warn("No cdc block found in {}, leaving it untouched", exe);
            return false;
        }
        String block = m.group();
        if (block.length() < MARKER.length()) {
            LOG.warn("cdc block in {} is shorter than the marker ({} < {}), leaving it untouched",
                    exe, block.length(), MARKER.length());
            return false;
        }
        String replacement = MARKER + " ".repeat(block.length() - MARKER.length());
        write(exe, content.replace(block, replacement));
        return true;
    }

    public static boolean isPatched(Path exe) {
        if (!Files.isRegularFile(exe)) {
            return false;
        }
        return read(exe).contains(PATCHED_TEXT);
    }

    /** Reads the version string embedded in the executable, if present. */
    public static Optional<ChromeVersion> parseExeVersion(Path exe) {
        Matcher m = EXE_VERSION.matcher(read(exe));
        if (!m.find() || m.group(1).isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(ChromeVersion.parse(m.group(1)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    private static void write(Path file, String content) {
        try {
            Files.write(file, content.getBytes(StandardCharsets.ISO_8859_1));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        }
    }
}
