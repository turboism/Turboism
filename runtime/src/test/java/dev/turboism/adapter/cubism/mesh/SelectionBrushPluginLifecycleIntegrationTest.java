package dev.turboism.adapter.cubism.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.plugin.selectionbrush.SelectionBrushPlugin;
import dev.turboism.sdk.cubism.mesh.SelectionMode;
import dev.turboism.sdk.cubism.mesh.VertexSelection;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.ui.contribution.EditorUiContributionAuthority;
import dev.turboism.ui.host.EditorUiFamily;
import dev.turboism.ui.host.RuntimeEditorUiHostLifecycle;
import java.lang.reflect.Proxy;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SelectionBrushPluginLifecycleIntegrationTest {
    @Test
    void officialPluginReloadReplacesOnlyItsExactGenerationAndFakeSessionsFailWithoutPartialActivation()
            throws Exception {
        RuntimeEditorUiHostLifecycle lifecycle = new RuntimeEditorUiHostLifecycle();
        long hostGeneration = lifecycle.connecting().generation();
        lifecycle.ready(hostGeneration, Set.of(EditorUiFamily.MESH_TOOLBAR));
        EditorUiContributionAuthority authority = new EditorUiContributionAuthority(lifecycle);
        MeshToolCoordinator coordinator = new MeshToolCoordinator();

        DisposableScope firstScope = new DisposableScope();
        RuntimeMeshToolRegistry firstRegistry = registry(1L, coordinator, authority, firstScope);
        SelectionBrushPlugin first = load(firstRegistry, firstScope);
        assertEquals(2, authority.contributions(EditorUiFamily.MESH_TOOLBAR).size());
        assertEquals(1, coordinator.snapshot().size());

        coordinator.beginSession(new Session(hostGeneration));
        assertThrows(
                IllegalStateException.class,
                () -> coordinator.activate("dev.turboism.plugin.selection-brush", 1L, "selection-brush"));
        assertTrue(coordinator.activeTool().isEmpty());

        first.disable();
        firstScope.close();
        first.shutdown();
        assertEquals(0, authority.contributions(EditorUiFamily.MESH_TOOLBAR).size());
        assertEquals(0, coordinator.snapshot().size());

        DisposableScope replacementScope = new DisposableScope();
        RuntimeMeshToolRegistry replacementRegistry = registry(2L, coordinator, authority, replacementScope);
        SelectionBrushPlugin replacement = load(replacementRegistry, replacementScope);
        assertEquals(2, authority.contributions(EditorUiFamily.MESH_TOOLBAR).size());
        assertEquals(2L, coordinator.snapshot().get(0).generation());

        firstScope.close();
        coordinator.replaceHostGeneration(hostGeneration + 1L);
        assertEquals(2, authority.contributions(EditorUiFamily.MESH_TOOLBAR).size());
        assertEquals(2L, coordinator.snapshot().get(0).generation());

        replacement.disable();
        replacementScope.close();
        replacement.shutdown();
        coordinator.close();
        lifecycle.close();
    }

    private static RuntimeMeshToolRegistry registry(
            long generation,
            MeshToolCoordinator coordinator,
            EditorUiContributionAuthority authority,
            DisposableScope scope) {
        RuntimeMeshToolRegistry registry = new RuntimeMeshToolRegistry(
                "dev.turboism.plugin.selection-brush",
                generation,
                (permission, operation) -> {},
                true,
                coordinator,
                authority);
        registry.bind(scope);
        return registry;
    }

    private static SelectionBrushPlugin load(RuntimeMeshToolRegistry registry, DisposableScope scope) throws Exception {
        PluginContext context = (PluginContext) Proxy.newProxyInstance(
                SelectionBrushPluginLifecycleIntegrationTest.class.getClassLoader(),
                new Class<?>[] {PluginContext.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "services" ->
                        new dev.turboism.sdk.plugin.PluginServiceDirectory() {
                            @Override
                            public java.util.Set<dev.turboism.sdk.plugin.PluginService> installed() {
                                return java.util.Set.of(dev.turboism.sdk.plugin.PluginService.MESH_TOOLS);
                            }

                            @Override
                            public <T> T get(Class<T> type) {
                                return type == dev.turboism.sdk.cubism.mesh.MeshToolRegistry.class
                                        ? type.cast(registry)
                                        : null;
                            }
                        };
                    case "disposableScope" -> scope;
                    case "toString" -> "SelectionBrushRuntimeContext";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        SelectionBrushPlugin plugin = new SelectionBrushPlugin();
        plugin.init(context);
        plugin.enable();
        return plugin;
    }

    private record Session(long hostGeneration) implements MeshToolCoordinator.Session {
        @Override
        public boolean revalidate() {
            return true;
        }

        @Override
        public dev.turboism.sdk.cubism.model.Drawable drawable() {
            return null;
        }

        @Override
        public VertexSelection selection() {
            return VertexSelection.empty();
        }

        @Override
        public void select(VertexSelection selection, SelectionMode mode) {}
    }
}
