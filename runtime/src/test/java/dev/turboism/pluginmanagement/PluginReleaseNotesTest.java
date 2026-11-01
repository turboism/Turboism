package dev.turboism.pluginmanagement;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.core.version.PluginVersion;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PluginReleaseNotesTest {
    private static final String REVISION = "a".repeat(40);
    private static final String README = """
            # Demo
            Intro
            ## 更新日志
            ### 2.0.0
            - Future
            ### 1.2.0
            - Newest fix
            #### Details
            More detail
            ### 1.1.0
            - Earlier fix
            ### 1.0.0
            - Already installed
            ## Support
            Not release notes
            """;

    @Test
    void extractsOnlyChangesBetweenInstalledAndTargetVersions() {
        final String notes =
                PluginReleaseNotes.extract(README, PluginVersion.parse("1.0.0"), PluginVersion.parse("1.2.0"));
        assertTrue(notes.contains("Newest fix"));
        assertTrue(notes.contains("Earlier fix"));
        assertTrue(notes.contains("More detail"));
        assertFalse(notes.contains("Future"));
        assertFalse(notes.contains("Already installed"));
        assertFalse(notes.contains("Not release notes"));
        assertEquals(
                "",
                PluginReleaseNotes.extract(
                        "# README\nNo changelog", PluginVersion.parse("1.0.0"), PluginVersion.parse("1.2.0")));
    }

    @Test
    void readsPinnedLocalizedReadmeAndFallsBackWhenTranslationIsMissing() throws Exception {
        final List<URI> requests = new ArrayList<>();
        final PluginCatalogClient client = client(uri -> {
            requests.add(uri);
            return new Response(uri, uri.getPath().endsWith("_zh.md") ? 404 : 200, README);
        });
        assertTrue(client.fetchReleaseNotes(update("demo", REVISION), Locale.SIMPLIFIED_CHINESE)
                .contains("Newest fix"));
        assertEquals(
                List.of(
                        URI.create("https://raw.githubusercontent.com/turboism/Turboism/" + REVISION
                                + "/plugins/demo/README_zh.md"),
                        URI.create("https://raw.githubusercontent.com/turboism/Turboism/" + REVISION
                                + "/plugins/demo/README.md")),
                requests);
    }

    @Test
    void rawHostTimeoutFallsBackToGithubApiForTheSameSourceAndLanguage() throws Exception {
        final List<URI> requests = new ArrayList<>();
        final PluginCatalogClient client = client(uri -> {
            requests.add(uri);
            if ("raw.githubusercontent.com".equals(uri.getHost()))
                throw new java.net.SocketTimeoutException("Blocked raw host");
            return new Response(uri, 200, README);
        });
        assertTrue(client.fetchReleaseNotes(update("demo", REVISION), Locale.SIMPLIFIED_CHINESE)
                .contains("Newest fix"));
        assertEquals(2, requests.size());
        assertEquals(
                URI.create("https://api.github.com/repos/turboism/Turboism/contents/plugins/demo/README_zh.md?ref="
                        + REVISION),
                requests.get(1));
    }

    @Test
    void localizedNotesArePreferredAndMissingNotesStayEmpty() throws Exception {
        final List<URI> requests = new ArrayList<>();
        final PluginCatalogClient client = client(uri -> {
            requests.add(uri);
            return new Response(uri, 200, README.replace("Newest fix", "本次修复"));
        });
        assertTrue(client.fetchReleaseNotes(update("demo", REVISION), Locale.SIMPLIFIED_CHINESE)
                .contains("本次修复"));
        assertEquals(1, requests.size());
        assertEquals(
                "",
                client(uri -> new Response(uri, 404, "")).fetchReleaseNotes(update("demo", REVISION), Locale.ENGLISH));
    }

    @Test
    void invalidSourcePathsNeverConnectAndOversizeResponsesAreRejected() throws Exception {
        final PluginCatalogClient offline = client(uri -> {
            throw new AssertionError("Unexpected request");
        });
        assertEquals("", offline.fetchReleaseNotes(update("../demo", REVISION), Locale.ENGLISH));
        assertEquals("", offline.fetchReleaseNotes(update("demo", "main"), Locale.ENGLISH));
        assertThrows(
                IOException.class,
                () -> client(uri -> new Response(uri, 200, "x".repeat(262_145)))
                        .fetchReleaseNotes(update("demo", REVISION), Locale.ENGLISH));
        assertThrows(
                IOException.class,
                () -> client(uri -> new Response(uri, 302, ""))
                        .fetchReleaseNotes(update("demo", REVISION), Locale.ENGLISH));
    }

    private static PluginCatalogClient client(PluginCatalogClient.ConnectionFactory factory) {
        return new PluginCatalogClient(
                PluginCatalogClient.CATALOG_URI, PluginCatalogClient.SIGNATURE_URI, Duration.ofSeconds(2), factory);
    }

    private static PluginAutoUpdatePlanner.PlannedUpdate update(String slug, String revision) {
        final var artifact = new PluginCatalog.CatalogArtifact(
                "application/java-archive",
                "demo.jar",
                "https://github.com/turboism/test/releases/download/v1/demo.jar",
                "0".repeat(64),
                "0".repeat(64),
                1);
        final var release = new PluginCatalog.CatalogRelease(
                "1.2.0",
                "stable",
                "active",
                Instant.EPOCH,
                "workflow",
                List.of(),
                "[0.1.0,2.0.0)",
                false,
                List.of(),
                List.of("windows-x64"),
                List.of(),
                List.of(),
                "https://github.com/turboism/test/releases/tag/v1",
                Optional.of(revision),
                artifact);
        final var plugin = new PluginCatalog.CatalogPlugin(
                "dev.turboism.plugin.demo",
                slug,
                "Demo",
                "Demo",
                "official",
                "Turboism",
                "Project License",
                "https://github.com/turboism/Turboism",
                "https://turboism.dev",
                Map.of(),
                List.of(release));
        return new PluginAutoUpdatePlanner.PlannedUpdate(
                plugin, release, PluginVersion.parse("1.0.0"), PluginVersion.parse("1.2.0"));
    }

    private static class Response extends HttpURLConnection {
        private final int status;
        private final byte[] body;

        Response(URI uri, int status, String body) throws IOException {
            super(uri.toURL());
            this.status = status;
            this.body = body.getBytes(StandardCharsets.UTF_8);
        }

        public void connect() {}

        public void disconnect() {}

        public boolean usingProxy() {
            return false;
        }

        public int getResponseCode() {
            return status;
        }

        public long getContentLengthLong() {
            return body.length;
        }

        public InputStream getInputStream() {
            return new ByteArrayInputStream(body);
        }

        public InputStream getErrorStream() {
            return new ByteArrayInputStream(body);
        }
    }
}
