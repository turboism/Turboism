package dev.turboism.adapter.cubism.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.turboism.sdk.cubism.mesh.MeshTool;
import dev.turboism.sdk.cubism.mesh.MeshToolContext;
import dev.turboism.sdk.cubism.mesh.SelectionMode;
import dev.turboism.sdk.cubism.mesh.VertexSelection;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The geometric mesh layout surface is admitted by the {@code cubism.mesh.point-and-edge-layout}
 * capability, not by a reserved tool identifier.
 *
 * <p>Before slice S8 the runtime built its own geometric brush for the one hardcoded tool id, so a
 * third-party plugin that registered that id received runtime behaviour it never declared while a
 * capability-declaring plugin under another id did not. These cases pin the replacement rule: the
 * capability decides for every tool id, and each branch stops at its own distinct non-native
 * refusal, which tells the branches apart without a host.</p>
 */
class GeometricMeshLayoutAdmissionTest {
    private static final String CANVAS_REFUSAL = "mesh canvas session requires an exact native mesh-tool session";
    private static final String CAPABILITY_REFUSAL =
            "mesh canvas session requires the cubism.mesh.point-and-edge-layout capability";

    @Test
    void aCapabilityDeclaringPluginIsAdmittedUnderAnyToolId() {
        assertEquals(CANVAS_REFUSAL, refusal("geometric-mesh-layout", true));
        assertEquals(CANVAS_REFUSAL, refusal("custom-point-layout", true));
    }

    @Test
    void reusingTheFirstPartyToolIdWithoutTheCapabilityIsRefused() {
        assertEquals(CAPABILITY_REFUSAL, refusal("geometric-mesh-layout", false));
        assertEquals(CAPABILITY_REFUSAL, refusal("custom-point-layout", false));
    }

    private static String refusal(final String toolId, final boolean pointAndEdgeLayoutGranted) {
        final AtomicReference<String> refusal = new AtomicReference<>();
        try (MeshToolCoordinator coordinator = new MeshToolCoordinator()) {
            coordinator.register(
                    "plugin", 1, new Tool(toolId, refusal), (permission, operation) -> {}, pointAndEdgeLayoutGranted);
            coordinator.beginSession(new Session());
            try {
                coordinator.activate("plugin", 1, toolId);
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
                context.canvasSession();
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
