package dev.turboism.runtime.json;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The three JSON profiles and the guarantee that a caller can tighten, never relax, the parser
 * limits a verifier depends on.
 */
class RuntimeJsonTest {

    @Test
    void strictProfileRejectsDuplicateKeysAndTrailingTokens() {
        final ObjectMapper strict = RuntimeJson.strict();
        assertThrows(IOException.class, () -> strict.readTree("{\"a\":1,\"a\":2}"));
        assertThrows(IOException.class, () -> strict.readTree("{\"a\":1}{\"b\":2}"));
        assertDoesNotThrow(() -> strict.readTree("{\"a\":1}"));
    }

    @Test
    void standardProfileKeepsThePermissiveDocumentReading() throws IOException {
        final JsonNode parsed = RuntimeJson.standard().readTree("{\"a\":1,\"a\":2}");
        assertEquals(2, parsed.path("a").asInt(), "the default profile keeps the last duplicate");
    }

    @Test
    void canonicalProfileSortsMapEntriesDeterministically() throws IOException {
        final Map<String, Integer> entries = new LinkedHashMap<>();
        entries.put("b", 2);
        entries.put("a", 1);
        assertEquals("{\"a\":1,\"b\":2}", RuntimeJson.canonical().writeValueAsString(entries));
    }

    @Test
    void strictProfileWithConstraintsEnforcesTheCallerLimits() {
        final ObjectMapper bounded = RuntimeJson.strict(
                StreamReadConstraints.builder().maxNestingDepth(2).build());
        assertThrows(IOException.class, () -> bounded.readTree("{\"a\":{\"b\":{\"c\":1}}}"));
    }

    @Test
    void sharedProfilesAreTheSameInstance() {
        assertTrue(RuntimeJson.strict() == RuntimeJson.strict());
        assertTrue(RuntimeJson.standard() == RuntimeJson.standard());
        assertTrue(RuntimeJson.canonical() == RuntimeJson.canonical());
    }
}
