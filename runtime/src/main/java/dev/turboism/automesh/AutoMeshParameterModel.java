package dev.turboism.automesh;

import dev.turboism.sdk.cubism.mesh.automesh.AlgorithmParameterDescriptor;
import dev.turboism.sdk.cubism.mesh.automesh.AlgorithmPreset;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithm;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Editable parameter set of one algorithm, with the validation the dialog shows.
 *
 * <p>The model owns every rule the dialog needs: which control a descriptor asks for, whether a
 * typed value is acceptable, and what the reset target is. It holds no Swing type, so the whole
 * contract is testable without a display; the dialog only renders what this model reports.</p>
 *
 * <p>Validation is delegated to {@link AutoMeshValueCodec}, because one component must own the
 * mapping between declared bounds and the text a compute function receives. A rejected value keeps
 * the previous text and reports a message, so the dialog can never store a value the algorithm
 * would refuse at run time.</p>
 */
public final class AutoMeshParameterModel {

    /** The control a descriptor asks for. */
    public enum Control {
        /** A bounded integer: a spinner over {@code [min, max]} stepping by {@code step}. */
        STEPPER,
        /** A bounded decimal: a slider plus a field over {@code [min, max]}. */
        SLIDER,
        /** A boolean: a checkbox. */
        CHECKBOX,
        /** A single choice: a combo box over the declared options. */
        COMBO,
        /** Free text: a single-line field bounded by {@code maxLength}. */
        FIELD
    }

    /**
     * One editable row of the dialog, in declaration order.
     *
     * @param descriptor the declared parameter this row edits
     * @param control the control the dialog renders for it
     * @param value the current canonical value text
     * @param message the validation message of the last rejected edit, or empty
     */
    public record Row(
            AlgorithmParameterDescriptor descriptor, Control control, String value, Optional<String> message) {

        public Row {
            descriptor = Objects.requireNonNull(descriptor, "descriptor");
            control = Objects.requireNonNull(control, "control");
            value = Objects.requireNonNull(value, "value");
            message = Objects.requireNonNull(message, "message");
        }

        /** @return the declared parameter id this row edits */
        public String parameterId() {
            return descriptor.id();
        }

        /** @return the user-visible label the plugin declared */
        public String label() {
            return descriptor.label();
        }

        /** @return {@code true} while the last edit of this row was rejected */
        public boolean invalid() {
            return message.isPresent();
        }
    }

    private final AutoMeshAlgorithm algorithm;
    private final AutoMeshValueCodec codec;
    private final LinkedHashMap<String, String> values = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> messages = new LinkedHashMap<>();
    private final java.util.Set<String> declared;
    private Map<String, String> resetValues;

    /**
     * Creates the model of one algorithm, seeded with the values a run would use now.
     *
     * @param algorithm the algorithm whose schema bounds every value
     * @param currentValues the values the selection state holds; a missing id keeps its default
     * @throws IllegalArgumentException when a seeded value fails its descriptor
     */
    public AutoMeshParameterModel(final AutoMeshAlgorithm algorithm, final Map<String, String> currentValues) {
        this.algorithm = Objects.requireNonNull(algorithm, "algorithm");
        this.codec = AutoMeshValueCodec.forAlgorithm(algorithm);
        this.declared = algorithm.parameters().stream()
                .map(AlgorithmParameterDescriptor::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.values.putAll(codec.resolve(Objects.requireNonNull(currentValues, "currentValues")));
        this.resetValues = Map.copyOf(values);
    }

    /** @return the algorithm this model edits */
    public AutoMeshAlgorithm algorithm() {
        return algorithm;
    }

    /** @return the current canonical value of every declared parameter, in declaration order */
    public Map<String, String> values() {
        return Map.copyOf(values);
    }

    /** @return one row per declared parameter, in declaration order */
    public List<Row> rows() {
        final List<Row> rows = new ArrayList<>(algorithm.parameters().size());
        for (final AlgorithmParameterDescriptor descriptor : algorithm.parameters()) {
            rows.add(new Row(
                    descriptor,
                    controlOf(descriptor),
                    values.get(descriptor.id()),
                    Optional.ofNullable(messages.get(descriptor.id()))));
        }
        return List.copyOf(rows);
    }

    /** @return {@code true} while no row carries a rejected edit */
    public boolean valid() {
        return messages.isEmpty();
    }

    /**
     * Applies one typed value.
     *
     * @param parameterId the declared parameter to change
     * @param value the value text the control produced
     * @return {@code true} when the value was accepted; a rejected edit keeps the previous value
     * @throws IllegalArgumentException when the parameter is undeclared
     */
    public boolean set(final String parameterId, final String value) {
        requireDeclared(parameterId);
        final String canonical;
        try {
            canonical = codec.canonicalize(parameterId, value);
        } catch (IllegalArgumentException rejected) {
            messages.put(parameterId, rejected.getMessage());
            return false;
        }
        values.put(parameterId, canonical);
        messages.remove(parameterId);
        return true;
    }

    /**
     * Removes the rejection message of one row without changing its value.
     *
     * @param parameterId the declared parameter to clear
     */
    public void clearMessage(final String parameterId) {
        messages.remove(parameterId);
    }

    /**
     * Restores the values the model was created with.
     *
     * <p>The dialog opens on the selection state's current values, so "reset" means "back to what
     * the algorithm would have run before this dialog was opened" — the chosen preset when the
     * selection still carries one, and the hand-edited values otherwise.</p>
     */
    public void reset() {
        values.clear();
        values.putAll(resetValues);
        messages.clear();
    }

    /**
     * Applies one preset of the algorithm and makes it the new reset target.
     *
     * <p>Applying a preset is the user's explicit choice of a named value set, so "reset" must
     * return to that set rather than to whatever the dialog opened with.</p>
     *
     * @param presetName the preset to apply
     * @return {@code true} when the algorithm declares that preset
     */
    public boolean applyPreset(final String presetName) {
        final Optional<AlgorithmPreset> preset = algorithm.presets().stream()
                .filter(candidate -> candidate.name().equals(presetName))
                .findFirst();
        if (preset.isEmpty()) {
            return false;
        }
        final LinkedHashMap<String, String> applied = new LinkedHashMap<>(values);
        preset.get()
                .values()
                .forEach((parameterId, value) -> applied.put(parameterId, codec.canonicalize(parameterId, value)));
        values.clear();
        values.putAll(applied);
        messages.clear();
        resetValues = Map.copyOf(values);
        return true;
    }

    private void requireDeclared(final String parameterId) {
        if (!declared.contains(parameterId)) {
            throw new IllegalArgumentException("parameter is not declared by this algorithm: " + parameterId);
        }
    }

    /**
     * Returns the control a descriptor asks for.
     *
     * @param descriptor the declared parameter
     * @return the control the dialog renders for it
     */
    public static Control controlOf(final AlgorithmParameterDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        if (descriptor instanceof AlgorithmParameterDescriptor.IntParameter) {
            return Control.STEPPER;
        }
        if (descriptor instanceof AlgorithmParameterDescriptor.DoubleParameter) {
            return Control.SLIDER;
        }
        if (descriptor instanceof AlgorithmParameterDescriptor.BoolParameter) {
            return Control.CHECKBOX;
        }
        if (descriptor instanceof AlgorithmParameterDescriptor.EnumParameter) {
            return Control.COMBO;
        }
        return Control.FIELD;
    }
}
