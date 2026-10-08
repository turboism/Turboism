package dev.turboism.adapter.cubism.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.mesh.MeshTool;
import dev.turboism.sdk.cubism.mesh.MeshToolContext;
import dev.turboism.sdk.cubism.mesh.SelectionMode;
import dev.turboism.sdk.cubism.mesh.VertexSelection;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MeshToolCoordinatorStateTest {

    @Test
    void switchesInDeactivateThenActivateOrderAndNativeActivationRevokesExactlyOnce() {
        MeshToolCoordinator coordinator = new MeshToolCoordinator();
        List<String> calls = new ArrayList<>();
        coordinator.register("plugin", 1, tool("a", calls), (permission, operation) -> {});
        coordinator.register("plugin", 1, tool("b", calls), (permission, operation) -> {});
        coordinator.beginSession(new Session(9));

        coordinator.activate("plugin", 1, "a");
        coordinator.activate("plugin", 1, "b");
        coordinator.nativeToolActivated();
        coordinator.nativeToolActivated();

        assertEquals(List.of("activate:a", "deactivate:a", "activate:b", "deactivate:b"), calls);
        assertTrue(coordinator.activeTool().isEmpty());
    }

    @Test
    void reportsWhetherAnExactMeshEditorSessionIsOpen() {
        // Tool activation is refused without an open session, and the toolbar reports that refusal
        // through the host action ingress, so the session state has to be observable from outside.
        final MeshToolCoordinator coordinator = new MeshToolCoordinator();
        coordinator.register("plugin", 1, tool("a", new ArrayList<>()), (permission, operation) -> {});

        assertFalse(coordinator.hasActiveSession());
        assertThrows(IllegalStateException.class, () -> coordinator.activate("plugin", 1, "a"));

        coordinator.beginSession(new Session(9));
        assertTrue(coordinator.hasActiveSession());
        coordinator.activate("plugin", 1, "a");
        assertEquals("a", coordinator.activeTool().orElseThrow().tool().id());

        coordinator.endSession();
        assertFalse(coordinator.hasActiveSession());
    }

    @Test
    void exactGenerationsAndHostSessionIdentityRejectStaleOperations() {
        MeshToolCoordinator coordinator = new MeshToolCoordinator();
        coordinator.register("plugin", 1, tool("a", new ArrayList<>()), (permission, operation) -> {});
        coordinator.beginSession(new Session(3));
        coordinator.activate("plugin", 1, "a");
        MeshToolCoordinator.ActivationLease lease = coordinator.activeLease().orElseThrow();

        assertTrue(lease.revalidate());
        coordinator.replaceHostGeneration(4);
        assertFalse(lease.revalidate());
        assertThrows(IllegalStateException.class, lease::requireCurrent);
        assertThrows(IllegalArgumentException.class, () -> coordinator.activate("plugin", 2, "a"));
    }

    private static MeshTool tool(String id, List<String> calls) {
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
            public void activate(MeshToolContext context) {
                calls.add("activate:" + id);
            }

            @Override
            public void deactivate() {
                calls.add("deactivate:" + id);
            }
        };
    }

    private static final class Session implements MeshToolCoordinator.Session {
        private final long generation;
        private boolean current = true;

        Session(long generation) {
            this.generation = generation;
        }

        @Override
        public long hostGeneration() {
            return generation;
        }

        @Override
        public boolean revalidate() {
            return current;
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
