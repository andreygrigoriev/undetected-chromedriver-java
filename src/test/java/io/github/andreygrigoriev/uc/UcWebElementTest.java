package io.github.andreygrigoriev.uc;

import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.remote.RemoteWebDriver;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UcWebElementTest {

    private final RemoteWebDriver parent = mock(RemoteWebDriver.class);

    private UcWebElement element(boolean advanced) {
        UcWebElement element = spy(new UcWebElement(advanced));
        element.setParent(parent);
        element.setId("e1");
        return element;
    }

    @Test
    void childrenWithoutTagSendsOnlyTheElement() {
        UcWebElement element = element(false);
        WebElement child = mock(WebElement.class);
        when(parent.executeScript(eq(UcWebElement.CHILDREN_SCRIPT), any(Object[].class))).thenReturn(java.util.List.of(child));

        assertThat(element.children(null, false)).containsExactly(child);
        verify(parent).executeScript(UcWebElement.CHILDREN_SCRIPT, element);
    }

    @Test
    void childrenWithTagSendsTheTag() {
        UcWebElement element = element(false);
        when(parent.executeScript(anyString(), any(Object[].class))).thenReturn(java.util.List.of());

        assertThat(element.children("a", false)).isEmpty();
        verify(parent).executeScript(UcWebElement.CHILDREN_BY_TAG_SCRIPT, element, "a");
    }

    @Test
    void recursiveChildrenUseDescendantScriptsAndKeepOrderWithoutDuplicates() {
        UcWebElement element = element(false);
        WebElement first = mock(WebElement.class);
        WebElement second = mock(WebElement.class);
        when(parent.executeScript(anyString(), any(Object[].class)))
                .thenReturn(java.util.List.of(first, second, first));

        assertThat(element.children("", true)).containsExactly(first, second);
        verify(parent).executeScript(UcWebElement.DESCENDANTS_SCRIPT, element);

        element.children("li", true);
        verify(parent).executeScript(UcWebElement.DESCENDANTS_BY_TAG_SCRIPT, element, "li");
    }

    @Test
    void attrsAreFetchedOnce() {
        UcWebElement element = element(false);
        when(parent.executeScript(eq(UcWebElement.ATTRS_SCRIPT), any(Object[].class)))
                .thenReturn(Map.of("href", "#"));

        assertThat(element.attrs()).containsEntry("href", "#");
        assertThat(element.attrs()).containsEntry("href", "#");
        verify(parent, times(1)).executeScript(eq(UcWebElement.ATTRS_SCRIPT), any(Object[].class));
    }

    @Test
    void toStringShowsTagAndAttrsWhenAdvanced() {
        UcWebElement element = element(true);
        org.mockito.Mockito.doReturn("a").when(element).getTagName();
        when(parent.executeScript(eq(UcWebElement.ATTRS_SCRIPT), any(Object[].class)))
                .thenReturn(new java.util.LinkedHashMap<>(Map.of("class", "x")));

        assertThat(element.toString()).isEqualTo("UcWebElement <a class=\"x\">");
    }

    @Test
    void toStringWithoutAttrsShowsOnlyTag() {
        UcWebElement element = element(true);
        org.mockito.Mockito.doReturn("p").when(element).getTagName();
        when(parent.executeScript(eq(UcWebElement.ATTRS_SCRIPT), any(Object[].class))).thenReturn(Map.of());

        assertThat(element.toString()).isEqualTo("UcWebElement <p>");
    }

    @Test
    void toStringIsSeleniumDefaultWhenNotAdvanced() {
        UcWebElement element = element(false);

        assertThat(element.toString()).doesNotStartWith("UcWebElement <");
        verify(parent, times(0)).executeScript(anyString(), any(Object[].class));
    }
}
