package dev.turboism.adapter.cubism.mesh;

import dev.turboism.adapter.cubism.optimization.ClassPinTable;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Exact selector tuple recovered from the reviewed legacy 5.2/5.3 path. */
public record MeshMirrorHostProfile(
        String meshEditorOwner,
        String mirrorPointMethod,
        String mirrorAxisPointMethod,
        String mirrorPointDescriptor,
        String mirrorHitMethod,
        String mirrorHitDescriptor,
        String mirrorWidgetOwner,
        String mirrorWidgetMethod,
        String mirrorWidgetDescriptor,
        String mirrorAxisDrawOwner,
        String mirrorAxisDrawMethod,
        String mirrorAxisDrawDescriptor,
        ToolEligibility toolEligibility,
        SelectedPointMove selectedPointMove,
        LinkedDeletion linkedDeletion) {
    /** Exact 5.2.x selector for the native mirror/subtool eligibility predicate. */
    public record ToolEligibility(String method, String descriptor) {}

    /** Exact 5.2.x selected-point movement loop that 5.3.x extends with counterparts. */
    public record SelectedPointMove(String owner, String method, String descriptor) {}

    /**
     * Selectors for mirror-linked deletion, which Cubism ships natively on 5.3.x.
     * Null on hosts that already have it, so the transformer never double-applies.
     *
     * <p>Each action names the enclosing method to transform plus the inner call to
     * intercept inside it. Interception is used rather than a fixed offset so the
     * injected step lands exactly where the host is about to delete.
     */
    public record LinkedDeletion(
            String pointActionOwner,
            String pointActionMethod,
            String pointActionDescriptor,
            String pointDeleteOwner,
            String pointDeleteMethod,
            String pointDeleteDescriptor,
            String edgeActionOwner,
            String edgeActionMethod,
            String edgeActionDescriptor,
            String edgeUndoOwner,
            String edgeUndoMethod,
            String edgeUndoDescriptor,
            String edgeRemoveOwner,
            String edgeRemoveMethod,
            String edgeRemoveDescriptor,
            String eraserActionOwner,
            String eraserActionMethod,
            String eraserActionDescriptor,
            String eraserRemoveOwner,
            String eraserRemoveMethod,
            String eraserRemoveDescriptor,
            String eraserPointRemoveOwner,
            String eraserPointRemoveMethod,
            String eraserPointRemoveDescriptor) {}

    /** Keeps the twelve-argument shape used by hosts that need no linked-deletion backport. */
    public MeshMirrorHostProfile(
            final String meshEditorOwner,
            final String mirrorPointMethod,
            final String mirrorAxisPointMethod,
            final String mirrorPointDescriptor,
            final String mirrorHitMethod,
            final String mirrorHitDescriptor,
            final String mirrorWidgetOwner,
            final String mirrorWidgetMethod,
            final String mirrorWidgetDescriptor,
            final String mirrorAxisDrawOwner,
            final String mirrorAxisDrawMethod,
            final String mirrorAxisDrawDescriptor) {
        this(
                meshEditorOwner,
                mirrorPointMethod,
                mirrorAxisPointMethod,
                mirrorPointDescriptor,
                mirrorHitMethod,
                mirrorHitDescriptor,
                mirrorWidgetOwner,
                mirrorWidgetMethod,
                mirrorWidgetDescriptor,
                mirrorAxisDrawOwner,
                mirrorAxisDrawMethod,
                mirrorAxisDrawDescriptor,
                null,
                null,
                null);
    }

    /**
     * Reviewed 5.2.x generations: the mirror selectors share one obfuscation
     * generation and none of these hosts delete mirror counterparts natively, so
     * every 5.2 profile carries the linked-deletion backport selectors.
     */
    private static final Set<String> REVIEWED_52_VERSIONS = Set.of(
            ReviewedHostArtifacts.CUBISM_5_2_00_VERSION,
            ReviewedHostArtifacts.CUBISM_5_2_01_VERSION,
            ReviewedHostArtifacts.CUBISM_5_2_02_VERSION,
            ReviewedHostArtifacts.CUBISM_5_2_03_VERSION);

    /**
     * Reviewed 5.3.x generations: the same selector tuple as 5.2, but every one
     * of these hosts deletes mirror counterparts natively, so no backport
     * selectors are installed.
     */
    private static final Set<String> REVIEWED_53_VERSIONS = Set.of(
            ReviewedHostArtifacts.CUBISM_5_3_00_VERSION,
            ReviewedHostArtifacts.CUBISM_5_3_01_VERSION,
            ReviewedHostArtifacts.CUBISM_5_3_02_VERSION,
            ReviewedHostArtifacts.CUBISM_5_3_03_VERSION,
            ReviewedHostArtifacts.CUBISM_5_3_04_VERSION);

    /** Returns the exact reviewed selector profile for the artifact, if it is supported. */
    public static Optional<MeshMirrorHostProfile> forArtifact(final HostArtifactDigest artifact) {
        return ReviewedHostArtifacts.cubismVersionOf(artifact).flatMap(MeshMirrorHostProfile::forReviewedVersion);
    }

    /**
     * Returns the reviewed selector profile for a declared reviewed version,
     * independently of the whole-artifact digest. Callers must still verify the
     * pinned class digests in {@link #reviewedClassSha256()} against the actual
     * artifact — the version alone is never proof.
     */
    public static Optional<MeshMirrorHostProfile> forReviewedVersion(final String version) {
        if (REVIEWED_52_VERSIONS.contains(version)) {
            return Optional.of(reviewed52());
        }
        if (REVIEWED_53_VERSIONS.contains(version)) {
            return Optional.of(reviewed52And53());
        }
        return Optional.empty();
    }

    /**
     * SHA-256 of every host class this hook transforms or calls into, keyed by
     * internal name, for every reviewed generation. The 5.2.x generations
     * additionally pin the linked-deletion action and edit-mode classes their
     * backport weaves.
     */
    public static Map<String, Map<String, String>> reviewedClassSha256() {
        return REVIEWED_CLASS_SHA256;
    }

    private static final Map<String, Map<String, String>> REVIEWED_CLASS_SHA256 = ClassPinTable.load("mesh-mirror");

    /**
     * 5.2.x adds the linked-deletion selectors; 5.3.x must not receive them because it
     * already deletes mirror counterparts natively.
     */
    static MeshMirrorHostProfile reviewed52() {
        final MeshMirrorHostProfile shared = reviewed52And53();
        return new MeshMirrorHostProfile(
                shared.meshEditorOwner(),
                shared.mirrorPointMethod(),
                shared.mirrorAxisPointMethod(),
                shared.mirrorPointDescriptor(),
                shared.mirrorHitMethod(),
                shared.mirrorHitDescriptor(),
                shared.mirrorWidgetOwner(),
                shared.mirrorWidgetMethod(),
                shared.mirrorWidgetDescriptor(),
                shared.mirrorAxisDrawOwner(),
                shared.mirrorAxisDrawMethod(),
                shared.mirrorAxisDrawDescriptor(),
                new ToolEligibility(
                        "a", "(Lcom/live2d/cubism/view/palette/tool/toolMode/meshEditor/ToolMode_MeshEdit_Manual$a;)Z"),
                new SelectedPointMove(
                        "com/live2d/cubism/view/context/action/action_meshEditor/d",
                        "c",
                        "(Lcom/live2d/cubism/view/context/actionManager/Z;)V"),
                new LinkedDeletion(
                        "com/live2d/cubism/view/context/action/action_meshEditor/d$g",
                        "b",
                        "(Lcom/live2d/cubism/view/context/actionManager/N;)V",
                        "com/live2d/cubism/doc/modeling/CModelingEditMode_MeshEditor",
                        "delete_exe",
                        "(Ljava/util/List;Lcom/live2d/undo/GroupUndo;)V",
                        "com/live2d/cubism/view/context/action/action_meshEditor/d$f",
                        "b",
                        "(Lcom/live2d/cubism/view/context/actionManager/N;)V",
                        "com/live2d/cubism/view/context/actionManager/N",
                        "a",
                        "(Ljava/lang/String;)Lcom/live2d/undo/GroupUndo;",
                        "com/live2d/graphics3d/editableMesh/GEditableMesh2",
                        "removeEdge",
                        "(Lcom/live2d/graphics3d/editableMesh/MEdge;)V",
                        "com/live2d/cubism/view/context/action/p$b",
                        "a",
                        "(Lcom/live2d/cubism/view/context/actionManager/Z;Lcom/live2d/ui/event/l;Lcom/live2d/undo/GroupUndo;)V",
                        "com/live2d/graphics3d/editableMesh/GEditableMeshHandler",
                        "a",
                        "(Ljava/util/List;)Lcom/live2d/undo/GroupUndo;",
                        "com/live2d/graphics3d/editableMesh/GEditableMeshHandler",
                        "a",
                        "(Ljava/util/List;Z)Lcom/live2d/undo/GroupUndo;"));
    }

    static MeshMirrorHostProfile reviewed52And53() {
        return new MeshMirrorHostProfile(
                "com/live2d/cubism/view/palette/tool/toolMode/meshEditor/g",
                "a",
                "b",
                "(Lcom/live2d/graphics3d/type/GVector2;)Lcom/live2d/graphics3d/type/GVector2;",
                "a",
                "(Lcom/live2d/graphics3d/type/GVector2;F)Z",
                "com/live2d/cubism/view/palette/tool/toolMode/meshEditor/ToolPanel_MeshEdit",
                "createWidgetMirrorEditForMeshEdit",
                "(Lcom/live2d/ui/control/CCheckBox;)Lcom/live2d/ui/container/CVBox;",
                "com/live2d/cubism/view/context/K",
                "a",
                "(FZFLcom/live2d/type/CColor;)V");
    }
}
