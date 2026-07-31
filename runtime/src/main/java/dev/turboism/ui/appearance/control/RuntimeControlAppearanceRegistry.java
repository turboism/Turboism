package dev.turboism.ui.appearance.control;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.appearance.ControlAppearanceContribution;
import dev.turboism.sdk.ui.appearance.ControlAppearanceRegistry;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Plugin-scoped registry for transient native-control appearance contributions. */
public final class RuntimeControlAppearanceRegistry implements ControlAppearanceRegistry {
    private final String pluginId;
    private final long pluginGeneration;
    private final PermissionChecker permissionChecker;
    private final ControlAppearanceCoordinator coordinator;

    public RuntimeControlAppearanceRegistry(
        final String pluginId,
        final long pluginGeneration,
        final PermissionChecker permissionChecker,
        final ControlAppearanceCoordinator coordinator
    ) {
        this.pluginId = requireText(pluginId, "pluginId");
        if (pluginGeneration < 0) {
            throw new IllegalArgumentException("pluginGeneration must not be negative");
        }
        this.pluginGeneration = pluginGeneration;
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        coordinator.removePlugin(pluginId, pluginGeneration);
    }

    public void bind(final dev.turboism.sdk.plugin.DisposableScope scope) {
        Objects.requireNonNull(scope, "scope").register(
            () -> coordinator.removePlugin(pluginId, pluginGeneration)
        );
    }

    @Override
    public Registration register(final ControlAppearanceContribution contribution) {
        final ControlAppearanceContribution requested = Objects.requireNonNull(
            contribution,
            "contribution"
        );
        permissionChecker.check(
            PermissionIds.TURBOISM_UI_APPEARANCE_MODIFY,
            "ui.control-appearance.register"
        );
        coordinator.put(pluginId, pluginGeneration, requested);
        final AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) {
                coordinator.remove(pluginId, pluginGeneration, requested.id(), requested);
            }
        };
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
