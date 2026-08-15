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
