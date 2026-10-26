package io.github.andreygrigoriev.uc;

/** Thrown when a chromedriver archive can't be downloaded or unpacked. */
public class DriverDownloadException extends RuntimeException {

    public DriverDownloadException(String message) {
        super(message);
    }

    public DriverDownloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
