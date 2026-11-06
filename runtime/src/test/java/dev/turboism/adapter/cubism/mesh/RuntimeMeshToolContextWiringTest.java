package dev.turboism.adapter.cubism.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.turboism.sdk.cubism.mesh.MeshTool;
import dev.turboism.sdk.cubism.mesh.MeshToolContext;
import dev.turboism.sdk.cubism.mesh.SelectionMode;
import dev.turboism.sdk.cubism.mesh.VertexSelection;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Wiring of {@link RuntimeMeshToolContext}: the canvas point-and-edge session is admitted by the
 * {@code cubism.mesh.point-and-edge-layout} capability carried by the tool registration, while the
 * selection brush is the same runtime brush for every tool. The session here is not a native one,
 * so each branch stops at its own distinct refusal, which tells the branches apart without a host.
 */
class RuntimeMeshToolContextWiringTest {

    private static final String CANVAS_REFUSAL = "mesh canvas session requires an exact native mesh-tool session";
    private static final String GENERIC_REFUSAL = "selection brush requires an exact native mesh-tool session";

    @Test
    void aCapabilityDeclaringPluginReceivesTheRuntimeCanvasSession() {
        assertEquals(CANVAS_REFUSAL, refusal(true, MeshToolContext::canvasSession));
    }

    @Test
    void aPluginWithoutTheCapabilityIsRefusedBeforeReachingTheCanvasSession() {
        assertEquals(
                "mesh canvas session requires the cubism.mesh.point-and-edge-layout capability",
                refusal(false, MeshToolContext::canvasSession));
    }

    @Test
    void theSelectionBrushNoLongerDependsOnThePointAndEdgeCapability() {
        assertEquals(GENERIC_REFUSAL, refusal(true, MeshToolContext::selectionBrush));
        assertEquals(GENERIC_REFUSAL, refusal(false, MeshToolContext::selectionBrush));
    }

    private static String refusal(
            final boolean pointAndEdgeLayoutGranted,
            final java.util.function.Function<MeshToolContext, Object> handle) {
        final AtomicReference<String> refusal = new AtomicReference<>();
        try (MeshToolCoordinator coordinator = new MeshToolCoordinator()) {
            coordinator.register(
                    "plugin",
                    1,
                    new Tool("geometric-mesh-layout", refusal, handle),
                    (permission, operation) -> {},
                    pointAndEdgeLayoutGranted);
            coordinator.beginSession(new Session());
            try {
                coordinator.activate("plugin", 1, "geometric-mesh-layout");
            } catch (RuntimeException ignored) {
                // The refusal is recorded inside activate(); the coordinator rethrows it.
            }
            return refusal.get();
        }
    }

    private record Tool(
            String id, AtomicReference<String> refusal, java.util.function.Function<MeshToolContext, Object> handle)
            implements MeshTool {
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
                handle.apply(context);
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
