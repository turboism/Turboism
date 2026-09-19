package dev.turboism.adapter.cubism.warpalt;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;

import java.util.Optional;

/**
 * Exact selector tuple for the Warp Deformer temporary-handler drag tick, recovered
 * from the reviewed Cubism 5.3.03 artifact by disassembly.
 *
 * <p>Two injection points cover the two known Warp control-point editing flows:</p>
 * <ul>
 *   <li>{@code WarpPointRef.moveToOnLocal(GVector2, float)} — the converged doc-level
 *       write of the deformer-edit flows; the bridge sees the point reference, the
 *       local target and the blend weight inside the gesture's undo envelope.</li>
 *   <li>{@code temporaryHandler.a.b(GVector2, aG)} — the drag-tick dispatcher of the
 *       dedicated bend (temporary handler) tool; the bridge sees the handler, the
 *       drag position and the modifier-carrying event.</li>
 * </ul>
 *
 * <p>Admission is fail-closed: only artifacts reviewed for this selector are
 * admitted, and every other host version returns an empty profile.</p>
 */
public record WarpAltMirrorHostProfile(
    String pointMoveOwner,
    String pointMoveMethod,
    String pointMoveDescriptor,
    String dragTickOwner,
    String dragTickMethod,
    String dragTickDescriptor
) {

    private static final HostArtifactDigest CUBISM_5303 = ReviewedHostArtifacts.CUBISM_5_3_03;

    /** Returns the exact reviewed selector profile for the artifact, if it is supported. */
    public static Optional<WarpAltMirrorHostProfile> forArtifact(final HostArtifactDigest artifact) {
        if (CUBISM_5303.equals(artifact)) {
            return Optional.of(reviewed5303());
        }
        return Optional.empty();
    }

    static WarpAltMirrorHostProfile reviewed5303() {
        return new WarpAltMirrorHostProfile(
            "com/live2d/cubism/doc/model/deformer/warp/WarpPointRef",
            "moveToOnLocal",
            "(Lcom/live2d/graphics3d/type/GVector2;F)V",
            "com/live2d/cubism/view/context/temporaryHandler/a",
            "b",
            "(Lcom/live2d/graphics3d/type/GVector2;"
                + "Lcom/live2d/cubism/view/context/actionManager/aG;)V"
        );
    }
}
