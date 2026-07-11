package dev.turboism.mapping.draft;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Locates and replaces one runtime JSON string token without reserializing the pack. */
final class ExactJsonRuntimeReplacement {
    private ExactJsonRuntimeReplacement() { }

    static Replacement replace(
        final byte[] baseBytes,
        final JsonNode strictPack,
        final String semanticName,
        final String beforeRuntime,
        final String afterRuntime
    ) {
        if (beforeRuntime.equals(afterRuntime)) {
            fail("NO_CHANGE", "runtime update must change the selected value");
        }
        final JsonNode entry = uniqueEntry(strictPack, semanticName);
        if (!beforeRuntime.equals(entry.path("runtime").asText())) {
            fail("PACK_BEFORE_MISMATCH", "expected runtime does not match the selected entry");
        }

        final List<TokenSpan> matches = locateRuntimeTokens(baseBytes, semanticName);
        if (matches.size() != 1) {
            fail("PACK_RUNTIME_TOKEN_NOT_UNIQUE", "selected runtime JSON string token could not be located uniquely");
        }
        final TokenSpan span = matches.get(0);
        if (!beforeRuntime.equals(span.value())) {
            fail("PACK_RUNTIME_TOKEN_MISMATCH", "located runtime token does not match the selected entry");
        }
        final byte[] replacementToken = encodeJsonString(afterRuntime);
        final byte[] result = new byte[baseBytes.length - (span.end() - span.start()) + replacementToken.length];
        System.arraycopy(baseBytes, 0, result, 0, span.start());
        System.arraycopy(replacementToken, 0, result, span.start(), replacementToken.length);
        System.arraycopy(baseBytes, span.end(), result, span.start() + replacementToken.length, baseBytes.length - span.end());

        verifyOnlyRuntimeChanged(baseBytes, result, semanticName, beforeRuntime, afterRuntime);
        return new Replacement(result, beforeRuntime, afterRuntime);
    }

    static Replacement verifyOnlyRuntimeChanged(
        final byte[] baseBytes,
        final byte[] resultBytes,
        final String semanticName,
        final String expectedBefore,
        final String expectedAfter
    ) {
        final JsonNode base = StrictJson.read(baseBytes, "PACK_JSON_INVALID");
        final JsonNode result = StrictJson.read(resultBytes, "RESULT_PACK_JSON_INVALID");
        final JsonNode baseEntry = uniqueEntry(base, semanticName);
        final JsonNode resultEntry = uniqueEntry(result, semanticName);
        if (!expectedBefore.equals(baseEntry.path("runtime").asText())
            || !expectedAfter.equals(resultEntry.path("runtime").asText())) {
            fail("RESULT_PACK_DIFF_INVALID", "base/result runtime values do not match the requested change");
        }
        final List<TokenSpan> baseSpans = locateRuntimeTokens(baseBytes, semanticName);
        final List<TokenSpan> resultSpans = locateRuntimeTokens(resultBytes, semanticName);
        if (baseSpans.size() != 1 || resultSpans.size() != 1) {
            fail("RESULT_PACK_DIFF_INVALID", "runtime token must be unique in base and result packs");
        }
        final TokenSpan oldSpan = baseSpans.get(0);
        final TokenSpan newSpan = resultSpans.get(0);
        if (!Arrays.equals(Arrays.copyOfRange(baseBytes, 0, oldSpan.start()), Arrays.copyOfRange(resultBytes, 0, newSpan.start()))
            || !Arrays.equals(Arrays.copyOfRange(baseBytes, oldSpan.end(), baseBytes.length),
                Arrays.copyOfRange(resultBytes, newSpan.end(), resultBytes.length))) {
            fail("RESULT_PACK_DIFF_INVALID", "bytes outside the selected runtime token changed");
        }
        return new Replacement(resultBytes, expectedBefore, expectedAfter);
    }

