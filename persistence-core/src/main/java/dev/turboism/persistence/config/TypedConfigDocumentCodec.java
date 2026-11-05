package dev.turboism.persistence.config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical text format of one persisted typed-config document.
 *
 * <p>The format is line-oriented and strictly canonical: a fixed
 * {@code turboism-typed-config-v1} header, decimal {@code schemaVersion}, {@code revision} and
 * {@code count} metadata, then one {@code base64url(key):base64url(value)} line per entry in
 * ascending key order. Decoding re-encodes the parsed document and rejects anything that does
 * not round-trip byte for byte, so a hand-edited, reordered or non-minimal document is refused
 * instead of silently rewritten.
 *
 * <p>This class is pure: it converts between a string and a {@link StoredDocument} and never
 * touches a file, a clock, a thread or a locale.
 */
public final class TypedConfigDocumentCodec {

    private static final String HEADER = "turboism-typed-config-v1";

    private TypedConfigDocumentCodec() {}

    /**
     * Renders one document into its canonical text form.
     *
     * @param document the document to render
     * @return the canonical text, ending with a newline
     */
    public static String encode(final StoredDocument document) {
        final List<String> keys = new ArrayList<>(document.encodedValues().keySet());
        keys.sort(String::compareTo);
        final StringBuilder output = new StringBuilder();
        output.append(HEADER).append('\n');
        output.append("schemaVersion=").append(document.schemaVersion()).append('\n');
        output.append("revision=").append(document.revision()).append('\n');
        output.append("count=").append(keys.size()).append('\n');
        final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        for (String key : keys) {
            output.append(encoder.encodeToString(key.getBytes(StandardCharsets.UTF_8)))
                    .append(':')
                    .append(encoder.encodeToString(
                            document.encodedValues().get(key).getBytes(StandardCharsets.UTF_8)))
                    .append('\n');
        }
        return output.toString();
    }

    /**
     * Parses one document and proves it is canonical.
     *
     * @param text the complete document text
     * @return the parsed document
     * @throws IOException when the framing, metadata, entry encoding or canonical form is invalid
     */
    public static StoredDocument decode(final String text) throws IOException {
        final String[] lines = text.split("\\n", -1);
        if (lines.length < 5 || !lines[0].equals(HEADER) || !lines[lines.length - 1].isEmpty()) {
            throw new IOException("typed config document header is invalid");
        }
        final int version = parseInt(lines[1], "schemaVersion=");
        final long revision = parseLong(lines[2], "revision=");
        final int count = parseInt(lines[3], "count=");
        if (version < 1 || revision < 0 || count < 0 || lines.length != count + 5) {
            throw new IOException("typed config document metadata is invalid");
        }
        final Base64.Decoder decoder = Base64.getUrlDecoder();
        final Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            final String line = lines[index + 4];
            final int separator = line.indexOf(':');
            if (separator <= 0 || separator != line.lastIndexOf(':')) {
                throw new IOException("typed config entry is invalid");
            }
            try {
                final String key = decodeUtf8(decoder.decode(line.substring(0, separator)));
                final String value = decodeUtf8(decoder.decode(line.substring(separator + 1)));
                if (key.isBlank() || values.putIfAbsent(key, value) != null) {
                    throw new IOException("typed config entry key is invalid");
                }
            } catch (IllegalArgumentException exception) {
                throw new IOException("typed config entry encoding is invalid", exception);
            }
        }
        final StoredDocument document = new StoredDocument(version, revision, values);
        if (!encode(document).equals(text)) {
            throw new IOException("typed config document is noncanonical");
        }
        return document;
    }

    private static String decodeUtf8(final byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("typed config entry is not valid UTF-8", exception);
        }
    }

    private static int parseInt(final String line, final String prefix) throws IOException {
        final long value = parseLong(line, prefix);
        if (value > Integer.MAX_VALUE) {
            throw new IOException("typed config integer metadata is out of range");
        }
        return (int) value;
    }

    private static long parseLong(final String line, final String prefix) throws IOException {
        if (!line.startsWith(prefix)) {
            throw new IOException("typed config metadata is missing");
        }
        final String raw = line.substring(prefix.length());
        if (!raw.matches("0|[1-9][0-9]*")) {
            throw new IOException("typed config metadata is noncanonical");
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException exception) {
            throw new IOException("typed config metadata is out of range", exception);
        }
    }
}
