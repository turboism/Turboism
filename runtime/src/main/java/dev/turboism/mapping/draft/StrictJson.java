package dev.turboism.mapping.draft;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Shared strict UTF-8 JSON reader for mapping review inputs and canonical writer model. */
public final class StrictJson {
    public static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .enable(SerializationFeature.INDENT_OUTPUT);

    private StrictJson() { }

    /**
     * Parses untrusted JSON under the pipeline's strict rules.
     *
     * <p>Rejects empty input, a UTF-8 byte-order mark, byte sequences that do not round-trip as
     * UTF-8, duplicate object keys, and any trailing token after the top-level value. Nothing is
     * repaired or coerced — every violation raises.
     *
     * @param bytes the raw document
     * @param code the failure code to attach to every rejection, so the caller can attribute the
     *     failure to the specific input it was reading
     * @return the parsed tree
     * @throws DraftMappingException carrying {@code code} for any violation, including a malformed
     *     document (with the parse error as cause)
     */
    public static JsonNode read(final byte[] bytes, final String code) {
        if (bytes.length == 0) {
            throw new DraftMappingException(code, "JSON input is empty");
        }
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xef && (bytes[1] & 0xff) == 0xbb
            && (bytes[2] & 0xff) == 0xbf) {
            throw new DraftMappingException(code, "UTF-8 BOM is not allowed");
        }
        final String decoded = new String(bytes, StandardCharsets.UTF_8);
        if (!java.util.Arrays.equals(bytes, decoded.getBytes(StandardCharsets.UTF_8))) {
            throw new DraftMappingException(code, "JSON must be valid UTF-8");
        }
        try (JsonParser parser = MAPPER.getFactory().createParser(bytes)) {
            final JsonNode value = MAPPER.readTree(parser);
            if (value == null) throw new DraftMappingException(code, "JSON input is empty");
            if (parser.nextToken() != null) throw new DraftMappingException(code, "trailing JSON tokens are not allowed");
            return value;
        } catch (DraftMappingException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new DraftMappingException(code, "invalid strict JSON", exception);
        }
    }
}
