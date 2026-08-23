package dev.turboism.tests.cubism;

import dev.turboism.sdk.cubism.CubismServiceException;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.cubism.service.query.HierarchyNode;
import dev.turboism.sdk.cubism.service.query.SelectionSummary;
import dev.turboism.sdk.cubism.event.SelectionChangedEvent;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.test.fake.FakeCubismHost;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static dev.turboism.tests.cubism.CubismQueryIntegrationSupport.MODEL_READ_PERMISSION;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SelectionQueryServiceIntegrationTest {

    @Test
    void currentSelectionReturnsSelectedModelObjectIdsWhenPermissionIsGranted() throws CubismServiceException {
        final FakeCubismHost host = CubismQueryIntegrationSupport.sampleHost();
        host.select("param-angle-x");
        host.select("mesh-face");
        final CubismQueryIntegrationSupport.QueryEnvironment environment = CubismQueryIntegrationSupport.environment(host, MODEL_READ_PERMISSION);

        final SelectionSummary selection = environment.context().selectionQuery().currentSelection();

        assertEquals(List.of(new ModelObjectId("param-angle-x"), new ModelObjectId("mesh-face")), selection.selectedModelObjectIds());
    }

    @Test
    void selectedIdsFiltersCurrentSelectionByKind() throws CubismServiceException {
        final FakeCubismHost host = CubismQueryIntegrationSupport.sampleHost();
        host.select("param-angle-x");
        host.select("mesh-face");
        host.select("deformer-root");
        final CubismQueryIntegrationSupport.QueryEnvironment environment = CubismQueryIntegrationSupport.environment(host, MODEL_READ_PERMISSION);

        final List<ModelObjectId> parameters = environment.context().selectionQuery().selectedIds(HierarchyNode.Kind.PARAMETER);
        final List<ModelObjectId> artMeshes = environment.context().selectionQuery().selectedIds(HierarchyNode.Kind.ART_MESH);
        final List<ModelObjectId> deformers = environment.context().selectionQuery().selectedIds(HierarchyNode.Kind.DEFORMER);

        assertEquals(List.of(new ModelObjectId("param-angle-x")), parameters);
        assertEquals(List.of(new ModelObjectId("mesh-face")), artMeshes);
        assertEquals(List.of(new ModelObjectId("deformer-root")), deformers);
    }

    @Test
    void subscribedListenerReceivesEventWhenSelectionChanges() throws Exception {
        final FakeCubismHost host = CubismQueryIntegrationSupport.sampleHost();
        host.select("param-angle-x");
        final CubismQueryIntegrationSupport.QueryEnvironment environment = selectionEnvironment(host);
        final List<SelectionChangedEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();

        environment.context().eventBus().subscribe(SelectionChangedEvent.class, events::add);
        environment.context().selectionQuery().currentSelection();
        host.clearSelection();
        host.select("mesh-face");
        environment.context().selectionQuery().currentSelection();

        waitForEvent(events);
        assertEquals(List.of(new ModelObjectId("param-angle-x")), events.get(0).previousSelection().selectedModelObjectIds());
        assertEquals(List.of(new ModelObjectId("mesh-face")), events.get(0).currentSelection().selectedModelObjectIds());
    }

    @Test
    void closedRegistrationStopsSelectionChangeEvents() throws CubismServiceException {
        final FakeCubismHost host = CubismQueryIntegrationSupport.sampleHost();
        host.select("param-angle-x");
        final CubismQueryIntegrationSupport.QueryEnvironment environment = selectionEnvironment(host);
        final List<SelectionChangedEvent> events = new ArrayList<>();

        final Registration registration = environment.context().eventBus().subscribe(
            SelectionChangedEvent.class,
            events::add
        );
        environment.context().selectionQuery().currentSelection();
        registration.close();
        host.clearSelection();
        host.select("mesh-face");
        environment.context().selectionQuery().currentSelection();

        assertEquals(List.of(), events);
    }

    private static CubismQueryIntegrationSupport.QueryEnvironment selectionEnvironment(
        final FakeCubismHost host
    ) {
        return CubismQueryIntegrationSupport.environment(
            host,
            MODEL_READ_PERMISSION,
            PermissionIds.TURBOISM_EVENT_SUBSCRIBE,
            PermissionIds.TURBOISM_CUBISM_SELECTION_OBSERVE
        );
    }

    private static void waitForEvent(final List<SelectionChangedEvent> events) throws Exception {
        final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(1);
        while (events.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(1, events.size());
    }
}
