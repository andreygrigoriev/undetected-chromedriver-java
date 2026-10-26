# undetected-chromedriver-java

Java 25 / Selenium 4 port of ultrafunkamsterdam/undetected-chromedriver v3.5.5. Package `io.andreygrigoriev.uc`.

## Commands
- Unit tests (surefire, `*Test`): `rtk ./mvnw test`
- Unit plus browser ITs (failsafe, `*IT`, `@Tag("browser")`, need Chrome and network, skipped when no Chrome is found): `rtk ./mvnw verify`
- Docker (amd64, Xvfb): `docker build -t uc-java .` then `docker run --rm --shm-size=2g uc-java`

## Layout
- `UndetectedChromeDriver` (extends `ChromeDriver`) and its `Builder`: entry point via `newBuilder()`. Overrides run during `super(...)`, so they must be null-safe on `context`.
- `Patcher`, `DriverReleases`, `DriverDownloader`, `BinaryPatcher`: resolve, download and patch chromedriver; per-version cache under the data dir (`Platform`).
- `ChromeLocator`, `ChromeVersion`: find Chrome and its version.
- `LaunchArguments`, `Profile`, `Prefs`, `OptionsView`, `UndetectedChromeOptions`: options, profile and browser arguments.
- `BrowserProcess`: launches Chrome itself; registry plus shutdown hook.
- `Cdp`: HTTP `/json/*` plus WebSocket client. `Reactor`: polls the performance log and dispatches CDP events.
- `HeadlessEvasions` with `src/main/resources/uc/*.js`; `FrameSearch`; `UcWebElement` and `UcElementConverter`.
- `LaunchContext`, `ServiceHolder`: state shared between the builder, `reconnect` and cleanup.

## Notes
- `DriverService.stop()` makes a service unusable, so `reconnect` builds a new service and command executor.
- Tests use a local `HttpServer`, `@TempDir`, and a test-classpath `Sleeper` instead of Chrome; ITs serve pages locally.
- License is GPL-3.0 (derived from upstream).
