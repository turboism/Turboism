package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.FloatSequence;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterBinding;
import dev.turboism.sdk.cubism.model.ParameterDefinition;
import dev.turboism.sdk.cubism.model.ParameterType;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable, host-detached parameter projection safe to retain from an event callback. */
final class DetachedParameter implements Parameter {

    private final ParameterId id;
    private final Optional<String> name;
    private final ParameterType type;
    private final Optional<Boolean> repeat;
    private final Optional<Boolean> combined;
    private final Optional<ParameterId> combinedWith;
    private final float value;
    private final float minimumValue;
    private final float maximumValue;
    private final float defaultValue;

    private DetachedParameter(
        final ParameterId id,
        final Optional<String> name,
        final ParameterType type,
        final Optional<Boolean> repeat,
        final Optional<Boolean> combined,
        final Optional<ParameterId> combinedWith,
        final float value,
        final float minimumValue,
        final float maximumValue,
        final float defaultValue
    ) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.repeat = repeat;
        this.combined = combined;
        this.combinedWith = combinedWith;
        this.value = value;
        this.minimumValue = minimumValue;
        this.maximumValue = maximumValue;
        this.defaultValue = defaultValue;
    }

    static DetachedParameter capture(final Parameter parameter, final float value) {
        final Parameter source = Objects.requireNonNull(parameter, "parameter");
        return new DetachedParameter(
            source.id(),
            source.name(),
            source.type(),
            source.repeat(),
            source.combined(),
            source.combinedWith(),
            value,
            source.getMinimumValue(),
            source.getMaximumValue(),
            source.getDefaultValue()
        );
    }

    @Override public ParameterId id() { return id; }
    @Override public Optional<String> name() { return name; }
    @Override public ParameterType type() { return type; }
    @Override public Optional<Boolean> repeat() { return repeat; }
    @Override public Optional<Boolean> combined() { return combined; }
    @Override public Optional<ParameterId> combinedWith() { return combinedWith; }
    @Override public float getValue() { return value; }
    @Override public float getMinimumValue() { return minimumValue; }
    @Override public float getMaximumValue() { return maximumValue; }
    @Override public float getDefaultValue() { return defaultValue; }

    @Override
    public int index() {
        throw detached();
    }

    @Override
    public FloatSequence keyValues() {
        throw detached();
    }

    @Override
    public List<ParameterBinding> getParameterBindings() {
        throw detached();
    }

    @Override
    public void combineWith(final ParameterId partnerId) {
        Objects.requireNonNull(partnerId, "partnerId");
        throw detached();
    }

    @Override public void uncombine() { throw detached(); }
    @Override public void setValue(final float value) { throw detached(); }
    @Override public void updateDefinition(final ParameterDefinition definition) { throw detached(); }

    private static UnsupportedOperationException detached() {
        return new UnsupportedOperationException("Event parameter snapshots are read-only and host-detached.");
    }
}
