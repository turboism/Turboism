package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.ui.appearance.model.ParameterAppearance;
import dev.turboism.sdk.cubism.id.ParameterId;
import java.util.Objects;
import java.util.Optional;
import java.util.List;

/** One Cubism parameter. */
public interface Parameter {

    /** Returns this parameter's stable identity within the model. */
    ParameterId id();

    /** Returns this Parameter's Cubism parameter-palette UI projection. */
    default ParameterAppearance ui() { return ParameterAppearance.unavailable(); }

    /** Returns this parameter's position within the model's parameter list. */
    default int index() {
        throw new UnsupportedOperationException("Cubism parameter index is unavailable.");
    }

    /** Returns the parameter's key values in declaration order. */
    default FloatSequence keyValues() {
        throw new UnsupportedOperationException("Cubism parameter key values are unavailable.");
    }

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

    /**
     * Returns the other parameter in this Editor four-corner pair.
     *
     * <p>The pair is structural: the first parameter carries the host Combined marker and
     * the immediately following parameter is its partner. Empty means this parameter is
     * not currently part of a verified pair or the backend cannot expose pair identity.</p>
     */
    default Optional<ParameterId> combinedWith() {
        return Optional.empty();
    }

    /** Returns this parameter's generation-bound Editor authoring bindings. */
    default List<ParameterBinding> getParameterBindings() {
        throw new UnsupportedOperationException(
            "Parameter binding projection is unavailable for this backend."
        );
    }

    /**
     * Creates this parameter's explicit Editor four-corner pairing.
     *
     * <p>The parameter and partner must be unpaired members of the same Editor parameter
     * group. Existing pairs are not silently replaced.</p>
     */
    default void combineWith(final ParameterId partnerId) {
        Objects.requireNonNull(partnerId, "partnerId");
        throw new UnsupportedOperationException(
            "Parameter Combined editing is unavailable for this backend."
        );
    }

    /** Removes this parameter's current Editor four-corner pairing. */
    default void uncombine() {
        throw new UnsupportedOperationException(
            "Parameter Combined editing is unavailable for this backend."
        );
    }

    /** Returns whether this parameter is a Blend Shape (morph) parameter. */
    default boolean isBlendShape() {
        return type() == ParameterType.BLEND_SHAPE;
    }

    /** Returns the parameter's current value. */
    float getValue();

    /** Returns the parameter's minimum value. */
    float getMinimumValue();

    /** Returns the parameter's maximum value. */
    float getMaximumValue();

    /** Returns the parameter's default value. */
    float getDefaultValue();

    /** Resets this parameter to its current default value through the normal write path. */
    default void resetToDefault() {
        setValue(getDefaultValue());
    }

    /**
     * Writes the parameter's value through the Editor authoring path when this object belongs to
     * an Editor document, including validation and Undo integration.
     */
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
