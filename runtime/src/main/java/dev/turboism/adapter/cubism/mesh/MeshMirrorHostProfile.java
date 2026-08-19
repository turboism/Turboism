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
    String mirrorAxisDrawDescriptor
) {
    private static final HostArtifactDigest CUBISM_52 = ReviewedHostArtifacts.CUBISM_5_2_03;
    private static final HostArtifactDigest CUBISM_53 = ReviewedHostArtifacts.CUBISM_5_3_02;

    public static Optional<MeshMirrorHostProfile> forArtifact(final HostArtifactDigest artifact) {
        if (!CUBISM_52.equals(artifact) && !CUBISM_53.equals(artifact)) return Optional.empty();
        return Optional.of(reviewed52And53());
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
