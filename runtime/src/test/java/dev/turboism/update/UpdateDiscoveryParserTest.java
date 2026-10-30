package dev.turboism.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    static ObjectNode incrementalDocument() {
        final ObjectNode root = UpdateFileSupport.JSON.createObjectNode();
        root.put("schemaVersion", 1)
                .put("channel", "stable")
                .put("status", "ready")
                .put("metadataUrl", "https://api.turboism.dev/v1/releases/stable.json");
        root.putObject("source").put("repository", "turboism/Turboism");
        final ObjectNode release = root.putObject("release");
        release.put("version", "2.0.0")
                .put("channel", "stable")
                .put("buildNumber", 50)
                .put("sourceRevision", "a".repeat(40));
        final ObjectNode wrapper = release.putObject("incremental");
        wrapper.putObject("source").put("origin", "https://api.turboism.dev").put("redirects", "reject");
        final ObjectNode descriptor = wrapper.putObject("descriptor");
        descriptor
                .put("format", "turboism.incremental-candidate")
                .put("schemaVersion", 1)
                .put("repository", "turboism/Turboism")
                .put("objectsSha256", "b".repeat(64));
        descriptor
                .putObject("identity")
                .put("version", "2.0.0")
                .put("channel", "stable")
                .put("buildNumber", 50)
                .put("sourceRevision", "a".repeat(40));
        descriptor.putObject("manifest").put("bytes", 1024).put("sha256", "c".repeat(64));
        descriptor.putObject("envelope").put("bytes", 512).put("sha256", "d".repeat(64));
        return root;
    }

    @Test
    void retainsCompleteCapabilityAndLegacySourceWithoutGrantingSignatureTrust() {
        final UpdateDiscovery discovery = parser.parseNode(incrementalDocument());
        final var candidate = discovery.candidate().orElseThrow();
        assertEquals(Optional.of("a".repeat(40)), candidate.sourceRevision());
        assertEquals(50, candidate.incremental().orElseThrow().buildNumber());
        assertEquals(discovery, parser.parseNode(discovery.toJson(UpdateFileSupport.JSON)));
        final ObjectNode legacy = incrementalDocument();
        ((ObjectNode) legacy.get("release")).remove("incremental");
        final var fullOnly = parser.parseNode(legacy);
        assertTrue(fullOnly.candidate().orElseThrow().incremental().isEmpty());
        assertEquals(fullOnly, parser.parseNode(fullOnly.toJson(UpdateFileSupport.JSON)));
        ((ObjectNode) legacy.get("release")).remove("sourceRevision");
        assertTrue(parser.parseNode(legacy)
                .candidate()
                .orElseThrow()
                .sourceRevision()
                .isEmpty());
    }

    @Test
    void rejectsCapabilityIdentityAuthoritySchemaDigestAndIntegerMismatches() {
        final List<Consumer<ObjectNode>> mutations = List.of(
                root -> ((ObjectNode) root.get("release")).put("version", "3.0.0"),
                root -> ((ObjectNode) root.get("release")).put("buildNumber", 51),
                root -> ((ObjectNode) root.get("release")).remove("buildNumber"),
                root -> ((ObjectNode) root.get("release")).put("sourceRevision", "f".repeat(40)),
                root -> ((ObjectNode) root.get("release")).remove("sourceRevision"),
                root -> ((ObjectNode) root.get("release")).put("sourceRevision", true),
                root -> ((ObjectNode) root.get("release")).put("channel", "beta"),
                root -> root.put("metadataUrl", "https://elsewhere.invalid/v1/releases/stable.json"),
                root -> root.put("metadataUrl", "https://api.turboism.dev:443/v1/releases/stable.json"),
                root -> ((ObjectNode) root.get("source")).put("repository", "untrusted/repository"),
                root -> ((ObjectNode) root.at("/release/incremental/source"))
                        .put("origin", "https://elsewhere.invalid"),
                root -> ((ObjectNode) root.at("/release/incremental/source")).put("redirects", "follow"),
                root -> ((ObjectNode) root.at("/release/incremental/descriptor")).put("schemaVersion", 2),
                root -> ((ObjectNode) root.at("/release/incremental/descriptor"))
                        .put("schemaVersion", new java.math.BigInteger("4294967297")),
                root -> ((ObjectNode) root.at("/release/incremental/descriptor")).put("unknown", true),
                root -> ((ObjectNode) root.at("/release/incremental/descriptor/identity")).put("channel", "beta"),
                root -> ((ObjectNode) root.at("/release/incremental/descriptor/identity")).put("buildNumber", 50.0),
                root -> ((ObjectNode) root.at("/release/incremental/descriptor/identity"))
                        .put("buildNumber", new java.math.BigInteger("18446744073709551666")),
                root -> ((ObjectNode) root.at("/release/incremental/descriptor/manifest")).put("sha256", "BAD"),
                root -> ((ObjectNode) root.at("/release/incremental/descriptor/manifest")).put("bytes", 1048577),
                root -> ((ObjectNode) root.at("/release/incremental/descriptor/manifest")).put("bytes", 0),
                root -> ((ObjectNode) root.at("/release/incremental/descriptor/envelope")).put("bytes", 16385),
                root -> ((ObjectNode) root.at("/release/incremental/descriptor/envelope")).put("bytes", true),
                root -> ((ObjectNode) root.at("/release/incremental/descriptor")).put("objectsSha256", "bad"));
        for (int i = 0; i < mutations.size(); i++) {
            final ObjectNode root = incrementalDocument();
            mutations.get(i).accept(root);
            assertThrows(IllegalArgumentException.class, () -> parser.parseNode(root), "case " + i);
        }
    }

    @Test
    void cachePersistsWholeIdentityAndRejectsMixedCapabilityOnRestart(@TempDir final Path home) throws Exception {
        final UpdateDiscovery discovery = parser.parseNode(incrementalDocument());
        final UpdateStateStore store = new UpdateStateStore(home);
        assertTrue(
                store.save(new UpdateStateStore.State(Optional.empty(), Optional.of(discovery), Optional.of("\"v50\"")))
                        .saved());
        assertEquals(Optional.of(discovery), store.read().cachedResult());
        final ObjectNode root = (ObjectNode) UpdateFileSupport.JSON.readTree(Files.readAllBytes(store.path()));
        ((ObjectNode) root.at("/cachedResult/release/incremental/descriptor/identity"))
                .put("sourceRevision", "e".repeat(40));
        Files.writeString(store.path(), root.toString());
        assertTrue(store.read().cachedResult().isEmpty());
        assertTrue(store.read().etag().isEmpty());
    }

    private static byte[] bytes(final String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
