package dev.turboism.adapter.cubism;

import dev.turboism.sdk.cubism.event.CubismOperation;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterGroup;
import dev.turboism.sdk.cubism.model.ParameterGroups;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Permission-checked {@link Parameter} view for one model generation. */
final class PermissionCheckedParameter implements Parameter {
    private final CubismFacadeImpl facade;
    private final Parameter delegate;

    PermissionCheckedParameter(final CubismFacadeImpl facade, final Parameter delegate) {
        this.facade = facade;
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }
    @Override public dev.turboism.sdk.ui.appearance.model.ParameterAppearance ui() {
        facade.requireModelRead("parameter.ui");
        return delegate.ui();
    }

    @Override public dev.turboism.sdk.cubism.id.ParameterId id() { facade.requireModelRead("parameter.id"); return delegate.id(); }
    @Override public int index() {
        facade.requireModelRead("parameter.index");
        return delegate.index();
    }
    @Override public dev.turboism.sdk.cubism.model.FloatSequence keyValues() {
        facade.requireModelRead("parameter.keyValues");
        return delegate.keyValues();
    }
    @Override public java.util.Optional<String> name() { facade.requireModelRead("parameter.name"); return delegate.name(); }
    @Override public dev.turboism.sdk.cubism.model.ParameterType type() { facade.requireModelRead("parameter.type"); return delegate.type(); }
    @Override public java.util.Optional<Boolean> repeat() { facade.requireModelRead("parameter.repeat"); return delegate.repeat(); }
    @Override public boolean isBlendShape() {
        facade.requireModelRead("parameter.isBlendShape");
        return delegate.isBlendShape();
    }
    @Override public java.util.Optional<Boolean> combined() { facade.requireModelRead("parameter.combined"); return delegate.combined(); }
    @Override public java.util.Optional<dev.turboism.sdk.cubism.id.ParameterId> combinedWith() {
        facade.requireModelRead("parameter.combinedWith");
        return delegate.combinedWith();
    }
    @Override public List<dev.turboism.sdk.cubism.model.ParameterBinding> getParameterBindings() {
        facade.requireModelRead("parameter.getParameterBindings");
        return delegate.getParameterBindings();
    }
    @Override public void combineWith(
        final dev.turboism.sdk.cubism.id.ParameterId partnerId
    ) {
        facade.requireModelWrite("parameter.combineWith");
        facade.runSemantic(
            CubismOperation.COMBINE_PARAMETER,
            id().value(),
            delegate::combinedWith,
            () -> delegate.combineWith(partnerId)
        );
    }
    @Override public void uncombine() {
        facade.requireModelWrite("parameter.uncombine");
        facade.runSemantic(
            CubismOperation.UNCOMBINE_PARAMETER,
            id().value(),
            delegate::combinedWith,
            delegate::uncombine
        );
    }
    @Override public float getValue() { facade.requireModelRead("parameter.getValue"); return delegate.getValue(); }
    @Override public float getMinimumValue() { facade.requireModelRead("parameter.getMinimumValue"); return delegate.getMinimumValue(); }
    @Override public float getMaximumValue() { facade.requireModelRead("parameter.getMaximumValue"); return delegate.getMaximumValue(); }
    @Override public float getDefaultValue() { facade.requireModelRead("parameter.getDefaultValue"); return delegate.getDefaultValue(); }
    @Override public void resetToDefault() {
        facade.requireModelWrite("parameter.resetToDefault");
        facade.runSemantic(
            CubismOperation.RESET_PARAMETER_TO_DEFAULT,
            id().value(),
            delegate::getValue,
            () -> facade.parameterLifecycle.setValue(
                this,
                delegate.getDefaultValue(),
                delegate::setValue
            )
        );
    }
    @Override public void setValue(final float value) {
        facade.requireModelWrite("parameter.setValue");
        facade.runSemantic(
            CubismOperation.SET_PARAMETER_VALUE,
            id().value(),
            delegate::getValue,
            () -> facade.parameterLifecycle.setValue(this, value, delegate::setValue)
        );
    }
    @Override public void updateDefinition(
        final dev.turboism.sdk.cubism.model.ParameterDefinition definition
    ) {
        facade.requireModelWrite("parameter.updateDefinition");
        final dev.turboism.sdk.cubism.model.ParameterDefinition requested =
            Objects.requireNonNull(definition, "definition");
        facade.runSemanticComparingTo(
            CubismOperation.UPDATE_PARAMETER_DEFINITION,
            id().value(),
            this::definitionState,
            definitionState(requested),
            () -> delegate.updateDefinition(requested)
        );
    }

    private List<?> definitionState() {
        return List.of(
            delegate.id(),
            delegate.name(),
            delegate.type(),
            delegate.repeat(),
            delegate.getMinimumValue(),
            delegate.getDefaultValue(),
            delegate.getMaximumValue()
        );
    }

    private List<?> definitionState(
        final dev.turboism.sdk.cubism.model.ParameterDefinition definition
    ) {
        return List.of(
            definition.id(),
            Optional.of(definition.name()),
            definition.type(),
            Optional.of(definition.repeat()),
            definition.minimumValue(),
            definition.defaultValue(),
            definition.maximumValue()
        );
    }
}
