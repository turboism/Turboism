package dev.turboism.adapter.cubism;

import dev.turboism.sdk.cubism.model.ParameterGroup;
import dev.turboism.sdk.cubism.model.Parameters;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Permission-checked {@link ParameterGroup} view for one model generation. */
final class PermissionCheckedParameterGroup implements ParameterGroup {
    private final CubismFacadeImpl facade;
    private final ParameterGroup delegate;

    PermissionCheckedParameterGroup(final CubismFacadeImpl facade, final ParameterGroup delegate) {
        this.facade = facade;
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }
    @Override public dev.turboism.sdk.ui.appearance.model.ParameterGroupAppearance ui() {
        facade.requireModelRead("parameterGroup.ui");
        return delegate.ui();
    }

    @Override public dev.turboism.sdk.cubism.id.ParameterGroupId id() {
        facade.requireModelRead("parameterGroup.id");
        return delegate.id();
    }
    @Override public java.util.Optional<String> name() {
        facade.requireModelRead("parameterGroup.name");
        return delegate.name();
    }
    @Override public java.util.Optional<dev.turboism.sdk.cubism.id.ParameterGroupId> parentId() {
        facade.requireModelRead("parameterGroup.parentId");
        return delegate.parentId();
    }
    @Override public List<dev.turboism.sdk.cubism.id.ParameterGroupId> childGroupIds() {
        facade.requireModelRead("parameterGroup.childGroupIds");
        return delegate.childGroupIds();
    }
    @Override public List<dev.turboism.sdk.cubism.id.ParameterId> parameterIds() {
        facade.requireModelRead("parameterGroup.parameterIds");
        return delegate.parameterIds();
    }


    @Override public void rename(final String name) {
        facade.requireModelWrite("parameterGroup.rename");
        delegate.rename(name);
    }
}
