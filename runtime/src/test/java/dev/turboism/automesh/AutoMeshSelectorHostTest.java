package dev.turboism.automesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.automesh.host.AutoMeshContainer;
import dev.turboism.automesh.host.AutoMeshFunnel;
import dev.turboism.automesh.host.NativeAutoMeshBridge;
import dev.turboism.sdk.cubism.mesh.automesh.AlgorithmParameterDescriptor;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithm;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshRequest;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshResult;
import dev.turboism.sdk.plugin.CancellationToken;
import dev.turboism.sdk.plugin.Registration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Session host behaviour of the native selector rows.
 *
 * <p>The host is what the transformed container constructors reach through the bridge. These tests
 * prove the two properties that keep the native popup safe: a native-only catalog adds no row at
 * all, and one container never receives two rows.</p>
 */
class AutoMeshSelectorHostTest {

    private final AutoMeshAlgorithmCatalog catalog = new AutoMeshAlgorithmCatalog();
    private final List<String> diagnostics = new ArrayList<>();
    private final AutoMeshSelectorHost host = new AutoMeshSelectorHost(
            new Source(catalog), Locale.ENGLISH, diagnostics::add);

    @AfterEach
    void closeHost() {
        host.close();
    }

    @Test
    void aNativeOnlySessionAddsNoRow() {
        final Object container = new Object();

        host.attach(container);

        assertEquals(0, host.attachedCount(), "a native-only session must not decorate a container");
        assertTrue(diagnostics.isEmpty(), "a native-only session reports nothing");
    }

    @Test
    void oneContainerNeverReceivesTwoRows() {
        catalog.register(algorithm(), "plugin", 1L, () -> true);
        final Object container = new Object();

        host.attach(container);
        host.attach(container);

        assertEquals(1, host.attachedCount(), "a repeated attach must not add a second row");
    }

    @Test
    void aClosedHostIgnoresFurtherAttaches() {
        catalog.register(algorithm(), "plugin", 1L, () -> true);
        host.close();

        host.attach(new Object());

        assertEquals(0, host.attachedCount(), "a closed host must not decorate a container");
    }

    @Test
    void releasingAContainerDropsItsRow() {
        catalog.register(algorithm(), "plugin", 1L, () -> true);
        final Object container = new Object();
        host.attach(container);

        host.release(container);

        assertEquals(0, host.attachedCount(), "a released container must lose its row");
        assertTrue(host.rowOf(container).isEmpty(), "a released container has no row");
    }

    @Test
    void theHostSelectionIsTheSessionSelection() {
        catalog.register(algorithm(), "plugin", 1L, () -> true);

        host.selection().selectAlgorithm("plugin.region");

        assertEquals(
                "plugin.region",
                catalog.selectedAlgorithmId().orElseThrow(),
                "the host must write the session selection the dispatcher reads");
        assertSame(host.selection(), host.source().selection(), "the source must expose the same selection");
    }

    @Test
    void theHostListsTheSessionAlgorithms() {
        catalog.register(algorithm(), "plugin", 1L, () -> true);

        assertEquals(
                List.of(AutoMeshAlgorithm.NATIVE_ALGORITHM_ID, "plugin.region"),
                host.algorithms().stream().map(AutoMeshAlgorithm::id).toList(),
                "the host must expose the session listing the row reads");
    }

    @Test
    void theBridgeRoutesAnAttachToTheHostAndSwallowsFailures() {
        catalog.register(algorithm(), "plugin", 1L, () -> true);
        final Object container = new Object();
        final Registration bridge = NativeAutoMeshBridge.install(new NativeAutoMeshBridge.Handler() {
            @Override
            public Boolean handle(final AutoMeshFunnel funnel, final Object[] hostArguments) {
                return Boolean.FALSE;
            }

            @Override
            public void attach(final AutoMeshContainer requested, final Object instance) {
                host.attach(instance);
            }
        }, diagnostics::add);
        try {
            assertEquals(
                    null,
                    dispatchAttach(new Object[] {"modeling-dialog-panel", container}),
                    "the attach callback must answer nothing");
            assertEquals(1, host.attachedCount(), "the bridge must reach the host");
        } finally {
            bridge.close();
        }
    }

    @Test
    void anUnknownContainerTokenIsRefusedWithoutDecorating() {
        catalog.register(algorithm(), "plugin", 1L, () -> true);
        final Registration bridge = NativeAutoMeshBridge.install(
                (funnel, hostArguments) -> Boolean.FALSE, diagnostics::add);
        try {
            dispatchAttach(new Object[] {"absent-container", new Object()});
            assertEquals(0, host.attachedCount(), "an unknown container must not be decorated");
            assertTrue(
                    diagnostics.stream().anyMatch(message -> message.contains("identity is unknown")),
                    "the refusal must be reported: " + diagnostics);
        } finally {
            bridge.close();
        }
    }

    /**
     * Reaches the bridge's private attach entry, which transformed host bytecode calls through the
     * JDK properties table.
     */
    private static Object dispatchAttach(final Object argument) {
        try {
            final java.lang.reflect.Method method =
                    NativeAutoMeshBridge.class.getDeclaredMethod("dispatchAttach", Object.class);
            dev.turboism.core.reflect.ReflectAccess.open(method);
            return method.invoke(null, argument);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("the bridge attach entry must exist", failure);
        }
    }

    private static AutoMeshAlgorithm algorithm() {
        return new AutoMeshAlgorithm(
                "plugin.region",
                "Region plan",
                "Plans a mesh from the region outline.",
                List.of(new AlgorithmParameterDescriptor.IntParameter(
                        "density", "Density", "Vertices per region edge.", 8, 128, 8, 48)),
                List.of(),
                false,
                (AutoMeshRequest request, CancellationToken cancellation) -> AutoMeshResult.Cancelled.INSTANCE);
    }

    /** Session source over the real catalog, mirroring the production wiring. */
    private static final class Source implements AutoMeshSelectorSource {

        private final AutoMeshAlgorithmCatalog catalog;
        private final AutoMeshSelectionState selection;

        private Source(final AutoMeshAlgorithmCatalog catalog) {
            this.catalog = catalog;
            this.selection = new AutoMeshSelectionState(catalog);
        }

        @Override
        public List<AutoMeshAlgorithm> algorithms() {
            return catalog.algorithms();
        }

        @Override
        public AutoMeshSelectionState selection() {
            return selection;
        }
    }
}
