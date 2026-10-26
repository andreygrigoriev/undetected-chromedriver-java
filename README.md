# undetected-chromedriver-java

Java 25 / Selenium 4 port of [ultrafunkamsterdam/undetected-chromedriver](https://github.com/ultrafunkamsterdam/undetected-chromedriver) v3.5.5.

A stock ChromeDriver session is easy to detect: chromedriver injects `cdc_` globals and launches the browser with automation flags. This library patches the chromedriver binary and launches Chrome itself, then has chromedriver attach through `debuggerAddress`.

`UndetectedChromeDriver` extends `ChromeDriver`, so it works anywhere a `WebDriver`, `JavascriptExecutor`, `HasCdp` or `TakesScreenshot` is accepted.

## Install

```xml
<dependency>
    <groupId>io.andreygrigoriev</groupId>
    <artifactId>undetected-chromedriver-java</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

Requires Java 25 and an installed Chrome or Chromium.

## Quick start

```java
UndetectedChromeDriver driver = UndetectedChromeDriver.newBuilder().build();
try {
    driver.get("https://example.com");
    System.out.println(driver.getTitle());
} finally {
    driver.quit();
}
```

The factory is named `newBuilder()` because `ChromeDriver.builder()` is a public static method and can't be hidden with a different return type.

## Python argument to builder method

| Python argument | Builder method |
|---|---|
| `options` | `options(ChromeOptions)` |
| `user_data_dir` | `userDataDir(Path)` |
| `driver_executable_path` | `driverExecutablePath(Path)` |
| `browser_executable_path` | `browserExecutablePath(Path)` |
| `port` | `port(int)` (0 picks a free port) |
| `enable_cdp_events` | `enableCdpEvents(boolean)` |
| `advanced_elements` | `advancedElements(boolean)` |
| `log_level` | `logLevel(int)` |
| `headless` | `headless(boolean)` |
| `version_main` | `versionMain(int)` |
| `patcher_force_close` | `patcherForceClose(boolean)` |
| `suppress_welcome` | `suppressWelcome(boolean)` (default true) |
| `use_subprocess` | `useSubprocess(boolean)` (default true) |
| `debug` | `debug(boolean)` |
| `no_sandbox` | `noSandbox(boolean)` (default true) |
| `user_multi_procs` | `userMultiProcs(boolean)` |
| `**kw` (service args / log) | `serviceLogFile(File)` |
| `desired_capabilities` | dropped: set capabilities on `options` |
| `keep_alive` | dropped: Selenium Java's `ClientConfig` has no toggle |

Extra driver methods: `reconnect(Duration)`, `windowNew()`, `tabNew(url)`, `findElementsRecursive(By)`, `addCdpListener(event, listener)`, `clearCdpListeners()`, `cdp()`, `browserPid()`, `userDataDir()`.

## CDP listeners

```java
UndetectedChromeDriver driver = UndetectedChromeDriver.newBuilder()
        .enableCdpEvents(true)
        .build();
driver.addCdpListener("Network.requestWillBeSent", event -> System.out.println(event));
driver.addCdpListener("*", event -> { /* every event */ });
```

Events are delivered by polling the `performance` log once a second, as upstream does, on a single thread and in order. There is one handler per event name (a later registration replaces the earlier one). `addCdpListener` returns `false` when CDP events were not enabled.

## Headless caveats

`headless(true)` passes `--headless=new` (Chrome 108+, or when the version is unknown) or `--headless=chrome`. The driver then applies evasions: it overrides the user agent to drop `Headless` and injects scripts that fix `navigator` and `window.chrome`. Headful mode is less detectable. On a machine without a display, use the Docker image below, which runs Chrome under Xvfb.

## Known differences from upstream

- Patched binaries are cached per Chrome version in the data dir (`undetected_chromedriver_<version>`) instead of being downloaded on every launch. The binary is never deleted.
- The driver version follows the installed Chrome (detected automatically; override with `versionMain`) instead of always using the latest Stable.
- `user_multi_procs` is a real lock (a thread lock plus a file lock on `<dataDir>/.lock`).
- Only arguments starting with `--headless` are removed from user args. Upstream removed any argument containing "headless".
- Fixed JavaScript in the headless evasions, `PUT /json/new` for new tabs (Chrome 111+), a macOS arm64 driver, and drained browser output pipes.
- `desired_capabilities` and `keep_alive` are dropped (see the table).
- `--no-sandbox` is appended unconditionally, as upstream does.

## CdpVersionFinder warning

Selenium's `ChromiumDriver` opens a CDP connection on startup. Without a matching `selenium-devtools-vNNN` artifact on the classpath, `CdpVersionFinder` logs a `java.util.logging` WARNING on every launch. It's harmless. To silence it, add the matching `selenium-devtools-v<N>` dependency, or raise the level:

```java
java.util.logging.Logger.getLogger("org.openqa.selenium.devtools.CdpVersionFinder")
        .setLevel(java.util.logging.Level.SEVERE);
```

## Docker

The image runs the tests under Xvfb, with Google Chrome installed. It is `linux/amd64` only: there's no linux-arm64 Google Chrome and Chrome-for-Testing publishes no linux-arm64 chromedriver, so on Apple Silicon it runs under emulation.

```sh
docker build -t uc-java .
docker run --rm --shm-size=2g uc-java
```

Chrome needs a bigger `/dev/shm`, hence `--shm-size=2g`. The default command is `./mvnw -B verify`. To run your own project, mount it and override the command:

```sh
docker run --rm --shm-size=2g -v "$PWD":/app uc-java ./mvnw -B verify
```

## License

GPL-3.0, as is upstream. See `LICENSE`.
