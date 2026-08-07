package dev.turboism.tests.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.core.schema.JsonSchemaValidator;
import dev.turboism.core.schema.SchemaValidationError;
import dev.turboism.core.schema.dependency.DependencyValidator;
import dev.turboism.core.schema.diagnostic.DiagnosticReportValidator;
import dev.turboism.core.schema.permission.PermissionValidator;
import dev.turboism.core.schema.plugin.PluginMetaValidator;
import dev.turboism.core.schema.runtimeconfig.RuntimeConfigValidator;
import dev.turboism.core.schema.version.VersionRangeValidator;
import dev.turboism.hook.spec.HookSpecValidator;
import dev.turboism.mapping.draft.DraftMappingException;
import dev.turboism.mapping.draft.MappingReviewValidator;
import dev.turboism.mapping.draft.MappingUpdateCandidateValidator;
import dev.turboism.mapping.draft.MappingUpdateDiffValidator;
import dev.turboism.mapping.draft.StrictJson;
import dev.turboism.mapping.schema.MappingPackValidator;
import dev.turboism.mapping.schema.ProfileValidator;
import dev.turboism.mapping.verification.StaticVerificationRecordValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates all schema fixtures against the runtime validators.
 */
class SchemaFixtureValidationTest {

    private static final Path FIXTURES = Paths.get(System.getProperty("projectRoot", System.getProperty("user.dir")))
        .resolve("testframework/src/main/resources/fixtures/schema");

    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest(name = "valid fixture: {0} ({1})")
    @CsvSource({
        "minimal.json, plugin-meta-v2, plugin-meta",
        "with-required-dependency.json, plugin-meta-v2, plugin-meta",
        "host-unsafe-permission.json, plugin-meta-v2, plugin-meta",
        "dialog-automate-permission.json, plugin-meta-v2, plugin-meta",
        "required.json, dependency-v1, dependency",
        "half-open.json, version-range-v1, version-range",
        "context-menu-contribute.json, permission-v1, permission",
        "recent-file-read.json, permission-v1, permission",
        "recent-preview-contribute.json, permission-v1, permission",
        "menu.json, permission-v1, permission",
        "parameter-read.json, permission-v1, permission",
        "mesh-read.json, permission-v1, permission",
        "dialog-automate.json, permission-v1, permission",
        "default.json, runtime-config-v1, runtime-config",
        "minimal.json, diagnostic-report-v1, diagnostic-report",
        "draft.json, mapping-pack-v1, mapping-pack",
        "cubism-5-3-02.json, profile-v1, profile",
        "cubism-5-3-02-low-risk.json, static-verification-record-v1, static-verification-record",
        "minimal.json, mapping-update-candidate-v1, mapping-update-candidate",
        "minimal.json, mapping-update-review-v1, mapping-update-review",
        "minimal.json, mapping-update-diff-v1, mapping-update-diff",
        "parameter-top-level-menu.json, hook-spec-v1, hook-spec"
    })
    void validFixturesPass(String file, String dir, String type) throws Exception {
        Path path = FIXTURES.resolve(dir).resolve("valid").resolve(file);
        JsonNode root = parseFixture(type, path, "VALID_FIXTURE_INVALID_JSON");
        JsonSchemaValidator validator = validatorFor(type);
        List<SchemaValidationError> errors = validator.validate(root, path.toString());
        assertTrue(errors.isEmpty(), "Expected no errors for valid fixture " + dir + "/valid/" + file + " but got: " + errors);
    }

