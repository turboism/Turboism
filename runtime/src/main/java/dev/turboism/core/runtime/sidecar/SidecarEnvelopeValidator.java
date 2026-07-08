package dev.turboism.core.runtime.sidecar;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Iterator;

/**
 * Validates that a sidecar envelope carries no host objects, no Swing/AWT,
 * no Cubism internals, no reflection handles, and no path-traversal strings.
 *
 * <p>This validator is runtime-internal and must never be exposed to plugins or the SDK.
 */
public final class SidecarEnvelopeValidator {

    public static final String PROBLEM_CODE_VALID = "SIDECAR_VALID";
    public static final String PROBLEM_CODE_HOST_OBJECT = "SIDECAR_HOST_OBJECT";
    public static final String PROBLEM_CODE_PATH_TRAVERSAL = "SIDECAR_PATH_TRAVERSAL";
    public static final String PROBLEM_CODE_REFLECTION_HANDLE = "SIDECAR_REFLECTION_HANDLE";
    public static final String PROBLEM_CODE_MALFORMED_PAYLOAD = "SIDECAR_MALFORMED_PAYLOAD";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Validates the given envelope.
     *
     * @param envelope the envelope to validate; must not be null
     * @return a structured result indicating validity and, if invalid, a problem code and message
     */
    public ValidationResult validate(final SidecarEnvelope envelope) {
        if (envelope == null) {
            return invalid(PROBLEM_CODE_MALFORMED_PAYLOAD, "envelope is null");
        }

        final JsonNode root;
        try {
            root = MAPPER.readTree(envelope.payload());
        } catch (final JsonProcessingException e) {
            return invalid(PROBLEM_CODE_MALFORMED_PAYLOAD, "payload is not valid JSON: " + e.getOriginalMessage());
        }

        return validateNode(root, "");
    }

    private ValidationResult validateNode(final JsonNode node, final String path) {
        if (node == null) {
            return valid();
        }

        if (node.isTextual()) {
            final ValidationResult textResult = validateText(node.asText(), path);
            if (!textResult.valid()) {
                return textResult;
            }
        }

        if (node.isObject()) {
            final Iterator<String> fieldNames = node.fieldNames();
            while (fieldNames.hasNext()) {
                final String fieldName = fieldNames.next();
                final JsonNode child = node.get(fieldName);
                final String childPath = path.isEmpty() ? fieldName : path + "." + fieldName;
                final ValidationResult fieldResult = validateNode(child, childPath);
                if (!fieldResult.valid()) {
                    return fieldResult;
                }
            }
        }

        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                final JsonNode child = node.get(i);
                final String childPath = path + "[" + i + "]";
                final ValidationResult elementResult = validateNode(child, childPath);
                if (!elementResult.valid()) {
                    return elementResult;
                }
            }
        }

        return valid();
    }

    private ValidationResult validateText(final String text, final String path) {
        if (containsHostObject(text)) {
            return invalid(PROBLEM_CODE_HOST_OBJECT, "Payload contains host object reference at " + path + ": " + text);
        }
        if (containsReflectionHandle(text)) {
            return invalid(PROBLEM_CODE_REFLECTION_HANDLE, "Payload contains reflection handle at " + path + ": " + text);
        }
        if (containsPathTraversal(text)) {
            return invalid(PROBLEM_CODE_PATH_TRAVERSAL, "Payload contains path traversal at " + path + ": " + text);
        }
        return valid();
    }

    private boolean containsHostObject(final String text) {
        return containsAny(text, "com.live2d.", "java.awt.", "javax.swing.");
    }

    private boolean containsReflectionHandle(final String text) {
        return containsAny(text, "java.lang.reflect.", "java.lang.invoke.", "sun.reflect.");
    }

    private boolean containsPathTraversal(final String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        final String trimmed = text.trim();
        return trimmed.contains("..")
            || trimmed.startsWith("/")
            || trimmed.startsWith("\\\\")
            || trimmed.matches("^[A-Za-z]:\\\\.*");
    }

    private boolean containsAny(final String text, final String... substrings) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (final String substring : substrings) {
            if (text.contains(substring)) {
                return true;
            }
        }
        return false;
    }

    private static ValidationResult valid() {
        return new ValidationResult(true, PROBLEM_CODE_VALID, "");
    }

    private static ValidationResult invalid(final String code, final String message) {
        return new ValidationResult(false, code, message);
    }

    /**
     * Structured result of a sidecar envelope validation.
     */
    public record ValidationResult(boolean valid, String problemCode, String problemMessage) {

        public ValidationResult {
            if (problemCode == null || problemCode.isBlank()) {
                throw new IllegalArgumentException("problemCode must not be blank");
            }
            if (problemMessage == null) {
                problemMessage = "";
            }
        }

        @Override
        public String toString() {
            return String.format("ValidationResult{valid=%s, code=%s, message=%s}", valid, problemCode, problemMessage);
        }
    }
}
