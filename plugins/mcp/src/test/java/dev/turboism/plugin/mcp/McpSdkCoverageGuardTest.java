package dev.turboism.plugin.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.command.EditorCommandService;
import dev.turboism.sdk.cubism.history.CubismHistory;
import dev.turboism.sdk.cubism.model.Glue;
import dev.turboism.sdk.cubism.model.Glues;
import dev.turboism.sdk.cubism.model.ModelObjectService;
import dev.turboism.sdk.cubism.model.ModelTextures;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterBindingBatchOperations;
import dev.turboism.sdk.cubism.model.ParameterBindingOperations;
import dev.turboism.sdk.cubism.model.ParameterDefinitions;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.cubism.service.query.SelectionQueryService;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionService;
import dev.turboism.sdk.json.Json;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

final class McpSdkCoverageGuardTest {

    private static final String RESOURCE = "META-INF/turboism/mcp-sdk-coverage.json";
    private static final Pattern EXACT_VERSION = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+");
    private static final Set<String> CLASSIFICATIONS = Set.of(
            "MCP_READ",
            "MCP_AUTHORIZED_JOB",
            "MCP_AUTHORIZED_EXPORT_JOB",
            "MCP_NATIVE_UNDO_JOB",
            "MCP_TRANSACTION_CONTROL",
            "MCP_WRITE_UNDOABLE",
            "MCP_WRITE_STANDALONE_UNDO",
            "MCP_COMMAND_NON_UNDOABLE",
            "MCP_LEGACY_WRITE",
            "RUNTIME_UNAVAILABLE",
            "EXCLUDED_WITH_REASON");
    private static final List<Class<?>> REQUIRED_OWNERS = List.of(
            Glue.class,
            Glues.class,
            CubismHistory.class,
            AuthoringTransactionService.class,
            SelectionQueryService.class,
            EditorCommandService.class,
            ModelObjectService.class,
            Parameters.class,
            ParameterDefinitions.class,
            ParameterBindingOperations.class,
            ParameterBindingBatchOperations.class,
            Parameter.class,
            ModelTextures.class,
            dev.turboism.sdk.ui.UserFileAccessService.class,
            dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutService.class,
            dev.turboism.sdk.cubism.model.ParameterGroup.class,
            dev.turboism.sdk.cubism.model.ParameterGroups.class,
            dev.turboism.sdk.cubism.model.Drawables.class);

    @Test
    void ledgerClassifiesEveryTrackedPublicSdkMethodExactlyOnce() throws Exception {
        final Map<String, ?> ledger = ledger();
        assertEquals(1, integer(ledger.get("schemaVersion")));
        assertEquals(REQUIRED_OWNERS.stream().map(Class::getName).toList(), strings(ledger.get("trackedOwners")));

        final Map<String, Map<String, Object>> byMethod = new LinkedHashMap<>();
        for (Map<String, Object> row : objects(ledger.get("entries"))) {
            assertTrue(CLASSIFICATIONS.contains(text(row.get("classification"))));
            if (row.get("sdkMethod") instanceof String method) {
                assertEquals(null, byMethod.put(method, row), "duplicate SDK classification: " + method);
            }
        }

        final Set<String> expected = new HashSet<>();
        for (Class<?> owner : REQUIRED_OWNERS) {
            Arrays.stream(owner.getDeclaredMethods())
                    .filter(method -> Modifier.isPublic(method.getModifiers()))
                    .filter(method -> !Modifier.isStatic(method.getModifiers()))
                    .filter(method -> !method.isBridge() && !method.isSynthetic())
                    .map(McpSdkCoverageGuardTest::methodKey)
                    .forEach(expected::add);
        }
        assertEquals(expected, byMethod.keySet());
    }

