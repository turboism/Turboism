package dev.turboism.tests.migration;

import dev.turboism.adapter.cubism.write.FakeHostWriteAdapter;
import dev.turboism.adapter.cubism.write.RuntimeTransactionManager;
import dev.turboism.sdk.cubism.boundingbox.BoundingBoxWriteCommand;
import dev.turboism.sdk.cubism.deformer.DeformerWriteCommand;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.mesh.MeshWriteCommand;
import dev.turboism.sdk.cubism.mesh.MirrorWritebackCommand;
import dev.turboism.sdk.cubism.psd.PsdBindingWriteCommand;
import dev.turboism.sdk.cubism.transaction.CommitFailedException;
import dev.turboism.sdk.cubism.id.DocumentId;
import dev.turboism.sdk.cubism.transaction.ModelTransaction;
import dev.turboism.sdk.cubism.transaction.TransactionStatus;
import dev.turboism.sdk.cubism.write.CubismWriteCommand;
import dev.turboism.sdk.cubism.write.WriteCanvasCommand;
import dev.turboism.sdk.cubism.write.WriteClipMaskCommand;
import dev.turboism.sdk.cubism.write.WriteModelObjectCommand;
import dev.turboism.sdk.cubism.write.WriteParameterCommand;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.turboism.tests.migration.M12RepresentativeFixtures.MODEL_ID;
import static dev.turboism.tests.migration.M12RepresentativeFixtures.PARAMETER_ID;
import static dev.turboism.tests.migration.M12RepresentativeFixtures.adapterWithParameterValue;
import static dev.turboism.tests.migration.M12RepresentativeFixtures.permission;
import static dev.turboism.tests.migration.M12RepresentativeFixtures.scheduler;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class M12WriteCommandCatalogTest {

    private static final DocumentId DOCUMENT_ID = new DocumentId("document-1");
    private static final ModelObjectId OBJECT_ID = new ModelObjectId("object-1");
    private static final ModelObjectId MESH_ID = new ModelObjectId("mesh-1");
    private static final ModelObjectId DEFORMER_ID = new ModelObjectId("deformer-1");
    private static final ArtMeshId ART_MESH_ID = new ArtMeshId("mesh-1");

    @Test
    void allM12WriteCommandFamiliesCommitThroughOneTransaction() throws Exception {
        FakeHostWriteAdapter adapter = adapterWithParameterValue(0.25);
        M12RepresentativeFixtures.RecordingPolicy policy = new M12RepresentativeFixtures.RecordingPolicy();
        RuntimeTransactionManager manager = new RuntimeTransactionManager(
            adapter,
            dev.turboism.permissions.PermissionChecker.from(List.of(permission(RuntimeTransactionManager.TURBOISM_CUBISM_WRITE))),
            scheduler(policy)
        );
        ModelTransaction transaction = manager.openTransaction(new M12RepresentativeFixtures.TestPluginContext("plugin.demo"), DOCUMENT_ID);

        for (CubismWriteCommand command : commandsForModel(MODEL_ID)) {
            transaction.enqueue(command);
        }
        transaction.commit();

        assertEquals(TransactionStatus.COMMITTED, transaction.status());
        assertEquals(0.75, adapter.parameterValue(MODEL_ID.value(), PARAMETER_ID.value()));
        assertEquals(
            List.of("parameter-1", "model-object-1", "mesh-1", "deformer-1", "mirror-1", "psd-1", "clipmask-1", "canvas-1", "bbox-1"),
            adapter.appliedCommandIds()
        );
    }

    @Test
    void genericWriteCommandFailureRollsBackEarlierCommands() throws Exception {
        FakeHostWriteAdapter adapter = adapterWithParameterValue(0.25);
        RuntimeTransactionManager manager = new RuntimeTransactionManager(
            adapter,
            dev.turboism.permissions.PermissionChecker.from(List.of(permission(RuntimeTransactionManager.TURBOISM_CUBISM_WRITE))),
            scheduler(new M12RepresentativeFixtures.RecordingPolicy())
        );
        ModelTransaction transaction = manager.openTransaction(new M12RepresentativeFixtures.TestPluginContext("plugin.demo"), DOCUMENT_ID);
        transaction.enqueue(new WriteModelObjectCommand("model-object-1", MODEL_ID, OBJECT_ID, "rename"));
        transaction.enqueue(new MeshWriteCommand("mesh-fail", new ModelId("missing-model"), MESH_ID, "replace-vertices"));

        assertThrows(CommitFailedException.class, transaction::commit);

        assertEquals(TransactionStatus.ROLLED_BACK, transaction.status());
        assertEquals(0.25, adapter.parameterValue(MODEL_ID.value(), PARAMETER_ID.value()));
        assertEquals(List.of(), adapter.appliedCommandIds());
    }

    @Test
    void writeCommandDtosValidateRequiredFieldsAndImmutableLists() {
        assertThrows(IllegalArgumentException.class, () -> new MeshWriteCommand("", MODEL_ID, MESH_ID, "op"));
        assertThrows(IllegalArgumentException.class, () -> new DeformerWriteCommand("deformer", MODEL_ID, DEFORMER_ID, ""));
        assertThrows(IllegalArgumentException.class, () -> new MirrorWritebackCommand("mirror", MODEL_ID, null, MESH_ID));
        assertThrows(IllegalArgumentException.class, () -> new PsdBindingWriteCommand("psd", MODEL_ID, "psd-1", "", OBJECT_ID));
        assertThrows(IllegalArgumentException.class, () -> new BoundingBoxWriteCommand("bbox", MODEL_ID, OBJECT_ID, ""));

        WriteClipMaskCommand command = new WriteClipMaskCommand("clipmask-1", OBJECT_ID, List.of(ART_MESH_ID));
        assertThrows(UnsupportedOperationException.class, () -> command.clippedMeshIds().add(new ArtMeshId("mesh-2")));
    }

    private static List<CubismWriteCommand> commandsForModel(ModelId modelId) {
        return List.of(
            new WriteParameterCommand("parameter-1", modelId, new ParameterId(PARAMETER_ID.value()), 0.75F),
            new WriteModelObjectCommand("model-object-1", modelId, OBJECT_ID, "rename"),
            new MeshWriteCommand("mesh-1", modelId, MESH_ID, "replace-vertices"),
            new DeformerWriteCommand("deformer-1", modelId, DEFORMER_ID, "move"),
            new MirrorWritebackCommand("mirror-1", modelId, MESH_ID, new ModelObjectId("mesh-2")),
            new PsdBindingWriteCommand("psd-1", modelId, "psd-1", "layer-1", OBJECT_ID),
            new WriteClipMaskCommand("clipmask-1", OBJECT_ID, List.of(ART_MESH_ID)),
            new WriteCanvasCommand("canvas-1", modelId, 2048, 2048),
            new BoundingBoxWriteCommand("bbox-1", modelId, OBJECT_ID, "select")
        );
    }
}
