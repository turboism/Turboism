package dev.turboism.distribution.record;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Iterator;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;

final class ProtocolRecordParser {
    private static final int MAX_BYTES = 65_536;
    private static final Set<String> TOP_FIELDS = Set.of(
        "format", "schemaVersion", "rootId", "rootPath", "protocolVersion", "javaMajor",
        "initializedAt", "environment", "capabilities", "fileStoreId"
    );
    private static final Set<String> ENVIRONMENT_FIELDS = Set.of("scope", "storage", "userMode");
    private static final Set<String> CAPABILITY_FIELDS = Set.of(
        "atomicReplaceMove", "fileForce", "directorySync", "noFollowObjectIdentity"
    );
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]{2,63}");
    private static final Pattern TIMESTAMP = Pattern.compile(
        "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?Z"
    );
    private static final ObjectMapper JSON = strictMapper();

    private ProtocolRecordParser() {}

    static ProtocolValidationResult parse(byte[] input) {
        ProtocolValidationResult envelopeIssue = validateEnvelope(input);
        if (envelopeIssue != null) return envelopeIssue;
        String source;
        try {
            source = decode(input);
        } catch (CharacterCodingException exception) {
            return invalid("PROTOCOL_UTF8_INVALID", "Protocol record is not valid UTF-8", "$");
        }
        if (source.isBlank()) return invalid("PROTOCOL_EMPTY", "Protocol record is empty", "$");
        JsonNode root = parseJson(input);
        if (root == null) return invalid("PROTOCOL_JSON_INVALID", "Protocol record JSON is malformed", "$");
        if (!root.isObject()) {
            return invalid("PROTOCOL_ROOT_TYPE_INVALID", "Protocol record root must be an object", "$");
        }
        ProtocolValidationResult shapeIssue = validateClosedObject((ObjectNode) root, TOP_FIELDS, "$", "protocol");
        if (shapeIssue != null) return shapeIssue;
        return validateFields((ObjectNode) root, source);
    }

    private static ProtocolValidationResult validateEnvelope(byte[] input) {
        if (input == null || input.length == 0) return invalid("PROTOCOL_EMPTY", "Protocol record is empty", "$");
        if (input.length > MAX_BYTES) return invalid("PROTOCOL_TOO_LARGE", "Protocol record exceeds 65,536 bytes", "$");
        if (hasBom(input)) return invalid("PROTOCOL_BOM", "UTF-8 BOM is forbidden", "$");
        return null;
    }

    private static JsonNode parseJson(byte[] input) {
        try {
            return JSON.readTree(input);
        } catch (Exception exception) {
            return null;
        }
    }

    private static ProtocolValidationResult validateFields(ObjectNode root, String source) {
        ProtocolValidationResult issue = firstIssue(
            () -> exactText(root, "format", "turboism.distribution.protocol"),
            () -> exactInteger(root, "schemaVersion", 1),
            () -> identifier(root, "rootId"),
            () -> text(root, "rootPath", value -> !value.isEmpty(), "PROTOCOL_ROOT_PATH_INVALID"),
            () -> exactInteger(root, "protocolVersion", 1),
            () -> exactInteger(root, "javaMajor", 17),
            () -> timestamp(root, "initializedAt"),
            () -> validateEnvironment(root.get("environment")),
            () -> validateCapabilities(root.get("capabilities")),
            () -> identifier(root, "fileStoreId")
        );
        return issue == null ? ProtocolValidationResult.valid(new ProtocolRecord(source)) : issue;
    }

    private static ProtocolValidationResult validateEnvironment(JsonNode node) {
        ProtocolValidationResult issue = object(node, "environment");
        if (issue != null) return issue;
        ObjectNode value = (ObjectNode) node;
        return firstIssue(
            () -> validateClosedObject(value, ENVIRONMENT_FIELDS, "environment", "environment"),
            () -> exactText(value, "scope", "EXPLICIT_ROOT"),
            () -> exactText(value, "storage", "LOCAL_SINGLE_FILESYSTEM"),
            () -> exactText(value, "userMode", "SINGLE_USER")
        );
    }

    private static ProtocolValidationResult validateCapabilities(JsonNode node) {
        ProtocolValidationResult issue = object(node, "capabilities");
        if (issue != null) return issue;
        ObjectNode value = (ObjectNode) node;
        return firstIssue(
            () -> validateClosedObject(value, CAPABILITY_FIELDS, "capabilities", "capabilities"),
            () -> exactBoolean(value, "atomicReplaceMove", true),
            () -> exactBoolean(value, "fileForce", true),
            () -> enumText(value, "directorySync", Set.of("SUPPORTED", "BEST_EFFORT", "UNSUPPORTED")),
            () -> exactBoolean(value, "noFollowObjectIdentity", true)
        );
    }

    private static ProtocolValidationResult validateClosedObject(
        ObjectNode node, Set<String> expected, String path, String label
    ) {
        Iterator<String> fields = node.fieldNames();
        int count = 0;
        while (fields.hasNext()) {
            String field = fields.next();
            count++;
            if (!expected.contains(field)) {
                return invalid("PROTOCOL_UNKNOWN_FIELD", "Unknown " + label + " field: " + field, child(path, field));
            }
        }
        if (count == expected.size()) return null;
        for (String field : expected) {
            if (!node.has(field)) {
                return invalid("PROTOCOL_FIELD_MISSING", "Missing " + label + " field: " + field, child(path, field));
            }
        }
        return null;
    }

    @SafeVarargs
    private static ProtocolValidationResult firstIssue(Supplier<ProtocolValidationResult>... validations) {
        for (Supplier<ProtocolValidationResult> validation : validations) {
            ProtocolValidationResult issue = validation.get();
            if (issue != null) return issue;
        }
        return null;
    }

    private static ProtocolValidationResult exactText(ObjectNode node, String field, String expected) {
        return text(node, field, expected::equals, "PROTOCOL_VALUE_INVALID");
    }

    private static ProtocolValidationResult enumText(ObjectNode node, String field, Set<String> expected) {
        return text(node, field, expected::contains, "PROTOCOL_VALUE_INVALID");
    }

    private static ProtocolValidationResult identifier(ObjectNode node, String field) {
        return text(node, field, value -> ID.matcher(value).matches(), "PROTOCOL_ID_INVALID");
    }

    private static ProtocolValidationResult timestamp(ObjectNode node, String field) {
        return text(node, field, ProtocolRecordParser::isTimestamp, "PROTOCOL_TIMESTAMP_INVALID");
    }

    private static ProtocolValidationResult text(
        ObjectNode node, String field, Predicate<String> predicate, String invalidCode
    ) {
        JsonNode value = node.get(field);
        if (!value.isTextual()) return invalid("PROTOCOL_TYPE_INVALID", field + " must be a string", field);
        if (!predicate.test(value.textValue())) return invalid(invalidCode, "Invalid value for " + field, field);
        return null;
    }

    private static ProtocolValidationResult exactInteger(ObjectNode node, String field, int expected) {
        JsonNode value = node.get(field);
        if (!value.isIntegralNumber()) return invalid("PROTOCOL_TYPE_INVALID", field + " must be an integer", field);
        if (!value.canConvertToInt() || value.intValue() != expected) {
            return invalid("PROTOCOL_VALUE_INVALID", "Invalid value for " + field, field);
        }
        return null;
    }

    private static ProtocolValidationResult exactBoolean(ObjectNode node, String field, boolean expected) {
        JsonNode value = node.get(field);
        if (!value.isBoolean()) return invalid("PROTOCOL_TYPE_INVALID", field + " must be a boolean", field);
        return value.booleanValue() == expected
            ? null : invalid("PROTOCOL_VALUE_INVALID", "Invalid value for " + field, field);
    }

    private static ProtocolValidationResult object(JsonNode value, String field) {
        return value.isObject()
            ? null : invalid("PROTOCOL_TYPE_INVALID", field + " must be an object", field);
    }

    private static boolean isTimestamp(String value) {
        if (!TIMESTAMP.matcher(value).matches()) return false;
        try {
            return OffsetDateTime.parse(value).getOffset().equals(ZoneOffset.UTC);
        } catch (DateTimeException exception) {
            return false;
        }
    }

    private static String child(String path, String field) {
        return "$".equals(path) ? field : path + "." + field;
    }

    private static ObjectMapper strictMapper() {
        JsonFactory factory = JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
        return new ObjectMapper(factory).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    private static String decode(byte[] input) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(input)).toString();
    }

    private static boolean hasBom(byte[] input) {
        return input.length >= 3 && (input[0] & 255) == 0xef && (input[1] & 255) == 0xbb && (input[2] & 255) == 0xbf;
    }

    private static ProtocolValidationResult invalid(String code, String message, String path) {
        return ProtocolValidationResult.invalid(new ProtocolValidationIssue(code, message, path));
    }
}
