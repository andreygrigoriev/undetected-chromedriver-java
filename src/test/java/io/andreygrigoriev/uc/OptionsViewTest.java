package io.andreygrigoriev.uc;

import org.junit.jupiter.api.Test;
import org.openqa.selenium.PageLoadStrategy;
import org.openqa.selenium.chrome.ChromeOptions;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OptionsViewTest {

    @Test
    void readsArgsPrefsBinaryAndDebuggerAddress() {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--foo", "--bar=1");
        options.setBinary("/opt/chrome");
        options.setExperimentalOption("prefs", Map.of("a.b", 1));
        options.setExperimentalOption("debuggerAddress", "127.0.0.1:9222");
        options.setPageLoadStrategy(PageLoadStrategy.EAGER);

        OptionsView view = OptionsView.of(options);

        assertThat(view.args()).containsExactly("--foo", "--bar=1");
        assertThat(view.binary()).contains("/opt/chrome");
        assertThat(view.prefs()).isEqualTo(Map.of("a.b", 1));
        assertThat(view.debuggerAddress()).contains("127.0.0.1:9222");
        assertThat(view.topLevelCapabilities())
                .containsEntry("pageLoadStrategy", PageLoadStrategy.EAGER)
                .doesNotContainKey(OptionsView.CHROME_OPTIONS);
    }

    @Test
    void emptyOptionsGiveEmptyViews() {
        OptionsView view = OptionsView.of(new ChromeOptions());

        assertThat(view.args()).isEmpty();
        assertThat(view.binary()).isEmpty();
        assertThat(view.prefs()).isEmpty();
        assertThat(view.debuggerAddress()).isEmpty();
    }

    @Test
    void bindingTwiceThrows() {
        UndetectedChromeOptions options = new UndetectedChromeOptions();
        options.bind();

        assertThatThrownBy(options::bind).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void userDataDirIsAbsoluteAndNormalized() {
        UndetectedChromeOptions options = new UndetectedChromeOptions();
        assertThat(options.userDataDir()).isEmpty();

        options.userDataDir(Path.of("a/../b"));

        assertThat(options.userDataDir()).contains(Path.of("b").toAbsolutePath().normalize());
    }
}
