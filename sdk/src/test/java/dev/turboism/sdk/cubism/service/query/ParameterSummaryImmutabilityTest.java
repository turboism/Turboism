package dev.turboism.sdk.cubism.service.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.DocumentId;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.id.ProjectId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ParameterSummaryImmutabilityTest {

    @Test
    void parameterSummaryAndBoundsAreRecords_whenInspectingSourceContract() throws IOException {
        assertTrue(sourceFor("ParameterSummary").contains("public record ParameterSummary("));
        assertTrue(sourceFor("ParameterBounds").contains("public record ParameterBounds("));
    }

    @Test
    void parameterSummaryHasNoMutableListFields_whenInspectingSourceContract() throws IOException {
        assertTrue(!sourceFor("ParameterSummary").contains("List<"));
    }

    @Test
    void listAccessorsAreUnmodifiable_whenSdkSummariesExposeLists() {
        SelectionSummary selection = selectionSummaryWithMutableLists();
        HierarchyNode node = hierarchyNodeWithMutableChildIds();
        ModelHierarchy hierarchy = modelHierarchyWithMutableNodes(node);

        assertThrows(UnsupportedOperationException.class, () -> selection.selectedParameterIds().add(new ParameterId("blocked")));
        assertThrows(UnsupportedOperationException.class, () -> selection.selectedArtMeshIds().add(new ArtMeshId("blocked")));
        assertThrows(UnsupportedOperationException.class, () -> selection.selectedDeformerIds().add(new DeformerId("blocked")));
        assertThrows(UnsupportedOperationException.class, () -> selection.selectedModelObjectIds().add(new ModelObjectId("blocked")));
        assertThrows(UnsupportedOperationException.class, () -> node.childIds().add(new ModelObjectId("blocked")));
        assertThrows(UnsupportedOperationException.class, () -> hierarchy.nodes().add(node));
    }

    @Test
    void constructorDefensivelyCopiesListInputs_whenSdkSummariesExposeLists() {
        ArrayList<ParameterId> parameterIds = new ArrayList<>(List.of(new ParameterId("param-angle-x")));
        ArrayList<ArtMeshId> artMeshIds = new ArrayList<>(List.of(new ArtMeshId("mesh-face")));
        ArrayList<DeformerId> deformerIds = new ArrayList<>(List.of(new DeformerId("warp-head")));
        ArrayList<ModelObjectId> objectIds = new ArrayList<>(List.of(new ModelObjectId("object-root")));
        SelectionSummary selection = new SelectionSummary(
            Optional.of(new ProjectId("project")),
            Optional.of(new DocumentId("document")),
            Optional.of(new ModelObjectId("model")),
            parameterIds,
            artMeshIds,
            deformerIds,
            objectIds
        );
        ArrayList<ModelObjectId> childIds = new ArrayList<>(List.of(new ModelObjectId("child")));
        HierarchyNode node = new HierarchyNode(
            new ModelObjectId("root"),
            "Root",
            HierarchyNode.Kind.MODEL,
            Optional.empty(),
            childIds
        );
        ArrayList<HierarchyNode> nodes = new ArrayList<>(List.of(node));
        ModelHierarchy hierarchy = new ModelHierarchy(node, nodes);

        parameterIds.add(new ParameterId("param-added"));
        artMeshIds.add(new ArtMeshId("mesh-added"));
        deformerIds.add(new DeformerId("deformer-added"));
        objectIds.add(new ModelObjectId("object-added"));
        childIds.add(new ModelObjectId("child-added"));
        nodes.clear();

        assertEquals(List.of(new ParameterId("param-angle-x")), selection.selectedParameterIds());
        assertEquals(List.of(new ArtMeshId("mesh-face")), selection.selectedArtMeshIds());
        assertEquals(List.of(new DeformerId("warp-head")), selection.selectedDeformerIds());
        assertEquals(List.of(new ModelObjectId("object-root")), selection.selectedModelObjectIds());
        assertEquals(List.of(new ModelObjectId("child")), node.childIds());
        assertEquals(List.of(node), hierarchy.nodes());
    }

    private static SelectionSummary selectionSummaryWithMutableLists() {
        return new SelectionSummary(
            Optional.of(new ProjectId("project")),
            Optional.of(new DocumentId("document")),
            Optional.of(new ModelObjectId("model")),
            new ArrayList<>(List.of(new ParameterId("param-angle-x"))),
            new ArrayList<>(List.of(new ArtMeshId("mesh-face"))),
            new ArrayList<>(List.of(new DeformerId("warp-head"))),
            new ArrayList<>(List.of(new ModelObjectId("object-root")))
        );
    }

    private static HierarchyNode hierarchyNodeWithMutableChildIds() {
        return new HierarchyNode(
            new ModelObjectId("root"),
            "Root",
            HierarchyNode.Kind.MODEL,
            Optional.empty(),
            new ArrayList<>(List.of(new ModelObjectId("child")))
        );
    }

    private static ModelHierarchy modelHierarchyWithMutableNodes(HierarchyNode node) {
        return new ModelHierarchy(node, new ArrayList<>(List.of(node)));
    }

    private static String sourceFor(String sourceName) throws IOException {
        return Files.readString(Path.of("src/main/java/dev/turboism/sdk/cubism/service/query", sourceName + ".java"));
    }
}
