package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.id.ParameterId;
import java.util.Optional;

/** One Cubism parameter. */
@PreviewApi
public interface Parameter {

    ParameterId id();

    /**
     * Returns the user-facing parameter name when the active backend exposes it.
     *
     * <p>The ID is not substituted when a distinct display name is unavailable.</p>
     */
    default Optional<String> name() {
        return Optional.empty();
    }

    /** Returns the version-neutral semantic parameter type. */
    default ParameterType type() {
        return ParameterType.UNKNOWN;
    }

    /** Returns whether the parameter wraps around, or empty when unknown. */
    default Optional<Boolean> repeat() {
        return Optional.empty();
    }

    /**
     * Returns the Editor four-corner combined flag, or empty when the backend
     * does not expose an equivalent property.
     */
    default Optional<Boolean> combined() {
        return Optional.empty();
    }

    default boolean isBlendShape() {
        return type() == ParameterType.BLEND_SHAPE;
    }

    float getValue();

    float getMinimumValue();

    float getMaximumValue();

    float getDefaultValue();

    void setValue(float value);

    /**
     * Atomically updates this parameter's Editor authoring definition.
     *
     * <p>Backends that do not expose an Editor-native definition transaction fail
     * explicitly rather than mutating detached runtime metadata.</p>
     */
    default void updateDefinition(final ParameterDefinition definition) {
        throw new UnsupportedOperationException(
            "Parameter definition editing is unavailable for this backend."
        );
    }
}
