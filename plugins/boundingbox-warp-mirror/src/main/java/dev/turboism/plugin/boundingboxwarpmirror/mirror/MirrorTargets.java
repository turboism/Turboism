package dev.turboism.plugin.boundingboxwarpmirror.mirror;

import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.cubism.service.query.HierarchyNode;
import dev.turboism.sdk.cubism.service.query.ModelHierarchy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Resolves which selected objects are whole-object mirror targets and their apply order. */
public final class MirrorTargets {

    private MirrorTargets() { }

    /**
     * Returns the selected Warp Deformers ordered parent-before-child.
     *
     * <p>Only ids present in {@code warpIds} (the model's actual Warp Deformer set) are
     * kept; selected ArtMeshes, Rotation Deformers, and other objects are not mirror
     * targets. Ordering walks the hierarchy snapshot upward so a selected parent is
     * mirrored before a selected child; a {@code null} hierarchy keeps selection order.</p>
     *
     * @param selectedDeformers selected deformer ids in selection order
     * @param warpIds ids of the model's real Warp Deformers
     * @param hierarchy current hierarchy snapshot, or {@code null} when unavailable
     * @return ordered mirror targets; empty when nothing applies
     */
    public static List<DeformerId> resolveWarpTargets(
        final List<DeformerId> selectedDeformers,
        final Set<DeformerId> warpIds,
        final ModelHierarchy hierarchy
    ) {
        final ArrayList<DeformerId> targets = new ArrayList<>();
        for (DeformerId id : selectedDeformers) {
            if (warpIds.contains(id)) {
                targets.add(id);
            }
        }
        if (hierarchy != null && targets.size() > 1) {
            targets.sort(Comparator.comparingInt(id -> depth(hierarchy, id)));
        }
        return List.copyOf(targets);
    }

    private static int depth(final ModelHierarchy hierarchy, final DeformerId id) {
        int depth = 0;
        ModelObjectId current = new ModelObjectId(id.value());
        final int guard = hierarchy.nodes().size() + 1;
        while (depth < guard) {
            final HierarchyNode node = hierarchy.findNode(current).orElse(null);
            if (node == null || node.parentId().isEmpty()) {
                return depth;
            }
            current = node.parentId().get();
            depth++;
        }
        return depth;
    }
}
