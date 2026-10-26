package io.github.andreygrigoriev.uc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.andreygrigoriev.uc.support.StubHttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DriverDownloaderTest {

    private static final byte[] DRIVER = "fake driver binary".getBytes(StandardCharsets.UTF_8);

    private final DriverDownloader downloader = new DriverDownloader();
    private final Platform linux = Platform.of("Linux", "amd64", Map.of(), Path.of("/home/u"));
    private final Platform windows = Platform.of("Windows 11", "amd64", Map.of(), Path.of("C:/Users/u"));
    private StubHttpServer server;

    @TempDir
    Path tmp;

    @BeforeEach
    void setUp() {
        server = new StubHttpServer();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bytes)) {
            for (var e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private Path target() {
        return tmp.resolve("out").resolve("chromedriver");
    }

    private void assertNoLeftovers() throws IOException {
        Path dir = tmp.resolve("out");
        if (Files.exists(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                assertThat(files).isEmpty();
            }
        }
    }

    @Test
    void extractsNestedChromeForTestingLayout() throws IOException {
        server.respond("/d.zip", 200, zip(Map.of(
                "chromedriver-linux64/LICENSE.chromedriver", "license".getBytes(StandardCharsets.UTF_8),
                "chromedriver-linux64/chromedriver", DRIVER)));

        downloader.download(server.baseUrl() + "/d.zip", linux, target());

        assertThat(target()).hasBinaryContent(DRIVER);
    }

    @Test
    void extractsLegacyFlatLayout() throws IOException {
        server.respond("/d.zip", 200, zip(Map.of("chromedriver", DRIVER)));

        downloader.download(server.baseUrl() + "/d.zip", linux, target());

        assertThat(target()).hasBinaryContent(DRIVER);
    }

    @Test
    void usesExeNameOnWindows() throws IOException {
        server.respond("/d.zip", 200, zip(Map.of("chromedriver-win64/chromedriver.exe", DRIVER)));
        Path exe = tmp.resolve("chromedriver.exe");

        downloader.download(server.baseUrl() + "/d.zip", windows, exe);

        assertThat(exe).hasBinaryContent(DRIVER);
    }

    @Test
    void setsExecutablePermissions() throws IOException {
        server.respond("/d.zip", 200, zip(Map.of("chromedriver", DRIVER)));

        downloader.download(server.baseUrl() + "/d.zip", linux, target());

        if (target().getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertThat(Files.getPosixFilePermissions(target())).isEqualTo(Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE));
        }
    }

    @Test
    void replacesExistingTarget() throws IOException {
        Files.createDirectories(target().getParent());
        Files.writeString(target(), "old");
        server.respond("/d.zip", 200, zip(Map.of("chromedriver", DRIVER)));

        downloader.download(server.baseUrl() + "/d.zip", linux, target());

        assertThat(target()).hasBinaryContent(DRIVER);
    }

    @Test
    void ignoresZipSlipAndDeepEntries() throws IOException {
        server.respond("/d.zip", 200, zip(Map.of(
                "../chromedriver", "evil".getBytes(StandardCharsets.UTF_8),
                "a/b/chromedriver", "deep".getBytes(StandardCharsets.UTF_8))));

        assertThatThrownBy(() -> downloader.download(server.baseUrl() + "/d.zip", linux, target()))
                .isInstanceOf(DriverDownloadException.class)
                .hasMessageContaining("does not contain");
        assertThat(tmp.resolve("chromedriver")).doesNotExist();
        assertThat(target()).doesNotExist();
        assertNoLeftovers();
    }

    @Test
    void failsWhenEntryIsMissing() throws IOException {
        server.respond("/d.zip", 200, zip(Map.of("chromedriver-linux64/README", DRIVER)));

        assertThatThrownBy(() -> downloader.download(server.baseUrl() + "/d.zip", linux, target()))
                .isInstanceOf(DriverDownloadException.class)
                .hasMessageContaining("does not contain");
        assertThat(target()).doesNotExist();
        assertNoLeftovers();
    }

    @Test
    void failsOnCorruptedZip() throws IOException {
        byte[] good = zip(Map.of("chromedriver", DRIVER));
        byte[] corrupted = good.clone();
        corrupted[0] = 'X';
        corrupted[1] = 'X';
        server.respond("/d.zip", 200, corrupted);

        assertThatThrownBy(() -> downloader.download(server.baseUrl() + "/d.zip", linux, target()))
                .isInstanceOf(DriverDownloadException.class);
        assertThat(target()).doesNotExist();
        assertNoLeftovers();
    }

    @Test
    void failsOn404() throws IOException {
        assertThatThrownBy(() -> downloader.download(server.baseUrl() + "/missing.zip", linux, target()))
                .isInstanceOf(DriverDownloadException.class)
                .hasMessageContaining("HTTP 404");
        assertThat(target()).doesNotExist();
        assertNoLeftovers();
    }

    @Test
    void keepsExistingTargetOnFailure() throws IOException {
        Files.createDirectories(target().getParent());
        Files.writeString(target(), "old");

        assertThatThrownBy(() -> downloader.download(server.baseUrl() + "/missing.zip", linux, target()))
                .isInstanceOf(DriverDownloadException.class);

        assertThat(target()).hasContent("old");
    }
}
