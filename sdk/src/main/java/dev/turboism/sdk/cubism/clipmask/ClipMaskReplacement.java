package dev.turboism.sdk.cubism.clipmask;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.id.ArtMeshId;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** One conditional ArtMesh clip-mask replacement in an all-or-nothing batch. */
@PreviewApi
public record ClipMaskReplacement(
    ArtMeshId targetArtMeshId,
    List<ArtMeshId> expectedMaskArtMeshIds,
    boolean expectedInverted,
    List<ArtMeshId> replacementMaskArtMeshIds,
    boolean replacementInverted
) {
    public ClipMaskReplacement {
        targetArtMeshId = Objects.requireNonNull(targetArtMeshId, "targetArtMeshId");
        expectedMaskArtMeshIds = List.copyOf(
            Objects.requireNonNull(expectedMaskArtMeshIds, "expectedMaskArtMeshIds")
        );
        replacementMaskArtMeshIds = List.copyOf(
            Objects.requireNonNull(replacementMaskArtMeshIds, "replacementMaskArtMeshIds")
        );
        if (replacementMaskArtMeshIds.isEmpty()) {
            throw new IllegalArgumentException("replacementMaskArtMeshIds must not be empty");
        }
        rejectInvalid(targetArtMeshId, expectedMaskArtMeshIds, "expectedMaskArtMeshIds");
        rejectInvalid(targetArtMeshId, replacementMaskArtMeshIds, "replacementMaskArtMeshIds");
    }

    private static void rejectInvalid(
        final ArtMeshId targetArtMeshId,
        final List<ArtMeshId> maskArtMeshIds,
        final String fieldName
    ) {
        final HashSet<ArtMeshId> unique = new HashSet<>();
        for (ArtMeshId maskArtMeshId : maskArtMeshIds) {
            Objects.requireNonNull(maskArtMeshId, fieldName + " element");
            if (targetArtMeshId.equals(maskArtMeshId)) {
                throw new IllegalArgumentException("an ArtMesh cannot mask itself");
            }
            if (!unique.add(maskArtMeshId)) {
                throw new IllegalArgumentException(fieldName + " must not contain duplicates");
            }
        }
    }
}
