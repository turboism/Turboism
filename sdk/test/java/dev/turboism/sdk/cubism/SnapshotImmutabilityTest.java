package dev.turboism.sdk.cubism;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SnapshotImmutabilityTest {

    @Test
    void modelSnapshotCopiesInputCollectionsAndExposesImmutableViews() {
        final List<ModelObjectSnapshot> objects = new ArrayList<>();
        final List<ParameterSnapshot> parameters = new ArrayList<>();
        final List<ArtMeshSnapshot> artMeshes = new ArrayList<>();
        final List<DeformerSnapshot> deformers = new ArrayList<>();

        final ModelSnapshot snapshot = new ModelSnapshot(
            "model-1",
            "Demo Model",
            objects,
            parameters,
            artMeshes,
            deformers
        );

        objects.add(new ParameterSnapshot("param-1", "Param", 1.0, 0.0, -1.0, 1.0, true, true));
        parameters.add(new ParameterSnapshot("param-2", "Param 2", 2.0, 0.0, -1.0, 1.0, true, true));
        artMeshes.add(new ArtMeshSnapshot("mesh-1", "Mesh", Optional.empty(), true, true));
        deformers.add(new DeformerSnapshot("deformer-1", "Deformer", DeformerType.ROOT, Optional.empty(), List.of()));

        assertTrue(snapshot.objects().isEmpty());
        assertTrue(snapshot.parameters().isEmpty());
        assertTrue(snapshot.artMeshes().isEmpty());
        assertTrue(snapshot.deformers().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.objects().add(new ParameterSnapshot(
            "param-3",
            "Param 3",
            3.0,
            0.0,
            -1.0,
            1.0,
            true,
            false
        )));
    }

    @Test
    void projectSnapshotAndRuntimeSnapshotPreserveDefensiveCopies() {
        final List<DocumentSnapshot> documents = new ArrayList<>();
        final List<String> selectedObjectIds = new ArrayList<>();
        final List<String> childIds = new ArrayList<>();

        final DocumentSnapshot document = new DocumentSnapshot(
            "document-1",
            "Document",
            "models/demo/model.cdi3.json",
            Optional.of(Path.of("models/demo/model.cdi3.json")),
            Optional.empty()
        );
        documents.add(document);

        final ProjectSnapshot project = new ProjectSnapshot(
            "project-1",
            "Project",
            Optional.of(Path.of("projects/demo")),
            documents
        );

        selectedObjectIds.add("param-1");
        childIds.add("child-1");

        final SelectionSnapshot selection = new SelectionSnapshot(
            selectedObjectIds,
            Optional.of("param-1"),
            Optional.empty(),
            Optional.empty()
        );
        final DeformerSnapshot deformer = new DeformerSnapshot(
            "deformer-1",
            "Deformer",
            DeformerType.WARP,
            Optional.empty(),
            childIds
        );
        final CubismRuntimeSnapshot runtimeSnapshot = new CubismRuntimeSnapshot(
            Optional.of(project),
            Optional.of(document),
            Optional.empty(),
            selection,
            List.of(deformer),
            List.of(),
            List.of(),
            List.of(deformer)
        );

        documents.add(new DocumentSnapshot(
            "document-2",
            "Document 2",
            "models/demo/other.cdi3.json",
            Optional.of(Path.of("models/demo/other.cdi3.json")),
            Optional.empty()
        ));
        selectedObjectIds.add("param-2");
        childIds.add("child-2");

        assertEquals(1, project.documents().size());
        assertEquals(1, selection.selectedObjectIds().size());
        assertEquals(1, deformer.childIds().size());
        assertEquals(1, runtimeSnapshot.modelObjects().size());
        assertThrows(UnsupportedOperationException.class, () -> project.documents().add(document));
        assertThrows(UnsupportedOperationException.class, () -> selection.selectedObjectIds().add("param-3"));
    }
}
