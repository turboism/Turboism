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
