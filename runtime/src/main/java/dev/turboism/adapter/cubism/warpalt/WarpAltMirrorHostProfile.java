package dev.turboism.adapter.cubism.warpalt;

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

    private static final String WARP_POINT_REF =
        "com/live2d/cubism/doc/model/deformer/warp/WarpPointRef";
    private static final String DRAG_TICK =
        "com/live2d/cubism/view/context/temporaryHandler/a";
    private static final String STRIP = "com/live2d/cubism/view/context/a/b";
    private static final String GREEN_TICK = "com/live2d/cubism/doc/model/deformer/warp/a$b";

    private static final Map<String, Map<String, String>> REVIEWED_CLASS_SHA256 = Map.of(
        ReviewedHostArtifacts.CUBISM_5_2_03_VERSION, Map.of(
            WARP_POINT_REF, "e1dfde3066a17def1caa66052431793af47c04523b3347bb93572160ac97369d",
            DRAG_TICK, "1b22a324345a3f537804684c0e96044264c92de0e5da5fa85d1333351ff953eb",
            STRIP, "7a64d696813b44dda628487c4c47c64a9ebf99cc8bc00df01432ab8f64fc0293",
            GREEN_TICK, "da1685329d08c6566396b89aa28ccc9536132ba8f0faa0345b922b8d3e874e51"),
        ReviewedHostArtifacts.CUBISM_5_3_02_VERSION, Map.of(
            WARP_POINT_REF, "e1dfde3066a17def1caa66052431793af47c04523b3347bb93572160ac97369d",
            DRAG_TICK, "60db25516b5466387b9f60550a24fde61c6b963b66f61e83827ea750ca48a7e6",
            STRIP, "f212f2797f104f2a45e4c596101c49d4b484e9cb7b2dc289412f3424135d9566",
            GREEN_TICK, "67aeb05e99486a9851c21d098c4d325bee106e805038037d85ca9038f0a7a271"),
        ReviewedHostArtifacts.CUBISM_5_3_03_VERSION, Map.of(
            WARP_POINT_REF, "e1dfde3066a17def1caa66052431793af47c04523b3347bb93572160ac97369d",
            DRAG_TICK, "02f046569d3c3c83bc06cbac68dc62b3218f243bf473043c65822e1f94599e3e",
            STRIP, "bc29a9bca6a09b0aceeb7b61f0b68170c558870db7beec1f06ef142861d5bec4",
            GREEN_TICK, "d85901df5f0ae742ee7cb3d8019295240d9d9ca3d9c6deb59b8a75c50286a56a"));

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
