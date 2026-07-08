package dev.turboism.tests.cubism;

import dev.turboism.sdk.cubism.CubismServiceException;
import dev.turboism.sdk.event.cubism.CubismSelectionChangedEvent;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.test.fake.FakeCubismHost;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.turboism.tests.cubism.CubismQueryIntegrationSupport.MODEL_READ_PERMISSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class QueryServiceListenerCleanupTest {

    @Test
    void listenerRemovedOnRegistrationClose() throws CubismServiceException {
        final FakeCubismHost host = CubismQueryIntegrationSupport.sampleHost();
        host.select("param-angle-x");
        final CubismQueryIntegrationSupport.QueryEnvironment environment = CubismQueryIntegrationSupport.environment(host, MODEL_READ_PERMISSION);
        final AtomicInteger callCount = new AtomicInteger();

        final Registration registration = environment.context().selectionQuery().onSelectionChanged(event -> callCount.incrementAndGet());
        registration.close();
        host.clearSelection();
        host.select("mesh-face");
        environment.context().selectionQuery().currentSelection();

        assertEquals(0, callCount.get());
    }

    @Test
    void listenerRemovedOnPluginDisableLifecycle() throws Exception {
        final FakeCubismHost host = CubismQueryIntegrationSupport.sampleHost();
        host.select("param-angle-x");
        final CubismQueryIntegrationSupport.QueryEnvironment environment = CubismQueryIntegrationSupport.environment(host, MODEL_READ_PERMISSION);
        final List<CubismSelectionChangedEvent> events = new ArrayList<>();

        environment.disposableScope().register(environment.context().selectionQuery().onSelectionChanged(events::add));
        environment.disposableScope().close();
        host.clearSelection();
        host.select("mesh-face");
        environment.context().selectionQuery().currentSelection();

        assertEquals(List.of(), events);
    }

    @Test
    void closedListenerRegistrationDoesNotRetainListenerObject() throws CubismServiceException {
        final FakeCubismHost host = CubismQueryIntegrationSupport.sampleHost();
        final CubismQueryIntegrationSupport.QueryEnvironment environment = CubismQueryIntegrationSupport.environment(host, MODEL_READ_PERMISSION);
        final WeakReference<ListenerProbe> reference = closedListenerReference(environment);

        forceGc();

        assertNull(reference.get());
    }

    private static WeakReference<ListenerProbe> closedListenerReference(
        final CubismQueryIntegrationSupport.QueryEnvironment environment
    ) throws CubismServiceException {
        final ListenerProbe probe = new ListenerProbe();
        final WeakReference<ListenerProbe> reference = new WeakReference<>(probe);
        final Registration registration = environment.context().selectionQuery().onSelectionChanged(probe::onSelectionChanged);
        registration.close();
        return reference;
    }

    private static void forceGc() {
        for (int attempt = 0; attempt < 10; attempt++) {
            System.gc();
        }
    }

    private static final class ListenerProbe {
        private void onSelectionChanged(final CubismSelectionChangedEvent event) {
        }
    }
}
