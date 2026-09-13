package dev.turboism.update;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class UpdateDiscoveryParserTest {
    private final UpdateDiscoveryParser parser = new UpdateDiscoveryParser();

    @Test
    void parsesTheRealStableDocumentServedByTheProductionApi() throws Exception {
        final byte[] document;
        try (InputStream source = UpdateDiscoveryParserTest.class.getResourceAsStream(
            "/fixtures/update/stable-live.json"
        )) {
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
    }

    @Test
    void acceptsAReadyDocumentWithoutARecordedBuildNumberAsLegacyIdentity() {
        final UpdateDiscovery discovery = parser.parse(bytes(
            "{\"schemaVersion\":1,\"channel\":\"stable\",\"status\":\"ready\","
                + "\"release\":{\"version\":\"0.43.10\",\"publishedAt\":\"2026-09-10T05:18:21Z\"}}"
        ));

        final UpdateDiscovery.Candidate candidate = discovery.candidate().orElseThrow();
        assertEquals(OptionalLong.empty(), candidate.buildNumber());
        assertEquals("0.43.10", candidate.identity());
    }

    @Test
    void reportsNotPublishedAndUnavailableChannelsWithoutACandidate() {
        final UpdateDiscovery notPublished = parser.parse(bytes(
            "{\"schemaVersion\":1,\"channel\":\"stable\",\"status\":\"not_published\",\"release\":null}"
        ));
        assertEquals(UpdateDiscovery.Status.NOT_PUBLISHED, notPublished.status());
        assertTrue(notPublished.notPublished());
        assertTrue(notPublished.candidate().isEmpty());

        final UpdateDiscovery unavailable = parser.parse(bytes(
            "{\"schemaVersion\":1,\"channel\":\"stable\",\"status\":\"unavailable\","
                + "\"release\":null,\"error\":{\"code\":\"SYNC_UNAVAILABLE\"}}"
        ));
        assertEquals(UpdateDiscovery.Status.UNAVAILABLE, unavailable.status());
        assertFalse(unavailable.notPublished());
    }

    @Test
    void rejectsForeignChannelsUnsupportedSchemasAndUnknownStatuses() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse(bytes(
            "{\"schemaVersion\":1,\"channel\":\"nightly\",\"status\":\"ready\","
                + "\"release\":{\"version\":\"0.43.10\",\"buildNumber\":3}}"
        )));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(bytes(
            "{\"schemaVersion\":2,\"channel\":\"stable\",\"status\":\"not_published\"}"
        )));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(bytes(
            "{\"schemaVersion\":1,\"channel\":\"stable\",\"status\":\"weird\"}"
        )));
    }

    @Test
    void rejectsMalformedIncompleteAndInjectedDocuments() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(bytes("not json")));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(bytes(
            "{\"schemaVersion\":1,\"channel\":\"stable\",\"status\":\"ready\",\"release\":{}}"
        )));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(bytes(
            "{\"schemaVersion\":1,\"channel\":\"stable\",\"status\":\"ready\","
                + "\"release\":{\"version\":\"0.43.10-0.nightly.3\",\"buildNumber\":3}}"
        )));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(bytes(
            "{\"schemaVersion\":1,\"channel\":\"stable\",\"status\":\"not_published\","
                + "\"release\":{\"version\":\"0.43.10\",\"buildNumber\":3}}"
        )));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(bytes(
            "{\"schemaVersion\":1,\"schemaVersion\":1,\"channel\":\"stable\",\"status\":\"not_published\"}"
        )));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(bytes(
            "{\"schemaVersion\":1,\"channel\":\"stable\",\"status\":\"ready\","
                + "\"release\":{\"version\":\"0.43.10\",\"buildNumber\":0}}"
        )));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(bytes(
            "{\"schemaVersion\":1,\"channel\":\"stable\",\"status\":\"ready\","
                + "\"release\":{\"version\":\"0.43.10\",\"buildNumber\":9007199254740992}}"
        )));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(bytes(
            "{\"schemaVersion\":1,\"channel\":\"stable\",\"status\":\"ready\","
                + "\"release\":{\"version\":\"0.43.10\",\"publishedAt\":\"2026-09-10T05:18:21+02:00\"}}"
        )));
    }

    @Test
    void rejectsOversizedAndBomPreambleDocuments() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse(
            new byte[UpdateDiscoveryParser.MAX_BYTES + 1]
        ));
        final byte[] withBom = "{\"schemaVersion\":1}".getBytes(StandardCharsets.UTF_8);
        final byte[] bom = {(byte) 0xef, (byte) 0xbb, (byte) 0xbf};
        final byte[] combined = new byte[bom.length + withBom.length];
        System.arraycopy(bom, 0, combined, 0, bom.length);
        System.arraycopy(withBom, 0, combined, bom.length, withBom.length);
        assertThrows(IllegalArgumentException.class, () -> parser.parse(combined));
    }

    @Test
    void cachedDocumentsSurviveTheSameValidationAsWireDocuments() {
        final UpdateDiscovery discovery = parser.parse(bytes(
            "{\"schemaVersion\":1,\"channel\":\"stable\",\"status\":\"ready\","
                + "\"release\":{\"version\":\"0.43.10\",\"buildNumber\":4,"
                + "\"publishedAt\":\"2026-09-10T05:18:21Z\"}}"
        ));

        final UpdateDiscovery restored = parser.parseNode(discovery.toJson(UpdateFileSupport.JSON));

        assertEquals(discovery, restored);
        assertThrows(IllegalArgumentException.class, () -> parser.parseNode(
            UpdateFileSupport.JSON.createObjectNode().put("schemaVersion", 1)
        ));
    }

    private static byte[] bytes(final String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
