package io.andreygrigoriev.uc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.andreygrigoriev.uc.support.StubHttpServer;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DriverReleasesTest {

    private static final String STABLE = "/last-known-good-versions-with-downloads.json";
    private static final String MILESTONES = "/latest-versions-per-milestone-with-downloads.json";

    private StubHttpServer server;
    private DriverReleases releases;

    @BeforeEach
    void setUp() {
        server = new StubHttpServer();
        releases = new DriverReleases(server.baseUrl() + "/legacy", server.baseUrl(), server.baseUrl() + "/dl");
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private static Platform linux() {
        return Platform.of("Linux", "amd64", Map.of(), Path.of("/home/u"));
    }

    @Test
    void nullMilestoneResolvesStableChannel() {
        server.respond(STABLE, 200, "{\"channels\":{\"Stable\":{\"version\":\"131.0.6778.85\"},\"Beta\":{\"version\":\"132.0.1.1\"}}}");
        assertThat(releases.resolve(null)).isEqualTo(ChromeVersion.parse("131.0.6778.85"));
    }

    @Test
    void newMilestoneUsesPerMilestoneEndpoint() {
        server.respond(MILESTONES, 200, "{\"milestones\":{\"115\":{\"version\":\"115.0.5790.170\"},\"130\":{\"version\":\"130.0.6723.116\"}}}");
        assertThat(releases.resolve(130)).isEqualTo(ChromeVersion.parse("130.0.6723.116"));
        assertThat(releases.resolve(115)).isEqualTo(ChromeVersion.parse("115.0.5790.170"));
    }

    @Test
    void legacyMilestoneUsesLatestRelease() {
        server.respond("/legacy/LATEST_RELEASE_114", 200, "114.0.5735.90\n");
        assertThat(releases.resolve(114)).isEqualTo(ChromeVersion.parse("114.0.5735.90"));
        assertThat(server.requests()).extracting(StubHttpServer.Request::path).containsExactly("/legacy/LATEST_RELEASE_114");
    }

    @Test
    void boundaryBetweenLegacyAndNewIs114And115() {
        server.respond("/legacy/LATEST_RELEASE_114", 200, "114.0.5735.90");
        server.respond(MILESTONES, 200, "{\"milestones\":{\"115\":{\"version\":\"115.0.5790.170\"}}}");
        releases.resolve(114);
        releases.resolve(115);
        assertThat(server.requests()).extracting(StubHttpServer.Request::path)
                .containsExactly("/legacy/LATEST_RELEASE_114", MILESTONES);
    }

    @Test
    void downloadUrlForNewVersionUsesChromeForTesting() {
        assertThat(releases.downloadUrl(ChromeVersion.parse("131.0.6778.85"), linux()))
                .isEqualTo(server.baseUrl() + "/dl/131.0.6778.85/linux64/chromedriver-linux64.zip");
        Platform mac = Platform.of("Mac OS X", "aarch64", Map.of(), Path.of("/Users/u"));
        assertThat(releases.downloadUrl(ChromeVersion.parse("115.0.5790.170"), mac))
                .endsWith("/115.0.5790.170/mac-arm64/chromedriver-mac-arm64.zip");
    }

    @Test
    void downloadUrlForLegacyVersionUsesLegacyRepo() {
        assertThat(releases.downloadUrl(ChromeVersion.parse("114.0.5735.90"), linux()))
                .isEqualTo(server.baseUrl() + "/legacy/114.0.5735.90/chromedriver_linux64.zip");
        Platform mac = Platform.of("Mac OS X", "aarch64", Map.of(), Path.of("/Users/u"));
        assertThat(releases.downloadUrl(ChromeVersion.parse("114.0.5735.90"), mac))
                .endsWith("/114.0.5735.90/chromedriver_mac_arm64.zip");
    }

    @Test
    void trailingSlashInBaseUrlsIsIgnored() {
        DriverReleases slashed = new DriverReleases(server.baseUrl() + "/legacy/", server.baseUrl() + "/", server.baseUrl() + "/dl/");
        assertThat(slashed.downloadUrl(ChromeVersion.parse("131.0.6778.85"), linux()))
                .isEqualTo(server.baseUrl() + "/dl/131.0.6778.85/linux64/chromedriver-linux64.zip");
    }

    @Test
    void unknownMilestoneFails() {
        server.respond(MILESTONES, 200, "{\"milestones\":{\"130\":{\"version\":\"130.0.6723.116\"}}}");
        assertThatThrownBy(() -> releases.resolve(999))
                .isInstanceOf(DriverResolutionException.class).hasMessageContaining("999");
    }

    @Test
    void httpErrorsFail() {
        server.respond(STABLE, 500, "boom");
        assertThatThrownBy(() -> releases.resolve(null))
                .isInstanceOf(DriverResolutionException.class).hasMessageContaining("HTTP 500");
        assertThatThrownBy(() -> releases.resolve(120))
                .isInstanceOf(DriverResolutionException.class).hasMessageContaining("HTTP 404");
        assertThatThrownBy(() -> releases.resolve(100))
                .isInstanceOf(DriverResolutionException.class).hasMessageContaining("HTTP 404");
    }

    @Test
    void malformedJsonFails() {
        server.respond(STABLE, 200, "{not json");
        server.respond(MILESTONES, 200, "<html>");
        assertThatThrownBy(() -> releases.resolve(null)).isInstanceOf(DriverResolutionException.class);
        assertThatThrownBy(() -> releases.resolve(130)).isInstanceOf(DriverResolutionException.class);
    }

    @Test
    void missingOrInvalidVersionFieldFails() {
        server.respond(STABLE, 200, "{\"channels\":{}}");
        assertThatThrownBy(() -> releases.resolve(null)).isInstanceOf(DriverResolutionException.class);
        server.respond(STABLE, 200, "{\"channels\":{\"Stable\":{\"version\":\"abc\"}}}");
        assertThatThrownBy(() -> releases.resolve(null)).isInstanceOf(DriverResolutionException.class);
    }

    @Test
    void garbageLegacyReleaseFails() {
        server.respond("/legacy/LATEST_RELEASE_100", 200, "<Error>nope</Error>");
        assertThatThrownBy(() -> releases.resolve(100)).isInstanceOf(DriverResolutionException.class);
    }

    @Test
    void unreachableServerFails() {
        DriverReleases dead = new DriverReleases("http://127.0.0.1:1/legacy", "http://127.0.0.1:1", "http://127.0.0.1:1/dl");
        assertThatThrownBy(() -> dead.resolve(null)).isInstanceOf(DriverResolutionException.class);
    }
}
