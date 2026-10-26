package io.andreygrigoriev.uc;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Lazily finds elements in the top-level document and then in every (nested) frame. The driver is switched into
 * the element's frame right before the element is returned, so it is usable straight away.
 */
final class FrameSearch implements Iterable<WebElement> {

    private static final By FRAMES = By.cssSelector("iframe, frame");

    private final WebDriver driver;
    private final By by;

    FrameSearch(WebDriver driver, By by) {
        this.driver = driver;
        this.by = by;
    }

    @Override
    public Iterator<WebElement> iterator() {
        return new Cursor();
    }

    private record Found(WebElement element, List<Integer> path) {
    }

    private final class Cursor implements Iterator<WebElement> {

        private final Deque<List<Integer>> pending = new ArrayDeque<>(List.of(List.of()));
        private final Deque<Found> buffer = new ArrayDeque<>();

        @Override
        public boolean hasNext() {
            while (buffer.isEmpty() && !pending.isEmpty()) {
                scan(pending.pop());
            }
            return !buffer.isEmpty();
        }

        @Override
        public WebElement next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            Found found = buffer.pop();
            enter(found.path());
            return found.element();
        }

        private void scan(List<Integer> path) {
            enter(path);
            for (WebElement element : driver.findElements(by)) {
                buffer.add(new Found(element, path));
            }
            int frames = driver.findElements(FRAMES).size();
            for (int i = frames - 1; i >= 0; i--) {
                List<Integer> child = new ArrayList<>(path);
                child.add(i);
                pending.push(child);
            }
        }

        private void enter(List<Integer> path) {
            driver.switchTo().defaultContent();
            for (int index : path) {
                driver.switchTo().frame(index);
            }
        }
    }
}
