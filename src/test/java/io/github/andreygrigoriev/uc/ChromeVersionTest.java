package io.github.andreygrigoriev.uc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ChromeVersionTest {

    @Test
    void parsesFullVersion() {
        ChromeVersion v = ChromeVersion.parse("131.0.6778.85");
        assertThat(v.major()).isEqualTo(131);
        assertThat(v.minor()).isZero();
        assertThat(v.build()).isEqualTo(6778);
        assertThat(v.patch()).isEqualTo(85);
        assertThat(v).hasToString("131.0.6778.85");
    }

    @Test
    void missingPartsAreZero() {
        assertThat(ChromeVersion.parse("114")).isEqualTo(new ChromeVersion(114, 0, 0, 0));
        assertThat(ChromeVersion.parse(" 114.0.5735 ")).isEqualTo(new ChromeVersion(114, 0, 5735, 0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "abc", "1.2.3.4.5", "1..2", "v131.0.0.0", "131.0.x.1", "-1.0.0.0", "1.2.3.4 beta"})
    void rejectsInvalid(String text) {
        assertThatThrownBy(() -> ChromeVersion.parse(text)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> ChromeVersion.parse(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ordersNumericallyNotLexically() {
        ChromeVersion a = ChromeVersion.parse("99.0.4844.51");
        ChromeVersion b = ChromeVersion.parse("100.0.4896.60");
        assertThat(a).isLessThan(b);
        assertThat(ChromeVersion.parse("131.0.6778.9")).isLessThan(ChromeVersion.parse("131.0.6778.85"));
        assertThat(ChromeVersion.parse("131.0.6778.85")).isLessThan(ChromeVersion.parse("131.1.0.0"));
        assertThat(ChromeVersion.parse("131.0.6778.85")).isEqualByComparingTo(ChromeVersion.parse("131.0.6778.85"));
    }
}
