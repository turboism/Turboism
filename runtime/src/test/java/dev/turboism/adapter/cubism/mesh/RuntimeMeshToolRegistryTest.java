package dev.turboism.adapter.cubism.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.turboism.sdk.cubism.mesh.MeshTool;
import dev.turboism.sdk.cubism.mesh.MeshToolContext;
import dev.turboism.sdk.cubism.mesh.MeshToolbarSlider;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.ui.contribution.EditorUiContributionAuthority;
import dev.turboism.ui.host.EditorUiFamily;
import dev.turboism.ui.host.RuntimeEditorUiHostLifecycle;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RuntimeMeshToolRegistryTest {
    @Test
    void publishesExactGenerationRejectsDuplicatesAndStaleCloseCannotRevokeReplacement() {
        RuntimeEditorUiHostLifecycle lifecycle = readyMeshLifecycle();
        EditorUiContributionAuthority authority = new EditorUiContributionAuthority(lifecycle);
        MeshToolCoordinator coordinator = new MeshToolCoordinator();
        RuntimeMeshToolRegistry first = registry("plugin", 1L, coordinator, authority);
        RuntimeMeshToolRegistry second = registry("plugin", 2L, coordinator, authority);

        var stale = first.register(tool("brush"));
        second.register(tool("brush"));
        first.contributeSlider(slider("radius"));
        second.contributeSlider(slider("radius"));

        assertEquals(4, authority.contributions(EditorUiFamily.MESH_TOOLBAR).size());
        assertEquals(2, coordinator.snapshot().size());
        assertThrows(IllegalStateException.class, () -> second.register(tool("brush")));
        assertThrows(IllegalStateException.class, () -> second.contributeSlider(slider("radius")));

        stale.close();
        assertEquals(3, authority.contributions(EditorUiFamily.MESH_TOOLBAR).size());
        assertEquals(1, coordinator.snapshot().size());
        assertEquals(2L, coordinator.snapshot().get(0).generation());
    }

    @Test
    void capabilityPermissionAndScopeCleanupFailClosed() throws Exception {
        RuntimeEditorUiHostLifecycle lifecycle = readyMeshLifecycle();
        EditorUiContributionAuthority authority = new EditorUiContributionAuthority(lifecycle);
        MeshToolCoordinator coordinator = new MeshToolCoordinator();
        RuntimeMeshToolRegistry noCapability =
                new RuntimeMeshToolRegistry("plugin", 1L, (permission, operation) -> {}, false, coordinator, authority);
        RuntimeMeshToolRegistry denied = new RuntimeMeshToolRegistry(
                "plugin",
                2L,
                (permission, operation) -> {
                    throw new SecurityException(permission);
                },
                true,
                coordinator,
                authority);

        assertThrows(UnsupportedOperationException.class, () -> noCapability.register(tool("a")));
        assertThrows(SecurityException.class, () -> denied.contributeSlider(slider("radius")));
        assertEquals(0, authority.contributions(EditorUiFamily.MESH_TOOLBAR).size());

        DisposableScope scope = new DisposableScope();
        RuntimeMeshToolRegistry owned = registry("plugin", 3L, coordinator, authority);
        owned.bind(scope);
        owned.register(tool("a"));
        owned.contributeSlider(slider("radius"));
        scope.close();

        assertEquals(0, authority.contributions(EditorUiFamily.MESH_TOOLBAR).size());
        assertEquals(0, coordinator.snapshot().size());
        assertThrows(IllegalStateException.class, () -> owned.register(tool("b")));
    }

    @Test
    void sliderCallbackUsesValidatedSdkValuePath() {
        RuntimeEditorUiHostLifecycle lifecycle = readyMeshLifecycle();
        EditorUiContributionAuthority authority = new EditorUiContributionAuthority(lifecycle);
        RuntimeMeshToolRegistry registry = registry("plugin", 4L, new MeshToolCoordinator(), authority);
        AtomicInteger value = new AtomicInteger(32);
        registry.contributeSlider(new MeshToolbarSlider() {
            @Override
            public String id() {
                return "radius";
            }

            @Override
            public String label() {
                return "Radius";
            }

            @Override
            public int minimum() {
                return 8;
            }

            @Override
            public int maximum() {
                return 128;
            }

            @Override
            public int value() {
                return value.get();
            }

            @Override
            public void setValue(int next) {
                value.set(next);
            }
        });

        var descriptor = (dev.turboism.ui.mesh.MeshToolbarSliderContributionDescriptor)
                authority.contributions(EditorUiFamily.MESH_TOOLBAR).get(0).descriptor();
        descriptor.changed(64);
        assertEquals(64, value.get());
        assertThrows(IllegalArgumentException.class, () -> descriptor.changed(129));
    }

    private static RuntimeMeshToolRegistry registry(
            String pluginId,
            long generation,
            MeshToolCoordinator coordinator,
            EditorUiContributionAuthority authority) {
        return new RuntimeMeshToolRegistry(
                pluginId, generation, (permission, operation) -> {}, true, coordinator, authority);
    }

    private static RuntimeEditorUiHostLifecycle readyMeshLifecycle() {
        RuntimeEditorUiHostLifecycle lifecycle = new RuntimeEditorUiHostLifecycle();
        long generation = lifecycle.connecting().generation();
        lifecycle.ready(generation, java.util.Set.of(EditorUiFamily.MESH_TOOLBAR));
        return lifecycle;
    }

    private static MeshTool tool(String id) {
        return new MeshTool() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String label() {
                return id;
            }

            @Override
            public String iconResourcePath() {
                return "icons/" + id + ".png";
            }

            @Override
            public void activate(MeshToolContext context) {}

            @Override
            public void deactivate() {}
        };
    }

    private static MeshToolbarSlider slider(String id) {
        return new MeshToolbarSlider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String label() {
                return id;
            }

            @Override
            public int minimum() {
                return 8;
            }

            @Override
            public int maximum() {
                return 128;
            }

            @Override
            public int value() {
                return 32;
            }

            @Override
            public void setValue(int value) {}
        };
    }
}
