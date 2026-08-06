package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.id.ParameterId;

import java.util.List;
import java.util.NoSuchElementException;

/** Keyform Morph Targets of one Editor object, in stable host order. */
@PreviewApi
public interface MorphTargets {

    /** Returns all Morph Targets in stable host order. */
    List<MorphTarget> all();

    /**
     * Finds the Morph Target bound to the exact parameter.
     *
     * @throws NoSuchElementException when the parameter has no Morph Target
     */
    MorphTarget find(ParameterId id);

    /**
     * Returns the Morph Target bound to the exact parameter, or empty.
     */
    default java.util.Optional<MorphTarget> findById(final ParameterId id) {
        java.util.Objects.requireNonNull(id, "id");
        return all().stream().filter(target -> target.parameterId().equals(id)).findFirst();
    }
}
