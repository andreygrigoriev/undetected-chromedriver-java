package io.andreygrigoriev.uc;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/** Downloads a chromedriver zip and unpacks the driver executable to its target path. */
public class DriverDownloader {

    private final HttpClient http;

    public DriverDownloader() {
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Downloads {@code url} and puts the driver executable at {@code target}. Handles the Chrome-for-Testing layout
     * ({@code chromedriver-<platform>/chromedriver}) and the legacy flat layout. On failure nothing is left at
     * {@code target}.
     */
    public void download(String url, Platform platform, Path target) {
        Path dir = target.toAbsolutePath().getParent();
        Path zip = null;
        Path extracted = null;
        try {
            Files.createDirectories(dir);
            zip = Files.createTempFile(dir, "chromedriver-download", ".zip");
            fetch(url, zip);
            extracted = Files.createTempFile(dir, "chromedriver-extract", ".tmp");
            extract(zip, platform.exeName(), extracted);
            makeExecutable(extracted);
            move(extracted, target);
        } catch (IOException e) {
            throw new DriverDownloadException("Failed to download chromedriver from " + url, e);
        } finally {
            deleteQuietly(zip);
            deleteQuietly(extracted);
        }
    }

    private void fetch(String url, Path file) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).GET().build();
        try {
            HttpResponse<Path> response = http.send(request, HttpResponse.BodyHandlers.ofFile(file));
            if (response.statusCode() != 200) {
                throw new DriverDownloadException("HTTP " + response.statusCode() + " from " + url);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DriverDownloadException("Interrupted while downloading " + url, e);
        }
    }

    private static void extract(Path zip, String exeName, Path out) throws IOException {
        try (InputStream in = Files.newInputStream(zip); ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory() && isDriverEntry(entry.getName(), exeName)) {
                    Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
                    return;
                }
            }
        } catch (ZipException e) {
            throw new DriverDownloadException("Corrupted chromedriver archive", e);
        }
        throw new DriverDownloadException("Archive does not contain " + exeName);
    }

    /** Only the bare executable, or the executable directly inside one top-level directory. */
    private static boolean isDriverEntry(String name, String exeName) {
        if (name.equals(exeName)) {
            return true;
        }
        int slash = name.indexOf('/');
        return slash > 0 && !name.startsWith("..") && name.substring(slash + 1).equals(exeName);
    }

    private static void makeExecutable(Path file) throws IOException {
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // best effort cleanup
        }
    }
}
