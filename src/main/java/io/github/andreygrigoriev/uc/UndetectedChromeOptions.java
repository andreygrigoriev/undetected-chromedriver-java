package io.github.andreygrigoriev.uc;

import org.openqa.selenium.chrome.ChromeOptions;

import java.nio.file.Path;
import java.util.Optional;

/** {@link ChromeOptions} that also carry a user data dir and refuse to be used for two drivers. */
public class UndetectedChromeOptions extends ChromeOptions {

    private Path userDataDir;
    private boolean bound;

    /** Sets the profile directory; it is made absolute and normalized, and is never deleted on quit. */
    public UndetectedChromeOptions userDataDir(Path dir) {
        this.userDataDir = dir == null ? null : dir.toAbsolutePath().normalize();
        return this;
    }

    public Optional<Path> userDataDir() {
        return Optional.ofNullable(userDataDir);
    }

    /** Marks these options as used by a driver. A second call throws. */
    public synchronized void bind() {
        if (bound) {
            throw new IllegalStateException("These options are already bound to a driver; create a new instance");
        }
        bound = true;
    }
}
