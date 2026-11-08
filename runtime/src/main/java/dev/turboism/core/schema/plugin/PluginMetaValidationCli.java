package dev.turboism.core.schema.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.core.lifecycle.CapabilityAdmission;
import dev.turboism.core.schema.SchemaValidationError;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Command-line entry point used by the Gradle repository gate to validate plugin metadata
 * through the runtime parser-backed validator instead of shallow text matching.
 */
public final class PluginMetaValidationCli {

    private PluginMetaValidationCli() {}

    /**
     * Validates every supplied {@code plugin.json} and reports the verdict through the process exit
     * code, for the Gradle repository gate to act on.
     *
     * <p>All files are validated before anything is reported, so one bad descriptor does not hide the
     * others. The validator is chosen per file from that file's own {@code schemaVersion}, defaulting
     * to {@code 2} when the field is absent or non-numeric. Findings go to standard error as
     * {@code source:path: code message}; the success line goes to standard output.
     *
     * <p>Exits with status {@code 1} when no arguments were given or when any file produced a
     * finding, and returns normally otherwise. Note that a file that cannot be read or is not valid
     * JSON aborts with an {@code IOException} rather than a finding.
     *
     * @param args paths to the {@code plugin.json} files to validate; at least one is required
     * @throws IOException if any file cannot be read or parsed as JSON
     */
    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.err.println("No plugin.json files were provided for validation.");
            System.exit(1);
            return;
        }

        ObjectMapper mapper = new ObjectMapper();
        List<SchemaValidationError> allErrors = new ArrayList<>();

        for (String arg : args) {
            Path path = Path.of(arg);
            JsonNode root = mapper.readTree(path.toFile());
            PluginMetaValidator validator = PluginMetaValidator.forSchemaVersion(
                    root.path("schemaVersion").asInt(2));
            List<SchemaValidationError> errors = validator.validate(root, path.toString());
            allErrors.addAll(errors);
            allErrors.addAll(capabilityPermissionErrors(root, path.toString()));
        }

        if (!allErrors.isEmpty()) {
            allErrors.forEach(error -> System.err.println(
                    error.source() + ":" + error.path() + ": " + error.code() + " " + error.message()));
            System.exit(1);
            return;
        }

        System.out.println("Plugin meta validation passed for " + args.length + " file(s).");
    }

    /**
     * Rejects a manifest whose declared capability is gated on a permission it does not declare.
     *
     * <p>A runtime-gated surface is admitted by capability name and then permission-checked on every
     * call. A plugin that declares the capability without the permission is therefore admitted and
     * then denied on every operation: the feature silently never works, and the only trace is a
     * WARNING audit event per attempt. Catching the mismatch here turns a silent runtime denial into
     * a build failure.</p>
     *
     * @param root the parsed {@code plugin.json}
     * @param source the descriptor path used in findings
     * @return one finding per capability whose required permissions are not all declared
     */
    private static List<SchemaValidationError> capabilityPermissionErrors(final JsonNode root, final String source) {
        final List<SchemaValidationError> errors = new ArrayList<>();
        final Set<String> declared = new HashSet<>();
        for (final JsonNode permission : root.path("permissions")) {
            final String id = permission.path("id").asText("");
            if (!id.isBlank()) {
                declared.add(id);
            }
        }
        for (final JsonNode capability : root.path("capabilities")) {
            final String id = capability.asText("");
            final Set<String> required = CapabilityAdmission.requiredPermissions(id);
            if (required.isEmpty()) {
                continue;
            }
            final Set<String> missing = new HashSet<>(required);
            missing.removeAll(declared);
            if (!missing.isEmpty()) {
                errors.add(new SchemaValidationError(
                        "capability-permission-mismatch",
                        dev.turboism.core.schema.SchemaValidationError.Severity.ERROR,
                        "capability '" + id + "' is gated on undeclared permission(s) " + missing
                                + "; the surface would be admitted and then denied on every call",
                        "/capabilities",
                        source));
            }
        }
        return errors;
    }
}
