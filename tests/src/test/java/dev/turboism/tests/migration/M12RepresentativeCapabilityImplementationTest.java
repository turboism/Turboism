package dev.turboism.tests.migration;

import dev.turboism.adapter.cubism.CubismFacadeImpl;
import dev.turboism.adapter.cubism.write.FakeHostWriteAdapter;
import dev.turboism.adapter.cubism.write.RuntimeTransactionManager;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.sdk.cubism.ClipMaskSnapshot;
import dev.turboism.sdk.cubism.PsdDocumentSnapshot;
import dev.turboism.sdk.cubism.RenderStatusSnapshot;
import dev.turboism.sdk.cubism.TextureAtlasSnapshot;
import dev.turboism.sdk.cubism.WorkspaceSnapshot;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.transaction.DocumentId;
import dev.turboism.sdk.cubism.transaction.ModelTransaction;
import dev.turboism.sdk.cubism.transaction.TransactionStatus;
import dev.turboism.sdk.cubism.write.WriteCanvasCommand;
import dev.turboism.sdk.cubism.write.WriteClipMaskCommand;
import dev.turboism.sdk.cubism.write.WriteModelObjectCommand;
import dev.turboism.sdk.cubism.write.WriteParameterCommand;
import dev.turboism.hook.ingress.HookIngressSpec;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.theme.ThemeStatusSnapshot;
import dev.turboism.sdk.ui.DialogRequest;
import dev.turboism.sdk.ui.EmbeddedPanelContribution;
import dev.turboism.sdk.ui.FileChooserRequest;
import dev.turboism.sdk.ui.OverlayContribution;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.ViewportSnapshot;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.ui.context.RuntimeContextMenuRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static dev.turboism.tests.migration.M12RepresentativeFixtures.adapterWithParameterValue;
import static dev.turboism.tests.migration.M12RepresentativeFixtures.facadeFor;
import static dev.turboism.tests.migration.M12RepresentativeFixtures.permission;
import static dev.turboism.tests.migration.M12RepresentativeFixtures.sampleHost;
import static dev.turboism.tests.migration.M12RepresentativeFixtures.scheduler;

class M12RepresentativeCapabilityImplementationTest {

    private static final DocumentId DOCUMENT_ID = new DocumentId("document-1");
    private static final ModelId MODEL_ID = new ModelId("model-1");
    private static final ModelObjectId MODEL_OBJECT_ID = new ModelObjectId("mesh-1");
    private static final ParameterId PARAMETER_ID = new ParameterId("parameter-1");

    @Test
    void readCapabilityRepresentativesExposeOnlyImmutableSdkDtosFromFakeHost() {
        CubismFacadeImpl facade = facadeFor(sampleHost(), CubismFacadeImpl.PROJECT_READ_PERMISSION, CubismFacadeImpl.MODEL_READ_PERMISSION);

        assertEquals("mesh-1", facade.runtime().artMeshes().get(0).id());
        assertEquals("deformer-1", facade.runtime().deformers().get(0).id());
        assertThrows(UnsupportedOperationException.class, () -> facade.runtime().artMeshes().add(facade.runtime().artMeshes().get(0)));
        assertThrows(UnsupportedOperationException.class, () -> new TextureAtlasSnapshot("atlas-1", 1024, 1024, List.of("texture-1")).textureIds().add("texture-2"));
        assertThrows(UnsupportedOperationException.class, () -> new ClipMaskSnapshot("mask-1", List.of("mesh-1"), List.of("mesh-2"), true).sourceMeshIds().add("mesh-3"));
        assertThrows(UnsupportedOperationException.class, () -> new WorkspaceSnapshot("workspace-1", "workspace", List.of("project-1")).recentProjectIds().add("project-2"));
        assertThrows(UnsupportedOperationException.class, () -> new PsdDocumentSnapshot("psd-1", "psd/source.psd", List.of(new PsdDocumentSnapshot.PsdLayerSnapshot("layer-1", "Line", true))).layers().add(new PsdDocumentSnapshot.PsdLayerSnapshot("layer-2", "Color", true)));
        assertEquals(60.0, new RenderStatusSnapshot(true, 60.0, "fake-renderer").framesPerSecond());
        assertEquals("dark", new ThemeStatusSnapshot("dark", "Dark", true).themeId());
    }

