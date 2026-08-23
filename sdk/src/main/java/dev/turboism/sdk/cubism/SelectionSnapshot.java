package dev.turboism.sdk.cubism;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable snapshot of what the user has selected in the Editor.
 *
 * <p>The three "active" components are the focused object of each palette and are tracked
 * independently of {@code selectedObjectIds}: an identifier may be active without appearing in the
 * selection list, and vice versa.</p>
 *
 * @param selectedObjectIds unmodifiable copy of the selected object identifiers, in host order;
 *     empty when nothing is selected
 * @param activeParameterId focused parameter, empty when the parameter palette has no focus
 * @param activeArtMeshId focused art mesh, empty when no art mesh has focus
 * @param activeDeformerId focused deformer, empty when no deformer has focus
 */
public record SelectionSnapshot(
    List<String> selectedObjectIds,
    Optional<String> activeParameterId,
    Optional<String> activeArtMeshId,
    Optional<String> activeDeformerId
) {
    public SelectionSnapshot {
        selectedObjectIds = List.copyOf(Objects.requireNonNull(selectedObjectIds, "selectedObjectIds"));
        activeParameterId = Objects.requireNonNull(activeParameterId, "activeParameterId");
        activeArtMeshId = Objects.requireNonNull(activeArtMeshId, "activeArtMeshId");
        activeDeformerId = Objects.requireNonNull(activeDeformerId, "activeDeformerId");
    }
}
