package io.andreygrigoriev.uc;

/** Thrown when the chromedriver version to use can't be determined. */
public class DriverResolutionException extends RuntimeException {

    public DriverResolutionException(String message) {
        super(message);
    }

    public DriverResolutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
