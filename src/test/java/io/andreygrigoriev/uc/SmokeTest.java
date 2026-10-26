package io.andreygrigoriev.uc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

class SmokeTest {

    @Test
    void junitAssertjAndMockitoRun() {
        List<String> list = mock(List.class);
        when(list.size()).thenReturn(3);
        assertThat(list.size()).isEqualTo(3);
    }
}
