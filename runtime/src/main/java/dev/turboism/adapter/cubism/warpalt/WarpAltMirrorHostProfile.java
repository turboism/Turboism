package dev.turboism.adapter.cubism.warpalt;

import dev.turboism.adapter.cubism.optimization.ClassPinTable;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.Map;
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
 *   <li>{@code doc.selection.PointSelector.add(IPointRef, float, boolean)} and
 *       {@code setWeight(IPointRef, float)} — the converged writes of every
 *       weighted-selection flow, including the Brush Selection Tool; the bridge
 *       sees the selector, the point reference and the incoming weight inside
 *       the caller's selection/undo envelope.</li>
 * </ul>
 *
 * <p>The strip selectors differ between obfuscation generations: the mount pass is
 * {@code L()V} on 5.2.03 and {@code R()V} on 5.3.02/5.3.03 — 5.2.03's own {@code R()}
 * is an unrelated delegate, so the mount name must come from the per-version
 * profile rather than a shared constant.</p>
 *
 * <p>These profiles supply reviewed selector contracts. Runtime binding verifies
 * each candidate's pinned classes against the actual host artifact; a declaration
 * or a profile lookup alone does not authorize the hook.</p>
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
        String greenTickDescriptor,
        String weightWriteOwner,
        String weightAddMethod,
        String weightAddDescriptor,
        String weightSetMethod,
        String weightSetDescriptor,
        String actionDispatchOwner,
        String actionDispatchMethod,
        String actionDispatchDescriptor,
        String actionClaimMethod,
        String actionClaimDescriptor,
        String inputIngressOwner,
        String inputIngressMethod,
        String inputIngressDescriptor) {

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
     * Returns the reviewed selector profile for a declared reviewed version,
     * independently of the whole-artifact digest. Callers must still verify the
     * pinned class digests in {@link #reviewedClassSha256()} against the actual
     * artifact — the version alone is never proof.
     */
    public static Optional<WarpAltMirrorHostProfile> forReviewedVersion(final String version) {
        if (ReviewedHostArtifacts.CUBISM_5_2_03_VERSION.equals(version)) {
            return Optional.of(reviewed5203());
        }
        if (ReviewedHostArtifacts.CUBISM_5_3_02_VERSION.equals(version)
                || ReviewedHostArtifacts.CUBISM_5_3_03_VERSION.equals(version)) {
            return Optional.of(reviewed5302And5303());
        }
        return Optional.empty();
    }

    /**
     * SHA-256 of each host class this hook transforms, keyed by internal name, for
     * every reviewed generation. A repackaged reviewed artifact — or any host whose
     * declared identity is not a reviewed release — is admissible only when every
     * pinned class entry of exactly one distinct contract matches.
     */
    public static Map<String, Map<String, String>> reviewedClassSha256() {
        return REVIEWED_CLASS_SHA256;
    }

    private static final String WARP_POINT_REF = "com/live2d/cubism/doc/model/deformer/warp/WarpPointRef";
    private static final String DRAG_TICK = "com/live2d/cubism/view/context/temporaryHandler/a";
    private static final String STRIP = "com/live2d/cubism/view/context/a/b";
    private static final String GREEN_TICK = "com/live2d/cubism/doc/model/deformer/warp/a$b";
    private static final String POINT_SELECTOR = "com/live2d/doc/selection/PointSelector";
    private static final String ACTION_DISPATCH = "com/live2d/cubism/view/context/actionManager/CEActionManager";
    private static final String INPUT_INGRESS = "com/live2d/cubism/view/context/CEViewContext";

    private static final Map<String, Map<String, String>> REVIEWED_CLASS_SHA256 = ClassPinTable.load("warp-alt-mirror");

    /**
     * 5.2.03 uses a different obfuscation generation for the strip mount pass
     * ({@code L()V}); every other selector is shared with 5.3.x.
     */
    static WarpAltMirrorHostProfile reviewed5203() {
        final WarpAltMirrorHostProfile shared = reviewed5302And5303();
        return new WarpAltMirrorHostProfile(
                shared.pointMoveOwner(),
                shared.pointMoveMethod(),
                shared.pointMoveDescriptor(),
                shared.dragTickOwner(),
                shared.dragTickMethod(),
                shared.dragTickDescriptor(),
                shared.stripOwner(),
                "L",
                "()V",
                shared.stripLayoutMethod(),
                shared.stripLayoutDescriptor(),
                shared.greenTickOwner(),
                shared.greenTickMethod(),
                shared.greenTickDescriptor(),
                shared.weightWriteOwner(),
                shared.weightAddMethod(),
                shared.weightAddDescriptor(),
                shared.weightSetMethod(),
                shared.weightSetDescriptor(),
                shared.actionDispatchOwner(),
                shared.actionDispatchMethod(),
                shared.actionDispatchDescriptor(),
                shared.actionClaimMethod(),
                shared.actionClaimDescriptor(),
                shared.inputIngressOwner(),
                shared.inputIngressMethod(),
                shared.inputIngressDescriptor());
    }

    /** Selectors verified identical between the reviewed 5.3.02 and 5.3.03 artifacts. */
    static WarpAltMirrorHostProfile reviewed5302And5303() {
        return new WarpAltMirrorHostProfile(
                "com/live2d/cubism/doc/model/deformer/warp/WarpPointRef",
                "moveToOnLocal",
                "(Lcom/live2d/graphics3d/type/GVector2;F)V",
                "com/live2d/cubism/view/context/temporaryHandler/a",
                "b",
                "(Lcom/live2d/graphics3d/type/GVector2;" + "Lcom/live2d/cubism/view/context/actionManager/aG;)V",
                "com/live2d/cubism/view/context/a/b",
                "R",
                "()V",
                "a",
                "(Lcom/live2d/cubism/view/context/actionManager/N;" + "Lcom/live2d/graphics3d/entity/GEntity;)V",
                "com/live2d/cubism/doc/model/deformer/warp/a$b",
                "b",
                "(Lcom/live2d/cubism/view/context/actionManager/N;)V",
                POINT_SELECTOR,
                "add",
                "(Lcom/live2d/doc/selection/IPointRef;FZ)Z",
                "setWeight",
                "(Lcom/live2d/doc/selection/IPointRef;F)V",
                ACTION_DISPATCH,
                "mouseAction",
                "(Lcom/live2d/cubism/view/context/actionManager/N;)V",
                "setCurrentAction",
                "(Lcom/live2d/cubism/view/context/actionManager/a;"
                        + "Lcom/live2d/cubism/view/context/actionManager/N;)V",
                INPUT_INGRESS,
                "onInputEvent_exe",
                "(Lcom/live2d/ui/event/h;)V");
    }
}
