package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.ui.appearance.model.ParameterAppearance;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One Cubism parameter.
 *
 * <p>This is the recommended parameter handle: {@link #getValue()} reads the current Editor
 * authoring value and {@link #setValue(float)} performs a single validated, undoable write.
 * To group several writes into one Editor Undo unit, run them inside {@link
 * dev.turboism.sdk.cubism.CubismFacade#authoringTransactions()}.
 *
 * <p>Other types named like a parameter are not write handles:
 * {@link dev.turboism.sdk.cubism.ParameterSnapshot} is an immutable read snapshot,
 * {@link ParameterDefinition} is an authoring-definition payload for {@link #updateDefinition},
 * {@link dev.turboism.sdk.cubism.core.OwnedParameter} belongs to the detached owned model, and
 * {@link dev.turboism.sdk.cubism.edit.EditParameterNode} is a node of the incubating
 * edit-session structure tree.
 */
public interface Parameter {

    /** Returns this parameter's stable identity within the model. */
    ParameterId id();

    /** Returns this Parameter's Cubism parameter-palette UI projection. */
    default ParameterAppearance ui() {
        return ParameterAppearance.unavailable();
    }

    /**
     * Returns this parameter's position within the model's parameter list.
     * @throws UnsupportedOperationException when the backend does not expose the parameter index
     */
    default int index() {
        throw new UnsupportedOperationException("Cubism parameter index is unavailable.");
    }

    /**
     * Returns the parameter's key values in declaration order.
     * @throws UnsupportedOperationException when the backend does not expose key values
     */
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

    /**
     * Returns this parameter's generation-bound Editor authoring bindings.
     * @throws UnsupportedOperationException when the backend does not expose parameter bindings
     */
    default List<ParameterBinding> getParameterBindings() {
        throw new UnsupportedOperationException("Parameter binding projection is unavailable for this backend.");
    }

    /**
     * Creates this parameter's explicit Editor four-corner pairing.
     *
     * <p>The parameter and partner must be unpaired members of the same Editor parameter
     * group. Existing pairs are not silently replaced.</p>
     * @throws UnsupportedOperationException when the backend does not support parameter combining
     */
    default void combineWith(final ParameterId partnerId) {
        Objects.requireNonNull(partnerId, "partnerId");
        throw new UnsupportedOperationException("Parameter Combined editing is unavailable for this backend.");
    }

    /**
     * Removes this parameter's current Editor four-corner pairing.
     * @throws UnsupportedOperationException when the backend does not support parameter combining
     */
    default void uncombine() {
        throw new UnsupportedOperationException("Parameter Combined editing is unavailable for this backend.");
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
     *
     * <p>Prefer this method for single writes — do not reach for an edit session or a command
     * queue. When several writes must land as one Undo unit, wrap them in {@link
     * dev.turboism.sdk.cubism.CubismFacade#authoringTransactions()} instead of calling this method
     * in a loop.
     */
    void setValue(float value);

    /**
     * Atomically updates this parameter's Editor authoring definition.
     *
     * <p>Backends that do not expose an Editor-native definition transaction fail
     * explicitly rather than mutating detached runtime metadata.</p>
     * @throws UnsupportedOperationException when the backend does not support parameter definition editing
     */
    default void updateDefinition(final ParameterDefinition definition) {
        throw new UnsupportedOperationException("Parameter definition editing is unavailable for this backend.");
    }
}
