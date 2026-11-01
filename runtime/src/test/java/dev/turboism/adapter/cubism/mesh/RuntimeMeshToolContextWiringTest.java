package dev.turboism.adapter.cubism.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.turboism.sdk.cubism.mesh.MeshTool;
import dev.turboism.sdk.cubism.mesh.MeshToolContext;
import dev.turboism.sdk.cubism.mesh.SelectionMode;
import dev.turboism.sdk.cubism.mesh.VertexSelection;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Wiring of {@link RuntimeMeshToolContext#selectionBrush()}: which brush a plugin receives is
 * decided by the lease's plugin identity. The session here is not a native one, so each branch
 * stops at its own distinct "requires an exact native mesh-tool session" refusal, which tells the
 * branches apart without a host.
 */
class RuntimeMeshToolContextWiringTest {

    private static final String GEOMETRIC_REFUSAL = "geometric mesh layout requires an exact native mesh-tool session";
    private static final String GENERIC_REFUSAL = "selection brush requires an exact native mesh-tool session";

    @Test
    void theOwningPluginReceivesTheRuntimeGeometricBrush() {
        assertEquals(
                GEOMETRIC_REFUSAL,
                brushRefusal(RuntimeGeometricMeshLayout.OWNER_PLUGIN_ID, RuntimeGeometricMeshLayout.TOOL_ID));
    }

    @Test
    void aForeignPluginReusingTheReservedToolIdReceivesTheGenericBrush() {
        assertEquals(GENERIC_REFUSAL, brushRefusal("dev.example.impostor", RuntimeGeometricMeshLayout.TOOL_ID));
    }

    private static String brushRefusal(final String pluginId, final String toolId) {
        final AtomicReference<String> refusal = new AtomicReference<>();
        try (MeshToolCoordinator coordinator = new MeshToolCoordinator()) {
            coordinator.register(pluginId, 1, new Tool(toolId, refusal), (permission, operation) -> {});
            coordinator.beginSession(new Session());
            try {
                coordinator.activate(pluginId, 1, toolId);
            } catch (RuntimeException ignored) {
                // The refusal is recorded inside activate(); the coordinator rethrows it.
            }
            return refusal.get();
        }
    }

    private record Tool(String id, AtomicReference<String> refusal) implements MeshTool {
        @Override
        public String label() {
            return id;
        }

        @Override
        public String iconResourcePath() {
            return "icons/" + id + ".png";
        }

        @Override
        public void activate(final MeshToolContext context) {
            try {
                context.selectionBrush();
            } catch (IllegalStateException failure) {
                refusal.set(failure.getMessage());
                throw failure;
            }
        }

        @Override
        public void deactivate() {}
    }

    private static final class Session implements MeshToolCoordinator.Session {
        @Override
        public long hostGeneration() {
            return 1;
        }

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
        public void select(final VertexSelection selection, final SelectionMode mode) {}
    }
}