    @Test
    void ledgerEnforcesUndoTransactionAndExactVersionInvariants() throws Exception {
        for (Map<String, Object> row : objects(ledger().get("entries"))) {
            final String classification = text(row.get("classification"));
            final String effect = text(row.get("effect"));
            final boolean transactionEligible = flag(row.get("transactionEligible"));
            final String undoVerification = text(row.get("undoVerification"));
            final List<String> versions = strings(row.get("supportedVersions"));
            assertTrue(versions.stream()
                    .allMatch(version -> EXACT_VERSION.matcher(version).matches()));
            assertEquals(versions.size(), new HashSet<>(versions).size());

            if (transactionEligible) {
                assertTrue(Set.of("MCP_READ", "MCP_WRITE_UNDOABLE").contains(classification));
                assertTrue(Set.of("READ", "UNDOABLE_WRITE").contains(effect));
            }
            if ("MCP_WRITE_UNDOABLE".equals(classification)) {
                assertEquals("UNDOABLE_WRITE", effect);
                assertTrue(transactionEligible);
                assertTrue(Set.of("RUNTIME_VERIFIED", "EXACT_HOST_VERIFIED").contains(undoVerification));
                assertEquals(McpGlueDomain.GLUES_WRITE, row.get("endpoint"));
                assertTrue(Set.of("set_name", "set_id", "set_intensity", "set_drawable_a", "set_drawable_b")
                        .contains(row.get("operation")));
            }
            if ("MCP_WRITE_STANDALONE_UNDO".equals(classification)) {
                assertEquals("UNDOABLE_WRITE", effect);
                assertFalse(transactionEligible);
                assertTrue(Set.of("RUNTIME_VERIFIED", "EXACT_HOST_VERIFIED").contains(undoVerification));
                assertEquals("turboism.textures.write", row.get("endpoint"));
                assertTrue(Set.of(
                                "add_model_image_group",
                                "remove_model_image",
                                "add_texture_atlas",
                                "remove_texture_atlas",
                                "remove_raw_image")
                        .contains(row.get("operation")));
            }
            if ("MCP_TRANSACTION_CONTROL".equals(classification)) {
                assertEquals("TRANSACTION_CONTROL", effect);
                assertFalse(transactionEligible);
                assertEquals("RUNTIME_VERIFIED", undoVerification);
                assertEquals(
                        Map.of(
                                        McpKeyformWriteDomain.WRITE,
                                        "ordinary_stored_batch",
                                        McpPoseGridDomain.SCAN,
                                        "pose_grid",
                                        McpMaskDomain.WRITE,
                                        "clip_mask_batch",
                                        McpSourcePropertiesDomain.WRITE,
                                        "source_properties_batch",
                                        McpImageBindingDomain.BIND,
                                        "existing_model_image_binding",
                                        McpParameterStructureDomain.APPLY,
                                        "parameter_structure_batch",
                                        McpMeshCreationDomain.CREATE,
                                        "source_mesh_creation_batch",
                                        McpBindingInitializationDomain.APPLY,
                                        "initialize_ordinary_grid")
                                .get(row.get("endpoint")),
                        row.get("operation"));
                assertNotNull(row.get("operation"));
                assertFalse(versions.isEmpty());
            }
            if ("MCP_AUTHORIZED_EXPORT_JOB".equals(classification)) {
                assertEquals("EXTERNAL_SIDE_EFFECT", effect);
                assertFalse(transactionEligible);
                assertEquals("NOT_APPLICABLE", undoVerification);
                assertEquals(McpMocExportDomain.EXECUTE, row.get("endpoint"));
                assertEquals("authorized_moc_package_job", row.get("operation"));
                assertFalse(versions.isEmpty());
                assertTrue(versions.stream().allMatch(version -> version.startsWith("5.3.")));
            }
            if ("MCP_NATIVE_UNDO_JOB".equals(classification)) {
                assertEquals("UNDOABLE_WRITE", effect);
                assertFalse(transactionEligible);
                assertEquals("RUNTIME_VERIFIED", undoVerification);
                assertEquals(McpAtlasDomain.APPLY, row.get("endpoint"));
                assertEquals("complete_rectangle_apply_job", row.get("operation"));
                assertFalse(versions.isEmpty());
            }
            if ("MCP_AUTHORIZED_JOB".equals(classification)) {
                assertEquals("EXTERNAL_SIDE_EFFECT", effect);
                assertFalse(transactionEligible);
                assertEquals("RUNTIME_VERIFIED", undoVerification);
                assertEquals(McpImageRegistrationDomain.START, row.get("endpoint"));
                assertEquals("authorized_png_registration", row.get("operation"));
                assertFalse(versions.isEmpty());
            }
            if ("MCP_COMMAND_NON_UNDOABLE".equals(classification)) {
                assertFalse(transactionEligible);
            }
            if ("MCP_LEGACY_WRITE".equals(classification)) {
                assertFalse(transactionEligible);
                assertEquals("EXTERNAL_SIDE_EFFECT", effect);
                assertEquals("UNVERIFIED", undoVerification);
                assertTrue(Set.of(
                                McpProductionDomainCatalog.APPLY,
                                McpParameterDomain.PARAMETERS_APPLY,
                                McpParameterDomain.BINDINGS_APPLY)
                        .contains(row.get("endpoint")));
                assertFalse(text(row.get("reason")).isBlank());
            }
            if (Set.of("RUNTIME_UNAVAILABLE", "EXCLUDED_WITH_REASON").contains(classification)) {
                assertFalse(text(row.get("reason")).isBlank());
            }
        }
    }