    private static List<TokenSpan> locateRuntimeTokens(final byte[] bytes, final String semanticName) {
        final List<TokenSpan> matches = new ArrayList<>();
        try (JsonParser parser = StrictJson.MAPPER.getFactory().createParser(bytes)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                fail("PACK_RUNTIME_TOKEN_LOCATION_FAILED", "pack root must be a JSON object");
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) {
                    fail("PACK_RUNTIME_TOKEN_LOCATION_FAILED", "pack root contains an unexpected JSON token");
                }
                final String field = parser.currentName();
                final JsonToken valueToken = parser.nextToken();
                if ("entries".equals(field) && valueToken == JsonToken.START_ARRAY) {
                    locateEntryRuntimeTokens(bytes, parser, semanticName, matches);
                } else {
                    parser.skipChildren();
                }
            }
            return matches;
        } catch (DraftMappingException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new DraftMappingException("PACK_RUNTIME_TOKEN_LOCATION_FAILED", "could not safely locate runtime JSON token", exception);
        }
    }

    /** Parser is positioned at the START_ARRAY value of the root entries field. */
    private static void locateEntryRuntimeTokens(
        final byte[] bytes,
        final JsonParser parser,
        final String semanticName,
        final List<TokenSpan> matches
    ) throws IOException {
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (parser.currentToken() != JsonToken.START_OBJECT) {
                parser.skipChildren();
                continue;
            }
            String selectedSemantic = null;
            TokenSpan runtime = null;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) {
                    fail("PACK_RUNTIME_TOKEN_LOCATION_FAILED", "entry contains an unexpected JSON token");
                }
                final String field = parser.currentName();
                final JsonToken valueToken = parser.nextToken();
                if ("semanticName".equals(field) && valueToken == JsonToken.VALUE_STRING) {
                    selectedSemantic = parser.getText();
                } else if ("runtime".equals(field) && valueToken == JsonToken.VALUE_STRING) {
                    runtime = stringSpan(bytes, parser);
                } else {
                    parser.skipChildren();
                }
            }
            if (semanticName.equals(selectedSemantic) && runtime != null) {
                matches.add(runtime);
            }
        }
    }

    private static TokenSpan stringSpan(final byte[] bytes, final JsonParser parser) throws IOException {
        final long byteOffset = parser.currentTokenLocation().getByteOffset();
        if (byteOffset < 0 || byteOffset >= bytes.length) {
            fail("PACK_RUNTIME_TOKEN_LOCATION_FAILED", "JSON parser did not expose a byte offset");
        }
        final int offset = Math.toIntExact(byteOffset);
        TokenSpan match = null;
        // Jackson versions may report the opening quote or the first string-content byte.
        // Keep the search deliberately local and require a decoded-value match, so an escaped
        // quote or an adjacent string can never be selected as the runtime token.
        for (int candidate = Math.max(0, offset - 1); candidate <= Math.min(bytes.length - 1, offset + 1); candidate++) {
            if (bytes[candidate] != '"' || !followsValueSeparator(bytes, candidate)) {
                continue;
            }
            final TokenSpan span = stringSpanAt(bytes, candidate, parser.getText());
            if (span != null) {
                if (match != null && match.start() != span.start()) {
                    fail("PACK_RUNTIME_TOKEN_LOCATION_FAILED", "runtime token location is ambiguous");
                }
                match = span;
            }
        }
        if (match == null) {
            fail("PACK_RUNTIME_TOKEN_LOCATION_FAILED", "runtime token does not start at the parser byte offset");
        }
        return match;
    }

    private static TokenSpan stringSpanAt(final byte[] bytes, final int start, final String expectedValue) throws IOException {
        boolean escaped = false;
        for (int index = start + 1; index < bytes.length; index++) {
            final byte value = bytes[index];
            if (escaped) {
                escaped = false;
            } else if (value == '\\') {
                escaped = true;
            } else if (value == '"') {
                final String token = new String(bytes, start, index + 1 - start, StandardCharsets.UTF_8);
                final String decoded = StrictJson.MAPPER.readValue(token, String.class);
                return expectedValue.equals(decoded) ? new TokenSpan(start, index + 1, decoded) : null;
            }
        }
        fail("PACK_RUNTIME_TOKEN_LOCATION_FAILED", "runtime string token is unterminated");
        throw new AssertionError();
    }

    private static boolean followsValueSeparator(final byte[] bytes, final int start) {
        for (int index = start - 1; index >= 0; index--) {
            if (isWhitespace(bytes[index])) {
                continue;
            }
            return bytes[index] == ':';
        }
        return false;
    }

    private static byte[] encodeJsonString(final String value) {
        try {
            return StrictJson.MAPPER.writeValueAsBytes(value);
        } catch (IOException exception) {
            throw new DraftMappingException("JSON_WRITE_FAILED", "could not encode replacement JSON string", exception);
        }
    }

    private static JsonNode uniqueEntry(final JsonNode pack, final String semanticName) {
        final List<JsonNode> matches = new ArrayList<>();
        for (JsonNode entry : pack.path("entries")) {
            if (semanticName.equals(entry.path("semanticName").asText())) matches.add(entry);
        }
        if (matches.size() != 1) fail("PACK_SEMANTIC_NOT_UNIQUE", "semanticName must select exactly one entry");
        return matches.get(0);
    }

    private static boolean isWhitespace(final byte value) {
        return value == ' ' || value == '\t' || value == '\r' || value == '\n';
    }

    private static void fail(final String code, final String message) {
        throw new DraftMappingException(code, message);
    }

    record Replacement(byte[] bytes, String before, String after) {
        Replacement {
            bytes = bytes.clone();
        }
        @Override public byte[] bytes() { return bytes.clone(); }
    }

    private record TokenSpan(int start, int end, String value) { }
}
