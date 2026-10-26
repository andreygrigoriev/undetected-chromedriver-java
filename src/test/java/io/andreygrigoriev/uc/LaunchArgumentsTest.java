package io.andreygrigoriev.uc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaunchArgumentsTest {

    @TempDir
    Path tmp;

    private LaunchArguments.Settings settings(boolean headless, int version, boolean welcome, boolean sandbox,
                                              Path builder, Path options) {
        return new LaunchArguments.Settings(headless, version, 0, welcome, sandbox, "127.0.0.1", 9222, builder, options);
    }

    private LaunchArguments.Settings defaults() {
        return settings(false, 0, true, true, tmp.resolve("b"), null);
    }

    @Test
    void defaultsProduceExactList() {
        Locale old = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("de-DE"));
        try {
            var r = LaunchArguments.assemble(List.of(), defaults());
            assertEquals(List.of(
                    "--lang=de-DE",
                    "--remote-debugging-host=127.0.0.1",
                    "--remote-debugging-port=9222",
                    "--user-data-dir=" + tmp.resolve("b").toAbsolutePath().normalize(),
                    "--no-default-browser-check", "--no-first-run",
                    "--no-sandbox", "--test-type",
                    "--window-size=1920,1080", "--start-maximized",
                    "--no-sandbox",
                    "--log-level=0"), r.args());
            assertTrue(r.keepUserDataDir());
            assertFalse(r.headless());
        } finally {
            Locale.setDefault(old);
        }
    }

    @Test
    void userHeadlessIsRemovedAndReplaced() {
        var user = List.of("--headless", "--foo");
        var r = LaunchArguments.assemble(user, settings(false, 120, true, true, tmp, null));
        assertTrue(r.headless());
        assertFalse(r.args().contains("--headless"));
        assertTrue(r.args().contains("--foo"));
        assertTrue(r.args().contains("--headless=new"));
        assertEquals(List.of("--headless", "--foo"), user);
    }

    @Test
    void headlessFlagDependsOnVersion() {
        assertTrue(LaunchArguments.assemble(List.of(), settings(true, 107, true, true, tmp, null))
                .args().contains("--headless=chrome"));
        assertTrue(LaunchArguments.assemble(List.of(), settings(true, 108, true, true, tmp, null))
                .args().contains("--headless=new"));
        assertTrue(LaunchArguments.assemble(List.of(), settings(true, 0, true, true, tmp, null))
                .args().contains("--headless=new"));
    }

    @Test
    void consecutiveHeadlessArgsAreAllRemoved() {
        var r = LaunchArguments.assemble(List.of("--headless", "--headless=new", "--x"),
                settings(false, 120, true, true, tmp, null));
        assertEquals(1, r.args().stream().filter(a -> a.startsWith("--headless")).count());
        assertTrue(r.args().contains("--x"));
    }

    @Test
    void userDataDirPrecedence() {
        Path b = tmp.resolve("builder");
        Path o = tmp.resolve("opts");
        var userArg = List.of("--user-data-dir=" + tmp.resolve("arg"));

        var r1 = LaunchArguments.assemble(userArg, settings(false, 0, true, true, b, o));
        assertEquals(b, r1.userDataDir());
        assertTrue(r1.keepUserDataDir());

        var r2 = LaunchArguments.assemble(userArg, settings(false, 0, true, true, null, o));
        assertEquals(tmp.resolve("arg"), r2.userDataDir());
        assertTrue(r2.keepUserDataDir());
        assertEquals(1, r2.args().stream().filter(a -> a.startsWith("--user-data-dir=")).count());

        var r3 = LaunchArguments.assemble(List.of(), settings(false, 0, true, true, null, o));
        assertEquals(o, r3.userDataDir());
        assertTrue(r3.keepUserDataDir());

        var r4 = LaunchArguments.assemble(List.of(), settings(false, 0, true, true, null, null));
        assertFalse(r4.keepUserDataDir());
        assertTrue(r4.userDataDir().toFile().isDirectory());
        r4.userDataDir().toFile().delete();
    }

    @Test
    void optionalFlagsAreOmitted() {
        var r = LaunchArguments.assemble(List.of(), settings(false, 0, false, false, tmp, null));
        assertFalse(r.args().contains("--no-first-run"));
        assertFalse(r.args().contains("--no-default-browser-check"));
        assertFalse(r.args().contains("--test-type"));
        assertEquals(1, r.args().stream().filter("--no-sandbox"::equals).count());
    }

    @Test
    void langUserValueBareAndDefault() {
        assertTrue(LaunchArguments.assemble(List.of("--lang=fr-FR"), defaults()).args().contains("--lang=fr-FR"));
        var bare = LaunchArguments.assemble(List.of("--lang"), defaults()).args();
        assertTrue(bare.contains("--lang=en-US,en;q=0.9"));
        assertFalse(bare.contains("--lang"));

        Locale old = Locale.getDefault();
        Locale.setDefault(Locale.ROOT);
        try {
            assertTrue(LaunchArguments.assemble(List.of(), defaults()).args().contains("--lang=en-US"));
        } finally {
            Locale.setDefault(old);
        }
    }

    @Test
    void injectedLocaleIsUsed() {
        Locale old = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("ja-JP"));
        try {
            assertTrue(LaunchArguments.assemble(List.of(), defaults()).args().contains("--lang=ja-JP"));
        } finally {
            Locale.setDefault(old);
        }
    }
}
