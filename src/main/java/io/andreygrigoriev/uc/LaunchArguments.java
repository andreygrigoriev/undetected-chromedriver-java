package io.andreygrigoriev.uc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Builds the command line the browser is launched with, from the user's arguments and the builder settings. */
public final class LaunchArguments {

    private static final String DEFAULT_LANG = "en-US";
    private static final String BARE_LANG = "en-US,en;q=0.9";
    private static final int HEADLESS_NEW_SINCE = 108;

    private LaunchArguments() {
    }

    /**
     * Inputs for {@link #assemble}. {@code builderUserDataDir} and {@code optionsUserDataDir} may be null,
     * {@code versionMain} is 0 when unknown.
     */
    public record Settings(
            boolean headless,
            int versionMain,
            int logLevel,
            boolean suppressWelcome,
            boolean noSandbox,
            String debugHost,
            int debugPort,
            Path builderUserDataDir,
            Path optionsUserDataDir) {
    }

    public record Result(List<String> args, boolean headless, Path userDataDir, boolean keepUserDataDir) {
    }

    /** Returns the final argument list; {@code userArgs} is not modified. */
    public static Result assemble(List<String> userArgs, Settings s) {
        boolean headless = s.headless();
        String lang = null;
        String userDirArg = null;
        List<String> out = new ArrayList<>();

        for (String arg : userArgs) {
            if (arg.startsWith("--headless")) {
                headless = true;
            } else if (arg.equals("--lang")) {
                lang = BARE_LANG;
            } else if (arg.startsWith("--lang=")) {
                lang = arg.substring("--lang=".length());
            } else if (arg.startsWith("--user-data-dir=")) {
                userDirArg = arg.substring("--user-data-dir=".length());
            } else {
                out.add(arg);
            }
        }

        if (lang == null || lang.isBlank()) {
            lang = systemLanguage();
        }
        out.add("--lang=" + lang);

        Path userDataDir;
        boolean keep = true;
        if (s.builderUserDataDir() != null) {
            userDataDir = s.builderUserDataDir();
        } else if (userDirArg != null && !userDirArg.isBlank()) {
            userDataDir = Path.of(userDirArg);
        } else if (s.optionsUserDataDir() != null) {
            userDataDir = s.optionsUserDataDir();
        } else {
            userDataDir = tempDir();
            keep = false;
        }
        userDataDir = userDataDir.toAbsolutePath().normalize();

        out.add("--remote-debugging-host=" + s.debugHost());
        out.add("--remote-debugging-port=" + s.debugPort());
        out.add("--user-data-dir=" + userDataDir);

        if (s.suppressWelcome()) {
            out.add("--no-default-browser-check");
            out.add("--no-first-run");
        }
        if (s.noSandbox()) {
            out.add("--no-sandbox");
            out.add("--test-type");
        }
        if (headless) {
            boolean legacy = s.versionMain() > 0 && s.versionMain() < HEADLESS_NEW_SINCE;
            out.add(legacy ? "--headless=chrome" : "--headless=new");
        }
        out.add("--window-size=1920,1080");
        out.add("--start-maximized");
        out.add("--no-sandbox");
        out.add("--log-level=" + s.logLevel());

        return new Result(List.copyOf(out), headless, userDataDir, keep);
    }

    private static String systemLanguage() {
        String tag = Locale.getDefault().toLanguageTag();
        return tag.isBlank() || tag.equals("und") ? DEFAULT_LANG : tag;
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("undetected_chromedriver_");
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create a temporary profile directory", e);
        }
    }
}
