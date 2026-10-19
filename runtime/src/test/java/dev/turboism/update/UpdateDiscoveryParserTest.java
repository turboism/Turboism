package dev.turboism.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

final class UpdateDiscoveryParserTest {
    private final UpdateDiscoveryParser parser = new UpdateDiscoveryParser();

    @Test
    void parsesTheRealStableDocumentServedByTheProductionApi() throws Exception {
        final byte[] document;
        try (InputStream source =
                UpdateDiscoveryParserTest.class.getResourceAsStream("/fixtures/update/stable-live.json")) {
            assertTrue(source != null, "captured production fixture is missing");
            document = source.readAllBytes();
        }

        final UpdateDiscovery discovery = parser.parse(document);

        assertEquals(UpdateDiscovery.SCHEMA_VERSION, discovery.schemaVersion());
        assertEquals(UpdateDiscovery.CHANNEL_STABLE, discovery.channel());
        assertEquals(UpdateDiscovery.Status.READY, discovery.status());
        final UpdateDiscovery.Candidate candidate = discovery.candidate().orElseThrow();
        assertEquals(UpdateVersion.parse("0.43.10"), candidate.version());
        assertEquals(OptionalLong.of(4L), candidate.buildNumber());
        assertEquals(Optional.of(Instant.parse("2026-09-10T05:18:21Z")), candidate.publishedAt());
        assertEquals("0.43.10 (Build 4)", candidate.identity());
        assertEquals(4, candidate.assets().size());
        assertEquals(
                "TurboismInstaller-0.43.10.exe",
                candidate.pickInstallerAsset("full").orElseThrow().name());
        assertEquals(
                "TurboismInstaller-0.43.10.exe",
                candidate.pickInstallerAsset("thin").orElseThrow().name());
    }

    @Test
    void picksMatchingInstallerAssetForFullAndThinVariantsFromNewReleaseFormat() {
        final String newFormat = "{\"schemaVersion\":1,\"channel\":\"stable\",\"status\":\"ready\","
                + "\"release\":{\"version\":\"0.67.0\",\"buildNumber\":5,\"publishedAt\":\"2026-10-14T00:00:00Z\","
                + "\"assets\":["
                + "{\"name\":\"TurboismInstaller-0.67.0-full.exe\",\"kind\":\"windows-installer-full\",\"variant\":\"full\",\"platform\":\"windows\",\"url\":\"https://api.turboism.dev/files/full.exe\",\"size\":1000,\"sha256\":\""
                + "a".repeat(64) + "\"},"
                + "{\"name\":\"TurboismInstaller-0.67.0-thin.exe\",\"kind\":\"windows-installer-thin\",\"variant\":\"thin\",\"platform\":\"windows\",\"url\":\"https://api.turboism.dev/files/thin.exe\",\"size\":500,\"sha256\":\""
                + "b".repeat(64) + "\"},"
                + "{\"name\":\"turboism-0.67.0-full.zip\",\"kind\":\"full\",\"variant\":\"full\",\"url\":\"https://api.turboism.dev/files/full.zip\",\"size\":2000,\"sha256\":\""
                + "c".repeat(64) + "\"},"
                + "{\"name\":\"turboism-0.67.0-thin.zip\",\"kind\":\"thin\",\"variant\":\"thin\",\"url\":\"https://api.turboism.dev/files/thin.zip\",\"size\":1500,\"sha256\":\""
                + "d".repeat(64) + "\"}"
                + "]}}";
        final UpdateDiscovery discovery = parser.parse(bytes(newFormat));
        final UpdateDiscovery.Candidate candidate = discovery.candidate().orElseThrow();
        assertEquals(4, candidate.assets().size());

        final UpdateDiscovery.Asset fullInstaller =
                candidate.pickInstallerAsset("full").orElseThrow();
        assertEquals("TurboismInstaller-0.67.0-full.exe", fullInstaller.name());
        assertEquals("windows-installer-full", fullInstaller.kind());
        assertEquals(Optional.of("full"), fullInstaller.variant());

        final UpdateDiscovery.Asset thinInstaller =
                candidate.pickInstallerAsset("thin").orElseThrow();
        assertEquals("TurboismInstaller-0.67.0-thin.exe", thinInstaller.name());
        assertEquals("windows-installer-thin", thinInstaller.kind());
        assertEquals(Optional.of("thin"), thinInstaller.variant());

        // Roundtrip serialization preserves assets
        final UpdateDiscovery restored = parser.parseNode(discovery.toJson(UpdateFileSupport.JSON));
        assertEquals(discovery, restored);
    }

