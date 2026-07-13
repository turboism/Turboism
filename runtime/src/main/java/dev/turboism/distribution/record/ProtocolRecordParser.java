package dev.turboism.distribution.record;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.CharacterCodingException;

final class ProtocolRecordParser {
    private static final int MAX_BYTES = 65_536;

    private ProtocolRecordParser() {}

    static ProtocolValidationResult parse(byte[] input) {
        ProtocolValidationResult envelopeIssue = validateEnvelope(input);
        if (envelopeIssue != null) return envelopeIssue;
        String source;
        try {
            source = ProtocolStrictJson.decode(input);
        } catch (CharacterCodingException exception) {
            return invalid("PROTOCOL_UTF8_INVALID", "Protocol record is not valid UTF-8", "$");
        }
        if (source.isBlank()) return invalid("PROTOCOL_EMPTY", "Protocol record is empty", "$");
        JsonNode root = ProtocolStrictJson.parse(input);
        if (root == null) return invalid("PROTOCOL_JSON_INVALID", "Protocol record JSON is malformed", "$");
        if (!root.isObject()) {
            return invalid("PROTOCOL_ROOT_TYPE_INVALID", "Protocol record root must be an object", "$");
        }
        ProtocolValidationResult issue = ProtocolShapeValidator.validate(root);
        return issue == null ? ProtocolValidationResult.valid(new ProtocolRecord(source)) : issue;
    }

    private static ProtocolValidationResult validateEnvelope(byte[] input) {
        if (input == null || input.length == 0) return invalid("PROTOCOL_EMPTY", "Protocol record is empty", "$");
        if (input.length > MAX_BYTES) return invalid("PROTOCOL_TOO_LARGE", "Protocol record exceeds 65,536 bytes", "$");
        if (ProtocolStrictJson.hasBom(input)) return invalid("PROTOCOL_BOM", "UTF-8 BOM is forbidden", "$");
        return null;
    }

    private static ProtocolValidationResult invalid(String code, String message, String path) {
        return ProtocolValidationResult.invalid(new ProtocolValidationIssue(code, message, path));
    }
}
