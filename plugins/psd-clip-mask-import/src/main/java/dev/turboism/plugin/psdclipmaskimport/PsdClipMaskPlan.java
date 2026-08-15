package dev.turboism.plugin.psdclipmaskimport;

import dev.turboism.sdk.cubism.id.ArtMeshId;

import java.util.List;
import java.util.Objects;

/**
 * Plugin-private previewable import plan built from all PSD relationship
 * candidates of the active model.
 *
 * <p>Items keep PSD/source order. {@code assignments} are ready to write,
 * {@code conflicts} are ready to write only after explicit overwrite
 * confirmation, and {@code skips} are never written. Every item preserves all
 * contributing source layer references in order.</p>
 */
public record PsdClipMaskPlan(
    List<Assignment> assignments,
    List<Conflict> conflicts,
    List<Skip> skips
) {
    public PsdClipMaskPlan {
        assignments = List.copyOf(Objects.requireNonNull(assignments, "assignments"));
        conflicts = List.copyOf(Objects.requireNonNull(conflicts, "conflicts"));
        skips = List.copyOf(Objects.requireNonNull(skips, "skips"));
    }

    /** True when there is nothing the user could confirm. */
    public boolean isEmpty() {
        return assignments.isEmpty() && conflicts.isEmpty();
    }

    /** One contributing PSD relationship (document + layer) of an item. */
    public record SourceRef(String documentId, String layerId) {
        public SourceRef {
            documentId = Objects.requireNonNull(documentId, "documentId");
            layerId = Objects.requireNonNull(layerId, "layerId");
        }
    }

    /**
     * A target whose masks were aggregated from one or more relationships of a
     * single PSD document, with no overwrite conflict, in PSD/source order.
     */
    public record Assignment(
        ArtMeshId targetArtMeshId,
        List<ArtMeshId> orderedMaskArtMeshIds,
        List<SourceRef> sourceLayers
    ) {
        public Assignment {
            targetArtMeshId = Objects.requireNonNull(targetArtMeshId, "targetArtMeshId");
            orderedMaskArtMeshIds = List.copyOf(
                Objects.requireNonNull(orderedMaskArtMeshIds, "orderedMaskArtMeshIds")
            );
            sourceLayers = List.copyOf(Objects.requireNonNull(sourceLayers, "sourceLayers"));
        }
    }

    /**
     * A target whose current clip state differs from the planned state (masks
     * or inversion) and would be overwritten on confirmation. The planned
     * inversion is always {@code false}; {@code existingInverted} is the
     * current host value.
     */
    public record Conflict(
        ArtMeshId targetArtMeshId,
        List<ArtMeshId> existingMaskArtMeshIds,
        boolean existingInverted,
        List<ArtMeshId> plannedMaskArtMeshIds,
        List<SourceRef> sourceLayers
    ) {
        public Conflict {
            targetArtMeshId = Objects.requireNonNull(targetArtMeshId, "targetArtMeshId");
            existingMaskArtMeshIds = List.copyOf(
                Objects.requireNonNull(existingMaskArtMeshIds, "existingMaskArtMeshIds")
            );
            plannedMaskArtMeshIds = List.copyOf(
                Objects.requireNonNull(plannedMaskArtMeshIds, "plannedMaskArtMeshIds")
            );
            sourceLayers = List.copyOf(Objects.requireNonNull(sourceLayers, "sourceLayers"));
        }
    }

    /** An unresolvable, ambiguous, or no-change relationship that will never be written. */
    public record Skip(
        ArtMeshId targetArtMeshId,
        List<SourceRef> sourceLayers,
        SkipReason reason,
        String detail
    ) {
        public Skip {
            targetArtMeshId = Objects.requireNonNull(targetArtMeshId, "targetArtMeshId");
            sourceLayers = List.copyOf(Objects.requireNonNull(sourceLayers, "sourceLayers"));
            reason = Objects.requireNonNull(reason, "reason");
            detail = Objects.requireNonNull(detail, "detail");
        }
    }

    public enum SkipReason {
        /** The ArtMesh bound to the PSD layer does not exist in the model. */
        TARGET_UNRESOLVED,
        /** One or more mask identities from the clipping base subtree do not exist in the model. */
        MASK_UNRESOLVED,
        /** The clipping base layer id cannot be found in its PSD document. */
        BASE_LAYER_UNRESOLVED,
        /** The clipping base subtree resolves to no usable mask identities (empty or self-only). */
        NO_RESOLVED_MASKS,
        /** The target has valid relationships in more than one PSD document; ownership cannot be proven. */
        AMBIGUOUS_DOCUMENT,
        /** The planned ordered masks already match the current clip list; no write is needed. */
        ALREADY_MATCHES
    }
}
