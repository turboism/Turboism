package dev.turboism.adapter.cubism.warpalt;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;

import java.util.Optional;

/**
 * Exact selector tuple for the Warp Deformer temporary-handler drag tick, recovered
 * from the reviewed Cubism 5.2.03, 5.3.02 and 5.3.03 artifacts by disassembly.
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
 * <p>The strip selectors differ between obfuscation generations: the mount pass is
 * {@code L()V} on 5.2.03 and {@code R()V} on 5.3.02/5.3.03 — 5.2.03's own {@code R()}
 * is an unrelated delegate, so the mount name must come from the per-version
 * profile rather than a shared constant.</p>
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
    String dragTickDescriptor,
    String stripOwner,
    String stripMountMethod,
    String stripMountDescriptor,
    String stripLayoutMethod,
    String stripLayoutDescriptor,
    String greenTickOwner,
    String greenTickMethod,
    String greenTickDescriptor
) {

    private static final HostArtifactDigest CUBISM_5203 = ReviewedHostArtifacts.CUBISM_5_2_03;
    private static final HostArtifactDigest CUBISM_5302 = ReviewedHostArtifacts.CUBISM_5_3_02;
    private static final HostArtifactDigest CUBISM_5303 = ReviewedHostArtifacts.CUBISM_5_3_03;

    /** Returns the exact reviewed selector profile for the artifact, if it is supported. */
    public static Optional<WarpAltMirrorHostProfile> forArtifact(final HostArtifactDigest artifact) {
        if (CUBISM_5203.equals(artifact)) {
            return Optional.of(reviewed5203());
        }
        if (CUBISM_5302.equals(artifact) || CUBISM_5303.equals(artifact)) {
            return Optional.of(reviewed5302And5303());
        }
        return Optional.empty();
    }

    /**
     * 5.2.03 uses a different obfuscation generation for the strip mount pass
     * ({@code L()V}); every other selector is shared with 5.3.x.
     */
    static WarpAltMirrorHostProfile reviewed5203() {
        final WarpAltMirrorHostProfile shared = reviewed5302And5303();
        return new WarpAltMirrorHostProfile(
            shared.pointMoveOwner(), shared.pointMoveMethod(), shared.pointMoveDescriptor(),
            shared.dragTickOwner(), shared.dragTickMethod(), shared.dragTickDescriptor(),
            shared.stripOwner(), "L", "()V",
            shared.stripLayoutMethod(), shared.stripLayoutDescriptor(),
            shared.greenTickOwner(), shared.greenTickMethod(), shared.greenTickDescriptor()
        );
    }

    /** Selectors verified identical between the reviewed 5.3.02 and 5.3.03 artifacts. */
    static WarpAltMirrorHostProfile reviewed5302And5303() {
        return new WarpAltMirrorHostProfile(
            "com/live2d/cubism/doc/model/deformer/warp/WarpPointRef",
            "moveToOnLocal",
            "(Lcom/live2d/graphics3d/type/GVector2;F)V",
            "com/live2d/cubism/view/context/temporaryHandler/a",
            "b",
            "(Lcom/live2d/graphics3d/type/GVector2;"
                + "Lcom/live2d/cubism/view/context/actionManager/aG;)V",
            "com/live2d/cubism/view/context/a/b",
            "R", "()V",
            "a",
            "(Lcom/live2d/cubism/view/context/actionManager/N;"
                + "Lcom/live2d/graphics3d/entity/GEntity;)V",
            "com/live2d/cubism/doc/model/deformer/warp/a$b",
            "a",
            "(Lcom/live2d/cubism/view/context/actionManager/aG;)V"
        );
    }
}
