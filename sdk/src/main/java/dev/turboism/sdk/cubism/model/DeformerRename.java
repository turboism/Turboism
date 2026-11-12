package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.id.DeformerId;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * One conditional deformer rename in an all-or-nothing batch.
 *
 * <p>The expected name makes the batch conditional: when any target no longer carries the name the
 * caller previewed, the whole batch is refused instead of renaming a deformer the operator never
 * saw. The whole batch is one Editor edit, so it undoes as one step.</p>
 *
 * @param deformerId the deformer to rename
 * @param expectedName the name the caller observed, used as the precondition
 * @param replacementName the name to apply
 */
@CubismEditor
public record DeformerRename(DeformerId deformerId, String expectedName, String replacementName) {
    public DeformerRename {
        deformerId = Objects.requireNonNull(deformerId, "deformerId");
        expectedName = Objects.requireNonNull(expectedName, "expectedName");
        replacementName = Objects.requireNonNull(replacementName, "replacementName");
        if (replacementName.isBlank()) {
            throw new IllegalArgumentException("replacementName must not be blank");
        }
        if (replacementName.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("replacementName must not exceed " + MAX_NAME_LENGTH + " characters");
        }
    }

    /** Longest name this batch accepts, matching the model-object name limit. */
    public static final int MAX_NAME_LENGTH = 256;

    /**
     * Rejects a batch that names one deformer twice.
     *
     * @param renames the batch to validate
     * @return the same list, defensively copied
     * @throws IllegalArgumentException when a deformer appears more than once
     */
    public static List<DeformerRename> requireUnique(final List<DeformerRename> renames) {
        final List<DeformerRename> batch = List.copyOf(Objects.requireNonNull(renames, "renames"));
        final HashSet<DeformerId> seen = new HashSet<>();
        for (DeformerRename rename : batch) {
            if (!seen.add(rename.deformerId())) {
                throw new IllegalArgumentException("renames must not repeat a deformer: "
                        + rename.deformerId().value());
            }
        }
        return batch;
    }
}
