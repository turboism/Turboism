package dev.turboism.mapping.verification.selector;

import java.util.Set;

/**
 * Exact additive contract for the BoundingBox overlay whole-object Warp mirror.
 *
 * <p>Bundles the shared object read/write surface with the Warp-grid transform,
 * calculated-Form, exact-keyform-membership, and detached-copy selectors the mirror
 * compensation solver needs. Admission requires the capability
 * {@code cubism.editor-model.warp-mirror} on an exact reviewed record.</p>
 */
public final class EditorWarpMirrorSelectorContract {

    public static final String ADAPTER_SLICE_ID = EditorObjectReadSelectorContract.ADAPTER_SLICE_ID;
    public static final String CAPABILITY_ID = "cubism.editor-model.warp-mirror";

    /**
     * Mirror-specific selectors beyond the shared object read/write contracts:
     * evaluated descendant capture, native Warp-grid transforms, canvas/local
     * conversions, stored-keyform membership, detached Form copies, and the
     * native keyform-edit Undo admission used inside the authoring transaction.
     */
    private static final Set<String> MIRROR_ALIASES = Set.of(
        "cubism.editor-model.parameter-controllable.calculated-form",
        "cubism.editor-model.parameter-controllable-source.default-key-form",
        "cubism.editor-model.warp-grid.simple.create",
        "cubism.editor-model.warp-grid.transform.create",
        "cubism.editor-model.warp-grid.transform.inverse",
        "cubism.editor-model.warp-grid.transform.forward",
        "cubism.editor-model.deformer.transform-local-to-canvas",
        "cubism.editor-model.deformer.transform-canvas-to-local",
        "cubism.editor-model.art-mesh-source.keyforms",
        "cubism.editor-model.warp-source.keyforms",
        "cubism.editor-model.rotation-source.keyforms",
        "cubism.editor-model.copy-helper.copy",
        "cubism.editor-model.parameter-controllable-handler.create-undo-for-keyform-edit"
    );

    public static final Set<String> REQUIRED_ALIASES = union(
        union(
            EditorObjectReadSelectorContract.REQUIRED_ALIASES,
            union(
                union(
                    EditorObjectWriteSelectorContract.WARP_REQUIRED_ALIASES,
                    EditorObjectWriteSelectorContract.ART_MESH_REQUIRED_ALIASES
                ),
                EditorObjectWriteSelectorContract.ROTATION_REQUIRED_ALIASES
            )
        ),
        union(
            EditorHistoryReadSelectorContract.REQUIRED_ALIASES,
            MIRROR_ALIASES
        )
    );

    private static Set<String> union(final Set<String> left, final Set<String> right) {
        final java.util.HashSet<String> values = new java.util.HashSet<>(left);
        values.addAll(right);
        return Set.copyOf(values);
    }

    private EditorWarpMirrorSelectorContract() { }
}
