package dev.turboism.tests.cubism;

import dev.turboism.sdk.cubism.CubismServiceException;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.cubism.service.query.HierarchyNode;
import dev.turboism.sdk.cubism.service.query.ModelHierarchy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static dev.turboism.tests.cubism.CubismQueryIntegrationSupport.MODEL_READ_PERMISSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelHierarchyQueryServiceIntegrationTest {

    @Test
    void currentHierarchyReturnsModelTreeWhenPermissionIsGranted() throws CubismServiceException {
        final CubismQueryIntegrationSupport.QueryEnvironment environment = CubismQueryIntegrationSupport.environment(
            CubismQueryIntegrationSupport.sampleHost(),
            MODEL_READ_PERMISSION
        );

        final ModelHierarchy hierarchy = environment.context().modelHierarchyQuery().currentHierarchy().orElseThrow();

        assertEquals(new ModelObjectId("model-1"), hierarchy.rootNode().id());
        assertEquals(HierarchyNode.Kind.MODEL, hierarchy.rootNode().kind());
        assertEquals(List.of(
            new ModelObjectId("param-angle-x"),
            new ModelObjectId("param-opacity"),
            new ModelObjectId("deformer-root"),
            new ModelObjectId("mesh-face")
        ), hierarchy.rootNode().childIds());
    }

    @Test
    void childrenOfReturnsDirectChildrenForNode() throws CubismServiceException {
        final CubismQueryIntegrationSupport.QueryEnvironment environment = CubismQueryIntegrationSupport.environment(
            CubismQueryIntegrationSupport.sampleHost(),
            MODEL_READ_PERMISSION
        );

        final List<HierarchyNode> children = environment.context().modelHierarchyQuery().childrenOf(new ModelObjectId("model-1"));

        assertEquals(List.of(
            new ModelObjectId("param-angle-x"),
            new ModelObjectId("param-opacity"),
            new ModelObjectId("deformer-root"),
            new ModelObjectId("mesh-face")
        ), children.stream().map(HierarchyNode::id).toList());
    }

    @Test
    void findNodeReturnsMatchingNodeById() throws CubismServiceException {
        final CubismQueryIntegrationSupport.QueryEnvironment environment = CubismQueryIntegrationSupport.environment(
            CubismQueryIntegrationSupport.sampleHost(),
            MODEL_READ_PERMISSION
        );

        final Optional<HierarchyNode> node = environment.context().modelHierarchyQuery().findNode(new ModelObjectId("mesh-face"));

        assertTrue(node.isPresent());
        assertEquals(HierarchyNode.Kind.ART_MESH, node.orElseThrow().kind());
    }

    @Test
    void absentModelReturnsEmptyHierarchyAndEmptyLookups() throws CubismServiceException {
        final CubismQueryIntegrationSupport.QueryEnvironment environment = CubismQueryIntegrationSupport.environment(
            CubismQueryIntegrationSupport.absentModelSource(),
            MODEL_READ_PERMISSION
        );

        assertTrue(environment.context().modelHierarchyQuery().currentHierarchy().isEmpty());
        assertTrue(environment.context().modelHierarchyQuery().findNode(new ModelObjectId("missing")).isEmpty());
        assertTrue(environment.context().modelHierarchyQuery().childrenOf(new ModelObjectId("missing")).isEmpty());
    }
}
