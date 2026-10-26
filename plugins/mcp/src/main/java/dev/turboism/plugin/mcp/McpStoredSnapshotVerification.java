package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import java.util.*;
import java.util.function.Supplier;

/** Exact live observation of a retained source/stored snapshot, not write admission or parent evaluation. */
final class McpStoredSnapshotVerification {
    static void verify(
            McpAuthoringSnapshotStore.Entry entry,
            McpStoredAuthoringDomain.Context initial,
            Supplier<McpStoredAuthoringDomain.Context> context) {
        var now = context.get();
        if (!initial.binding().equals(now.binding())) throw new Stale();
        Object actual;
        String source;
        String id;
        Optional<PartId> part;
        Optional<DeformerId> parent;
        if (entry.kind().equals("art_mesh")) {
            var mesh = now.model().drawables().find(new ArtMeshId(entry.objectId()));
            var s = mesh.authoringSnapshot();
            actual = s;
            source = s.sourceGuid();
            id = s.artMeshId().value();
            part = mesh.parentPartId();
            parent = mesh.parentDeformerId();
        } else {
            var deformer = now.model().deformers().find(new DeformerId(entry.objectId()));
            if (entry.kind().equals("warp_deformer") && deformer instanceof WarpDeformer warp) {
                var s = warp.authoringSnapshot();
                actual = s;
                source = s.sourceGuid();
                id = s.deformerId().value();
            } else if (entry.kind().equals("rotation_deformer") && deformer instanceof RotationDeformer rotation) {
                var s = rotation.authoringSnapshot();
                actual = s;
                source = s.sourceGuid();
                id = s.deformerId().value();
            } else throw new Stale();
            part = deformer.parentPartId();
            parent = deformer.parentDeformerId();
        }
        var row = entry.rows().stream()
                .filter(r -> "parent_space".equals(r.get("kind")))
                .findFirst()
                .orElseThrow(Stale::new);
        if (!entry.nativeSnapshot().equals(actual)
                || !entry.sourceGuid().equals(source)
                || !entry.objectId().equals(id)
                || !Objects.equals(
                        row.get("parentPartId"), part.map(PartId::value).orElse(null))
                || !Objects.equals(
                        row.get("parentDeformerId"),
                        parent.map(DeformerId::value).orElse(null))
                || !initial.binding().equals(context.get().binding())) throw new Stale();
    }

    static final class Stale extends IllegalStateException {}
}
