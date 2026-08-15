package dev.turboism.plugin.psdclipmaskimport;

import dev.turboism.sdk.cubism.clipmask.PsdClipMaskDocumentSnapshot;
import dev.turboism.sdk.cubism.clipmask.PsdClipMaskDocumentSnapshot.PsdLayerSnapshot;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.model.Drawable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds the previewable batch plan from every PSD clipping relationship of
 * the active model, preserving PSD/source order.
 *
 * <p>Relationships of the same target are aggregated: masks are merged in
 * source-relationship order (each relationship contributing its base-subtree
 * masks in order) with first-occurrence dedupe, and every contributing source
 * layer is preserved. All clipping candidates are recorded before validity is
 * judged; an invalid relationship never poisons a later valid one, and a
 * target with any clipping candidate in more than one PSD document cannot
 * prove unique ownership and fails closed as {@code AMBIGUOUS_DOCUMENT}.
 * Empty or self-only mask sets are explicit {@code NO_RESOLVED_MASKS} skips.
 * A target whose ordered clip list and inversion already match the planned
 * write is an explicit {@code ALREADY_MATCHES} no-change; any differing
 * current state (masks or inversion) is an overwrite conflict.</p>
 */
public final class PsdClipMaskPlanner {

    public PsdClipMaskPlan plan(
        final List<PsdClipMaskDocumentSnapshot> psdDocuments,
        final List<Drawable> drawables
    ) {
        Objects.requireNonNull(psdDocuments, "psdDocuments");
        Objects.requireNonNull(drawables, "drawables");

        final Set<ArtMeshId> modelDrawableIds = new HashSet<>();
        final Map<ArtMeshId, Drawable> drawableById = new LinkedHashMap<>();
        for (Drawable drawable : drawables) {
            if (drawableById.putIfAbsent(drawable.id(), drawable) != null) {
                throw new IllegalArgumentException("duplicate model drawable id: " + drawable.id());
            }
            modelDrawableIds.add(drawable.id());
        }

        final List<PsdClipMaskPlan.Skip> skips = new ArrayList<>();
        final Map<ArtMeshId, TargetAggregate> aggregates = new LinkedHashMap<>();
        final Map<ArtMeshId, List<PsdClipMaskPlan.SourceRef>> candidates = new LinkedHashMap<>();
        final Set<String> documentIds = new HashSet<>();

        for (PsdClipMaskDocumentSnapshot document : psdDocuments) {
            // Two distinct documents must never share one stable identity: that
            // would be misread as a single document and merged into one write.
            if (!documentIds.add(document.documentId())) {
                throw new IllegalArgumentException(
                    "duplicate PSD document id: " + document.documentId()
                );
            }
            final Map<String, PsdLayerSnapshot> layersById = new LinkedHashMap<>();
            index(document.layers(), layersById);
            for (PsdLayerSnapshot layer : document.layers()) {
                planLayer(
                    document, layer, layersById,
                    modelDrawableIds, aggregates, candidates, skips
                );
            }
        }

        final List<PsdClipMaskPlan.Assignment> assignments = new ArrayList<>();
        final List<PsdClipMaskPlan.Conflict> conflicts = new ArrayList<>();
        for (Map.Entry<ArtMeshId, List<PsdClipMaskPlan.SourceRef>> entry : candidates.entrySet()) {
            resolveTarget(
                entry.getKey(), entry.getValue(), aggregates.get(entry.getKey()),
                drawableById, assignments, conflicts, skips
            );
        }
        return new PsdClipMaskPlan(assignments, conflicts, skips);
    }