    @Test
    void unifiedInstallerServesBothInstalledVariants() {
        final String document = """
                {"schemaVersion":1,"channel":"stable","status":"ready",
                 "release":{"version":"0.67.0","assets":[
                   {"name":"turboism-0.67.0.zip","kind":"full","variant":"full",
                    "url":"https://api.turboism.dev/files/standard.zip","size":100,"sha256":"%s"},
                   {"name":"TurboismInstaller-0.67.0.exe","kind":"windows-installer","variant":"full","platform":"windows",
                    "url":"https://api.turboism.dev/files/standard.exe","size":100,"sha256":"%s"}
                 ]}}
                """.formatted("a".repeat(64), "b".repeat(64));
        final UpdateDiscovery discovery = parser.parse(bytes(document));
        final UpdateDiscovery.Candidate candidate = discovery.candidate().orElseThrow();
        for (String variant : new String[] {"full", "thin"}) {
            assertEquals(
                    "TurboismInstaller-0.67.0.exe",
                    candidate.pickInstallerAsset(variant).orElseThrow().name());
        }
        assertEquals(discovery, parser.parseNode(discovery.toJson(UpdateFileSupport.JSON)));
    }

    @Test
    void acceptsAReadyDocumentWithoutARecordedBuildNumberAsLegacyIdentity() {
        final UpdateDiscovery discovery =
                parser.parse(bytes("{\"schemaVersion\":1,\"channel\":\"stable\",\"status\":\"ready\","
                        + "\"release\":{\"version\":\"0.43.10\",\"publishedAt\":\"2026-09-10T05:18:21Z\"}}"));

        final UpdateDiscovery.Candidate candidate = discovery.candidate().orElseThrow();
        assertEquals(OptionalLong.empty(), candidate.buildNumber());
        assertEquals("0.43.10", candidate.identity());
    }

    @Test
    void reportsNotPublishedAndUnavailableChannelsWithoutACandidate() {
        final UpdateDiscovery notPublished = parser.parse(
                bytes("{\"schemaVersion\":1,\"channel\":\"stable\",\"status\":\"not_published\",\"release\":null}"));
        assertEquals(UpdateDiscovery.Status.NOT_PUBLISHED, notPublished.status());
        assertTrue(notPublished.notPublished());
        assertTrue(notPublished.candidate().isEmpty());

        final UpdateDiscovery unavailable =
                parser.parse(bytes("{\"schemaVersion\":1,\"channel\":\"stable\",\"status\":\"unavailable\","
                        + "\"release\":null,\"error\":{\"code\":\"SYNC_UNAVAILABLE\"}}"));
        assertEquals(UpdateDiscovery.Status.UNAVAILABLE, unavailable.status());
        assertFalse(unavailable.notPublished());
    }

