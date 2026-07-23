package dev.turboism.mapping.verification;

import com.fasterxml.jackson.databind.JsonNode;
import dev.turboism.core.schema.AbstractJsonValidator;
import dev.turboism.core.schema.SchemaValidationError;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Validator for hash-anchored static selector evidence records. */
public final class StaticVerificationRecordValidator extends AbstractJsonValidator {

    private static final Set<String> ALLOWED_FIELDS = Set.of(
        "verificationId", "adapterSliceId", "capabilityIds", "cubismVersion", "profileId",
        "artifact", "evidenceType", "evidencePath", "owner", "verifiedBy", "verifiedAt",
        "safeMode", "status", "selectors"
    );
    private static final Set<String> ALLOWED_ARTIFACT_FIELDS = Set.of("name", "size", "sha256");
    private static final Set<String> ALLOWED_SELECTOR_FIELDS = Set.of(
        "mappingId", "alias", "kind", "ownerInternalName", "memberName", "descriptor",
        "requiredAccessFlags", "forbiddenAccessFlags", "status"
    );
    private static final Set<String> ALLOWED_KINDS = Set.of(
        "class", "constructor", "method", "field"
    );

    public StaticVerificationRecordValidator() {
        super(
            "turboism.static.verification.record",
            "STATIC_VERIFICATION_RECORD",
            1,
            ALLOWED_FIELDS
        );
    }

    @Override
    public List<SchemaValidationError> validate(final JsonNode node) {
        return validate(node, "");
    }

    @Override
    public List<SchemaValidationError> validate(final JsonNode node, final String source) {
        final List<SchemaValidationError> errors = new ArrayList<>(validateRoot(node, source));
        requireStringField(node, "verificationId", "STATIC_VERIFICATION_RECORD_MISSING", errors, source);
        requireStringField(node, "adapterSliceId", "STATIC_VERIFICATION_RECORD_MISSING", errors, source);
        requireArrayField(node, "capabilityIds", "STATIC_VERIFICATION_RECORD_MISSING", errors, source);
        requireStringField(node, "cubismVersion", "STATIC_VERIFICATION_RECORD_MISSING", errors, source);
        requireStringField(node, "profileId", "STATIC_VERIFICATION_RECORD_MISSING", errors, source);
        requireObjectField(node, "artifact", "STATIC_VERIFICATION_RECORD_MISSING", errors, source);
        requireStringField(node, "evidenceType", "STATIC_VERIFICATION_RECORD_MISSING", errors, source);
        requireStringField(node, "evidencePath", "STATIC_VERIFICATION_RECORD_MISSING", errors, source);
        requireStringField(node, "owner", "STATIC_VERIFICATION_RECORD_MISSING", errors, source);
        requireStringField(node, "status", "STATIC_VERIFICATION_RECORD_MISSING", errors, source);
        requireStringField(node, "verifiedBy", "STATIC_VERIFICATION_RECORD_MISSING", errors, source);
        requireStringField(node, "verifiedAt", "STATIC_VERIFICATION_RECORD_MISSING", errors, source);
        requireStringField(node, "safeMode", "STATIC_VERIFICATION_RECORD_MISSING", errors, source);
        requireArrayField(node, "selectors", "STATIC_VERIFICATION_RECORD_MISSING", errors, source);

        if (node.has("capabilityIds") && node.get("capabilityIds").isArray()) {
            if (node.get("capabilityIds").isEmpty()) {
                errors.add(error(
                    "STATIC_VERIFICATION_RECORD_EMPTY_CAPABILITIES",
                    "capabilityIds must contain at least one capability",
                    "capabilityIds",
                    source
                ));
            }
            final java.util.Set<String> seenCapabilities = new java.util.HashSet<>();
            for (int index = 0; index < node.get("capabilityIds").size(); index++) {
                final JsonNode capability = node.get("capabilityIds").get(index);
                if (!capability.isTextual() || capability.asText().isBlank()) {
                    errors.add(error(
                        "STATIC_VERIFICATION_RECORD_BAD_CAPABILITY",
                        "capabilityIds entries must be non-blank strings",
                        "capabilityIds[" + index + "]",
                        source
                    ));
                } else if (!seenCapabilities.add(capability.asText())) {
                    errors.add(error(
                        "STATIC_VERIFICATION_RECORD_DUPLICATE_CAPABILITY",
                        "capabilityIds must not contain duplicates",
                        "capabilityIds[" + index + "]",
                        source
                    ));
                }
            }
        }
        if (node.has("evidencePath") && node.get("evidencePath").isTextual()
            && isAbsoluteOrTraversingPath(node.get("evidencePath").asText())) {
            errors.add(error(
                "STATIC_VERIFICATION_RECORD_ABSOLUTE_PATH",
                "evidencePath must be a tracked relative path without traversal",
                "evidencePath",
                source
            ));
        }
        if (node.has("cubismVersion") && node.get("cubismVersion").isTextual()
            && !node.get("cubismVersion").asText().matches("[0-9]+\\.[0-9]+\\.[0-9]+")) {
            errors.add(error(
                "STATIC_VERIFICATION_RECORD_BAD_VERSION",
                "cubismVersion must be one exact MAJOR.MINOR.PATCH version",
                "cubismVersion",
                source
            ));
        }
        if (node.has("evidenceType") && !"JAR_METADATA".equals(node.get("evidenceType").asText())) {
            errors.add(error(
                "STATIC_VERIFICATION_RECORD_BAD_EVIDENCE_TYPE",
                "evidenceType must be JAR_METADATA",
                "evidenceType",
                source
            ));
        }
        if (node.has("status") && !"VERIFIED_STATIC".equals(node.get("status").asText())) {
            errors.add(error(
                "STATIC_VERIFICATION_RECORD_BAD_STATUS",
                "status must be VERIFIED_STATIC",
                "status",
                source
            ));
        }
        if (node.has("verifiedAt") && node.get("verifiedAt").isTextual()
            && !isIsoTimestamp(node.get("verifiedAt").asText())) {
            errors.add(error(
                "STATIC_VERIFICATION_RECORD_BAD_TIME",
                "verifiedAt must be a UTC ISO-8601 timestamp",
                "verifiedAt",
                source
            ));
        }
        validateArtifact(node.get("artifact"), errors, source);
        validateSelectors(node.get("selectors"), errors, source);
        return errors;
    }

