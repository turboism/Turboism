package dev.turboism.plugin.boundingboxwarpmirror;

import dev.turboism.plugin.boundingboxwarpmirror.mirror.MirrorTargets;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.cubism.service.query.HierarchyNode;
import dev.turboism.sdk.cubism.service.query.ModelHierarchy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MirrorTargetsTest {

    private static final DeformerId WARP_PARENT = new DeformerId("warp-parent");
    private static final DeformerId WARP_CHILD = new DeformerId("warp-child");
    private static final DeformerId ROTATION = new DeformerId("rotation-1");

    @Test
    void keepsOnlyWarpDeformersFromTheSelection() {
        final List<DeformerId> targets = MirrorTargets.resolveWarpTargets(
            List.of(ROTATION, WARP_PARENT),
            Set.of(WARP_PARENT, WARP_CHILD),
            null
        );
        assertEquals(List.of(WARP_PARENT), targets);
    }

    @Test
    void ordersParentsBeforeChildrenWhenBothSelected() {
        final HierarchyNode root = node("root", null);
        final HierarchyNode parent = node(WARP_PARENT.value(), "root");
        final HierarchyNode child = node(WARP_CHILD.value(), WARP_PARENT.value());
        final ModelHierarchy hierarchy = new ModelHierarchy(
            root, List.of(root, parent, child));

        final List<DeformerId> targets = MirrorTargets.resolveWarpTargets(
            List.of(WARP_CHILD, WARP_PARENT),
            Set.of(WARP_PARENT, WARP_CHILD),
            hierarchy
        );
        assertEquals(List.of(WARP_PARENT, WARP_CHILD), targets);
    }

    @Test
    void returnsEmptyWhenNoWarpIsSelected() {
        assertTrue(MirrorTargets.resolveWarpTargets(
            List.of(ROTATION), Set.of(WARP_PARENT), null).isEmpty());
        assertTrue(MirrorTargets.resolveWarpTargets(
            List.of(), Set.of(WARP_PARENT), null).isEmpty());
    }

    private static HierarchyNode node(final String id, final String parentId) {
        return new HierarchyNode(
            new ModelObjectId(id),
            id,
            HierarchyNode.Kind.DEFORMER,
            Optional.ofNullable(parentId).map(ModelObjectId::new),
            List.of()
        );
    }
}
