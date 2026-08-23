package dev.turboism.core.schema.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.core.schema.SchemaValidationError;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Command-line entry point used by the Gradle repository gate to validate plugin metadata
 * through the runtime parser-backed validator instead of shallow text matching.
 */
public final class PluginMetaValidationCli {

    private PluginMetaValidationCli() {
    }

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
            PluginMetaValidator validator =
                PluginMetaValidator.forSchemaVersion(root.path("schemaVersion").asInt(2));
            List<SchemaValidationError> errors = validator.validate(root, path.toString());
            allErrors.addAll(errors);
        }

        if (!allErrors.isEmpty()) {
            allErrors.forEach(error -> System.err.println(
                error.source() + ":" + error.path() + ": " + error.code() + " " + error.message()
            ));
            System.exit(1);
            return;
        }

        System.out.println("Plugin meta validation passed for " + args.length + " file(s).");
    }
}