    @Test
    void rejectsForeignChannelsUnsupportedSchemasAndUnknownStatuses() {
        assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse(bytes("{\"schemaVersion\":1,\"channel\":\"nightly\",\"status\":\"ready\","
                        + "\"release\":{\"version\":\"0.43.10\",\"buildNumber\":3}}")));
        assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse(bytes("{\"schemaVersion\":2,\"channel\":\"stable\",\"status\":\"not_published\"}")));
        assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse(bytes("{\"schemaVersion\":1,\"channel\":\"stable\",\"status\":\"weird\"}")));
    }

    @Test
    void rejectsMalformedIncompleteAndInjectedDocuments() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(bytes("not json")));
        assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse(
                        bytes("{\"schemaVersion\":1,\"channel\":\"stable\",\"status\":\"ready\",\"release\":{}}")));
        assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse(bytes("{\"schemaVersion\":1,\"channel\":\"stable\",\"status\":\"ready\","
                        + "\"release\":{\"version\":\"0.43.10-0.nightly.3\",\"buildNumber\":3}}")));
        assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse(bytes("{\"schemaVersion\":1,\"channel\":\"stable\",\"status\":\"not_published\","
                        + "\"release\":{\"version\":\"0.43.10\",\"buildNumber\":3}}")));
        assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse(
                        bytes(
                                "{\"schemaVersion\":1,\"schemaVersion\":1,\"channel\":\"stable\",\"status\":\"not_published\"}")));
        assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse(bytes("{\"schemaVersion\":1,\"channel\":\"stable\",\"status\":\"ready\","
                        + "\"release\":{\"version\":\"0.43.10\",\"buildNumber\":0}}")));
        assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse(bytes("{\"schemaVersion\":1,\"channel\":\"stable\",\"status\":\"ready\","
                        + "\"release\":{\"version\":\"0.43.10\",\"buildNumber\":9007199254740992}}")));
        assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse(bytes("{\"schemaVersion\":1,\"channel\":\"stable\",\"status\":\"ready\","
                        + "\"release\":{\"version\":\"0.43.10\",\"publishedAt\":\"2026-09-10T05:18:21+02:00\"}}")));
    }

    @Test
    void rejectsOversizedAndBomPreambleDocuments() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse(new byte[UpdateDiscoveryParser.MAX_BYTES + 1]));
        final byte[] withBom = "{\"schemaVersion\":1}".getBytes(StandardCharsets.UTF_8);
        final byte[] bom = {(byte) 0xef, (byte) 0xbb, (byte) 0xbf};
        final byte[] combined = new byte[bom.length + withBom.length];
        System.arraycopy(bom, 0, combined, 0, bom.length);
        System.arraycopy(withBom, 0, combined, bom.length, withBom.length);
        assertThrows(IllegalArgumentException.class, () -> parser.parse(combined));
    }

    @Test
    void cachedDocumentsSurviveTheSameValidationAsWireDocuments() {
        final UpdateDiscovery discovery =
                parser.parse(bytes("{\"schemaVersion\":1,\"channel\":\"stable\",\"status\":\"ready\","
                        + "\"release\":{\"version\":\"0.43.10\",\"buildNumber\":4,"
                        + "\"publishedAt\":\"2026-09-10T05:18:21Z\"}}"));

        final UpdateDiscovery restored = parser.parseNode(discovery.toJson(UpdateFileSupport.JSON));

        assertEquals(discovery, restored);
        assertThrows(
                IllegalArgumentException.class,
                () -> parser.parseNode(UpdateFileSupport.JSON.createObjectNode().put("schemaVersion", 1)));
    }

    @Test
    void parsesBetaAndNightlyChannelDocumentsWhenConfiguredForThoseChannels() {
        final UpdateDiscoveryParser betaParser = new UpdateDiscoveryParser(UpdateDiscovery.CHANNEL_BETA);
        final UpdateDiscovery beta =
                betaParser.parse(bytes("{\"schemaVersion\":1,\"channel\":\"beta\",\"status\":\"ready\","
                        + "\"release\":{\"version\":\"0.67.0-beta.1\",\"buildNumber\":14,"
                        + "\"publishedAt\":\"2026-10-01T00:00:00Z\"}}"));
        assertEquals(UpdateDiscovery.CHANNEL_BETA, beta.channel());
        assertEquals(
                UpdateVersion.parse("0.67.0"), beta.candidate().orElseThrow().version());
        assertEquals("0.67.0-beta.1", beta.candidate().orElseThrow().versionText());
        assertEquals("0.67.0-beta.1 (Build 14)", beta.candidate().orElseThrow().identity());
        assertEquals(beta, betaParser.parseNode(beta.toJson(UpdateFileSupport.JSON)));
        assertThrows(
                IllegalArgumentException.class,
                () -> betaParser.parse(
                        bytes("{\"schemaVersion\":1,\"channel\":\"stable\",\"status\":\"not_published\"}")));

        final UpdateDiscoveryParser nightlyParser = new UpdateDiscoveryParser(UpdateDiscovery.CHANNEL_NIGHTLY);
        final UpdateDiscovery nightly =
                nightlyParser.parse(bytes("{\"schemaVersion\":1,\"channel\":\"nightly\",\"status\":\"ready\","
                        + "\"release\":{\"version\":\"0.67.1-0.nightly.25\",\"buildNumber\":25,"
                        + "\"publishedAt\":\"2026-10-02T00:00:00Z\"}}"));
        assertEquals(UpdateDiscovery.CHANNEL_NIGHTLY, nightly.channel());
        assertEquals(
                UpdateVersion.parse("0.67.1"), nightly.candidate().orElseThrow().version());
        assertEquals("0.67.1-0.nightly.25", nightly.candidate().orElseThrow().versionText());
        assertEquals(
                "0.67.1-0.nightly.25 (Build 25)",
                nightly.candidate().orElseThrow().identity());
        assertEquals(nightly, nightlyParser.parseNode(nightly.toJson(UpdateFileSupport.JSON)));
        assertThrows(
                IllegalArgumentException.class,
                () -> nightlyParser.parse(
                        bytes("{\"schemaVersion\":1,\"channel\":\"beta\",\"status\":\"not_published\"}")));
    }

    private static byte[] bytes(final String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
