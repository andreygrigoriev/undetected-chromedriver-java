package io.github.andreygrigoriev.uc;

import org.openqa.selenium.remote.JsonToWebElementConverter;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.remote.RemoteWebElement;

/** Makes the driver return {@link UcWebElement} instead of plain {@code RemoteWebElement}. */
final class UcElementConverter extends JsonToWebElementConverter {

    private final boolean advancedElements;

    UcElementConverter(RemoteWebDriver driver, boolean advancedElements) {
        super(driver);
        this.advancedElements = advancedElements;
    }

    @Override
    protected RemoteWebElement newRemoteWebElement() {
        return setOwner(new UcWebElement(advancedElements));
    }
}