    @Test
    void readPermissionDenialBlocksRepresentativeFacadeFamily() {
        CubismFacadeImpl facade = facadeFor(sampleHost());

        assertThrows(dev.turboism.sdk.permission.CubismPermissionException.class, facade::runtime);
    }

    @Test
    void uiCapabilityRepresentativesAreTypedAndDisposableWithoutNativeHandles() {
        RuntimeContextMenuRegistry registry = new RuntimeContextMenuRegistry((permissionId, operation) -> { }, "plugin.demo");
        ContextMenuRegistry.ContextMenuContribution contribution = new ContextMenuRegistry.ContextMenuContribution("menu-1", "Inspect", null, "parameter", 10);

        Registration registration = registry.contribute(contribution);

        assertEquals(List.of(contribution), registry.contributions());
        registration.close();
        registration.close();
        assertTrue(registry.contributions().isEmpty());
        assertEquals("overlay-1", new OverlayContribution("overlay-1", "viewport", 10).id());
        assertEquals(1280, new ViewportSnapshot("viewport-1", 1280, 720, 1.0).width());
        assertEquals("dialog-1", new DialogRequest("dialog-1", "Title", "Body").id());
        assertEquals("panel-1", new EmbeddedPanelContribution("panel-1", "Panel", "right", 20).id());
        assertEquals(List.of("psd"), new FileChooserRequest("file-1", "Open PSD", List.of("psd")).allowedExtensions());
        assertEquals("INFO", new StatusNotification("status-1", "INFO", "Ready").severity());
    }

    @Test
    void writeCapabilityRepresentativeUsesTransactionPermissionSchedulerAndRollback() throws Exception {
        FakeHostWriteAdapter adapter = adapterWithParameterValue(0.25);
        M12RepresentativeFixtures.RecordingPolicy policy = new M12RepresentativeFixtures.RecordingPolicy();
        RuntimeScheduler scheduler = scheduler(policy);
        RuntimeTransactionManager manager = new RuntimeTransactionManager(
            adapter,
            dev.turboism.permissions.PermissionChecker.from(List.of(permission(RuntimeTransactionManager.TURBOISM_CUBISM_WRITE))),
            scheduler
        );
        ModelTransaction transaction = manager.openTransaction(new M12RepresentativeFixtures.TestPluginContext("plugin.demo"), DOCUMENT_ID);

        transaction.enqueue(new WriteParameterCommand("command-1", MODEL_ID, PARAMETER_ID, 0.75F));
        transaction.enqueue(new WriteParameterCommand("command-2", MODEL_ID, new ParameterId("missing"), 0.5F));

        assertThrows(dev.turboism.sdk.cubism.transaction.CommitFailedException.class, transaction::commit);
        assertTrue(policy.dispatched.await(1, TimeUnit.SECONDS));
        assertEquals("transaction.commit", policy.taskType);
        assertEquals(TransactionStatus.ROLLED_BACK, transaction.status());
        assertEquals(0.25, adapter.parameterValue(MODEL_ID.value(), PARAMETER_ID.value()));
        assertEquals("canvas-1", new WriteCanvasCommand("canvas-1", MODEL_ID, 2048, 2048).commandId());
        assertEquals("clip-1", new WriteClipMaskCommand("clip-1", new ModelObjectId("mask-1"), List.of(new ArtMeshId("mesh-1"))).commandId());
        assertEquals("object-1", new WriteModelObjectCommand("object-1", MODEL_ID, MODEL_OBJECT_ID, "rename").commandId());
        scheduler.shutdown();
    }

    @Test
    void hookIngressRepresentativeIsFakeOnlyAndProductionDisabled() {
        HookIngressSpec spec = new HookIngressSpec("hook-ingress.selection.changed", "event.selection.changed", false, "enqueue-only");

        assertEquals("event.selection.changed", spec.emittedEvent());
        assertFalse(spec.productionEnabled());
        assertThrows(IllegalArgumentException.class, () -> new HookIngressSpec("hook-ingress.selection.changed", "event.selection.changed", true, "enqueue-only"));
    }

}
