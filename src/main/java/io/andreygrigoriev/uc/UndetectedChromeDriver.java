package io.andreygrigoriev.uc;

import org.openqa.selenium.SessionNotCreatedException;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.logging.LogType;
import org.openqa.selenium.logging.LoggingPreferences;
import org.openqa.selenium.net.PortProber;
import org.openqa.selenium.remote.CommandPayload;
import org.openqa.selenium.remote.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.lang.ref.Cleaner;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * A ChromeDriver that starts the browser itself, with a patched chromedriver that only attaches to it.
 * Create one with {@link #newBuilder()}.
 *
 * <p>Overrides run inside {@code super(...)} before any field here is assigned, so they must tolerate
 * {@code context == null}.
 */
public class UndetectedChromeDriver extends ChromeDriver {

    private static final Logger LOG = LoggerFactory.getLogger(UndetectedChromeDriver.class);
    private static final Cleaner CLEANER = Cleaner.create();
    private static final Duration TERMINATE_GRACE = Duration.ofSeconds(5);

    private final LaunchContext context;
    private final AtomicBoolean quitCalled = new AtomicBoolean();
    private final Cleaner.Cleanable cleanable;

    private UndetectedChromeDriver(LaunchContext context) {
        super(context.service().get(), context.attachOptions());
        this.context = context;
        this.cleanable = CLEANER.register(this,
                new CleanupAction(context.service(), context.browser(), context.profile()));
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public long browserPid() {
        return context.browser().pid();
    }

    public Path userDataDir() {
        return context.profile().dir();
    }

    @Override
    protected Response execute(CommandPayload payload) {
        if (context != null && context.debug()) {
            LOG.debug("execute {} {}", payload.getName(), payload.getParameters());
        }
        return super.execute(payload);
    }

    @Override
    public void quit() {
        if (context == null) {
            // the session failed to start inside super(...); the builder cleans up
            super.quit();
            return;
        }
        if (!quitCalled.compareAndSet(false, true)) {
            return;
        }
        try {
            super.quit();
        } catch (RuntimeException e) {
            LOG.debug("Session was already gone on quit", e);
        }
        context.release();
        cleanable.clean();
    }

    /** Stops what a driver owns when it is garbage collected without {@link #quit()}. Must not reference the driver. */
    private record CleanupAction(ServiceHolder service, BrowserProcess browser, Profile profile) implements Runnable {
        @Override
        public void run() {
            service.stop();
            browser.terminate(TERMINATE_GRACE);
            profile.delete();
        }
    }

    /** Collects the options, then launches the browser and attaches to it. */
    public static final class Builder {

        private ChromeOptions options;
        private Path userDataDir;
        private Path driverExecutablePath;
        private Path browserExecutablePath;
        private int port;
        private boolean enableCdpEvents;
        private boolean advancedElements;
        private int logLevel;
        private boolean headless;
        private Integer versionMain;
        private boolean patcherForceClose;
        private boolean suppressWelcome = true;
        private boolean useSubprocess = true;
        private boolean debug;
        private boolean noSandbox = true;
        private boolean userMultiProcs;
        private File serviceLogFile;

        private Function<Patcher.Settings, Path> patcher = settings -> new Patcher(settings).auto();
        private ChromeLocator locator = new ChromeLocator();

        private Builder() {
        }

        public Builder options(ChromeOptions options) {
            this.options = options;
            return this;
        }

        public Builder userDataDir(Path userDataDir) {
            this.userDataDir = userDataDir;
            return this;
        }

        public Builder driverExecutablePath(Path path) {
            this.driverExecutablePath = path;
            return this;
        }

        public Builder browserExecutablePath(Path path) {
            this.browserExecutablePath = path;
            return this;
        }

        /** Remote debugging port; 0 picks a free one. */
        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder enableCdpEvents(boolean enable) {
            this.enableCdpEvents = enable;
            return this;
        }

        public Builder advancedElements(boolean enable) {
            this.advancedElements = enable;
            return this;
        }

        public Builder logLevel(int logLevel) {
            this.logLevel = logLevel;
            return this;
        }

        public Builder headless(boolean headless) {
            this.headless = headless;
            return this;
        }

        public Builder versionMain(int versionMain) {
            this.versionMain = versionMain;
            return this;
        }

        public Builder patcherForceClose(boolean force) {
            this.patcherForceClose = force;
            return this;
        }

        public Builder suppressWelcome(boolean suppress) {
            this.suppressWelcome = suppress;
            return this;
        }

        /** True keeps the browser a plain child process, false detaches it where the OS allows. */
        public Builder useSubprocess(boolean useSubprocess) {
            this.useSubprocess = useSubprocess;
            return this;
        }

        public Builder debug(boolean debug) {
            this.debug = debug;
            return this;
        }

        public Builder noSandbox(boolean noSandbox) {
            this.noSandbox = noSandbox;
            return this;
        }

        public Builder userMultiProcs(boolean userMultiProcs) {
            this.userMultiProcs = userMultiProcs;
            return this;
        }

        public Builder serviceLogFile(File file) {
            this.serviceLogFile = file;
            return this;
        }

        Builder patcher(Function<Patcher.Settings, Path> patcher) {
            this.patcher = patcher;
            return this;
        }

        Builder chromeLocator(ChromeLocator locator) {
            this.locator = locator;
            return this;
        }

        public UndetectedChromeDriver build() {
            LaunchContext context = prepare();
            try {
                return new UndetectedChromeDriver(context);
            } catch (RuntimeException | Error e) {
                context.release();
                throw e;
            }
        }

        /** Patches the driver, launches the browser and builds everything needed to attach to it. */
        LaunchContext prepare() {
            ChromeOptions opts = options != null ? options : new UndetectedChromeOptions();
            if (opts instanceof UndetectedChromeOptions undetected) {
                undetected.bind();
            }
            OptionsView view = OptionsView.of(opts);

            Path browser = Optional.ofNullable(view.binary().map(Path::of).orElse(browserExecutablePath))
                    .or(locator::findExecutable)
                    .orElseThrow(() -> new SessionNotCreatedException(
                            "Could not find a Chrome binary. Install Google Chrome, or pass the path with "
                                    + "browserExecutablePath(...) or ChromeOptions#setBinary."));

            Integer major = versionMain != null ? versionMain
                    : locator.detectVersion(browser).map(ChromeVersion::major).orElse(null);
            Path driver = patcher.apply(new Patcher.Settings(driverExecutablePath, browser, major,
                    userMultiProcs, patcherForceClose));

            String[] address = debuggerAddress(view);
            String host = address[0];
            int debugPort = Integer.parseInt(address[1]);

            LaunchArguments.Result launch = LaunchArguments.assemble(view.args(), new LaunchArguments.Settings(
                    headless, major == null ? 0 : major, logLevel, suppressWelcome, noSandbox, host, debugPort,
                    userDataDir,
                    opts instanceof UndetectedChromeOptions u ? u.userDataDir().orElse(null) : null));
            Profile profile = Profile.of(launch.userDataDir(), launch.keepUserDataDir());

            BrowserProcess process = null;
            try {
                Prefs.writeTo(profile.dir(), view.prefs());
                profile.fixExitType();
                process = BrowserProcess.launch(browser, launch.args(),
                        useSubprocess ? BrowserProcess.Mode.CHILD : BrowserProcess.Mode.DETACHED);

                Supplier<ChromeDriverService> factory = () -> newService(driver);
                ServiceHolder holder = new ServiceHolder(factory.get());
                return new LaunchContext(holder, factory, process, profile,
                        attachOptions(view, host + ":" + debugPort), host + ":" + debugPort, driver,
                        launch.headless(), advancedElements, enableCdpEvents, debug);
            } catch (RuntimeException | Error e) {
                if (process != null) {
                    process.terminate(TERMINATE_GRACE);
                }
                profile.delete();
                throw e;
            }
        }

        private String[] debuggerAddress(OptionsView view) {
            Optional<String> given = view.debuggerAddress();
            if (given.isPresent() && given.get().contains(":")) {
                String value = given.get();
                int colon = value.lastIndexOf(':');
                return new String[]{value.substring(0, colon), value.substring(colon + 1)};
            }
            return new String[]{"127.0.0.1", String.valueOf(port != 0 ? port : PortProber.findFreePort())};
        }

        /** User capabilities, but {@code goog:chromeOptions} reduced to the debugger address. */
        private ChromeOptions attachOptions(OptionsView view, String address) {
            ChromeOptions attach = new ChromeOptions();
            Map<String, Object> caps = new LinkedHashMap<>(view.topLevelCapabilities());
            caps.forEach(attach::setCapability);
            attach.setExperimentalOption("debuggerAddress", address);
            if (enableCdpEvents) {
                LoggingPreferences logs = new LoggingPreferences();
                logs.enable(LogType.PERFORMANCE, Level.ALL);
                attach.setCapability("goog:loggingPrefs", logs);
            }
            return attach;
        }

        private ChromeDriverService newService(Path driver) {
            ChromeDriverService.Builder service = new ChromeDriverService.Builder()
                    .usingDriverExecutable(driver.toFile());
            if (serviceLogFile != null) {
                service.withLogFile(serviceLogFile);
            }
            return service.build();
        }
    }
}
