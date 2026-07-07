package dev.turboism.core.schema.diagnostic;

import com.fasterxml.jackson.databind.JsonNode;
import dev.turboism.core.schema.AbstractJsonValidator;
import dev.turboism.core.schema.SchemaValidationError;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Validator for turboism.diagnostic.report v1.
 */
public final class DiagnosticReportValidator extends AbstractJsonValidator {

    private static final Set<String> ALLOWED_FIELDS = Set.of(
        "createdAt", "worktreeId", "problems"
    );
    private static final Set<String> ALLOWED_SEVERITIES = Set.of("ERROR", "WARNING", "INFO");

    public DiagnosticReportValidator() {
        super("turboism.diagnostic.report", "DIAGNOSTIC_REPORT", 1, ALLOWED_FIELDS);
    }

    @Override
    public List<SchemaValidationError> validate(JsonNode node) {
        return validate(node, "");
    }

    @Override
    public List<SchemaValidationError> validate(JsonNode node, String source) {
        List<SchemaValidationError> errors = new ArrayList<>(validateRoot(node, source));
        requireStringField(node, "createdAt", "DIAGNOSTIC_REPORT_MISSING", errors, source);
        requireStringField(node, "worktreeId", "DIAGNOSTIC_REPORT_MISSING", errors, source);
        requireArrayField(node, "problems", "DIAGNOSTIC_REPORT_MISSING", errors, source);

        if (node.has("createdAt") && !node.get("createdAt").isNull()) {
            String ts = node.get("createdAt").asText("");
            try {
                Instant.parse(ts);
            } catch (DateTimeParseException e) {
                errors.add(error("DIAGNOSTIC_REPORT_BAD_TIMESTAMP", "createdAt must be a valid UTC ISO-8601 timestamp: " + ts, "createdAt", source));
            }
        }

        if (node.has("problems") && node.get("problems").isArray()) {
            node.get("problems").forEach(p -> validateProblem(p, errors, source));
        }

        return errors;
    }

    private void validateProblem(JsonNode p, List<SchemaValidationError> errors, String source) {
        if (!p.isObject()) return;
        for (String field : List.of("code", "severity", "message", "path")) {
            if (!p.has(field) || p.get(field).isNull() || !p.get(field).isTextual() || p.get(field).asText().isBlank()) {
                errors.add(error("DIAGNOSTIC_REPORT_MISSING", "Missing required problem field: " + field, "problems[]." + field, source));
            }
        }
        if (p.has("severity") && !p.get("severity").isNull() && !ALLOWED_SEVERITIES.contains(p.get("severity").asText(""))) {
            errors.add(error("DIAGNOSTIC_REPORT_BAD_SEVERITY", "severity must be one of " + ALLOWED_SEVERITIES, "problems[].severity", source));
        }
    }
}
