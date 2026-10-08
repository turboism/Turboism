package dev.turboism.adapter.host;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.cubism.mesh.MeshToolCoordinator;
import dev.turboism.sdk.cubism.mesh.MeshTool;
import dev.turboism.sdk.cubism.mesh.MeshToolContext;
import dev.turboism.sdk.cubism.mesh.SelectionMode;
import dev.turboism.sdk.cubism.mesh.VertexSelection;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HostSessionMeshToolLifecycleTest {
    @Test
    void ownsExactlyOneCoordinatorPerHostSessionAndAdapterViewsShareIt() {
        HostSession first = new HostSession(() -> Optional.empty());
        HostSession second = new HostSession(() -> Optional.empty());
        try {
            assertSame(first.meshToolCoordinator(), first.adapterAccess().meshToolCoordinator());
            assertSame(
                    first.adapterAccess().meshToolCoordinator(),
                    first.adapterAccess().meshToolCoordinator());
            assertNotSame(first.meshToolCoordinator(), second.meshToolCoordinator());
        } finally {
            first.close();
            second.close();
        }
    }

    @Test
    void hostCloseRevokesActiveLeaseBeforeReturning() {
        HostSession session = new HostSession(() -> Optional.empty());
        MeshToolCoordinator coordinator = session.meshToolCoordinator();
        AtomicInteger deactivations = new AtomicInteger();
        coordinator.register(
                "plugin",
                1L,
                new MeshTool() {
                    @Override
                    public String id() {
                        return "brush";
                    }

                    @Override
                    public String label() {
                        return "Brush";
                    }

                    @Override
                    public String iconResourcePath() {
                        return "icons/brush.png";
                    }

                    @Override
                    public void activate(MeshToolContext context) {}

                    @Override
                    public void deactivate() {
                        deactivations.incrementAndGet();
                    }
                },
                (permission, operation) -> {});
        coordinator.beginSession(new Session(0L));
        coordinator.activate("plugin", 1L, "brush");
        var lease = coordinator.activeLease().orElseThrow();

        session.close();

        assertTrue(coordinator.activeTool().isEmpty());
        assertTrue(!lease.revalidate());
        org.junit.jupiter.api.Assertions.assertEquals(1, deactivations.get());
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
