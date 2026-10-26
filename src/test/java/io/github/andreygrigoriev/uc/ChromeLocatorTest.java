package io.github.andreygrigoriev.uc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

class ChromeLocatorTest {

    @TempDir
    Path tmp;

    private Platform linux(String path) {
        return Platform.of("Linux", "amd64", Map.of("PATH", path), tmp);
    }

    private Path fake(Path dir, String name, boolean executable) throws IOException {
        Files.createDirectories(dir);
        Path file = Files.writeString(dir.resolve(name), "#!/bin/sh\necho 'Google Chrome 131.0.6778.85'\n");
        assertThat(file.toFile().setExecutable(executable)).isTrue();
        return file;
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void findsExecutableOnPath() throws IOException {
        Path file = fake(tmp.resolve("bin"), "chromium", true);
        assertThat(new ChromeLocator(linux(tmp.resolve("bin").toString())).findExecutable()).contains(file);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void skipsNonExecutable() throws IOException {
        fake(tmp.resolve("bin"), "google-chrome", false);
        assertThat(new ChromeLocator(linux(tmp.resolve("bin").toString())).findExecutable()).isEmpty();
    }

    @Test
    void notFound() {
        assertThat(new ChromeLocator(linux(tmp.resolve("nothing").toString())).findExecutable()).isEmpty();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void respectsOrder() throws IOException {
        Path a = tmp.resolve("a");
        Path b = tmp.resolve("b");
        fake(a, "chrome", true);
        Path preferredName = fake(b, "google-chrome", true);
        // earlier PATH entry wins even over a better name later in PATH
        assertThat(new ChromeLocator(linux(a + ":" + b)).findExecutable()).contains(a.resolve("chrome"));
        // within one directory the name order wins
        fake(b, "chromium", true);
        assertThat(new ChromeLocator(linux(b.toString())).findExecutable()).contains(preferredName);
    }

    @Test
    void macCandidatesFollowPath() {
        Platform mac = Platform.of("Mac OS X", "aarch64", Map.of("PATH", "/x"), tmp);
        List<Path> c = new ChromeLocator(mac).candidates();
        assertThat(c.getFirst()).isEqualTo(Path.of("/x/google-chrome"));
        assertThat(c.getLast().toString()).endsWith("Google Chrome Canary");
        assertThat(c).contains(Path.of("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"));
    }

    @Test
    void windowsCandidates() {
        Platform win = Platform.of("Windows 11", "amd64",
                Map.of("PATH", "C:\\bin;D:\\tools", "PROGRAMFILES", "C:/PF", "LOCALAPPDATA", "C:/LA"), tmp);
        List<Path> c = new ChromeLocator(win).candidates();
        assertThat(c.getFirst().getFileName().toString()).isEqualTo("google-chrome.exe");
        List<Path> app = c.stream().filter(p -> p.endsWith("Application/chrome.exe")).toList();
        assertThat(app).hasSize(8);
        assertThat(app.getFirst()).isEqualTo(Path.of("C:/PF/Google/Chrome/Application/chrome.exe"));
        assertThat(app.get(4)).isEqualTo(Path.of("C:/LA/Google/Chrome/Application/chrome.exe"));
    }

    @Test
    void detectVersionUsesDashDashVersionOnPosix() {
        List<List<String>> calls = new ArrayList<>();
        ChromeLocator locator = new ChromeLocator(linux(""), cmd -> {
            calls.add(cmd);
            return Optional.of("Google Chrome 131.0.6778.85\n");
        });
        assertThat(locator.detectVersion(Path.of("/opt/chrome")))
                .contains(ChromeVersion.parse("131.0.6778.85"));
        assertThat(calls).containsExactly(List.of("/opt/chrome", "--version"));
    }

    @Test
    void detectVersionUsesRegOnWindows() {
        List<List<String>> calls = new ArrayList<>();
        Platform win = Platform.of("Windows 11", "amd64", Map.of(), tmp);
        ChromeLocator locator = new ChromeLocator(win, cmd -> {
            calls.add(cmd);
            return Optional.of("""

                    HKEY_CURRENT_USER\\Software\\Google\\Chrome\\BLBeacon
                        version    REG_SZ    129.0.6668.101
                    """);
        });
        assertThat(locator.detectVersion(Path.of("C:/chrome.exe")).map(ChromeVersion::major)).contains(129);
        assertThat(calls.getFirst()).startsWith("reg", "query");
    }

    @Test
    void detectVersionEmptyWhenRunnerFails() {
        assertThat(new ChromeLocator(linux(""), cmd -> Optional.empty()).detectVersion(Path.of("/x"))).isEmpty();
    }

    @Test
    void parsesVersionOutputs() {
        assertThat(ChromeLocator.parseVersion("Google Chrome 131.0.6778.85")).contains(ChromeVersion.parse("131.0.6778.85"));
        assertThat(ChromeLocator.parseVersion("Chromium 130.0.6723.116 snap")).contains(ChromeVersion.parse("130.0.6723.116"));
        assertThat(ChromeLocator.parseVersion("version REG_SZ 129.0.6668.101")).contains(ChromeVersion.parse("129.0.6668.101"));
        assertThat(ChromeLocator.parseVersion("garbage 1.2.3")).isEmpty();
        assertThat(ChromeLocator.parseVersion("")).isEmpty();
        assertThat(ChromeLocator.parseVersion(null)).isEmpty();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void realProcessRunnerReadsFakeExecutable() throws IOException {
        Path file = fake(tmp.resolve("bin"), "google-chrome", true);
        ChromeLocator locator = new ChromeLocator(linux(tmp.resolve("bin").toString()));
        assertThat(locator.detectVersion(file)).contains(ChromeVersion.parse("131.0.6778.85"));
        assertThat(locator.detectVersion(tmp.resolve("missing"))).isEmpty();
    }
}