    @Test
    void ledgerRecordsTemporaryApplyExceptionsAndGlueProviderGaps() throws Exception {
        final Map<String, ?> ledger = ledger();
        final Set<String> exceptions = objects(ledger.get("temporaryPublicExceptions")).stream()
                .map(row -> text(row.get("endpoint")))
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(
                Set.of(
                        McpProductionDomainCatalog.APPLY,
                        McpParameterDomain.PARAMETERS_APPLY,
                        McpParameterDomain.BINDINGS_APPLY),
                exceptions);
        assertFalse(exceptions.contains("turboism.history.move"));
        for (Map<String, Object> exception : objects(ledger.get("temporaryPublicExceptions"))) {
            assertFalse(text(exception.get("reason")).isBlank());
            assertFalse(text(exception.get("replacement")).isBlank());
        }

        final Map<String, Map<String, Object>> semantics = objects(ledger.get("entries")).stream()
                .filter(row -> row.get("semanticCapability") instanceof String)
                .collect(java.util.stream.Collectors.toMap(row -> text(row.get("semanticCapability")), row -> row));
        for (String capability : List.of("glues.create", "glues.delete")) {
            final Map<String, Object> row = semantics.get(capability);
            assertNotNull(row);
            assertEquals("RUNTIME_UNAVAILABLE", row.get("classification"));
            assertEquals(McpGlueDomain.GLUES_WRITE, row.get("endpoint"));
            assertFalse(flag(row.get("transactionEligible")));
            assertTrue(text(row.get("reason")).contains("verified Editor provider"));
        }
    }

    private static Map<String, ?> ledger() throws IOException {
        try (InputStream input = McpSdkCoverageGuardTest.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            assertNotNull(input, "missing MCP SDK coverage ledger");
            return Json.parseObject(input.readAllBytes());
        }
    }

    private static String methodKey(final Method method) {
        return method.getDeclaringClass().getName() + "#" + method.getName() + descriptor(method);
    }

    private static String descriptor(final Method method) {
        final StringBuilder value = new StringBuilder("(");
        for (Class<?> parameter : method.getParameterTypes()) value.append(descriptor(parameter));
        return value.append(')').append(descriptor(method.getReturnType())).toString();
    }

    private static String descriptor(final Class<?> type) {
        if (type.isArray()) return "[" + descriptor(type.getComponentType());
        if (!type.isPrimitive()) return "L" + type.getName().replace('.', '/') + ";";
        if (type == void.class) return "V";
        if (type == boolean.class) return "Z";
        if (type == byte.class) return "B";
        if (type == char.class) return "C";
        if (type == short.class) return "S";
        if (type == int.class) return "I";
        if (type == long.class) return "J";
        if (type == float.class) return "F";
        if (type == double.class) return "D";
        throw new IllegalArgumentException("unsupported primitive: " + type);
    }

    private static int integer(final Object value) {
        if (!(value instanceof Number number)) throw new AssertionError("expected number: " + value);
        return number.intValue();
    }

    private static boolean flag(final Object value) {
        if (!(value instanceof Boolean result)) throw new AssertionError("expected boolean: " + value);
        return result;
    }

    private static String text(final Object value) {
        if (!(value instanceof String result)) throw new AssertionError("expected string: " + value);
        return result;
    }

    private static List<String> strings(final Object value) {
        if (!(value instanceof List<?> values)) throw new AssertionError("expected array: " + value);
        return values.stream().map(McpSdkCoverageGuardTest::text).toList();
    }

    private static List<Map<String, Object>> objects(final Object value) {
        if (!(value instanceof List<?> values)) throw new AssertionError("expected array: " + value);
        final ArrayList<Map<String, Object>> result = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof Map<?, ?> raw)) throw new AssertionError("expected object: " + item);
            final LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            raw.forEach((key, member) -> row.put((String) key, member));
            result.add(row);
        }
        return List.copyOf(result);
    }
}