    @ParameterizedTest(name = "invalid fixture: {0} ({1})")
    @CsvSource({
        "unknown-field.json, plugin-meta-v2, plugin-meta, PLUGIN_META_UNKNOWN_FIELD",
        "missing-entrypoints.json, plugin-meta-v2, plugin-meta, PLUGIN_META_MISSING",
        "bad-schema-version.json, plugin-meta-v2, plugin-meta, PLUGIN_META_BAD_SCHEMA_VERSION",
        "dependency-missing-version.json, plugin-meta-v2, plugin-meta, DEPENDENCY_MISSING_VERSION",
        "dependency-bad-type.json, plugin-meta-v2, plugin-meta, DEPENDENCY_BAD_TYPE",
        "dependency-bad-ordering.json, plugin-meta-v2, plugin-meta, DEPENDENCY_BAD_ORDERING",
        "unknown-field.json, dependency-v1, dependency, DEPENDENCY_UNKNOWN_FIELD",
        "missing-id.json, dependency-v1, dependency, DEPENDENCY_MISSING_ID",
        "bad-schema-version.json, dependency-v1, dependency, DEPENDENCY_BAD_SCHEMA_VERSION",
        "missing-range.json, version-range-v1, version-range, VERSION_RANGE_EMPTY",
        "unsupported-latest.json, version-range-v1, version-range, VERSION_RANGE_UNSUPPORTED",
        "closed-upper.json, version-range-v1, version-range, VERSION_RANGE_UNSUPPORTED",
        "unknown-field.json, permission-v1, permission, PERMISSION_UNKNOWN_FIELD",
        "missing-id.json, permission-v1, permission, PERMISSION_MISSING_ID",
        "bad-schema-version.json, permission-v1, permission, PERMISSION_BAD_SCHEMA_VERSION",
        "unknown-id.json, permission-v1, permission, PERMISSION_UNKNOWN_ID",
        "unknown-field.json, runtime-config-v1, runtime-config, RUNTIME_CONFIG_UNKNOWN_FIELD",
        "missing-worktreeId.json, runtime-config-v1, runtime-config, RUNTIME_CONFIG_MISSING",
        "bad-schema-version.json, runtime-config-v1, runtime-config, RUNTIME_CONFIG_BAD_SCHEMA_VERSION",
        "bad-max-log-storage-mib.json, runtime-config-v1, runtime-config, RUNTIME_CONFIG_BAD_LOG_STORAGE_LIMIT",
        "unknown-field.json, diagnostic-report-v1, diagnostic-report, DIAGNOSTIC_REPORT_UNKNOWN_FIELD",
        "missing-problems.json, diagnostic-report-v1, diagnostic-report, DIAGNOSTIC_REPORT_MISSING",
        "bad-schema-version.json, diagnostic-report-v1, diagnostic-report, DIAGNOSTIC_REPORT_BAD_SCHEMA_VERSION",
        "unknown-field.json, mapping-pack-v1, mapping-pack, MAPPING_PACK_UNKNOWN_FIELD",
        "missing-entries.json, mapping-pack-v1, mapping-pack, MAPPING_PACK_MISSING",
        "bad-schema-version.json, mapping-pack-v1, mapping-pack, MAPPING_PACK_BAD_SCHEMA_VERSION",
        "unknown-field.json, profile-v1, profile, PROFILE_UNKNOWN_FIELD",
        "empty-mapping-refs.json, profile-v1, profile, PROFILE_EMPTY_MAPPING_REFS",
        "bad-schema-version.json, profile-v1, profile, PROFILE_BAD_SCHEMA_VERSION",
        "unknown-field.json, static-verification-record-v1, static-verification-record, STATIC_VERIFICATION_RECORD_UNKNOWN_FIELD",
        "bad-status.json, static-verification-record-v1, static-verification-record, STATIC_VERIFICATION_RECORD_BAD_STATUS",
        "absolute-path.json, static-verification-record-v1, static-verification-record, STATIC_VERIFICATION_RECORD_ABSOLUTE_PATH",
        "unknown-field.json, mapping-update-candidate-v1, mapping-update-candidate, MAPPING_UPDATE_CANDIDATE_UNKNOWN_FIELD",
        "unknown-nested-field.json, mapping-update-candidate-v1, mapping-update-candidate, MAPPING_UPDATE_CANDIDATE_UNKNOWN_FIELD",
        "bad-format.json, mapping-update-candidate-v1, mapping-update-candidate, MAPPING_UPDATE_CANDIDATE_BAD_FORMAT",
        "bad-schema-version.json, mapping-update-candidate-v1, mapping-update-candidate, MAPPING_UPDATE_CANDIDATE_BAD_SCHEMA_VERSION",
        "bad-hash.json, mapping-update-candidate-v1, mapping-update-candidate, MAPPING_UPDATE_CANDIDATE_BAD_HASH",
        "bad-path.json, mapping-update-candidate-v1, mapping-update-candidate, MAPPING_UPDATE_CANDIDATE_BAD_TARGET",
        "missing-required.json, mapping-update-candidate-v1, mapping-update-candidate, MAPPING_UPDATE_CANDIDATE_MISSING",
        "forbidden-selector.json, mapping-update-candidate-v1, mapping-update-candidate, MAPPING_UPDATE_CANDIDATE_FORBIDDEN_SELECTOR",
        "duplicate-key.json, mapping-update-candidate-v1, mapping-update-candidate, MAPPING_UPDATE_CANDIDATE_INVALID_JSON",
        "bom.json, mapping-update-candidate-v1, mapping-update-candidate, MAPPING_UPDATE_CANDIDATE_INVALID_JSON",
        "trailing-token.json, mapping-update-candidate-v1, mapping-update-candidate, MAPPING_UPDATE_CANDIDATE_INVALID_JSON",
        "unknown-field.json, mapping-update-review-v1, mapping-update-review, MAPPING_UPDATE_REVIEW_UNKNOWN_FIELD",
        "bad-format.json, mapping-update-review-v1, mapping-update-review, MAPPING_UPDATE_REVIEW_BAD_FORMAT",
        "bad-schema-version.json, mapping-update-review-v1, mapping-update-review, MAPPING_UPDATE_REVIEW_BAD_SCHEMA_VERSION",
        "bad-hash.json, mapping-update-review-v1, mapping-update-review, MAPPING_UPDATE_REVIEW_BAD_HASH",
        "bad-timestamp.json, mapping-update-review-v1, mapping-update-review, MAPPING_UPDATE_REVIEW_BAD_TIME",
        "bad-status.json, mapping-update-review-v1, mapping-update-review, MAPPING_UPDATE_REVIEW_BAD_DECISION",
        "missing-required.json, mapping-update-review-v1, mapping-update-review, MAPPING_UPDATE_REVIEW_PENDING_FIELDS",
        "duplicate-key.json, mapping-update-review-v1, mapping-update-review, MAPPING_UPDATE_REVIEW_INVALID_JSON",
        "bom.json, mapping-update-review-v1, mapping-update-review, MAPPING_UPDATE_REVIEW_INVALID_JSON",
        "trailing-token.json, mapping-update-review-v1, mapping-update-review, MAPPING_UPDATE_REVIEW_INVALID_JSON",
        "unknown-field.json, mapping-update-diff-v1, mapping-update-diff, MAPPING_UPDATE_DIFF_UNKNOWN_FIELD",
        "unknown-nested-field.json, mapping-update-diff-v1, mapping-update-diff, MAPPING_UPDATE_DIFF_UNKNOWN_FIELD",
        "bad-format.json, mapping-update-diff-v1, mapping-update-diff, MAPPING_UPDATE_DIFF_BAD_FORMAT",
        "bad-schema-version.json, mapping-update-diff-v1, mapping-update-diff, MAPPING_UPDATE_DIFF_BAD_SCHEMA_VERSION",
        "bad-hash.json, mapping-update-diff-v1, mapping-update-diff, MAPPING_UPDATE_DIFF_BAD_HASH",
        "bad-path.json, mapping-update-diff-v1, mapping-update-diff, MAPPING_UPDATE_DIFF_BAD_CHANGES",
        "missing-required.json, mapping-update-diff-v1, mapping-update-diff, MAPPING_UPDATE_DIFF_BAD_CHANGES",
        "duplicate-key.json, mapping-update-diff-v1, mapping-update-diff, MAPPING_UPDATE_DIFF_INVALID_JSON",
        "bom.json, mapping-update-diff-v1, mapping-update-diff, MAPPING_UPDATE_DIFF_INVALID_JSON",
        "trailing-token.json, mapping-update-diff-v1, mapping-update-diff, MAPPING_UPDATE_DIFF_INVALID_JSON",
        "unknown-field.json, hook-spec-v1, hook-spec, HOOK_SPEC_UNKNOWN_FIELD",
        "missing-id.json, hook-spec-v1, hook-spec, HOOK_SPEC_MISSING",
        "bad-schema-version.json, hook-spec-v1, hook-spec, HOOK_SPEC_BAD_SCHEMA_VERSION"
    })
    void invalidFixturesFail(String file, String dir, String type, String expectedCode) throws Exception {
        Path path = FIXTURES.resolve(dir).resolve("invalid").resolve(file);
        JsonNode root;
        try {
            root = parseFixture(type, path, expectedCode);
        } catch (DraftMappingException exception) {
            assertEquals(expectedCode, exception.code());
            return;
        }
        JsonSchemaValidator validator = validatorFor(type);
        List<SchemaValidationError> errors = validator.validate(root, path.toString());
        assertFalse(errors.isEmpty(), "Expected errors for invalid fixture " + dir + "/invalid/" + file);
        assertTrue(errors.stream().anyMatch(e -> e.code().equals(expectedCode)),
            "Expected error code " + expectedCode + " in " + errors);
    }

