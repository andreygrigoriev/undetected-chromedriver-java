# Port undetected-chromedriver to Java

## Overview
- Port [ultrafunkamsterdam/undetected-chromedriver](https://github.com/ultrafunkamsterdam/undetected-chromedriver) v3.5.5 (commit `757ed6a`, Python, ~1.9k LOC) to Java 25 on Selenium 4, aiming for full feature parity.
- Problem it solves: a stock ChromeDriver session is easy to detect. The `cdc_` globals are injected by chromedriver, and the browser is launched by chromedriver with automation flags. undetected-chromedriver patches the chromedriver binary and launches Chrome **itself**, then has chromedriver *attach* through `debuggerAddress`.
- Integration: a library (`io.andreygrigoriev:undetected-chromedriver-java`) whose main type `UndetectedChromeDriver extends ChromeDriver`. It works anywhere a `WebDriver`, `JavascriptExecutor`, `HasCdp` or `TakesScreenshot` is accepted.

## Context (from discovery)
- **Current project:** an empty Maven skeleton (`pom.xml` with Java 25 compiler props only). It has no sources, no `mvnw` and no git repo yet.
- **Upstream modules and their Java targets:**
  - `patcher.py` (423 LOC): `Patcher` plus its helpers. Download from Chrome-for-Testing, unzip, regex-patch `\{window\.cdc.*?;\}` → same-length `{console.log("undetected chromedriver 1337!")}`.
  - `__init__.py` (897 LOC): `UndetectedChromeDriver` plus its builder, `ChromeLocator`, launch-argument assembly, profile handling, headless evasions, `reconnect`, `findElementsRecursive`, `quit`.
  - `options.py`: `UndetectedChromeOptions` (`userDataDir` plus reuse guard) and `Prefs` (dotted `prefs` keys → nested JSON in `<profile>/Default/Preferences`).
  - `dprocess.py`: `BrowserProcess`. Detached launch, plus a cleanup that kills registered PIDs when the JVM exits.
  - `reactor.py`: `Reactor`. Polls the `performance` log and dispatches CDP events to listeners.
  - `cdp.py`: `Cdp`. HTTP `/json/*` endpoints plus a WebSocket `send`.
  - `webelement.py`: `UcWebElement` (`clickSafe`, `children`, `attrs`, readable `toString`).
  - `devtool.py`: **not ported**. It's an experimental, unexported module: a dict `Structure`, a `timeout` decorator and an ad-hoc `test()`. Nothing in the package uses it.
- **Upstream defects to fix rather than copy:**
  - macOS is hardcoded to `mac-x64` and has no `mac-arm64`.
  - The `user_multi_procs` lock is a fresh, unshared `Lock()`, so it does nothing.
  - `CDP.tab_new` uses POST, but Chrome ≥ 111 requires `PUT /json/new`.
  - Stdout/stderr pipes on the browser are never drained, which can block Chrome.
  - The installed Chrome version is never detected; it always downloads the latest Stable driver.
  - The `Patcher.__del__` loop condition is inverted, so the binary is never deleted. The port **deliberately never deletes** the patched binary either. That's parity in practice, and the binary is now a per-version cache.
- **Selenium 4 Java facts this design relies on** (checked against 4.49 sources; re-check on 4.51 in Task 1):
  - `ChromeDriver(ChromeDriverService, ChromeOptions)` is public.
  - `RemoteWebDriver.setElementConverter` and `setCommandExecutor` are protected, and `startSession(Capabilities)` is protected.
  - `JsonToWebElementConverter.newRemoteWebElement()` is a protected hook.
  - `DriverCommandExecutor` starts the service on `NEW_SESSION`.
  - `manage().logs().get("performance")` → `GET_LOG`.
  - `PortProber.findFreePort()` exists.
  - **`DriverService.stop()` shuts down its internal executor, so a stopped service can't be restarted.** `reconnect` has to build a new service and command executor.
  - **The `RemoteWebDriver` constructor calls `startSession`/`execute` before any subclass field is assigned.**
  - **`ChromeDriver.builder()` is a public static method**, so a subclass `builder()` with a different return type won't compile.
  - The `ChromiumDriver` constructor eagerly opens a CDP connection through `debuggerAddress`. With no `selenium-devtools-vNNN` artifact, `CdpVersionFinder` logs a JUL WARNING on every launch.
- **Dependencies (latest stable, checked 2026-10-26):** `selenium-chrome-driver` 4.51.0, `jackson-databind` 2.22.4, `slf4j-api` 2.0.20. Test: `junit-jupiter` 6.1.3, `assertj-core` 3.27.7, `mockito-core` 5.24.0, `slf4j-simple` 2.0.20. Plugins: surefire/failsafe 3.6.1.
- HTTP, WebSocket and zip use the JDK (`java.net.http.HttpClient`, `java.net.http.WebSocket`, `java.util.zip`). Test HTTP servers use `com.sun.net.httpserver.HttpServer`.
- **License:** upstream is GPL-3.0, so this derivative port is GPL-3.0.

## Development Approach
- **testing approach**: Regular (code first, then tests)
- complete each task fully before moving to the next
- make small, focused changes
- **CRITICAL: every task MUST include new/updated tests** for code changes in that task
  - tests are not optional - they are a required part of the checklist
  - write unit tests for new functions/methods
  - write unit tests for modified functions/methods
  - add new test cases for new code paths
  - update existing test cases if behavior changes
  - tests cover both success and error scenarios
- **CRITICAL: all tests must pass before starting next task** - no exceptions
- **CRITICAL: update this plan file when scope changes during implementation**
- run tests after each change: `rtk ./mvnw test`
- the library has no existing consumers, so there's no backward compatibility to keep

## Testing Strategy
- **unit tests** (`*Test.java`, surefire): required for every task. Network goes to a local `HttpServer` with injectable base URLs. The filesystem uses JUnit `@TempDir`. Processes are tested by launching a small test-classpath `Sleeper` main via `java` instead of Chrome.
- **integration tests** (`*IT.java`, failsafe, `@Tag("browser")`): need a real Chrome and network access to Chrome-for-Testing. They're skipped through `Assumptions` when `ChromeLocator` finds no browser, and run with `rtk ./mvnw verify`. All pages are served locally; ITs don't touch live sites.
- Detection checks use a local HTML page. It asserts `navigator.webdriver === false`, that there are no `cdc_`/`$cdc_` window or document keys, and that the UA has no `HeadlessChrome`. External bot-check sites are only verified manually (see Post-Completion).
- This is a library with no UI, so there are no e2e tests beyond the browser ITs.

## Progress Tracking
- mark completed items with `[x]` immediately when done
- add newly discovered tasks with ➕ prefix
- document issues/blockers with ⚠️ prefix
- update plan if implementation deviates from original scope
- keep plan in sync with actual work done

## Solution Overview
- **API shape: subclass plus builder.** `UndetectedChromeDriver.newBuilder()…build()`. It's named `newBuilder` because `ChromeDriver.builder()` would be hidden with an incompatible return type. The builder does all the pre-launch work:
  1. patch the driver
  2. choose the port and profile
  3. write prefs
  4. launch the browser
  5. build the `ChromeDriverService`

  It then calls a private constructor with an immutable `LaunchContext` record. That constructor calls `super(service, attachOptions)` and stores the context.
- **Overrides must be null-safe during construction.** `execute`, `startSession` and `get` all run inside `super(...)` before `context` is assigned. Their logic is guarded by `context != null`, and `debug` is a plain `boolean` field.
- **The browser is launched by us, not by chromedriver.** The attach options keep the user's top-level capabilities (`pageLoadStrategy`, `timeouts`, `unhandledPromptBehavior`, `acceptInsecureCerts`, …). Inside `goog:chromeOptions` only `debuggerAddress` is kept, plus `goog:loggingPrefs` when CDP events are on. The browser gets the full argument list from `LaunchArguments`; chromedriver ignores `args`, `binary`, `prefs` and `extensions` when attaching.
- **Reconnect builds a new service.** It stops the old service, sleeps, builds a new `ChromeDriverService` for the same patched exe, and calls `setCommandExecutor(new ChromiumDriverCommandExecutor(newService, extraCommands, ClientConfig.defaultConfig()))`. `extraCommands` is `AddHasCdp` plus `AddHasCasting`, the same as `ChromeDriver`'s private executor. Then it calls `startSession(attachCaps)`. The current service lives in a mutable `ServiceHolder` shared with `quit()` and the `Cleaner` action, which never captures `this`.
- **CDP events are delivered by polling the performance log**, as upstream does, not through Selenium `DevTools`. DevTools needs a `selenium-devtools-vNNN` artifact pinned to a Chrome version.
- **Cleanup has three layers:**
  - an explicit `quit()`
  - a `java.lang.ref.Cleaner` action that kills the chromedriver and browser processes, replacing `weakref.finalize`
  - one JVM shutdown hook that terminates registered browser PIDs, replacing `atexit` in `dprocess`
- **Patched binaries are cached per version**, at `<dataDir>/undetected_chromedriver_<version>[.exe]`, and a binary that is already patched is reused. All writes happen under a static `ReentrantLock` (threads) plus a `FileChannel` lock on `<dataDir>/.lock` (processes), which is a real version of upstream's `user_multi_procs`. ⚠️ This deviates from upstream, which re-downloads on every launch; drop it if strict parity matters more.
- **Docker support.** A `Dockerfile` builds a `linux/amd64` image on `eclipse-temurin:25-jdk` with `google-chrome-stable` (Google apt repo), fonts and `xvfb`, running as a non-root user. The entrypoint runs `xvfb-run`, so headful mode (less detectable than headless) works without a display. The default command is `./mvnw verify`, which builds and runs every test including the browser ITs, and a mounted project can override it. The platform is pinned to amd64 because there's no linux-arm64 Google Chrome and Chrome-for-Testing publishes no linux-arm64 chromedriver; on Apple Silicon it runs under emulation. Chrome needs a bigger `/dev/shm`, so the docs say to run with `--shm-size=2g`.

## Technical Details

**Package** `io.andreygrigoriev.uc`:

| Class | Responsibility |
|---|---|
| `Platform` (record) | OS/arch detection. CfT platform name (`linux64`, `mac-arm64`, `mac-x64`, `win32`, `win64`) or legacy name (`linux64`, `mac64`, `mac_arm64`, `win32`). Exe suffix. Data dir, using upstream's precedence: Windows `~/AppData/Roaming/undetected_chromedriver`; then `LAMBDA_TASK_ROOT` set → `/tmp/undetected_chromedriver`; then Linux `~/.local/share/undetected_chromedriver`; then macOS `~/Library/Application Support/undetected_chromedriver`; otherwise `~/.undetected_chromedriver` |
| `ChromeVersion` (record, `Comparable`) | Parse `"131.0.6778.85"`; `major()` |
| `ChromeLocator` | `findExecutable()` (PATH candidates `google-chrome`, `chromium`, `chromium-browser`, `chrome`, `google-chrome-stable`; macOS `.app` paths; Windows `PROGRAMFILES`/`PROGRAMFILES(X86)`/`LOCALAPPDATA`/`PROGRAMW6432` × `Google/Chrome`, `Google/Chrome Beta`, `Google/Chrome SxS`, `Chromium`, each followed by `/Application/chrome.exe`), searched in a fixed order. `detectVersion(path)`: `--version` on POSIX, `reg query HKCU\Software\Google\Chrome\BLBeacon /v version` on Windows; empty `Optional` on failure |
| `DriverReleases` | `resolve(Integer versionMain)`. Legacy (≤114): `GET {legacyRepo}/LATEST_RELEASE_{major}`. New: `last-known-good-versions-with-downloads.json` → `channels.Stable.version`, or `latest-versions-per-milestone-with-downloads.json` → `milestones[major].version`. `downloadUrl(version, platform)` |
| `DriverDownloader` | Download to a temp file, extract `chromedriver[-{platform}]/chromedriver[.exe]`, move atomically to the target, `chmod 755` |
| `BinaryPatcher` | Read bytes as ISO-8859-1 (1:1 byte↔char), find `\{window\.cdc.*?;\}` with `Pattern.UNIX_LINES`, so `.` excludes only `\n` as in Python bytes regex. Replace **every** occurrence of the matched literal (Python `bytes.replace`) with the right-padded marker, then write back. `isPatched` searches for `"undetected chromedriver"`. `parseExeVersion` regex `platform_handle\x00content\x00([0-9.]*)` |
| `Patcher` | `auto()` flow: custom path → patch only if needed; `userMultiProcs` → reuse the newest patched binary; otherwise resolve, then use the cache or download, then patch. When `patcherForceClose` is set and the move or patch fails because the target is in use, `forceKillInstances(name)` uses `ProcessHandle.allProcesses()`, matching the exact target basename, then retries once |
| `UndetectedChromeOptions extends ChromeOptions` | Adds only `userDataDir(Path)` and the reuse guard. The builder accepts any `ChromeOptions` and reads `args`, `binary`, `prefs` and `debuggerAddress` from `asMap()["goog:chromeOptions"]` (`OptionsView` helper) |
| `Prefs` | `undot("a.b.c", v)` → `{a:{b:{c:v}}}`; `mergeNested(a, b)`; `writeTo(profileDir)`, which merges with the existing `Default/Preferences` (read as latin1, as upstream does) |
| `Profile` | Temp dir (`Files.createTempDirectory`) or the user dir, plus a keep flag. `fixExitType()` sets `profile.exit_type` to null. `delete()` retries 5× with 200 ms backoff |
| `LaunchArguments` | Pure function, covered in detail below |
| `BrowserProcess` | `launch(binary, args, mode)` with IO redirected to `DISCARD`. `DETACHED` prefixes `setsid` **on Linux only**. macOS has no `setsid` binary, and on Windows `cmd /c start` would return the PID of `cmd`. Java children already survive JVM exit on all three platforms, so elsewhere `DETACHED` behaves like `CHILD`. Static PID registry, one shutdown hook, `terminate(Duration grace)` → destroy, then `destroyForcibly`, then wait for `onExit()` |
| `UndetectedChromeDriver extends ChromeDriver` | Created with `newBuilder()`. Overrides `get`, `quit`, `startSession`, and `execute(CommandPayload)` for debug logging (`execute(String, Map)` isn't on the hot path). Adds `reconnect(Duration)`, `windowNew()`, `tabNew(url)`, `findElementsRecursive(By)` → `Iterable<WebElement>`, `addCdpListener`, `clearCdpListeners`, `browserPid()`, `userDataDir()` |
| `HeadlessEvasions` | Loads `uc/headless-navigator.js` and `uc/headless-chrome-runtime.js` from resources. `applyIfNeeded(driver)` checks `navigator.webdriver`, then runs `Page.addScriptToEvaluateOnNewDocument` ×2 and `Network.setUserAgentOverride` (UA with `Headless` removed) |
| `Reactor` | Virtual thread that polls `manage().logs().get("performance")` every 1 s, parses `message.message.method`, and sends each event **sequentially on the poll thread** to `"*"` or to the handler keyed by lowercased method (one per key, as upstream). Ignores `invalid session id`. `stop()` |
| `UcWebElement extends RemoteWebElement` | `clickSafe()` (click, then `reconnect(100ms)`), `children(String tag, boolean recursive)`, `attrs()` (cached JS), `toString()` → `UcWebElement <a class="…" href="#">` when `advancedElements` is on, otherwise Selenium's default |
| `UcElementConverter extends JsonToWebElementConverter` | Overrides `newRemoteWebElement()` to return `UcWebElement`, installed with `setElementConverter` |
| `Cdp` | `Cdp(String debuggerAddress)`. `tabList()`, `tabNew(url)` (**PUT**), `tabActivate(id)`, `tabClose(id)`, `tabCloseLastOpened()`, `send(method, params)` → `CompletableFuture<JsonNode>` over JDK `WebSocket`, `lastJson()` |

**`LaunchArguments`** takes the user args plus settings (headless, versionMain, logLevel, suppressWelcome, noSandbox, debug host/port, userDataDir) and returns `record Result(List<String> args, boolean headless, Path userDataDir, boolean keepUserDataDir)`. It follows `__init__.py` lines 309–436:
1. Remove every arg starting with `--headless`, which sets headless. Python matches any arg containing "headless"; this is a deliberate narrowing.
2. Language: a user `--lang=X` gives X; a bare `--lang` gives `en-US,en;q=0.9`; otherwise use the system locale (`Locale.getDefault().toLanguageTag()`), falling back to `en-US`. Always append `--lang=<language>`.
3. `userDataDir` precedence: builder value (keep), then a user `--user-data-dir` arg (keep), then `UndetectedChromeOptions.userDataDir` (keep), then a temp dir (no keep).
4. Append `--remote-debugging-host`/`--remote-debugging-port` and `--user-data-dir`.
5. If `suppressWelcome`, append `--no-default-browser-check --no-first-run`.
6. If `noSandbox`, append `--no-sandbox --test-type`.
7. If headless, append `--headless=new` (≥108 or unknown) or `--headless=chrome` (<108).
8. Append `--window-size=1920,1080 --start-maximized`.
9. Append `--no-sandbox` **unconditionally**, as upstream line 429 does.
10. Append `--log-level=N`.

**Builder options**, mapped from Python's keyword arguments: `options`, `userDataDir`, `driverExecutablePath`, `browserExecutablePath`, `port` (0 = `PortProber.findFreePort()`), `enableCdpEvents`, `advancedElements`, `logLevel`, `headless`, `versionMain`, `patcherForceClose`, `suppressWelcome` (true), `useSubprocess` (true → `CHILD`, false → `DETACHED`), `debug`, `noSandbox` (true), `userMultiProcs`, `serviceArgs`/`serviceLogFile` (from Python's `**kw`).

Dropped: `desired_capabilities`, because callers set capabilities on `options`, and `keep_alive`, because Selenium Java's `ClientConfig` has no keep-alive toggle. The README documents both.

**`build()` flow:**
1. `Patcher.auto()`
2. Bind the options (reuse guard).
3. Debugger address from `options` or `127.0.0.1:<port>`.
4. Add `goog:loggingPrefs` if CDP events are enabled.
5. `LaunchArguments.assemble()`
6. Resolve the browser binary (options, then `browserExecutablePath`, then `ChromeLocator`). If none is found, throw `SessionNotCreatedException` with the upstream hint text.
7. `Prefs.writeTo` and `Profile.fixExitType()`
8. `BrowserProcess.launch`
9. `ChromeDriverService` for the patched exe, placed in the `ServiceHolder`
10. Build the attach options (see Solution Overview).
11. Call the constructor, which runs `super(service, attachOptions)`, then sets the element converter, starts the `Reactor` and registers the `Cleaner`.

If any step after 8 fails, terminate the browser and delete the temp profile before rethrowing.

**`quit()`**:
1. Run `super.quit()`, which also closes Selenium's CDP connection, and swallow the error if the session is already dead.
2. Stop the current service.
3. Stop the reactor.
4. `BrowserProcess.terminate(5s)`, which waits for exit.
5. Delete the profile unless `keepUserDataDir`.
6. Make it idempotent.

## What Goes Where
- **Implementation Steps** (`[ ]` checkboxes): code, tests, resources and docs in this repo.
- **Post-Completion** (no checkboxes): checks against live bot-detection sites, platform coverage on machines we don't have, and the publishing decision.

## Implementation Steps

### Task 1: Project setup (pom, wrapper, logging)

**Files:**
- Modify: `pom.xml`
- Create: `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`
- Create: `src/test/java/io/andreygrigoriev/uc/SmokeTest.java`
- Create: `src/test/resources/simplelogger.properties`

- [x] add dependencies: `selenium-chrome-driver` 4.51.0, `jackson-databind` 2.22.4, `slf4j-api` 2.0.20. Test scope: `junit-jupiter` 6.1.3, `assertj-core` 3.27.7, `mockito-core` 5.24.0, `slf4j-simple` 2.0.20
- [x] replace `maven.compiler.source`/`target` with `maven.compiler.release=25`
- [x] configure `maven-surefire-plugin` 3.6.1 (excludes the `browser` tag) with `argLine` `-javaagent:${org.mockito:mockito-core:jar}` (via `maven-dependency-plugin:properties`), and `maven-failsafe-plugin` 3.6.1 (`*IT.java`, `verify` goal)
- [x] generate the Maven wrapper (`mvn wrapper:wrapper`)
- [x] re-check the Selenium facts listed in Context against the 4.51 sources jar (constructor calls during `super`, `DriverService.stop` closes its executor, `setCommandExecutor`/`setElementConverter`/`newRemoteWebElement` visibility, `ChromiumDriverCommandExecutor` constructor), and update this plan if anything differs (re-checked on 4.51.0: all facts hold; DriverService.stop() calls close(), which shuts down its executor; PortProber lives in selenium-api)
- [x] write a trivial `SmokeTest` that proves JUnit, AssertJ and Mockito run
- [x] run `rtk ./mvnw test`; it must pass before task 2

### Task 2: Platform detection and versions

**Files:**
- Create: `src/main/java/io/andreygrigoriev/uc/Platform.java`
- Create: `src/main/java/io/andreygrigoriev/uc/ChromeVersion.java`
- Create: `src/test/java/io/andreygrigoriev/uc/PlatformTest.java`
- Create: `src/test/java/io/andreygrigoriev/uc/ChromeVersionTest.java`

- [x] implement `Platform.detect()` from `os.name`/`os.arch`, plus a factory `Platform.of(os, arch, env, home)` for tests
- [x] implement the CfT and legacy platform names, exe suffix, and data-dir resolution with upstream precedence (Windows, then LAMBDA, then Linux, then macOS, then fallback)
- [x] implement `ChromeVersion.parse`, `major()` and `compareTo`
- [x] write tests covering every OS/arch combination → platform name, exe name and data dir, including Linux with `LAMBDA_TASK_ROOT` set
- [x] write tests for invalid version strings and for ordering
- [x] run tests; they must pass before task 3

### Task 3: Find the Chrome executable and its version

**Files:**
- Create: `src/main/java/io/andreygrigoriev/uc/ChromeLocator.java`
- Create: `src/test/java/io/andreygrigoriev/uc/ChromeLocatorTest.java`

- [x] implement candidate generation (PATH entries × names, macOS `.app` paths, Windows env dirs × `Chrome`/`Chrome Beta`/`Chrome SxS`/`Chromium`) in a fixed order, returning the first that exists and is executable
- [x] implement `detectVersion(Path)`: run `--version` on POSIX and parse the first `\d+(\.\d+){3}`; on Windows use `reg query`; return an empty `Optional` on failure or a 10 s timeout
- [x] make PATH, env and platform injectable for tests
- [x] write tests with fake executables under `@TempDir` (found, not executable, not found, order respected)
- [x] write tests for parsing version output (`Google Chrome 131.0.6778.85`, `Chromium 130.0.6723.116 snap`, `reg query` output, garbage)
- [x] run tests; they must pass before task 4

### Task 4: Resolve driver releases

**Files:**
- Create: `src/main/java/io/andreygrigoriev/uc/DriverReleases.java`
- Create: `src/main/java/io/andreygrigoriev/uc/DriverResolutionException.java`
- Create: `src/test/java/io/andreygrigoriev/uc/DriverReleasesTest.java`
- Create: `src/test/java/io/andreygrigoriev/uc/support/StubHttpServer.java`

- [x] implement `resolve(Integer versionMain)` against three endpoints (legacy `LATEST_RELEASE_N`, last-known-good Stable, per-milestone), with configurable base URLs
- [x] implement `downloadUrl(version, platform)`: new versions use `https://storage.googleapis.com/chrome-for-testing-public/{v}/{p}/chromedriver-{p}.zip`, legacy versions use `{legacyRepo}/{v}/chromedriver_{p}.zip`
- [x] create `StubHttpServer`, a JDK `HttpServer` wrapper that serves canned bodies and status codes and records requests
- [x] write tests for each endpoint branch, including the legacy/new boundary at 114/115
- [x] write tests for errors: unknown milestone, HTTP 404/500, malformed JSON. Each should raise a `DriverResolutionException`
- [x] run tests; they must pass before task 5

### Task 5: Download and unzip the driver

**Files:**
- Create: `src/main/java/io/andreygrigoriev/uc/DriverDownloader.java`
- Create: `src/test/java/io/andreygrigoriev/uc/DriverDownloaderTest.java`

- [ ] implement the streamed download to a temp file with `HttpClient`, then extract the zip entry (handling both new nested and legacy flat layouts)
- [ ] extract only the expected entry name (no zip-slip), then do an atomic move to the target and set POSIX `rwxr-xr-x` (skipped on Windows)
- [ ] write tests with in-memory zip archives served by `StubHttpServer` (both layouts)
- [ ] write tests for a missing entry, a corrupted zip and a 404, each leaving no partial target file
- [ ] run tests; they must pass before task 6

### Task 6: Patch the driver binary

**Files:**
- Create: `src/main/java/io/andreygrigoriev/uc/BinaryPatcher.java`
- Create: `src/test/java/io/andreygrigoriev/uc/BinaryPatcherTest.java`

- [ ] implement `patch(Path)`: ISO-8859-1 round-trip, regex `\{window\.cdc.*?;\}` with `Pattern.UNIX_LINES`, replace-all of the matched literal with the marker right-padded to the same length, then write back with the size unchanged. Log a warning if the block isn't found
- [ ] implement `isPatched(Path)` (false if the file is missing) and `parseExeVersion(Path)`
- [ ] write tests with synthetic binaries (random bytes plus an embedded block plus non-UTF-8 bytes): size unchanged, only the block changed, `isPatched` true, and patching twice changes nothing
- [ ] write regex-parity tests: a block containing `\r` and `0x85` bytes still matches, a block containing `\n` doesn't, and a block appearing twice gets both copies replaced
- [ ] write tests for no block found (file untouched), a block shorter than the marker (refuse and leave the file intact), and version parsing
- [ ] run tests; they must pass before task 7

### Task 7: `Patcher` orchestration (cache, locking, multi-proc, force kill)

**Files:**
- Create: `src/main/java/io/andreygrigoriev/uc/Patcher.java`
- Create: `src/test/java/io/andreygrigoriev/uc/PatcherTest.java`

- [ ] implement `auto()` with these branches: custom path (patch only if needed, never deleted), `userMultiProcs` (newest `*chromedriver*` that's already patched, otherwise fall through), and the default path (resolve version → use the cached patched binary or download and patch)
- [ ] default `versionMain`: the explicit value, otherwise `ChromeLocator.detectVersion(browser).major()`, otherwise latest Stable
- [ ] guard every write with a static `ReentrantLock` and then a `FileChannel.lock()` on `<dataDir>/.lock`
- [ ] implement `forceKillInstances(exeName)` (exact basename match via `ProcessHandle`), used only when `patcherForceClose` is set and writing the target fails, followed by one retry
- [ ] write tests (fake `DriverReleases`/`DriverDownloader` injected): cache hit skips the download, cache miss downloads and patches, custom path is not re-patched, and multi-procs reuses the newest file
- [ ] write concurrency tests: two threads calling `auto()` at once lead to one download and no `OverlappingFileLockException`
- [ ] write tests for errors: resolution failure propagates, and both locks are released after an exception
- [ ] run tests; they must pass before task 8

### Task 8: Options and prefs

**Files:**
- Create: `src/main/java/io/andreygrigoriev/uc/UndetectedChromeOptions.java`
- Create: `src/main/java/io/andreygrigoriev/uc/OptionsView.java`
- Create: `src/main/java/io/andreygrigoriev/uc/Prefs.java`
- Create: `src/test/java/io/andreygrigoriev/uc/OptionsViewTest.java`
- Create: `src/test/java/io/andreygrigoriev/uc/PrefsTest.java`

- [ ] implement `UndetectedChromeOptions`: `userDataDir` (absolute, normalized) and the reuse guard (`bind()` throws `IllegalStateException` the second time)
- [ ] implement `OptionsView.of(ChromeOptions)`: `args()`, `binary()`, `prefs()`, `debuggerAddress()` and `topLevelCapabilities()` (everything except `goog:chromeOptions`), all read from `asMap()`
- [ ] implement `Prefs.undot`, `mergeNested` (leaf values from b override a, maps merge recursively) and `writeTo(profileDir)`, which merges into the existing `Default/Preferences` (latin1)
- [ ] write tests: dotted keys become nested, merge overrides leaves while keeping siblings, the existing Preferences file is merged, and `OptionsView` reads args, prefs and binary from a plain `ChromeOptions`
- [ ] write tests: binding twice throws, missing `goog:chromeOptions` gives empty views
- [ ] run tests; they must pass before task 9

### Task 9: Profile handling

**Files:**
- Create: `src/main/java/io/andreygrigoriev/uc/Profile.java`
- Create: `src/test/java/io/andreygrigoriev/uc/ProfileTest.java`

- [ ] implement `Profile.temporary()` and `Profile.of(Path, keep)`
- [ ] implement `fixExitType()`, which nulls a non-null `profile.exit_type` and ignores a missing or invalid file with a debug log
- [ ] implement `delete()`, which retries 5× and is a no-op when `keep` is set
- [ ] write tests: exit_type is fixed and other keys kept, missing file is fine, kept profile survives `delete()`, temp profile is removed
- [ ] write tests for a corrupt Preferences JSON (no exception)
- [ ] run tests; they must pass before task 10

### Task 10: Assemble launch arguments

**Files:**
- Create: `src/main/java/io/andreygrigoriev/uc/LaunchArguments.java`
- Create: `src/test/java/io/andreygrigoriev/uc/LaunchArgumentsTest.java`

- [ ] implement `assemble(userArgs, settings)` following the numbered `LaunchArguments` spec in Technical Details, without changing the input list. Python's loop removes items from the list it's iterating over and skips elements; this version doesn't
- [ ] implement language resolution (user value, bare `--lang`, system locale, `en-US`) and the four-level `userDataDir` precedence
- [ ] choose the headless flag by `versionMain` (<108 → `--headless=chrome`, ≥108 or unknown → `--headless=new`)
- [ ] write tests: defaults (the exact expected list), user `--headless` removed and replaced, each `userDataDir` precedence branch with the right keep flag, `suppressWelcome=false` omits its flags, `noSandbox=false` omits `--test-type` but `--no-sandbox` is still present
- [ ] write tests for `--lang`: user value, bare `--lang`, injected default locale. Edge cases: two consecutive headless args, empty user args
- [ ] run tests; they must pass before task 11

### Task 11: Browser process launch

**Files:**
- Create: `src/main/java/io/andreygrigoriev/uc/BrowserProcess.java`
- Create: `src/test/java/io/andreygrigoriev/uc/BrowserProcessTest.java`
- Create: `src/test/java/io/andreygrigoriev/uc/support/Sleeper.java`

- [ ] implement `launch(binary, args, Mode.CHILD|DETACHED)` with output redirected to `DISCARD`; `DETACHED` prefixes `setsid` only on Linux when it's on PATH
- [ ] implement a static PID registry, one lazily registered shutdown hook, `pid()`, `isAlive()`, and `terminate(Duration grace)` (destroy, then `destroyForcibly`, then wait on `onExit()`)
- [ ] write tests that launch `Sleeper` (`java -cp target/test-classes …Sleeper`) in both modes: PID is registered, `terminate` kills it and waits, and it's removed from the registry
- [ ] write tests for errors: missing binary → `SessionNotCreatedException`, and terminate twice is a no-op
- [ ] run tests; they must pass before task 12

### Task 12: `Cdp` HTTP/WebSocket client

**Files:**
- Create: `src/main/java/io/andreygrigoriev/uc/Cdp.java`
- Create: `src/test/java/io/andreygrigoriev/uc/CdpTest.java`

- [ ] implement the constructor (`GET /json` → first target's `id` and `webSocketDebuggerUrl`), `tabList`, `tabNew` (**PUT** `/json/new?<url>`), `tabActivate`, `tabClose`, `tabCloseLastOpened` (last `type == page`) and `lastJson`
- [ ] implement `send(method, params)`: open a JDK WebSocket, send `{id, method, params}`, complete on the matching `id` (ignoring events), close, and time out after 10 s
- [ ] write unit tests against `StubHttpServer` for the HTTP endpoints, including checking the PUT verb and unparseable JSON → `null`
- [ ] write unit tests for `send` against a minimal stub WebSocket endpoint: events before the reply are skipped, and the timeout completes exceptionally (if a JDK-only WS server is too heavy, cover `send` in `CdpIT`, Task 15)
- [ ] run tests; they must pass before task 13

### Task 13: `UndetectedChromeDriver` plus builder (core attach flow)

**Files:**
- Create: `src/main/java/io/andreygrigoriev/uc/UndetectedChromeDriver.java`
- Create: `src/main/java/io/andreygrigoriev/uc/LaunchContext.java`
- Create: `src/main/java/io/andreygrigoriev/uc/ServiceHolder.java`
- Create: `src/test/java/io/andreygrigoriev/uc/UndetectedChromeDriverBuilderTest.java`
- Create: `src/test/java/io/andreygrigoriev/uc/UndetectedChromeDriverIT.java`
- Create: `src/test/resources/pages/detect.html`

- [ ] implement `newBuilder()` with all options from Technical Details, and `prepare()` → `LaunchContext` (steps 1–10 of the `build()` flow, testable without starting a driver), including rollback if a step fails
- [ ] implement the private constructor: `super(service, attachOptions)`, store the context, register the `Cleaner` (its state is `ServiceHolder` and `BrowserProcess`, never `this`), and keep every override null-safe while `super` is running
- [ ] implement the `quit()` sequence (idempotent), `browserPid()` and `userDataDir()`
- [ ] implement debug mode by overriding `execute(CommandPayload)` to log the command name and params at DEBUG
- [ ] write unit tests for `prepare()` with a stub patcher and a `Sleeper` browser: debugger address, `goog:loggingPrefs` only when CDP events are on, top-level user capabilities kept, `goog:chromeOptions` reduced to `debuggerAddress`, a missing browser raises the error with the hint text, and a failure after launch terminates the browser
- [ ] write `UndetectedChromeDriverIT`: launch (once with `debug=true`, to prove construction is null-safe), load `detect.html`, assert `navigator.webdriver === false` and no `cdc_` keys, then `quit()` and assert the browser PID is dead and the temp profile is gone
- [ ] run unit tests and ITs; they must pass before task 14

### Task 14: Headless evasions

**Files:**
- Create: `src/main/java/io/andreygrigoriev/uc/HeadlessEvasions.java`
- Create: `src/main/resources/uc/headless-navigator.js`
- Create: `src/main/resources/uc/headless-chrome-runtime.js`
- Modify: `src/main/java/io/andreygrigoriev/uc/UndetectedChromeDriver.java`
- Create: `src/test/java/io/andreygrigoriev/uc/HeadlessEvasionsTest.java`
- Create: `src/test/java/io/andreygrigoriev/uc/HeadlessIT.java`

- [ ] copy both JS payloads from upstream into resources. ⚠️ Deliberate deviation: upstream's first script has a duplicated `Object.defineProperty(window, "navigator", {` line, which makes it a syntax error and a no-op. The fixed version really installs a `navigator` Proxy, which itself might be detectable, so verify it against live detectors (Post-Completion) and drop the script if it makes things worse
- [ ] implement `applyIfNeeded(driver)` with the CDP calls, and override `get(url)` to call it when headless (null-safe during construction)
- [ ] write unit tests: resources load and are non-empty, and a mocked `HasCdp`/`JavascriptExecutor` gets the right `executeCdpCommand` calls only when `navigator.webdriver` is true
- [ ] write `HeadlessIT`: UA has no `Headless`, `navigator.webdriver === false`, `window.chrome.runtime` is defined
- [ ] run tests; they must pass before task 15

### Task 15: Reconnect, windows/tabs, recursive search across frames

**Files:**
- Modify: `src/main/java/io/andreygrigoriev/uc/UndetectedChromeDriver.java`
- Create: `src/test/java/io/andreygrigoriev/uc/FrameSearchIT.java`
- Create: `src/test/java/io/andreygrigoriev/uc/ReconnectIT.java`
- Create: `src/test/java/io/andreygrigoriev/uc/CdpIT.java`
- Create: `src/test/resources/pages/frames.html`

- [ ] implement `reconnect(Duration)` as described in Solution Overview, logging and swallowing each step's failure as upstream does
- [ ] override `startSession(Capabilities)` so that null falls back to the stored attach capabilities, but only once `context` is set
- [ ] implement `windowNew()` (`DriverCommand.NEW_WINDOW` type=window) and `tabNew(url)` (delegates to `Cdp.tabNew`)
- [ ] implement `findElementsRecursive(By)` as a lazy `Iterable<WebElement>` (custom `Iterator`) that switches frame before each element is yielded, matching the generator semantics
- [ ] write `FrameSearchIT` (local page with 2 iframes: elements found in the top level and in each frame, each element usable when yielded), `ReconnectIT` (session works after two reconnects in a row, the old service is stopped, browser PID unchanged) and `CdpIT` (`tabNew` opens a page that `tabList` shows, `send("Runtime.evaluate", …)` returns a result)
- [ ] run tests; they must pass before task 16

### Task 16: Reactor (CDP event listeners)

**Files:**
- Create: `src/main/java/io/andreygrigoriev/uc/Reactor.java`
- Modify: `src/main/java/io/andreygrigoriev/uc/UndetectedChromeDriver.java`
- Create: `src/test/java/io/andreygrigoriev/uc/ReactorTest.java`
- Create: `src/test/java/io/andreygrigoriev/uc/CdpEventsIT.java`

- [ ] implement `Reactor` with an injectable log source (`Supplier<List<LogEntry>>`): 1 s poll, parse JSON, dispatch sequentially on the poll thread to `*` or to the lowercased method, ignore `invalid session id`, `stop()`
- [ ] add `addCdpListener(String, Consumer<Map<String,Object>>)` (returns false when CDP events are off) and `clearCdpListeners()`, and stop the reactor in `quit()`
- [ ] write unit tests with a fake log source: wildcard wins, matching is case-insensitive, events arrive in log order, malformed entries are skipped without killing the loop, a throwing handler doesn't stop dispatch, and `stop()` ends the thread
- [ ] write `CdpEventsIT`: a `Network.responseReceived` listener fires on `get()`
- [ ] run tests; they must pass before task 17

### Task 17: `UcWebElement` and advanced elements

**Files:**
- Create: `src/main/java/io/andreygrigoriev/uc/UcWebElement.java`
- Create: `src/main/java/io/andreygrigoriev/uc/UcElementConverter.java`
- Modify: `src/main/java/io/andreygrigoriev/uc/UndetectedChromeDriver.java`
- Create: `src/test/java/io/andreygrigoriev/uc/UcWebElementTest.java`
- Create: `src/test/java/io/andreygrigoriev/uc/UcWebElementIT.java`

- [ ] implement `UcWebElement`: `clickSafe()`, `children(tag, recursive)` (recursive collects into a `LinkedHashSet`, so order is kept), cached `attrs()`, and `toString()` that depends on `advancedElements`
- [ ] implement `UcElementConverter.newRemoteWebElement()` and install it in the constructor with `setElementConverter`
- [ ] write unit tests with a mocked parent driver: the JS sent for `children` with and without a tag, `toString` formatting with and without attrs, `attrs` cached after the first call
- [ ] write `UcWebElementIT`: `findElement` returns `UcWebElement`, `children` and recursive children counts match the DOM, and `clickSafe` keeps the session usable
- [ ] run tests; they must pass before task 18

### Task 18: Docker image

**Files:**
- Create: `Dockerfile`
- Create: `.dockerignore`
- Create: `docker/entrypoint.sh`

- [ ] write the `Dockerfile` (`FROM --platform=linux/amd64 eclipse-temurin:25-jdk`). Install `google-chrome-stable` from Google's signed apt repo plus `xvfb`, `fonts-liberation` and `fonts-noto-color-emoji`, with no apt cache left in the layer. Create a non-root `uc` user
- [ ] cache dependencies in their own layer: copy `mvnw`, `.mvn/` and `pom.xml`, run `./mvnw -B dependency:go-offline`, then copy `src/`
- [ ] add `docker/entrypoint.sh` (`exec xvfb-run -a --server-args="-screen 0 1920x1080x24" "$@"`), and set the default `CMD ["./mvnw", "-B", "verify"]`
- [ ] add `.dockerignore` (`target/`, `.idea/`, `.git/`, `docs/`)
- [ ] verify: `docker build --platform linux/amd64 -t uc-java .` succeeds, and `docker run --rm --shm-size=2g uc-java` passes the full `verify`, including the browser ITs in headful mode under Xvfb. Also verify that `docker run … uc-java ./mvnw -B verify -Dheadless=true` passes, making `UndetectedChromeDriverIT` read a `headless` system property so it runs both modes
- [ ] run tests; they must pass before task 19

### Task 19: Verify acceptance criteria
- [ ] verify every upstream public API is mapped or explicitly excluded (`devtool.py`, `desired_capabilities`, `keep_alive`, `__hash__`, `__dir__`, `__exit__`)
- [ ] verify the upstream defects listed in Context are fixed and not copied
- [ ] port the flow of `example/example.py` against local pages as `ExampleIT` (the live-site version goes in Post-Completion)
- [ ] run the full test suite: `rtk ./mvnw verify`
- [ ] confirm unit coverage of the non-browser classes (`Platform`, `DriverReleases`, `BinaryPatcher`, `Patcher`, `Prefs`, `Profile`, `LaunchArguments`, `Reactor`, `Cdp`) is ≥ 80% (add `jacoco-maven-plugin` if needed)

### Task 20: [Final] Update documentation
- [ ] write `README.md`: install, quick start (`newBuilder()`), table mapping each Python argument to its builder method, CDP listeners, headless caveats, known differences from upstream (cache, narrowed headless match, fixed JS, dropped args), the `CdpVersionFinder` warning with how to silence it, and a Docker section (`docker build`/`docker run --shm-size=2g`, amd64-only, mounting your own project)
- [ ] add the upstream GPL-3.0 `LICENSE`
- [ ] create `CLAUDE.md` with the build/test commands and the package layout
- [ ] move this plan to `docs/plans/completed/`

## Post-Completion
*Items requiring manual intervention or external systems - no checkboxes, informational only*

**Manual verification:**
- run against live detectors (`https://nowsecure.nl`, `https://bot.sannysoft.com`, `https://abrahamjuliot.github.io/creepjs/`) in headful and headless modes, and compare with Python uc on the same machine. Include an A/B with and without the fixed `headless-navigator.js`
- run the original `example/example.py` site list by hand
- test on Windows (`reg query` version detection, `.exe` handling, profile deletion after terminate) and on Linux x64 outside Docker, including `setsid` detached mode. Also run the Docker image as root (`--user root`) to confirm `--no-sandbox` lets Chrome start
- check the behavior on linux-arm64, where Chrome-for-Testing publishes no chromedriver; expect users to pass `driverExecutablePath`
- run several JVMs with `userMultiProcs` at once to check the lock file

**External decisions:**
- license: this port must be GPL-3.0 because upstream is. Confirm this before any public release
- publishing to Maven Central was left out of scope for now
