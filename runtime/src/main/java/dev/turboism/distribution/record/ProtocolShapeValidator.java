package dev.turboism.distribution.record;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Iterator;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;

final class ProtocolShapeValidator {
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

    private ProtocolShapeValidator() {}

    static ProtocolValidationResult validate(JsonNode rootNode) {
        ObjectNode root = (ObjectNode) rootNode;
        return firstIssue(
            () -> closed(root, TOP_FIELDS, "$", "protocol"),
            () -> exactText(root, "format", "turboism.distribution.protocol"),
            () -> exactInteger(root, "schemaVersion", 1),
            () -> identifier(root, "rootId"),
            () -> text(root, "rootPath", PortableRootPath::isValid, "PROTOCOL_ROOT_PATH_INVALID"),
            () -> exactInteger(root, "protocolVersion", 1),
            () -> exactInteger(root, "javaMajor", 17),
            () -> timestamp(root, "initializedAt"),
            () -> environment(root.get("environment")),
            () -> capabilities(root.get("capabilities")),
            () -> identifier(root, "fileStoreId")
        );
    }

    private static ProtocolValidationResult environment(JsonNode node) {
        ProtocolValidationResult issue = object(node, "environment");
        if (issue != null) return issue;
        ObjectNode value = (ObjectNode) node;
        return firstIssue(
            () -> closed(value, ENVIRONMENT_FIELDS, "environment", "environment"),
            () -> exactText(value, "scope", "EXPLICIT_ROOT"),
            () -> exactText(value, "storage", "LOCAL_SINGLE_FILESYSTEM"),
            () -> exactText(value, "userMode", "SINGLE_USER")
        );
    }

    private static ProtocolValidationResult capabilities(JsonNode node) {
        ProtocolValidationResult issue = object(node, "capabilities");
        if (issue != null) return issue;
        ObjectNode value = (ObjectNode) node;
        return firstIssue(
            () -> closed(value, CAPABILITY_FIELDS, "capabilities", "capabilities"),
            () -> exactBoolean(value, "atomicReplaceMove", true),
            () -> exactBoolean(value, "fileForce", true),
            () -> enumText(value, "directorySync", Set.of("SUPPORTED", "BEST_EFFORT", "UNSUPPORTED")),
            () -> exactBoolean(value, "noFollowObjectIdentity", true)
        );
    }

    private static ProtocolValidationResult closed(
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
        return text(node, field, ProtocolShapeValidator::isTimestamp, "PROTOCOL_TIMESTAMP_INVALID");
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

    private static ProtocolValidationResult invalid(String code, String message, String path) {
        return ProtocolValidationResult.invalid(new ProtocolValidationIssue(code, message, path));
    }
}
