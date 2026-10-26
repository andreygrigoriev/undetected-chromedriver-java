package io.andreygrigoriev.uc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PlatformTest {

    private static final Path HOME = Path.of("/home/u");

    private static Platform of(String os, String arch) {
        return Platform.of(os, arch, Map.of(), HOME);
    }

    @ParameterizedTest
    @CsvSource({
            "Linux,amd64,linux64,linux64,chromedriver",
            "Linux,x86_64,linux64,linux64,chromedriver",
            "Mac OS X,aarch64,mac-arm64,mac_arm64,chromedriver",
            "Mac OS X,x86_64,mac-x64,mac64,chromedriver",
            "Windows 11,amd64,win64,win32,chromedriver.exe",
            "Windows 10,x86,win32,win32,chromedriver.exe",
            "Windows 11,aarch64,win64,win32,chromedriver.exe",
    })
    void namesPerOsAndArch(String os, String arch, String cft, String legacy, String exe) {
        Platform p = of(os, arch);
        assertThat(p.cftName()).isEqualTo(cft);
        assertThat(p.legacyName()).isEqualTo(legacy);
        assertThat(p.exeName()).isEqualTo(exe);
    }

    @Test
    void dataDirPerOs() {
        assertThat(of("Windows 11", "amd64").dataDir())
                .isEqualTo(HOME.resolve("AppData/Roaming/undetected_chromedriver"));
        assertThat(of("Linux", "amd64").dataDir())
                .isEqualTo(HOME.resolve(".local/share/undetected_chromedriver"));
        assertThat(of("Mac OS X", "aarch64").dataDir())
                .isEqualTo(HOME.resolve("Library/Application Support/undetected_chromedriver"));
        assertThat(of("FreeBSD", "amd64").dataDir()).isEqualTo(HOME.resolve(".undetected_chromedriver"));
    }

    @Test
    void lambdaOverridesEverythingButWindows() {
        Map<String, String> env = Map.of("LAMBDA_TASK_ROOT", "/var/task");
        assertThat(Platform.of("Linux", "amd64", env, HOME).dataDir())
                .isEqualTo(Path.of("/tmp/undetected_chromedriver"));
        assertThat(Platform.of("Mac OS X", "amd64", env, HOME).dataDir())
                .isEqualTo(Path.of("/tmp/undetected_chromedriver"));
        assertThat(Platform.of("Windows 11", "amd64", env, HOME).dataDir())
                .isEqualTo(HOME.resolve("AppData/Roaming/undetected_chromedriver"));
    }

    @Test
    void unknownOsHasNoDriverName() {
        Platform p = of("FreeBSD", "amd64");
        assertThat(p.os()).isEqualTo(Platform.Os.OTHER);
        assertThat(p.exeName()).isEqualTo("chromedriver");
        assertThatThrownBy(p::cftName).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(p::legacyName).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void detectReturnsCurrentMachine() {
        Platform p = Platform.detect();
        assertThat(p.os()).isNotNull();
        assertThat(p.home()).isEqualTo(Path.of(System.getProperty("user.home")));
    }
}
