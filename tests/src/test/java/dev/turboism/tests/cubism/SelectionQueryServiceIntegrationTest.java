package dev.turboism.tests.cubism;

import dev.turboism.sdk.cubism.CubismServiceException;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.cubism.service.query.HierarchyNode;
import dev.turboism.sdk.cubism.service.query.SelectionSummary;
import dev.turboism.sdk.event.cubism.CubismSelectionChangedEvent;
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
    void subscribedListenerReceivesEventWhenSelectionChanges() throws CubismServiceException {
        final FakeCubismHost host = CubismQueryIntegrationSupport.sampleHost();
        host.select("param-angle-x");
        final CubismQueryIntegrationSupport.QueryEnvironment environment = CubismQueryIntegrationSupport.environment(host, MODEL_READ_PERMISSION);
        final List<CubismSelectionChangedEvent> events = new ArrayList<>();

        environment.context().selectionQuery().onSelectionChanged(events::add);
        host.clearSelection();
        host.select("mesh-face");
        environment.context().selectionQuery().currentSelection();

        assertEquals(1, events.size());
        assertEquals(List.of(new ModelObjectId("param-angle-x")), events.get(0).previousSelection().selectedModelObjectIds());
        assertEquals(List.of(new ModelObjectId("mesh-face")), events.get(0).currentSelection().selectedModelObjectIds());
    }

    @Test
    void closedRegistrationStopsSelectionChangeEvents() throws CubismServiceException {
        final FakeCubismHost host = CubismQueryIntegrationSupport.sampleHost();
        host.select("param-angle-x");
        final CubismQueryIntegrationSupport.QueryEnvironment environment = CubismQueryIntegrationSupport.environment(host, MODEL_READ_PERMISSION);
        final List<CubismSelectionChangedEvent> events = new ArrayList<>();

        final Registration registration = environment.context().selectionQuery().onSelectionChanged(events::add);
        registration.close();
        host.clearSelection();
        host.select("mesh-face");
        environment.context().selectionQuery().currentSelection();

        assertEquals(List.of(), events);
    }
}
