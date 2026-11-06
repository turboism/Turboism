package dev.turboism.core.descriptor;

import com.fasterxml.jackson.databind.JsonNode;
import dev.turboism.core.schema.SchemaValidationError;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ordered sink for the findings one manifest read produced.
 *
 * <p>Findings are appended in read order, so {@link #first()} is exactly the failure {@code
 * PluginDescriptorParser.parse} would raise, and {@link #all()} is the gate surface that reports
 * every problem of a manifest instead of only the first one.</p>
 */
final class Findings {

    private final String source;
    private final List<SchemaValidationError> errors = new ArrayList<>();

    /**
     * @param source label attributed to every finding, for diagnostics only
     */
    Findings(final String source) {
        this.source = source;
    }

    /**
     * Appends one error finding.
     *
     * @param code stable diagnostic code
     * @param message human-readable explanation
     * @param path location inside the validated document
     */
    void add(final String code, final String message, final String path) {
        errors.add(new SchemaValidationError(code, SchemaValidationError.Severity.ERROR, message, path, source));
    }

    /**
     * Rejects every field of a nested object that the schema does not admit, in document order.
     *
     * @param object the object to inspect
     * @param allowed the field names the surrounding declaration admits
     * @param code stable diagnostic code reported per unknown field
     * @param base path prefix of the inspected object
     */
    void rejectUnknown(final JsonNode object, final Set<String> allowed, final String code, final String base) {
        rejectUnknown(object, allowed, code, base, "Unknown field: ");
    }

    /**
     * Rejects every top-level field no tier admits, in document order; it is reported apart from
     * nested unknown fields because the root object is where a version mismatch usually shows.
     *
     * @param root the manifest root object
     * @param allowed the field names the governing tier admits
     * @param code stable diagnostic code reported per unknown field
     */
    void rejectUnknownTopLevel(final JsonNode root, final Set<String> allowed, final String code) {
        rejectUnknown(root, allowed, code, "", "Unknown top-level field: ");
    }

    private void rejectUnknown(
            final JsonNode object,
            final Set<String> allowed,
            final String code,
            final String base,
            final String message) {
        final Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
        while (fields.hasNext()) {
            final String name = fields.next().getKey();
            if (!allowed.contains(name)) {
                add(code, message + name, base.isEmpty() ? name : base + "." + name);
            }
        }
    }

    /** @return true when the read produced no finding */
    boolean isEmpty() {
        return errors.isEmpty();
    }

    /**
     * @return the first finding in read order, which is the one a failing parse reports
     * @throws IllegalStateException if nothing was found
     */
    SchemaValidationError first() {
        if (errors.isEmpty()) {
            throw new IllegalStateException("no finding to report");
        }
        return errors.get(0);
    }

    /** @return every finding in read order, immutable */
    List<SchemaValidationError> all() {
        return List.copyOf(errors);
    }

    /**
     * @return the first finding as the checked parse failure callers branch on
     * @throws IllegalStateException if nothing was found
     */
    DescriptorParseException failure() {
        final SchemaValidationError first = first();
        return new DescriptorParseException(first.code(), first.message(), first.path());
    }
}
