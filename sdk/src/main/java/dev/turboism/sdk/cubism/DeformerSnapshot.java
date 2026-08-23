package dev.turboism.sdk.cubism;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable snapshot of one deformer node and its position in the deformer hierarchy.
 *
 * <p>The hierarchy is expressed by identifier rather than by object reference, so a snapshot can be
 * held after the underlying Editor objects have been mutated or disposed.</p>
 *
 * @param id stable Editor-assigned identifier of the deformer
 * @param name display name shown in the Editor's deformer tree
 * @param type which kind of deformer this is; {@link DeformerType#OTHER} when the host reported a
 *     kind this SDK does not model
 * @param parentId identifier of the enclosing deformer, empty for a root-level deformer
 * @param childIds unmodifiable copy of the identifiers of directly nested deformers, in host order
 */
public record DeformerSnapshot(
    String id,
    String name,
    DeformerType type,
    Optional<String> parentId,
    List<String> childIds
) implements ModelObjectSnapshot {
    public DeformerSnapshot {
        id = Objects.requireNonNull(id, "id");
        name = Objects.requireNonNull(name, "name");
        type = Objects.requireNonNull(type, "type");
        parentId = Objects.requireNonNull(parentId, "parentId");
        childIds = List.copyOf(Objects.requireNonNull(childIds, "childIds"));
    }
}
