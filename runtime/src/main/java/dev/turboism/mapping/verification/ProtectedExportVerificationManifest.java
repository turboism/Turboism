package dev.turboism.mapping.verification;

import java.util.List;
import java.util.Set;

/**
 * Exact-version static trust root for the protected-export orchestration slice.
 *
 * <p>The slice pins every host member the orchestrator needs: application/session access,
 * disposable-copy open/close, selector-driven deformer application, the native export
 * driver entry, the export dialog's bound model source, and the file-cache force-release
 * used to retire the dirty copy. Only the reviewed 5.2.03, 5.3.02 and 5.3.03 artifacts
 * admit it.</p>
 *
 * <p>The reviewed 5.2.03 record declares 134 selectors rather than 137: Cubism 5.2.03's
 * {@code CModelSource} predates the advanced-blend, alias and offscreen-rendering feature
 * predicates, so the three {@code contain*} gates for features the host cannot express are
 * absent from the record and from {@link #cubism52Aliases()}. They are not optional on 5.3.x —
 * the 5.3 records still pin all 137 selectors.</p>
 */
public final class ProtectedExportVerificationManifest {

    public static final String ADAPTER_SLICE_ID = "adapter.cubism.protected-export";
    public static final Set<String> CAPABILITY_IDS = Set.of(
        "cubism.protected-export.orchestration"
    );

    /** Cubism version reported for the reviewed 5.2.03 artifact. */
    public static final String CUBISM_VERSION_5_2_03 = "5.2.03";

    /** Cubism version reported for the reviewed 5.3.02 artifact. */
    public static final String CUBISM_VERSION_5_3_02 = "5.3.02";

    /** Cubism version reported for the reviewed 5.3.03 artifact. */
    public static final String CUBISM_VERSION_5_3_03 = "5.3.03";

