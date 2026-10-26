package io.github.andreygrigoriev.uc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Finds which chromedriver version to download and where to download it from. */
public class DriverReleases {

    public static final String DEFAULT_LEGACY_REPO = "https://chromedriver.storage.googleapis.com";
    public static final String DEFAULT_CFT_METADATA = "https://googlechromelabs.github.io/chrome-for-testing";
    public static final String DEFAULT_CFT_DOWNLOADS = "https://storage.googleapis.com/chrome-for-testing-public";

    /** Last milestone published in the legacy storage. */
    static final int LAST_LEGACY_MAJOR = 114;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String legacyRepo;
    private final String cftMetadata;
    private final String cftDownloads;
    private final HttpClient http;

    public DriverReleases() {
        this(DEFAULT_LEGACY_REPO, DEFAULT_CFT_METADATA, DEFAULT_CFT_DOWNLOADS);
    }

    public DriverReleases(String legacyRepo, String cftMetadata, String cftDownloads) {
        this.legacyRepo = stripSlash(legacyRepo);
        this.cftMetadata = stripSlash(cftMetadata);
        this.cftDownloads = stripSlash(cftDownloads);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** Latest Stable driver for {@code null}, otherwise the latest driver of that Chrome milestone. */
    public ChromeVersion resolve(Integer versionMain) {
        if (versionMain == null) {
            JsonNode node = fetchJson(cftMetadata + "/last-known-good-versions-with-downloads.json");
            return parseVersion(node.path("channels").path("Stable").path("version"), "Stable channel");
        }
        if (versionMain <= LAST_LEGACY_MAJOR) {
            String body = fetch(legacyRepo + "/LATEST_RELEASE_" + versionMain).strip();
            try {
                return ChromeVersion.parse(body);
            } catch (IllegalArgumentException e) {
                throw new DriverResolutionException("Unexpected legacy release '" + body + "' for milestone "
                        + versionMain, e);
            }
        }
        JsonNode node = fetchJson(cftMetadata + "/latest-versions-per-milestone-with-downloads.json");
        JsonNode version = node.path("milestones").path(String.valueOf(versionMain)).path("version");
        if (version.isMissingNode()) {
            throw new DriverResolutionException("No chromedriver release for Chrome milestone " + versionMain);
        }
        return parseVersion(version, "milestone " + versionMain);
    }

    public String downloadUrl(ChromeVersion version, Platform platform) {
        if (version.major() <= LAST_LEGACY_MAJOR) {
            return legacyRepo + "/" + version + "/chromedriver_" + platform.legacyName() + ".zip";
        }
        String name = platform.cftName();
        return cftDownloads + "/" + version + "/" + name + "/chromedriver-" + name + ".zip";
    }

    private static ChromeVersion parseVersion(JsonNode node, String what) {
        if (!node.isTextual()) {
            throw new DriverResolutionException("No chromedriver version found for " + what);
        }
        try {
            return ChromeVersion.parse(node.asText());
        } catch (IllegalArgumentException e) {
            throw new DriverResolutionException("Invalid chromedriver version for " + what + ": " + node.asText(), e);
        }
    }

    private JsonNode fetchJson(String url) {
        String body = fetch(url);
        try {
            return JSON.readTree(body);
        } catch (IOException e) {
            throw new DriverResolutionException("Malformed JSON from " + url, e);
        }
    }

    private String fetch(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new DriverResolutionException("HTTP " + response.statusCode() + " from " + url);
            }
            return response.body();
        } catch (IOException e) {
            throw new DriverResolutionException("Failed to fetch " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DriverResolutionException("Interrupted while fetching " + url, e);
        }
    }
}
