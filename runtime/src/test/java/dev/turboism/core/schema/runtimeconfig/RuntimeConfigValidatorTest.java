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
    void acceptsFileChooserHistorySectionAndSeparateExportFlag() {
        final ObjectNode root = base();
        root.withObject("hooks").withObject("startup").put("separateExportSaveDirectory", true);
        final ObjectNode section = root.putObject("fileChooserHistory");
        section.put("projectRecentDirectory", "C:/saves/project");
        section.put("exportRecentDirectory", "C:/exports");

        assertTrue(validator.validate(root, "test.json").isEmpty());
    }

    @Test
    void rejectsUnknownStartupField() {
        final ObjectNode root = base();
        root.withObject("hooks").withObject("startup").put("unknownStartupFlag", true);

        final List<String> codes = codes(root);
        assertTrue(codes.contains("RUNTIME_CONFIG_UNKNOWN_FIELD"));
    }

    @Test
    void rejectsFileChooserHistoryThatIsNotAnObject() {
        final ObjectNode root = base();
        root.putArray("fileChooserHistory").add("x");

        final List<String> codes = codes(root);
        assertTrue(codes.contains("RUNTIME_CONFIG_BAD_TYPE"));
    }

    @Test
    void rejectsNonStringDirectoryValues() {
        final ObjectNode root = base();
        root.withObject("fileChooserHistory").put("exportRecentDirectory", 42);

        final List<String> codes = codes(root);
        assertTrue(codes.contains("RUNTIME_CONFIG_BAD_TYPE"));
    }

    @Test
    void rejectsEmptyDirectoryStrings() {
        final ObjectNode root = base();
        root.withObject("fileChooserHistory").put("projectRecentDirectory", "  ");

        assertTrue(codes(root).contains("RUNTIME_CONFIG_BAD_TYPE"));
    }

    @Test
    void rejectsUnknownFileChooserHistoryField() {
        final ObjectNode root = base();
        root.withObject("fileChooserHistory").put("recentDirectories", "C:/x");

        final List<String> codes = codes(root);
        assertTrue(codes.contains("RUNTIME_CONFIG_UNKNOWN_FIELD"));
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

    @Test
    void errorCarriesTheSectionPath() {
        final ObjectNode root = base();
        root.putObject("fileChooserHistory").put("exportRecentDirectory", 1);

        final SchemaValidationError error = validator.validate(root, "test.json").stream()
            .filter(e -> e.code().equals("RUNTIME_CONFIG_BAD_TYPE"))
            .findFirst().orElseThrow();
        assertEquals("fileChooserHistory.exportRecentDirectory", error.path());
    }
}