    private void planLayer(
        final PsdClipMaskDocumentSnapshot document,
        final PsdLayerSnapshot layer,
        final Map<String, PsdLayerSnapshot> layersById,
        final Set<ArtMeshId> modelDrawableIds,
        final Map<ArtMeshId, TargetAggregate> aggregates,
        final Map<ArtMeshId, List<PsdClipMaskPlan.SourceRef>> candidates,
        final List<PsdClipMaskPlan.Skip> skips
    ) {
        if (layer.clipping()) {
            final String baseLayerId = layer.clippingBaseLayerId().orElse(null);
            final PsdLayerSnapshot baseLayer = baseLayerId == null ? null : layersById.get(baseLayerId);
            final PsdClipMaskPlan.SourceRef ref =
                new PsdClipMaskPlan.SourceRef(document.documentId(), layer.layerId());
            for (ArtMeshId target : layer.artMeshIds()) {
                if (!modelDrawableIds.contains(target)) {
                    skips.add(skip(target, document, layer,
                        PsdClipMaskPlan.SkipReason.TARGET_UNRESOLVED,
                        "ArtMesh is bound to the PSD layer but does not exist in the model."));
                    continue;
                }
                // Record the clipping candidate before validity so cross-document
                // ownership ambiguity is detected even when one side is invalid.
                final List<PsdClipMaskPlan.SourceRef> targetCandidates =
                    candidates.computeIfAbsent(target, ignored -> new ArrayList<>());
                if (!targetCandidates.contains(ref)) {
                    targetCandidates.add(ref);
                }
                if (baseLayer == null) {
                    skips.add(skip(target, document, layer,
                        PsdClipMaskPlan.SkipReason.BASE_LAYER_UNRESOLVED,
                        "Clipping layer has no resolvable base layer in the document"
                            + (baseLayerId == null ? "." : ": " + baseLayerId)));
                    continue;
                }
                final List<ArtMeshId> usableMasks = usableMasks(
                    target, baseLayer, modelDrawableIds, document, layer, skips
                );
                if (usableMasks == null) {
                    continue;
                }
                if (usableMasks.isEmpty()) {
                    skips.add(skip(target, document, layer,
                        PsdClipMaskPlan.SkipReason.NO_RESOLVED_MASKS,
                        "The clipping base subtree binds no usable ArtMesh masks (empty or self-only)."));
                    continue;
                }
                aggregates.computeIfAbsent(target, ignored -> new TargetAggregate()).add(
                    ref,
                    usableMasks
                );
            }
        }
        for (PsdLayerSnapshot child : layer.children()) {
            planLayer(document, child, layersById, modelDrawableIds, aggregates, candidates, skips);
        }
    }

    /**
     * Returns the relationship's usable masks (self-reference filtered, every
     * mask resolved against the model), or {@code null} when the relationship
     * must be skipped for {@code MASK_UNRESOLVED}.
     */
    private List<ArtMeshId> usableMasks(
        final ArtMeshId target,
        final PsdLayerSnapshot baseLayer,
        final Set<ArtMeshId> modelDrawableIds,
        final PsdClipMaskDocumentSnapshot document,
        final PsdLayerSnapshot layer,
        final List<PsdClipMaskPlan.Skip> skips
    ) {
        List<ArtMeshId> usable = null;
        for (ArtMeshId mask : collectMasks(baseLayer)) {
            if (target.equals(mask)) {
                continue; // an ArtMesh can never mask itself
            }
            if (!modelDrawableIds.contains(mask)) {
                skips.add(skip(target, document, layer,
                    PsdClipMaskPlan.SkipReason.MASK_UNRESOLVED,
                    "Mask identity does not exist in the model: " + mask.value()));
                return null;
            }
            if (usable == null) {
                usable = new ArrayList<>();
            }
            usable.add(mask);
        }
        return usable == null ? List.of() : usable;
    }

