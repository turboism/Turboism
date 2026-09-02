package dev.turboism.core.schema.runtimeconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.turboism.core.schema.SchemaValidationError;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeConfigValidatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FORMAT = "turboism.runtime.config";

    private final RuntimeConfigValidator validator = new RuntimeConfigValidator();

    private ObjectNode base() {
        final ObjectNode root = JSON.createObjectNode();
        root.put("format", FORMAT);
        root.put("schemaVersion", 1);
        root.put("worktreeId", "validator-test");
        root.putArray("pluginDirs").add("plugins");
        root.put("logLevel", "INFO");
        root.put("maxLogStorageMiB", 100);
        root.put("safeMode", false);
        final ObjectNode hooks = root.putObject("hooks");
        hooks.putArray("disabledIds");
        hooks.putArray("denylistedClasses");
        return root;
    }

    private List<String> codes(final ObjectNode root) {
        return validator.validate(root, "test.json").stream()
            .map(SchemaValidationError::code)
            .toList();
    }

    @Test
    void acceptsSeparateExportSaveDirectoryFlag() {
        final ObjectNode root = base();
        root.withObject("hooks").withObject("startup").put("separateExportSaveDirectory", true);

        assertTrue(validator.validate(root, "test.json").isEmpty());
    }

    @Test
    void acceptsCubismJvmLauncherSelection() {
        final ObjectNode root = base();
        root.withObject("launcher").put("cubismJvm", "graalvm");

        assertTrue(validator.validate(root, "test.json").isEmpty());
    }

    @Test
    void acceptsOptionalCustomGraalVmPath() {
        final ObjectNode root = base();
        root.withObject("launcher").put(
            "graalVmPath",
            "C:\\Program Files\\GraalVM\\bin\\java.exe"
        );

        assertTrue(validator.validate(root, "test.json").isEmpty());
    }

    @Test
    void rejectsBlankOrNonTextCustomGraalVmPath() {
        final ObjectNode blank = base();
        blank.withObject("launcher").put("graalVmPath", "  ");
        final ObjectNode nonText = base();
        nonText.withObject("launcher").put("graalVmPath", 42);

        assertTrue(codes(blank).contains("RUNTIME_CONFIG_BAD_GRAALVM_PATH"));
        assertTrue(codes(nonText).contains("RUNTIME_CONFIG_BAD_GRAALVM_PATH"));
    }

    @Test
    void rejectsUnknownCubismJvmLauncherSelection() {
        final ObjectNode root = base();
        root.withObject("launcher").put("cubismJvm", "other");

        assertTrue(codes(root).contains("RUNTIME_CONFIG_BAD_CUBISM_JVM"));
    }

    @Test
    void rejectsUnknownStartupField() {
        final ObjectNode root = base();
        root.withObject("hooks").withObject("startup").put("unknownStartupFlag", true);

        final List<String> codes = codes(root);
        assertTrue(codes.contains("RUNTIME_CONFIG_UNKNOWN_FIELD"));
    }

    @Test
    void rejectsFileChooserHistorySectionAsUnknownField() {
        final ObjectNode root = base();
        root.putObject("fileChooserHistory").put("exportRecentDirectory", "C:/exports");

        assertTrue(codes(root).contains("RUNTIME_CONFIG_UNKNOWN_FIELD"));
    }

    @Test
    void acceptsAbsentSections() {
        assertTrue(validator.validate(base(), "test.json").isEmpty());
    }

    @Test
    void rejectsUnknownTopLevelField() {
        final ObjectNode root = base();
        root.put("mysteryField", 1);

        assertTrue(codes(root).contains("RUNTIME_CONFIG_UNKNOWN_FIELD"));
    }

    @Test
    void rejectsSeparateExportFlagOfWrongType() {
        final ObjectNode root = base();
        root.withObject("hooks").withObject("startup").put("separateExportSaveDirectory", "yes");
        assertTrue(codes(root).contains("RUNTIME_CONFIG_BAD_TYPE"));
    }
}
