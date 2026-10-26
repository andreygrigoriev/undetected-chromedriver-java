package io.github.andreygrigoriev.uc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.andreygrigoriev.uc.support.Sleeper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.openqa.selenium.SessionNotCreatedException;

class BrowserProcessTest {

    private static final Path JAVA = Path.of(System.getProperty("java.home"), "bin", "java");

    private static BrowserProcess sleeper(BrowserProcess.Mode mode) {
        String classes = Path.of("target", "test-classes").toAbsolutePath().toString();
        return BrowserProcess.launch(JAVA, List.of("-cp", classes, Sleeper.class.getName()), mode);
    }

    @ParameterizedTest
    @EnumSource(BrowserProcess.Mode.class)
    void launchRegistersAndTerminateKillsAndUnregisters(BrowserProcess.Mode mode) {
        BrowserProcess process = sleeper(mode);
        try {
            assertThat(process.isAlive()).isTrue();
            assertThat(BrowserProcess.registeredPids()).contains(process.pid());

            process.terminate(Duration.ofSeconds(5));

            assertThat(process.isAlive()).isFalse();
            assertThat(BrowserProcess.registeredPids()).doesNotContain(process.pid());
        } finally {
            process.terminate(Duration.ofSeconds(5));
        }
    }

    @Test
    void terminateTwiceIsNoOp() {
        BrowserProcess process = sleeper(BrowserProcess.Mode.CHILD);
        process.terminate(Duration.ofSeconds(5));
        process.terminate(Duration.ofSeconds(5));
        assertThat(process.isAlive()).isFalse();
    }

    @Test
    void missingBinaryRaisesSessionNotCreated() {
        Path missing = Path.of("target", "no-such-browser");
        assertThatThrownBy(() -> BrowserProcess.launch(missing, List.of(), BrowserProcess.Mode.CHILD))
                .isInstanceOf(SessionNotCreatedException.class)
                .hasMessageContaining("no-such-browser");
    }
}
