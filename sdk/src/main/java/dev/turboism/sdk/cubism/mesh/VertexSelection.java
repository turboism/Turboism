package dev.turboism.sdk.cubism.mesh;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Immutable canonical vertex-index selection.
 *
 * <p><strong>Preview API:</strong> this contract may evolve before stabilization.</p>
 *
 * @param indices non-negative indices, canonicalized to ascending unique order
 */
public record VertexSelection(List<Integer> indices) {

    public VertexSelection {
        Objects.requireNonNull(indices, "indices");
        final var canonical = new TreeSet<Integer>();
        for (Integer index : indices) {
            Objects.requireNonNull(index, "indices must not contain null");
            if (index < 0) {
                throw new IllegalArgumentException("vertex indices must not be negative");
            }
            canonical.add(index);
        }
        indices = List.copyOf(new ArrayList<>(canonical));
    }

    /**
     * Returns the canonical empty selection.
     *
     * @return empty vertex selection
     */
    public static VertexSelection empty() {
        return new VertexSelection(List.of());
    }
}