    private void validateArtifact(
        final JsonNode artifact,
        final List<SchemaValidationError> errors,
        final String source
    ) {
        if (artifact == null || !artifact.isObject()) {
            return;
        }
        artifact.fieldNames().forEachRemaining(field -> {
            if (!ALLOWED_ARTIFACT_FIELDS.contains(field)) {
                errors.add(error(
                    "STATIC_VERIFICATION_RECORD_ARTIFACT_UNKNOWN_FIELD",
                    "Unknown artifact field: " + field,
                    "artifact." + field,
                    source
                ));
            }
        });
        if (!artifact.has("name") || !artifact.get("name").isTextual() || artifact.get("name").asText().isBlank()) {
            errors.add(error(
                "STATIC_VERIFICATION_RECORD_BAD_ARTIFACT",
                "artifact.name is required",
                "artifact.name",
                source
            ));
        } else if (isAbsoluteOrTraversingPath(artifact.get("name").asText())
            || artifact.get("name").asText().contains("/")) {
            errors.add(error(
                "STATIC_VERIFICATION_RECORD_ABSOLUTE_PATH",
                "artifact.name must be a file name, not a path",
                "artifact.name",
                source
            ));
        }
        if (!artifact.has("size") || !artifact.get("size").canConvertToLong()
            || artifact.get("size").asLong(-1) < 0) {
            errors.add(error(
                "STATIC_VERIFICATION_RECORD_BAD_ARTIFACT",
                "artifact.size must be a non-negative integer",
                "artifact.size",
                source
            ));
        }
        if (!artifact.has("sha256") || !artifact.get("sha256").isTextual()
            || !artifact.get("sha256").asText().matches("[0-9a-f]{64}")) {
            errors.add(error(
                "STATIC_VERIFICATION_RECORD_BAD_ARTIFACT",
                "artifact.sha256 must be 64 lowercase hexadecimal characters",
                "artifact.sha256",
                source
            ));
        }
    }

