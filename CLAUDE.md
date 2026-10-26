# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

# undetected-chromedriver-java

Java 25 / Selenium 4 port of ultrafunkamsterdam/undetected-chromedriver v3.5.5. Package `io.github.andreygrigoriev.uc`. README.md lists the Python-argument-to-builder mapping and the deliberate differences from upstream; keep both in sync with behavior changes.

## Commands
- Unit tests (surefire, `*Test`, excludes `@Tag("browser")`): `rtk ./mvnw test`
- Unit plus browser ITs (failsafe, `*IT`, `@Tag("browser")`, need Chrome and network, skipped via `assumeTrue` when no Chrome is found): `rtk ./mvnw verify`
- Single unit test: `rtk ./mvnw test -Dtest=PatcherTest#methodName`
- Single IT: `rtk ./mvnw verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=CdpIT`
- Coverage: jacoco report is written to `target/site/jacoco/` in the `test` phase.
- Release to Maven Central (always run locally by the user, no CI workflow): `./mvnw -Prelease deploy`; needs the `central` server in `~/.m2/settings.xml` and the GPG key (passphrase via pinentry or `MAVEN_GPG_PASSPHRASE`). Check without signing: `rtk ./mvnw -Prelease -Dgpg.skip package`. Coordinates `io.github.andreygrigoriev:undetected-chromedriver-java`.
- Docker (amd64, Xvfb): `docker build -t uc-java .` then `docker run --rm --shm-size=2g uc-java`

Mockito runs as a `-javaagent` (wired through `maven-dependency-plugin:properties` and `@{argLine}`), so tests run outside Maven may need the same agent.

## Launch flow
`Builder.build()` calls `prepare()`, then the private constructor:
1. `ChromeLocator` finds Chrome and its major version (or `versionMain`).
2. `Patcher` resolves (`DriverReleases`), downloads (`DriverDownloader`) and patches (`BinaryPatcher`) chromedriver, cached per version under the data dir (`Platform`).
3. `LaunchArguments` assembles browser args; `Profile` and `Prefs` prepare the user data dir.
4. `BrowserProcess` launches Chrome itself with a remote debugging port.
5. `ChromeDriver` attaches to it through `debuggerAddress` (`LaunchContext.attachOptions()`).

If any step fails, the builder releases what it created (process, profile, service).

## Layout
- `UndetectedChromeDriver` (extends `ChromeDriver`) and its `Builder`: entry point via `newBuilder()` (`builder()` can't be used: `ChromeDriver.builder()` is static). Overrides (`startSession`, `execute`) run during `super(...)`, so they must be null-safe on `context`.
- `OptionsView`: read-only view of user `ChromeOptions` (args, prefs, binary, debugger address). `UndetectedChromeOptions` adds a user data dir and throws if reused for a second driver (`bind()`).
- `Cdp`: HTTP `/json/*` plus WebSocket client. `Reactor`: polls the performance log once a second and dispatches CDP events.
- `HeadlessEvasions` with `src/main/resources/uc/*.js`; `FrameSearch`; `UcWebElement` and `UcElementConverter` (`advancedElements`).
- `LaunchContext`, `ServiceHolder`: state shared between the builder, `reconnect` and cleanup.

## Notes
- `DriverService.stop()` makes a service unusable, so `reconnect` builds a new service and command executor.
- Tests use a local `HttpServer`, `@TempDir`, and a test-classpath `Sleeper` instead of Chrome; ITs serve pages locally.
- License is GPL-3.0 (derived from upstream).
