package dev.turboism.adapter.cubism.mesh;

import dev.turboism.mapping.verification.HostArtifactDigest;

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
    private static final HostArtifactDigest CUBISM_52 = new HostArtifactDigest(
        40_805_584L,
        "bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd"
    );
    private static final HostArtifactDigest CUBISM_53 = new HostArtifactDigest(
        41_922_739L,
        "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21"
    );

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
