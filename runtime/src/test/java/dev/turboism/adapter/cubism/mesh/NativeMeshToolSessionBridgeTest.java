package dev.turboism.adapter.cubism.mesh;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.mesh.MeshTool;
import dev.turboism.sdk.cubism.mesh.MeshToolContext;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class NativeMeshToolSessionBridgeTest {
    @AfterEach
    void cleanup() {
        NativeMeshToolSessionBridge.resetForTests();
    }

    @Test
    void hostIngressNeverThrowsAndExactOwnerControlsBindingCleanup() {
        final Object owner = new Object();
        final Object foreignOwner = new Object();
        final Object mode = new Object();
        final MeshToolSessionResolver resolver = resolver(mode);
        final List<String> transitions = new ArrayList<>();
        final NativeMeshToolSessionBridge.SessionListener listener = new NativeMeshToolSessionBridge.SessionListener() {
            @Override
            public void opened(final NativeMeshToolSession session) {
                transitions.add("open");
            }

            @Override
            public void closed(final NativeMeshToolSession session) {
                transitions.add("close");
            }
        };

        NativeMeshToolSessionBridge.install(owner, 41L, resolver, listener);
        assertDoesNotThrow(() -> NativeMeshToolSessionBridge.afterStart(mode, List.of()));
        final NativeMeshToolSession current = NativeMeshToolSessionBridge.currentSessionForTests();
        assertSame(mode, current.identity().mode());
        assertEquals(41L, current.hostGeneration());
        assertTrue(current.revalidate());

        NativeMeshToolSessionBridge.uninstall(foreignOwner);
        assertSame(current, NativeMeshToolSessionBridge.currentSessionForTests());
        assertDoesNotThrow(() -> NativeMeshToolSessionBridge.beforeEnd(new Object()));
        assertTrue(current.revalidate());
        assertDoesNotThrow(() -> NativeMeshToolSessionBridge.beforeEnd(mode));
        assertFalse(current.revalidate());
        assertEquals(List.of("open", "close"), transitions);
    }

    @Test
    void listenerFailuresAndMalformedHostArgumentsRemainContained() {
        final Object mode = new Object();
        NativeMeshToolSessionBridge.install(
                new Object(), 1L, resolver(mode), new NativeMeshToolSessionBridge.SessionListener() {
                    @Override
                    public void opened(final NativeMeshToolSession session) {
                        throw new AssertionError("plugin path");
                    }

                    @Override
                    public void closed(final NativeMeshToolSession session) {
                        throw new AssertionError("plugin path");
                    }
                });

        assertDoesNotThrow(() -> NativeMeshToolSessionBridge.afterStart(null, null));
        assertDoesNotThrow(() -> NativeMeshToolSessionBridge.afterStart(mode, List.of()));
        assertDoesNotThrow(() -> NativeMeshToolSessionBridge.beforeEnd(mode));
    }

    @Test
    void nativeLifecycleListenerDrivesTheSharedCoordinatorAndInvalidatesActivationLeases() {
        final Object owner = new Object();
        final Object mode = new Object();
        final MeshToolCoordinator coordinator = new MeshToolCoordinator();
        final AtomicInteger deactivations = new AtomicInteger();
        coordinator.register(
                "plugin",
                7L,
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
                    public void activate(final MeshToolContext context) {}

                    @Override
                    public void deactivate() {
                        deactivations.incrementAndGet();
                    }
                },
                (permission, operation) -> {});
        NativeMeshToolSessionBridge.install(
                owner, 41L, resolver(mode), new NativeMeshToolSessionBridge.SessionListener() {
                    @Override
                    public void opened(final NativeMeshToolSession session) {
                        coordinator.beginSession(session);
                    }

                    @Override
                    public void closed(final NativeMeshToolSession session) {
                        coordinator.endSession();
                    }
                });

        NativeMeshToolSessionBridge.afterStart(mode, List.of());
        coordinator.activate("plugin", 7L, "brush");
        final MeshToolCoordinator.ActivationLease lease =
                coordinator.activeLease().orElseThrow();
        assertTrue(lease.revalidate());

        NativeMeshToolSessionBridge.beforeEnd(mode);

        assertTrue(coordinator.activeTool().isEmpty());
        assertFalse(lease.revalidate());
        assertEquals(1, deactivations.get());
    }

    private static MeshToolSessionResolver resolver(final Object mode) {
        final MeshToolSessionResolver.Snapshot snapshot = new MeshToolSessionResolver.Snapshot(
                mode,
                new Object(),
                new Object(),
                new Object(),
                new Object(),
                new Object(),
                new Object(),
                new Object(),
                new Object(),
                new Object(),
                new Object(),
                new Object(),
                new Object(),
                new Object());
        return new MeshToolSessionResolver((ignoredMode, ignoredEntries) -> snapshot);
    }
}
