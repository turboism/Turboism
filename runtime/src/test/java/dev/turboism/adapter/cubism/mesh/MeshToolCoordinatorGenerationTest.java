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

class MeshToolCoordinatorGenerationTest {
    @Test
    void pluginReloadAndStaleRegistrationCloseAffectOnlyTheirExactGeneration() {
        MeshToolCoordinator coordinator = new MeshToolCoordinator();
        List<String> calls = new ArrayList<>();
        var stale = coordinator.register("plugin", 1L, tool("brush", calls, "old"), allow());
        coordinator.register("plugin", 2L, tool("brush", calls, "new"), allow());
        coordinator.beginSession(new Session(7L));

        coordinator.activate("plugin", 2L, "brush");
        stale.close();

        assertEquals(1, coordinator.snapshot().size());
        assertEquals(2L, coordinator.snapshot().get(0).generation());
        assertEquals(2L, coordinator.activeTool().orElseThrow().generation());
        assertEquals(List.of("activate:new"), calls);
    }

    @Test
    void sessionReplacementAndHostReplacementInvalidateEveryCapturedLeaseIdentity() {
        MeshToolCoordinator coordinator = new MeshToolCoordinator();
        coordinator.register("plugin", 1L, tool("brush", new ArrayList<>(), "one"), allow());
        Session first = new Session(9L);
        coordinator.beginSession(first);
        coordinator.activate("plugin", 1L, "brush");
        var firstLease = coordinator.activeLease().orElseThrow();

        coordinator.beginSession(new Session(9L));
        assertFalse(firstLease.revalidate());
        coordinator.activate("plugin", 1L, "brush");
        var secondLease = coordinator.activeLease().orElseThrow();
        assertTrue(secondLease.activationGeneration() > firstLease.activationGeneration());
        assertTrue(secondLease.sessionGeneration() > firstLease.sessionGeneration());

        coordinator.replaceHostGeneration(10L);
        assertFalse(secondLease.revalidate());
        assertTrue(coordinator.activeTool().isEmpty());
        assertThrows(IllegalStateException.class, () -> coordinator.activate("plugin", 1L, "brush"));
    }

    @Test
    void staleIdentityAndClosedCoordinatorRejectFurtherMutation() {
        MeshToolCoordinator coordinator = new MeshToolCoordinator();
        coordinator.register("plugin", 4L, tool("brush", new ArrayList<>(), "four"), allow());
        coordinator.beginSession(new Session(2L));

        assertThrows(IllegalArgumentException.class, () -> coordinator.activate("plugin", 3L, "brush"));
        coordinator.close();
        assertThrows(
                IllegalStateException.class,
                () -> coordinator.register("plugin", 5L, tool("other", new ArrayList<>(), "five"), allow()));
    }

    private static dev.turboism.permissions.PermissionChecker allow() {
        return (permission, operation) -> {};
    }

    private static MeshTool tool(String id, List<String> calls, String suffix) {
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
                return "icons/brush.png";
            }

            @Override
            public void activate(MeshToolContext context) {
                calls.add("activate:" + suffix);
            }

            @Override
            public void deactivate() {
                calls.add("deactivate:" + suffix);
            }
        };
    }

    private static final class Session implements MeshToolCoordinator.Session {
        private final long generation;

        private Session(long generation) {
            this.generation = generation;
        }

        @Override
        public long hostGeneration() {
            return generation;
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
        public void select(VertexSelection selection, SelectionMode mode) {}
    }
}
