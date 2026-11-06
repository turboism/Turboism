package dev.turboism.persistence.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Pins the canonical typed-config document format and its rejection matrix. */
class TypedConfigDocumentCodecTest {

    @Test
    void encodesEntriesInAscendingKeyOrderWithOneTrailingNewline() {
        final Map<String, String> values = new LinkedHashMap<>();
        values.put("zeta", "last");
        values.put("alpha", "first");
        final StoredDocument document = new StoredDocument(2, 7, values);

        final String encoded = TypedConfigDocumentCodec.encode(document);

        assertEquals(
                "turboism-typed-config-v1\n"
                        + "schemaVersion=2\n"
                        + "revision=7\n"
                        + "count=2\n"
                        + entry("alpha", "first")
                        + entry("zeta", "last"),
                encoded);
        assertEquals(document, assertDoesNotThrowDecode(encoded));
    }

    @Test
    void decodesAnEmptyDocumentWithoutInventingEntries() throws IOException {
        final StoredDocument document =
                TypedConfigDocumentCodec.decode("turboism-typed-config-v1\nschemaVersion=1\nrevision=0\ncount=0\n");

        assertEquals(new StoredDocument(1, 0, Map.of()), document);
    }

    @Test
    void rejectsNonCanonicalFramingAndMetadata() {
        assertRejected("typed-config-v2\nschemaVersion=1\nrevision=0\ncount=0\n");
        assertRejected("turboism-typed-config-v1\nschemaVersion=1\nrevision=0\ncount=0");
        assertRejected("turboism-typed-config-v1\nschemaVersion=+1\nrevision=0\ncount=0\n");
        assertRejected("turboism-typed-config-v1\nschemaVersion=01\nrevision=0\ncount=0\n");
        assertRejected("turboism-typed-config-v1\nschemaVersion=0\nrevision=0\ncount=0\n");
        assertRejected("turboism-typed-config-v1\nschemaVersion=1\nrevision=-1\ncount=0\n");
        assertRejected("turboism-typed-config-v1\nschemaVersion=1\nrevision=0\ncount=1\n");
        assertRejected("turboism-typed-config-v1\nschemaVersion=1\nrevision=0\ncount=2147483648\n");
        assertRejected("turboism-typed-config-v1\nschemaVersion=1\nrevision=9223372036854775808\ncount=0\n");
    }

    @Test
    void rejectsEntriesThatDoNotRoundTripExactly() {
        // Entries must be ordered by key.
        assertRejected("turboism-typed-config-v1\nschemaVersion=1\nrevision=0\ncount=2\n"
                + entry("zeta", "last")
                + entry("alpha", "first"));
        // A duplicated key is a corrupt document, not a last-write-wins document.
        assertRejected("turboism-typed-config-v1\nschemaVersion=1\nrevision=0\ncount=2\n"
                + entry("alpha", "first")
                + entry("alpha", "second"));
        // Framing, base64url padding and strict UTF-8 are part of the canonical form.
        assertRejected("turboism-typed-config-v1\nschemaVersion=1\nrevision=0\ncount=1\nYQ==:Yg\n");
        assertRejected("turboism-typed-config-v1\nschemaVersion=1\nrevision=0\ncount=1\nYQ:Yg==\n");
        assertRejected("turboism-typed-config-v1\nschemaVersion=1\nrevision=0\ncount=1\nYQ:Yg\n\n");
        assertRejected("turboism-typed-config-v1\nschemaVersion=1\nrevision=0\ncount=1\n"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[] {(byte) 0xFF})
                + ":Yg\n");
        assertRejected("turboism-typed-config-v1\nschemaVersion=1\nrevision=0\ncount=1\n"
                + Base64.getUrlEncoder().withoutPadding().encodeToString("  ".getBytes(StandardCharsets.UTF_8))
                + ":Yg\n");
    }

    @Test
    void rejectsMetadataWhoseEntriesAreMisplaced() {
        assertRejected("turboism-typed-config-v1\nschemaVersion=1\nrevision=0\ncount=1\nYQ:Yg\nrevision=1\n");
        assertRejected("turboism-typed-config-v1\nrevision=0\nschemaVersion=1\ncount=0\n");
    }

    @Test
    void rejectsARecordThatWouldNotSerializeToTheSameText() {
        assertRejected("turboism-typed-config-v1\nschemaVersion=1\nrevision=0\ncount=0\n\n");
    }

    private static StoredDocument assertDoesNotThrowDecode(final String text) {
        try {
            return TypedConfigDocumentCodec.decode(text);
        } catch (IOException failure) {
            throw new AssertionError("decode must succeed for a canonical document", failure);
        }
    }

    private static void assertRejected(final String text) {
        assertThrows(IOException.class, () -> TypedConfigDocumentCodec.decode(text), text);
    }

    private static String entry(final String key, final String value) {
        final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString(key.getBytes(StandardCharsets.UTF_8)) + ":"
                + encoder.encodeToString(value.getBytes(StandardCharsets.UTF_8)) + "\n";
    }
}
