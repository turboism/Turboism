package dev.turboism.test.fake;

import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterBinding;
import dev.turboism.sdk.cubism.model.ParameterDefinition;
import dev.turboism.sdk.cubism.model.ParameterType;
import dev.turboism.sdk.ui.appearance.model.ParameterAppearance;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Configurable {@link Parameter} fake shared by module tests.
 *
 * <p>Only the SDK-spelled accessors are implemented. The legacy POJO
 * {@link FakeCubismParameter} remains for host-level tests that do not speak the SDK model API.
 */
public class FakeParameter implements Parameter {

    private ParameterId id;
    private String name;
    private ParameterType type = ParameterType.NORMAL;
    private Optional<Boolean> repeat = Optional.empty();
    private Optional<Boolean> combined = Optional.empty();
    private float minimumValue;
    private float maximumValue;
    private float defaultValue;
    /** Current value; tests may assign directly, matching common fixture style. */
    public float value;

    private ParameterAppearance ui = ParameterAppearance.unavailable();
    /** Parameter bindings exposed through {@link #getParameterBindings()}; tests may mutate this list. */
    public final List<ParameterBinding> bindings = new ArrayList<>();

    private Consumer<Float> beforeSetValue = ignored -> {};

    public FakeParameter(final String id) {
        this(new ParameterId(id), id);
    }

    public FakeParameter(final String id, final String name) {
        this(new ParameterId(id), name);
    }

    public FakeParameter(final String id, final String name, final ParameterType type, final boolean combined) {
        this(id, name, type, combined, 0f, 0f);
    }

    public FakeParameter(
            final String id,
            final String name,
            final ParameterType type,
            final boolean combined,
            final float minimumValue,
            final float maximumValue) {
        this(new ParameterId(id), name);
        this.type = Objects.requireNonNull(type, "type");
        this.combined = Optional.of(combined);
        this.minimumValue = minimumValue;
        this.maximumValue = maximumValue;
    }

    public FakeParameter(final ParameterId id, final String name) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = name;
    }

    /** Creates a parameter whose definition and initial value come from {@code definition}. */
    public static FakeParameter fromDefinition(final ParameterDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        return new FakeParameter(definition.id(), definition.name())
                .withType(definition.type())
                .withRepeat(definition.repeat())
                .withRange(definition.minimumValue(), definition.maximumValue())
                .withDefaultValue(definition.defaultValue())
                .withValue(definition.defaultValue());
    }

    public FakeParameter withType(final ParameterType newType) {
        this.type = Objects.requireNonNull(newType, "type");
        return this;
    }

    public FakeParameter withRepeat(final boolean newRepeat) {
        this.repeat = Optional.of(newRepeat);
        return this;
    }

    public FakeParameter withRange(final float newMinimum, final float newMaximum) {
        this.minimumValue = newMinimum;
        this.maximumValue = newMaximum;
        return this;
    }

    public FakeParameter withDefaultValue(final float newDefault) {
        this.defaultValue = newDefault;
        return this;
    }

    public FakeParameter withValue(final float newValue) {
        this.value = newValue;
        return this;
    }

    public FakeParameter withUi(final ParameterAppearance newUi) {
        this.ui = Objects.requireNonNull(newUi, "ui");
        return this;
    }

    /** Runs before the value is stored; throw to reject the write. */
    public FakeParameter beforeSetValue(final Consumer<Float> listener) {
        this.beforeSetValue = Objects.requireNonNull(listener, "listener");
        return this;
    }

    @Override
    public ParameterId id() {
        return id;
    }

    @Override
    public Optional<String> name() {
        return Optional.ofNullable(name);
    }

    @Override
    public ParameterType type() {
        return type;
    }

    @Override
    public Optional<Boolean> repeat() {
        return repeat;
    }

    @Override
    public Optional<Boolean> combined() {
        return combined;
    }

    @Override
    public ParameterAppearance ui() {
        return ui;
    }

    @Override
    public List<ParameterBinding> getParameterBindings() {
        return bindings;
    }

    @Override
    public float getValue() {
        return value;
    }

    @Override
    public float getMinimumValue() {
        return minimumValue;
    }

    @Override
    public float getMaximumValue() {
        return maximumValue;
    }

    @Override
    public float getDefaultValue() {
        return defaultValue;
    }

    @Override
    public void setValue(final float requestedValue) {
        beforeSetValue.accept(requestedValue);
        value = requestedValue;
    }
}
