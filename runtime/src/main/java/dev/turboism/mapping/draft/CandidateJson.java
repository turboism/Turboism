package dev.turboism.mapping.draft;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/** Canonical exact-byte JSON representation used for candidate hashing and review. */
public final class CandidateJson {
    static final ObjectMapper MAPPER = StrictJson.MAPPER.copy()
        .disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);

    private CandidateJson() { }

    /**
     * Serializes a node to its canonical byte form: strict pretty-printed JSON with alphabetically
     * ordered properties and map entries, followed by exactly one trailing newline.
     *
     * <p>Byte-for-byte stability is the point — these bytes are what candidate hashes are computed
     * over and what a reviewer diffs, so the same node must always produce the same bytes.
     *
     * @param node the tree to serialize
     * @return the canonical UTF-8 bytes, newline-terminated
     * @throws DraftMappingException with code {@code JSON_WRITE_FAILED} if serialization fails
     */
    public static byte[] write(final JsonNode node) {
        try {
            final byte[] json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(node);
            final byte[] result = java.util.Arrays.copyOf(json, json.length + 1);
            result[result.length - 1] = '\n';
            return result;
        } catch (IOException exception) {
            throw new DraftMappingException("JSON_WRITE_FAILED", "Could not write canonical JSON", exception);
        }
    }
}