    private void validateSelectors(
        final JsonNode selectors,
        final List<SchemaValidationError> errors,
        final String source
    ) {
        if (selectors == null || !selectors.isArray()) {
            return;
        }
        if (selectors.isEmpty()) {
            errors.add(error(
                "STATIC_VERIFICATION_RECORD_EMPTY_SELECTORS",
                "selectors must contain at least one entry",
                "selectors",
                source
            ));
        }
        for (int index = 0; index < selectors.size(); index++) {
            final JsonNode selector = selectors.get(index);
            final String path = "selectors[" + index + "]";
            if (!selector.isObject()) {
                errors.add(error(
                    "STATIC_VERIFICATION_RECORD_BAD_SELECTOR",
                    "selector must be an object",
                    path,
                    source
                ));
                continue;
            }
            selector.fieldNames().forEachRemaining(field -> {
                if (!ALLOWED_SELECTOR_FIELDS.contains(field)) {
                    errors.add(error(
                        "STATIC_VERIFICATION_RECORD_SELECTOR_UNKNOWN_FIELD",
                        "Unknown selector field: " + field,
                        path + "." + field,
                        source
                    ));
                }
            });
            requireSelectorText(selector, "mappingId", path, errors, source);
            requireSelectorText(selector, "alias", path, errors, source);
            requireSelectorText(selector, "kind", path, errors, source);
            requireSelectorText(selector, "ownerInternalName", path, errors, source);
            final String kind = selector.has("kind") && selector.get("kind").isTextual()
                ? selector.get("kind").asText()
                : "";
            if (!ALLOWED_KINDS.contains(kind)) {
                errors.add(error(
                    "STATIC_VERIFICATION_RECORD_BAD_SELECTOR",
                    "selector kind must be class, constructor, method, or field",
                    path + ".kind",
                    source
                ));
            }
            if (selector.has("ownerInternalName") && selector.get("ownerInternalName").isTextual()) {
                final String owner = selector.get("ownerInternalName").asText();
                if (owner.startsWith("/") || owner.endsWith("/") || owner.contains(".")
                    || owner.contains("..") || owner.contains("\\")) {
                    errors.add(error(
                        "STATIC_VERIFICATION_RECORD_BAD_SELECTOR",
                        "ownerInternalName must be a JVM internal name",
                        path + ".ownerInternalName",
                        source
                    ));
                }
            }
            if ("constructor".equals(kind) || "method".equals(kind) || "field".equals(kind)) {
                requireSelectorText(selector, "memberName", path, errors, source);
                requireSelectorText(selector, "descriptor", path, errors, source);
            }
            if ("constructor".equals(kind)
                && selector.has("memberName")
                && selector.get("memberName").isTextual()
                && !"<init>".equals(selector.get("memberName").asText())) {
                errors.add(error(
                    "STATIC_VERIFICATION_RECORD_BAD_SELECTOR",
                    "constructor selector memberName must be <init>",
                    path + ".memberName",
                    source
                ));
            }
            requireNonNegativeInteger(selector, "requiredAccessFlags", path, errors, source);
            requireNonNegativeInteger(selector, "forbiddenAccessFlags", path, errors, source);
            if (selector.has("requiredAccessFlags") && selector.has("forbiddenAccessFlags")
                && selector.get("requiredAccessFlags").canConvertToInt()
                && selector.get("forbiddenAccessFlags").canConvertToInt()
                && (selector.get("requiredAccessFlags").asInt()
                    & selector.get("forbiddenAccessFlags").asInt()) != 0) {
                errors.add(error(
                    "STATIC_VERIFICATION_RECORD_BAD_SELECTOR",
                    "required and forbidden access flags must not overlap",
                    path,
                    source
                ));
            }
            if (!selector.has("status") || !"VERIFIED_STATIC".equals(selector.get("status").asText())) {
                errors.add(error(
                    "STATIC_VERIFICATION_RECORD_BAD_STATUS",
                    "selector status must be VERIFIED_STATIC",
                    path + ".status",
                    source
                ));
            }
        }
    }

    private void requireSelectorText(
        final JsonNode selector,
        final String field,
        final String path,
        final List<SchemaValidationError> errors,
        final String source
    ) {
        if (!selector.has(field) || !selector.get(field).isTextual() || selector.get(field).asText().isBlank()) {
            errors.add(error(
                "STATIC_VERIFICATION_RECORD_BAD_SELECTOR",
                "selector " + field + " is required",
                path + "." + field,
                source
            ));
        }
    }

    private void requireNonNegativeInteger(
        final JsonNode selector,
        final String field,
        final String path,
        final List<SchemaValidationError> errors,
        final String source
    ) {
        if (!selector.has(field) || !selector.get(field).isIntegralNumber()
            || selector.get(field).asInt(-1) < 0) {
            errors.add(error(
                "STATIC_VERIFICATION_RECORD_BAD_SELECTOR",
                "selector " + field + " must be a non-negative integer",
                path + "." + field,
                source
            ));
        }
    }

    private static boolean isAbsoluteOrTraversingPath(final String value) {
        if (value.contains("\\") || value.contains(":") || value.contains("..")) {
            return true;
        }
        return value.startsWith("/");
    }

    private static boolean isIsoTimestamp(final String value) {
        try {
            java.time.Instant.parse(value);
            return true;
        } catch (java.time.format.DateTimeParseException exception) {
            return false;
        }
    }
}