    private void resolveTarget(
        final ArtMeshId target,
        final List<PsdClipMaskPlan.SourceRef> candidateRefs,
        final TargetAggregate aggregate,
        final Map<ArtMeshId, Drawable> drawableById,
        final List<PsdClipMaskPlan.Assignment> assignments,
        final List<PsdClipMaskPlan.Conflict> conflicts,
        final List<PsdClipMaskPlan.Skip> skips
    ) {
        final Set<String> documents = new LinkedHashSet<>();
        for (PsdClipMaskPlan.SourceRef ref : candidateRefs) {
            documents.add(ref.documentId());
        }
        if (documents.size() > 1) {
            skips.add(new PsdClipMaskPlan.Skip(
                target,
                candidateRefs,
                PsdClipMaskPlan.SkipReason.AMBIGUOUS_DOCUMENT,
                "Target has clipping relationships in multiple PSD documents: " + String.join(", ", documents)
            ));
            return;
        }
        if (aggregate == null) {
            return; // every relationship was invalid; per-relationship skips already recorded
        }
        final List<PsdClipMaskPlan.SourceRef> refs = aggregate.refs();
        final List<ArtMeshId> planned = dedupeFirstOccurrence(aggregate.masks());
        final Drawable drawable = drawableById.get(target);
        final List<ArtMeshId> existing = drawable.maskIds();
        final boolean existingInverted = drawable.invertedMask();
        if (existing.equals(planned) && !existingInverted) {
            skips.add(new PsdClipMaskPlan.Skip(
                target,
                refs,
                PsdClipMaskPlan.SkipReason.ALREADY_MATCHES,
                "The ArtMesh clip list already matches the planned ordered masks."
            ));
        } else if (!existing.isEmpty() || existingInverted) {
            conflicts.add(new PsdClipMaskPlan.Conflict(
                target, existing, existingInverted, planned, refs
            ));
        } else {
            assignments.add(new PsdClipMaskPlan.Assignment(
                target, planned, refs
            ));
        }
    }

    private static PsdClipMaskPlan.Skip skip(
        final ArtMeshId target,
        final PsdClipMaskDocumentSnapshot document,
        final PsdLayerSnapshot layer,
        final PsdClipMaskPlan.SkipReason reason,
        final String detail
    ) {
        return new PsdClipMaskPlan.Skip(
            target,
            List.of(new PsdClipMaskPlan.SourceRef(document.documentId(), layer.layerId())),
            reason,
            detail
        );
    }

    private static List<ArtMeshId> dedupeFirstOccurrence(final List<ArtMeshId> masks) {
        final LinkedHashSet<ArtMeshId> unique = new LinkedHashSet<>(masks);
        return List.copyOf(unique);
    }

    /**
     * Collects ArtMesh bindings of the clipping base group in PSD order: the
     * base layer's own bindings first, then its nested children recursively
     * (legacy {@code collectMaskArtMeshSources} behavior). First-occurrence
     * order is preserved within one relationship.
     */
    private List<ArtMeshId> collectMasks(final PsdLayerSnapshot baseLayer) {
        final LinkedHashSet<ArtMeshId> masks = new LinkedHashSet<>();
        collectMasks(baseLayer, masks);
        return List.copyOf(masks);
    }

    private void collectMasks(final PsdLayerSnapshot layer, final LinkedHashSet<ArtMeshId> masks) {
        masks.addAll(layer.artMeshIds());
        for (PsdLayerSnapshot child : layer.children()) {
            collectMasks(child, masks);
        }
    }

    private void index(
        final List<PsdLayerSnapshot> layers,
        final Map<String, PsdLayerSnapshot> layersById
    ) {
        for (PsdLayerSnapshot layer : layers) {
            if (layersById.putIfAbsent(layer.layerId(), layer) != null) {
                throw new IllegalArgumentException("duplicate PSD layer id: " + layer.layerId());
            }
            index(layer.children(), layersById);
        }
    }

    /** Ordered per-target accumulation of valid relationships. */
    private static final class TargetAggregate {
        private final List<PsdClipMaskPlan.SourceRef> refs = new ArrayList<>();
        private final List<ArtMeshId> masks = new ArrayList<>();
        private final Set<PsdClipMaskPlan.SourceRef> seenRefs = new HashSet<>();

        void add(final PsdClipMaskPlan.SourceRef ref, final List<ArtMeshId> relationshipMasks) {
            if (seenRefs.add(ref)) {
                refs.add(ref);
                masks.addAll(relationshipMasks);
            }
        }

        List<PsdClipMaskPlan.SourceRef> refs() {
            return List.copyOf(refs);
        }

        List<ArtMeshId> masks() {
            return List.copyOf(masks);
        }
    }
}
