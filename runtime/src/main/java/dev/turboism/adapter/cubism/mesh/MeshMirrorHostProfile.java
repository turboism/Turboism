package dev.turboism.adapter.cubism.mesh;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;

import java.util.Optional;

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
    LinkedDeletion linkedDeletion
) {
    /** Exact 5.2.03 selector for the native mirror/subtool eligibility predicate. */
    public record ToolEligibility(String method, String descriptor) { }

    /** Exact 5.2.03 selected-point movement loop that 5.3.02 extends with counterparts. */
    public record SelectedPointMove(String owner, String method, String descriptor) { }

    /**
     * Selectors for mirror-linked deletion, which Cubism ships natively from 5.3.02.
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
        String eraserPointRemoveDescriptor
    ) { }

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
        final String mirrorAxisDrawDescriptor
    ) {
        this(
            meshEditorOwner, mirrorPointMethod, mirrorAxisPointMethod, mirrorPointDescriptor,
            mirrorHitMethod, mirrorHitDescriptor, mirrorWidgetOwner, mirrorWidgetMethod,
            mirrorWidgetDescriptor, mirrorAxisDrawOwner, mirrorAxisDrawMethod,
            mirrorAxisDrawDescriptor, null, null, null
        );
    }

    private static final HostArtifactDigest CUBISM_52 = ReviewedHostArtifacts.CUBISM_5_2_03;
    private static final HostArtifactDigest CUBISM_53 = ReviewedHostArtifacts.CUBISM_5_3_02;
    private static final HostArtifactDigest CUBISM_5303 = ReviewedHostArtifacts.CUBISM_5_3_03;

    /** Returns the exact reviewed selector profile for the artifact, if it is supported. */
    public static Optional<MeshMirrorHostProfile> forArtifact(final HostArtifactDigest artifact) {
        if (CUBISM_52.equals(artifact)) return Optional.of(reviewed52());
        if (CUBISM_53.equals(artifact) || CUBISM_5303.equals(artifact)) {
            return Optional.of(reviewed52And53());
        }
        return Optional.empty();
    }

    /**
     * 5.2.03 adds the linked-deletion selectors; 5.3.02 must not receive them because it
     * already deletes mirror counterparts natively.
     */
    static MeshMirrorHostProfile reviewed52() {
        final MeshMirrorHostProfile shared = reviewed52And53();
        return new MeshMirrorHostProfile(
            shared.meshEditorOwner(), shared.mirrorPointMethod(), shared.mirrorAxisPointMethod(),
            shared.mirrorPointDescriptor(), shared.mirrorHitMethod(), shared.mirrorHitDescriptor(),
            shared.mirrorWidgetOwner(), shared.mirrorWidgetMethod(), shared.mirrorWidgetDescriptor(),
            shared.mirrorAxisDrawOwner(), shared.mirrorAxisDrawMethod(), shared.mirrorAxisDrawDescriptor(),
            new ToolEligibility(
                "a",
                "(Lcom/live2d/cubism/view/palette/tool/toolMode/meshEditor/ToolMode_MeshEdit_Manual$a;)Z"
            ),
            new SelectedPointMove(
                "com/live2d/cubism/view/context/action/action_meshEditor/d",
                "c", "(Lcom/live2d/cubism/view/context/actionManager/Z;)V"
            ),
            new LinkedDeletion(
                "com/live2d/cubism/view/context/action/action_meshEditor/d$g",
                "b", "(Lcom/live2d/cubism/view/context/actionManager/N;)V",
                "com/live2d/cubism/doc/modeling/CModelingEditMode_MeshEditor",
                "delete_exe", "(Ljava/util/List;Lcom/live2d/undo/GroupUndo;)V",
                "com/live2d/cubism/view/context/action/action_meshEditor/d$f",
                "b", "(Lcom/live2d/cubism/view/context/actionManager/N;)V",
                "com/live2d/cubism/view/context/actionManager/N",
                "a", "(Ljava/lang/String;)Lcom/live2d/undo/GroupUndo;",
                "com/live2d/graphics3d/editableMesh/GEditableMesh2",
                "removeEdge", "(Lcom/live2d/graphics3d/editableMesh/MEdge;)V",
                "com/live2d/cubism/view/context/action/p$b",
                "a", "(Lcom/live2d/cubism/view/context/actionManager/Z;Lcom/live2d/ui/event/l;Lcom/live2d/undo/GroupUndo;)V",
                "com/live2d/graphics3d/editableMesh/GEditableMeshHandler",
                "a", "(Ljava/util/List;)Lcom/live2d/undo/GroupUndo;",
                "com/live2d/graphics3d/editableMesh/GEditableMeshHandler",
                "a", "(Ljava/util/List;Z)Lcom/live2d/undo/GroupUndo;"
            )
        );
    }

    static MeshMirrorHostProfile reviewed52And53() {
        return new MeshMirrorHostProfile(
            "com/live2d/cubism/view/palette/tool/toolMode/meshEditor/g",
            "a", "b",
            "(Lcom/live2d/graphics3d/type/GVector2;)Lcom/live2d/graphics3d/type/GVector2;",
            "a", "(Lcom/live2d/graphics3d/type/GVector2;F)Z",
            "com/live2d/cubism/view/palette/tool/toolMode/meshEditor/ToolPanel_MeshEdit",
            "createWidgetMirrorEditForMeshEdit",
            "(Lcom/live2d/ui/control/CCheckBox;)Lcom/live2d/ui/container/CVBox;",
            "com/live2d/cubism/view/context/K", "a",
            "(FZFLcom/live2d/type/CColor;)V"
        );
    }
}
