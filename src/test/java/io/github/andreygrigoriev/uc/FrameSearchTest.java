package io.github.andreygrigoriev.uc;

import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FrameSearchTest {

    private final By by = By.className("item");
    private final WebDriver driver = mock(WebDriver.class);
    private final WebDriver.TargetLocator locator = mock(WebDriver.TargetLocator.class);
    private final WebElement top = mock(WebElement.class);
    private final WebElement inFrame = mock(WebElement.class);
    private final WebElement nested = mock(WebElement.class);
    private final WebElement frameA = mock(WebElement.class);
    private final WebElement frameB = mock(WebElement.class);

    FrameSearchTest() {
        when(driver.switchTo()).thenReturn(locator);
        when(locator.defaultContent()).thenReturn(driver);
        when(locator.frame(any(Integer.class))).thenReturn(driver);
    }

    @Test
    void yieldsTopLevelThenFramesDepthFirstAndSwitchesBeforeEachElement() {
        // top has one frame; that frame has one element and one nested frame holding one element
        when(driver.findElements(by)).thenReturn(List.of(top), List.of(inFrame), List.of(nested));
        when(driver.findElements(By.cssSelector("iframe, frame")))
                .thenReturn(List.of(frameA), List.of(frameB), List.of());

        List<WebElement> found = new ArrayList<>();
        new FrameSearch(driver, by).forEach(found::add);

        assertThat(found).containsExactly(top, inFrame, nested);
        verify(locator, atLeast(2)).frame(0);
    }

    @Test
    void isLazy() {
        when(driver.findElements(by)).thenReturn(List.of(top), List.of(inFrame));
        when(driver.findElements(By.cssSelector("iframe, frame"))).thenReturn(List.of(frameA), List.of());

        var iterator = new FrameSearch(driver, by).iterator();
        assertThat(iterator.next()).isSameAs(top);

        verify(locator, never()).frame(any(Integer.class));
    }

    @Test
    void throwsWhenExhausted() {
        when(driver.findElements(by)).thenReturn(List.of());
        when(driver.findElements(By.cssSelector("iframe, frame"))).thenReturn(List.of());

        var iterator = new FrameSearch(driver, by).iterator();

        assertThat(iterator.hasNext()).isFalse();
        assertThatThrownBy(iterator::next).isInstanceOf(NoSuchElementException.class);
    }
}