    @Test
    void allSchemasHaveAtLeastOneValidAndThreeInvalid() throws Exception {
        try (Stream<Path> schemas = Files.list(FIXTURES)) {
            schemas.filter(Files::isDirectory).forEach(schemaDir -> {
                Path validDir = schemaDir.resolve("valid");
                Path invalidDir = schemaDir.resolve("invalid");
                assertTrue(Files.exists(validDir), "valid directory must exist for " + schemaDir.getFileName());
                assertTrue(Files.exists(invalidDir), "invalid directory must exist for " + schemaDir.getFileName());
                try (Stream<Path> valid = Files.list(validDir)) {
                    assertTrue(valid.count() >= 1, "at least one valid fixture for " + schemaDir.getFileName());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                try (Stream<Path> invalid = Files.list(invalidDir)) {
                    assertTrue(invalid.count() >= 3, "at least three invalid fixtures for " + schemaDir.getFileName());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private JsonNode parseFixture(String type, Path path, String invalidJsonCode) throws Exception {
        if (type.startsWith("mapping-update-")) {
            return StrictJson.read(Files.readAllBytes(path), invalidJsonCode);
        }
        return mapper.readTree(path.toFile());
    }

    private JsonSchemaValidator validatorFor(String type) {
        return switch (type) {
            case "plugin-meta" -> new PluginMetaValidator();
            case "dependency" -> new DependencyValidator();
            case "version-range" -> new VersionRangeValidator();
            case "permission" -> new PermissionValidator();
            case "runtime-config" -> new RuntimeConfigValidator();
            case "diagnostic-report" -> new DiagnosticReportValidator();
            case "mapping-pack" -> new MappingPackValidator();
            case "profile" -> new ProfileValidator();
            case "static-verification-record" -> new StaticVerificationRecordValidator();
            case "mapping-update-candidate" -> new MappingUpdateCandidateValidator();
            case "mapping-update-review" -> new MappingReviewValidator();
            case "mapping-update-diff" -> new MappingUpdateDiffValidator();
            case "hook-spec" -> new HookSpecValidator();
            default -> throw new IllegalArgumentException("Unknown schema type: " + type);
        };
    }
}
