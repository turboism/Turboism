package dev.turboism.adapter.host;

import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.plugin.DisposableScope;

import java.util.Objects;

/** Runtime composition helper that invalidates one plugin's model references on scope close. */
public final class PluginScopedCubismModelAccess {

    private PluginScopedCubismModelAccess() {
    }

    public static CubismModelAccess bind(
        final CubismModelAccess delegate,
        final DisposableScope scope
    ) {
        final DynamicCubismModelAccess access = new DynamicCubismModelAccess();
        access.connect(Objects.requireNonNull(delegate, "delegate"));
        Objects.requireNonNull(scope, "scope").register(access::deactivate);
        return access;
    }
}