    /** Reviewed protected-export record admitted for exact Cubism 5.2.03. */
    public static final ReviewedSliceRecord RECORD_5_2_03 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_2_03,
        "cubism-5.2.03.protected-export.static",
        "acd8d661ea858568a87365c739e7f3da8d1b5bb15d90e161bbd92cd64f6d2aed",
        CUBISM_VERSION_5_2_03,
        "cubism-5.2.03"
    );

    /** Reviewed protected-export record admitted for exact Cubism 5.3.02. */
    public static final ReviewedSliceRecord RECORD_5_3_02 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_3_02,
        "cubism-5.3.02.protected-export.static",
        "753120bffa37a5479c77d0faa0d76b5a02ee265b957952b5a69371cbf2fb1f30",
        CUBISM_VERSION_5_3_02,
        "cubism-5.3.02"
    );

    /** Reviewed protected-export record admitted for exact Cubism 5.3.03. */
    public static final ReviewedSliceRecord RECORD_5_3_03 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_3_03,
        "cubism-5.3.03.protected-export.static",
        "7f61fddf1ceacb0f26fe5556950d433fb0cc3d1b053b3efc6c9f536089444f54",
        CUBISM_VERSION_5_3_03,
        "cubism-5.3.03"
    );

    private static final List<ReviewedSliceRecord> RECORDS = List.of(
        RECORD_5_2_03, RECORD_5_3_02, RECORD_5_3_03
    );

    /** Feature-gate aliases that only exist on Cubism 5.3.x {@code CModelSource}. */
    private static final Set<String> CUBISM_5_3_ONLY_GATE_ALIASES = Set.of(
        "cubism.protected-export.model-source.contain-advanced-blend",
        "cubism.protected-export.model-source.contain-alias",
        "cubism.protected-export.model-source.contain-offscreen-rendering"
    );

    /** Every selector alias declared by the reviewed protected-export record. */
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.protected-export.app-controller.class",
        "cubism.protected-export.app-controller.close-content",
        "cubism.protected-export.app-controller.current-document",
        "cubism.protected-export.app-controller.current-project",
        "cubism.protected-export.app-controller.instance",
        "cubism.protected-export.app-controller.main-frame-ctrl",
        "cubism.protected-export.app-controller.open",
        "cubism.protected-export.art-mesh.class",
        "cubism.protected-export.art-mesh-form.class",
        "cubism.protected-export.art-mesh-form.positions",
        "cubism.protected-export.art-mesh-instance.calculated-form",
        "cubism.protected-export.art-mesh-instance.class",
        "cubism.protected-export.art-mesh-instance.source",
        "cubism.protected-export.deformer-source.children",
        "cubism.protected-export.deformer-source.class",
        "cubism.protected-export.deformer.guid",
        "cubism.protected-export.deformer.target-guid",
        "cubism.protected-export.document.class",
        "cubism.protected-export.document.edit-mode-current",
        "cubism.protected-export.document.edit-mode-main",
        "cubism.protected-export.document.file",
        "cubism.protected-export.document.file-content",
        "cubism.protected-export.document.mark-saved",
        "cubism.protected-export.document.model-source",
        "cubism.protected-export.document.selector",
        "cubism.protected-export.document.undo-manager",
        "cubism.protected-export.drawable-id.class",
        "cubism.protected-export.drawable-id.create",
        "cubism.protected-export.drawable-source.class",
        "cubism.protected-export.drawable.id",
        "cubism.protected-export.drawable.set-id",
        "cubism.protected-export.edit-mode-base.class",
        "cubism.protected-export.edit-mode.apply-deformer",
        "cubism.protected-export.edit-mode.class",
        "cubism.protected-export.export-dialog.class",
        "cubism.protected-export.export-dialog.model-source",
        "cubism.protected-export.export-driver.class",
        "cubism.protected-export.export-driver.export",
        "cubism.protected-export.export-driver.instance",
        "cubism.protected-export.extended-interpolation-type.class",
        "cubism.protected-export.file-cache.class",
        "cubism.protected-export.file-cache.handle-by-file",
        "cubism.protected-export.file-cache.handles",
        "cubism.protected-export.file-cache.instance",
        "cubism.protected-export.file-cache.remove",
        "cubism.protected-export.file-content.class",
        "cubism.protected-export.file-content.file",
        "cubism.protected-export.file-content.modified",
        "cubism.protected-export.file-handle.class",
        "cubism.protected-export.file-handle.file",
        "cubism.protected-export.file-handle.listeners",
        "cubism.protected-export.file-handle.loader",
        "cubism.protected-export.file-handle.release",
        "cubism.protected-export.file-handle.unload",
        "cubism.protected-export.guid.class",
        "cubism.protected-export.guid.uuid-string",
        "cubism.protected-export.id.class",
        "cubism.protected-export.id.id-string",
        "cubism.protected-export.keyform-binding.class",
        "cubism.protected-export.keyform-binding.extended-type",
        "cubism.protected-export.keyform-binding.illegal-extended",
        "cubism.protected-export.keyform-binding.keys",
        "cubism.protected-export.keyform-binding.parameter-id",
        "cubism.protected-export.keyform-grid.bindings",
        "cubism.protected-export.keyform-grid.class",
        "cubism.protected-export.main-frame-ctrl.class",
        "cubism.protected-export.main-frame-ctrl.main-frame",
        "cubism.protected-export.model-source.all-art-meshes",
        "cubism.protected-export.model-source.all-deformers",
        "cubism.protected-export.model-source.all-motion-sync-settings",
        "cubism.protected-export.model-source.all-objects",
        "cubism.protected-export.model-source.all-parameters",
        "cubism.protected-export.model-source.all-parts",
        "cubism.protected-export.model-source.all-physics-settings",
        "cubism.protected-export.model-source.class",
        "cubism.protected-export.model-source.contain-advanced-blend",
        "cubism.protected-export.model-source.contain-alias",
        "cubism.protected-export.model-source.contain-art-path",
        "cubism.protected-export.model-source.contain-invert-clipping",
        "cubism.protected-export.model-source.contain-morph-target",
        "cubism.protected-export.model-source.contain-morph-target-enhancement",
        "cubism.protected-export.model-source.contain-motion-sync",
        "cubism.protected-export.model-source.contain-motion-sync-correction",
        "cubism.protected-export.model-source.contain-multiply-color",
        "cubism.protected-export.model-source.contain-offscreen-rendering",
        "cubism.protected-export.model-source.contain-quad-transform",
        "cubism.protected-export.model-source.contain-screen-color",
        "cubism.protected-export.model-source.current-instance",
        "cubism.protected-export.model-source.document",
        "cubism.protected-export.model-source.guid",
        "cubism.protected-export.model-source.root-part",
        "cubism.protected-export.model.class",
        "cubism.protected-export.model.all-art-meshes",
        "cubism.protected-export.model.parameter-set",
        "cubism.protected-export.model.reinit-instance-exe",
        "cubism.protected-export.morph-target-set.class",
        "cubism.protected-export.morph-target-set.morph-targets",
        "cubism.protected-export.parameter-id.class",
        "cubism.protected-export.parameter-instance.class",
        "cubism.protected-export.parameter-instance.id",
        "cubism.protected-export.parameter-instance.set-value",
        "cubism.protected-export.parameter-instance.value",
        "cubism.protected-export.parameter-set.class",
        "cubism.protected-export.parameter-set.parameters",
        "cubism.protected-export.parameter-source.default-value",
        "cubism.protected-export.parameter-source.id",
        "cubism.protected-export.parameter-source.max-value",
        "cubism.protected-export.parameter-source.min-value",
        "cubism.protected-export.parameter-source.name",
        "cubism.protected-export.parameter-source.repeat",
        "cubism.protected-export.parameter.class",
        "cubism.protected-export.part.child-guids",
        "cubism.protected-export.part.class",
        "cubism.protected-export.project.children",
        "cubism.protected-export.project.class",
        "cubism.protected-export.rotation-deformer.class",
        "cubism.protected-export.selector-interface.class",
        "cubism.protected-export.selector.add-source",
        "cubism.protected-export.selector.class",
        "cubism.protected-export.selector.clear",
        "cubism.protected-export.selector.selected",
        "cubism.protected-export.selector.selected-count",
        "cubism.protected-export.selector.selected-deformers",
        "cubism.protected-export.source.extended-keyform-grid",
        "cubism.protected-export.source.extended-morph-target-set",
        "cubism.protected-export.source.extensions",
        "cubism.protected-export.source.guid",
        "cubism.protected-export.source.keyform-morph-target-set",
        "cubism.protected-export.source.id",
        "cubism.protected-export.source.keyform-grid",
        "cubism.protected-export.source.local-name",
        "cubism.protected-export.source.set-local-name",
        "cubism.protected-export.undo-manager.can-undo",
        "cubism.protected-export.undo-manager.class",
        "cubism.protected-export.undo-manager.edit-count",
        "cubism.protected-export.undo-manager.position",
        "cubism.protected-export.warp-deformer.class"
    );

    /**
     * Exact selector roster carried by the reviewed 5.2.03 record: the full slice minus the
     * three {@code contain*} gates that Cubism 5.2.03's {@code CModelSource} does not
     * implement because the gated features did not exist in that release.
     */
    public static Set<String> cubism52Aliases() {
        final java.util.HashSet<String> aliases = new java.util.HashSet<>(REQUIRED_ALIASES);
        aliases.removeAll(CUBISM_5_3_ONLY_GATE_ALIASES);
        return Set.copyOf(aliases);
    }

    static PinnedVerifiedResolverWorkflow.Manifest forArtifact(final HostArtifactDigest artifact) {
        final ReviewedSliceRecord record =
            ReviewedSliceRecord.requireReviewed(RECORDS, artifact, "protected-export");
        if (RECORD_5_2_03.equals(record)) {
            return record.toManifest(ADAPTER_SLICE_ID, CAPABILITY_IDS, cubism52Aliases());
        }
        return record.toManifest(ADAPTER_SLICE_ID, CAPABILITY_IDS, REQUIRED_ALIASES);
    }

    private ProtectedExportVerificationManifest() {
    }
}
