package dev.turboism.distribution.record;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.runtime.json.RuntimeJson;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

final class ProtocolStrictJson {
    private static final ObjectMapper JSON = mapper();

    private ProtocolStrictJson() {}

    static JsonNode parse(byte[] input) {
        try {
            return JSON.readTree(input);
        } catch (Exception exception) {
            return null;
        }
    }

    static String decode(byte[] input) throws CharacterCodingException {
        return StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(input))
                .toString();
    }

    static boolean hasBom(byte[] input) {
        return input.length >= 3 && (input[0] & 255) == 0xef && (input[1] & 255) == 0xbb && (input[2] & 255) == 0xbf;
    }

    private static ObjectMapper mapper() {
        return RuntimeJson.strict();
    }
}
