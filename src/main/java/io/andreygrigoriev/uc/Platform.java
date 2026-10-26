package io.andreygrigoriev.uc;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Operating system and CPU architecture, plus everything derived from them: driver platform names,
 * executable suffix and the per-user data directory.
 */
public record Platform(Os os, Arch arch, Map<String, String> env, Path home) {

    public enum Os { WINDOWS, LINUX, MAC, OTHER }

    public enum Arch { X64, X86, ARM64 }

    private static final String DATA_DIR_NAME = "undetected_chromedriver";

    public Platform {
        env = Map.copyOf(env);
    }

    public static Platform detect() {
        return of(System.getProperty("os.name"), System.getProperty("os.arch"), System.getenv(),
                Path.of(System.getProperty("user.home")));
    }

    public static Platform of(String osName, String archName, Map<String, String> env, Path home) {
        return new Platform(parseOs(osName), parseArch(archName), env, home);
    }

    private static Os parseOs(String osName) {
        String name = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (name.contains("win")) {
            return Os.WINDOWS;
        }
        if (name.contains("mac") || name.contains("darwin")) {
            return Os.MAC;
        }
        if (name.contains("linux") || name.contains("nux")) {
            return Os.LINUX;
        }
        return Os.OTHER;
    }

    private static Arch parseArch(String archName) {
        String arch = archName == null ? "" : archName.toLowerCase(Locale.ROOT);
        return switch (arch) {
            case "aarch64", "arm64" -> Arch.ARM64;
            case "x86", "i386", "i486", "i586", "i686" -> Arch.X86;
            default -> Arch.X64;
        };
    }

    /** Platform name used by Chrome-for-Testing download URLs. */
    public String cftName() {
        return switch (os) {
            case LINUX -> "linux64";
            case MAC -> arch == Arch.ARM64 ? "mac-arm64" : "mac-x64";
            case WINDOWS -> arch == Arch.X86 ? "win32" : "win64";
            case OTHER -> throw new IllegalStateException("Unsupported operating system");
        };
    }

    /** Platform name used by the legacy chromedriver storage (Chrome 114 and older). */
    public String legacyName() {
        return switch (os) {
            case LINUX -> "linux64";
            case MAC -> arch == Arch.ARM64 ? "mac_arm64" : "mac64";
            case WINDOWS -> "win32";
            case OTHER -> throw new IllegalStateException("Unsupported operating system");
        };
    }

    public String exeSuffix() {
        return os == Os.WINDOWS ? ".exe" : "";
    }

    public String exeName() {
        return "chromedriver" + exeSuffix();
    }

    /** Where patched drivers are cached. Precedence follows upstream. */
    public Path dataDir() {
        if (os == Os.WINDOWS) {
            return home.resolve("AppData").resolve("Roaming").resolve(DATA_DIR_NAME);
        }
        if (env.containsKey("LAMBDA_TASK_ROOT")) {
            return Path.of("/tmp", DATA_DIR_NAME);
        }
        return switch (os) {
            case LINUX -> home.resolve(".local").resolve("share").resolve(DATA_DIR_NAME);
            case MAC -> home.resolve("Library").resolve("Application Support").resolve(DATA_DIR_NAME);
            default -> home.resolve("." + DATA_DIR_NAME);
        };
    }
}
