package dev.turboism.sdk.cubism.service.query;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ServiceInterfaceShapeTest {

    private static final List<String> QUERY_SERVICE_SOURCES = List.of(
        "ParameterQueryService",
        "SelectionQueryService",
        "ModelHierarchyQueryService"
    );

    private static final Set<String> MUTATING_PREFIXES = Set.of(
        "set",
        "add",
        "remove",
        "mutate",
        "write",
        "delete",
        "create",
        "update",
        "commit",
        "rollback"
    );

    @Test
    void pluginContextExposesExplicitReadOnlyQueryServices_whenInspectingSourceContract() throws IOException {
        String source = Files.readString(Path.of("src/main/java/dev/turboism/sdk/plugin/PluginContext.java"));

        assertTrue(source.contains("ParameterQueryService parameterQuery()"));
        assertTrue(source.contains("SelectionQueryService selectionQuery()"));
        assertTrue(source.contains("ModelHierarchyQueryService modelHierarchyQuery()"));
    }

    @Test
    void queryServiceMethodsUseReadOnlyNames_whenInspectingSourceContract() throws IOException {
        for (String sourceName : QUERY_SERVICE_SOURCES) {
            String source = queryServiceSource(sourceName);

            for (String prefix : MUTATING_PREFIXES) {
                assertTrue(
                    !Pattern.compile("\\b" + prefix + "[A-Z][A-Za-z0-9_]*\\s*\\(").matcher(source).find(),
                    () -> sourceName + " must not expose a mutating method prefix: " + prefix
                );
            }
        }
    }

    @Test
    void queryServiceReturnTypesAreImmutableContracts_whenInspectingSourceContract() throws IOException {
        assertTrue(queryServiceSource("ParameterQueryService").contains("Optional<ParameterSummary> findById(ParameterId id)"));
        assertTrue(queryServiceSource("ParameterQueryService").contains("List<ParameterSummary> listAll()"));
        assertTrue(queryServiceSource("ParameterQueryService").contains("boolean exists(ParameterId id)"));
        assertTrue(queryServiceSource("SelectionQueryService").contains("SelectionSummary currentSelection()"));
        assertTrue(queryServiceSource("SelectionQueryService").contains("List<ModelObjectId> selectedIds(HierarchyNode.Kind kind)"));
        assertTrue(queryServiceSource("SelectionQueryService").contains("Registration onSelectionChanged(SelectionChangedListener listener)"));
        assertTrue(queryServiceSource("ModelHierarchyQueryService").contains("Optional<ModelHierarchy> currentHierarchy()"));
        assertTrue(queryServiceSource("ModelHierarchyQueryService").contains("List<HierarchyNode> childrenOf(ModelObjectId id)"));
        assertTrue(queryServiceSource("ModelHierarchyQueryService").contains("Optional<HierarchyNode> findNode(ModelObjectId id)"));
    }

    @Test
    void queryServiceSignaturesAvoidHostUiReflectionAndMutableArrays_whenInspectingSourceContract() throws IOException {
        for (String sourceName : QUERY_SERVICE_SOURCES) {
            String source = queryServiceSource(sourceName);

            assertTrue(!source.contains("com." + "live2d."));
            assertTrue(!source.contains("javax." + "swing."));
            assertTrue(!source.contains("java." + "awt."));
            assertTrue(!source.contains("java.lang." + "reflect."));
            assertTrue(!source.contains("[" + "]"));
        }
    }

    @Test
    void selectionChangedListenerUsesSdkEventOnly_whenInspectingSourceContract() throws IOException {
        String source = queryServiceSource("SelectionQueryService");

        assertTrue(source.contains("void selectionChanged(CubismSelectionChangedEvent event)"));
    }

    private static String queryServiceSource(String sourceName) throws IOException {
        return Files.readString(Path.of("src/main/java/dev/turboism/sdk/cubism/service/query", sourceName + ".java"));
    }
}
