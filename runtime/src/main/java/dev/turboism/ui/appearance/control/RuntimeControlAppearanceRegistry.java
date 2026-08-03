package dev.turboism.ui.appearance.control;

import dev.turboism.adapter.cubism.NativeControlAppearanceAuthoring;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.appearance.ControlAppearanceContribution;
import dev.turboism.sdk.ui.appearance.ControlAppearanceRegistry;
import dev.turboism.sdk.ui.appearance.ControlAppearanceSnapshot;
import dev.turboism.sdk.ui.appearance.ControlAppearanceStyle;
import dev.turboism.sdk.ui.appearance.ControlAppearanceTarget;
import dev.turboism.sdk.ui.appearance.NativeControlBackground;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Plugin-scoped registry for transient native-control appearance contributions and Editor-native
 * control label-background reads/writes.
 */
public final class RuntimeControlAppearanceRegistry implements ControlAppearanceRegistry {
    private final String pluginId;
    private final long pluginGeneration;
    private final PermissionChecker permissionChecker;
    private final ControlAppearanceCoordinator coordinator;
    private final NativeControlAppearanceAuthoring nativeAuthoring;
    private final AtomicBoolean active = new AtomicBoolean(true);

    public RuntimeControlAppearanceRegistry(
        final String pluginId,
        final long pluginGeneration,
        final PermissionChecker permissionChecker,
        final ControlAppearanceCoordinator coordinator,
        final NativeControlAppearanceAuthoring nativeAuthoring
    ) {
        this.pluginId = requireText(pluginId, "pluginId");
        if (pluginGeneration < 0) {
            throw new IllegalArgumentException("pluginGeneration must not be negative");
        }
        this.pluginGeneration = pluginGeneration;
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.nativeAuthoring = Objects.requireNonNull(nativeAuthoring, "nativeAuthoring");
        coordinator.removePlugin(pluginId, pluginGeneration);
    }

    public void bind(final dev.turboism.sdk.plugin.DisposableScope scope) {
        Objects.requireNonNull(scope, "scope").register(() -> {
            active.set(false);
            coordinator.removePlugin(pluginId, pluginGeneration);
        });
    }

    @Override
    public Registration register(final ControlAppearanceContribution contribution) {
        requireActive();
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

    @Override
    public ControlAppearanceSnapshot snapshot(final ControlAppearanceTarget target) {
        requireActive();
        final ControlAppearanceTarget requested = Objects.requireNonNull(target, "target");
        if (requested instanceof ControlAppearanceTarget.ParameterLabel label) {
            return new ControlAppearanceSnapshot(
                Optional.empty(),
                coordinator.parameterLabel(label.id().value())
            );
        }
        permissionChecker.check(
            PermissionIds.TURBOISM_CUBISM_MODEL_READ,
            "ui.control-appearance.snapshot"
        );
        return new ControlAppearanceSnapshot(
            Optional.of(nativeAuthoring.snapshot(requested)),
            overlay(requested)
        );
    }

    @Override
    public void setNativeBackground(
        final ControlAppearanceTarget target,
        final NativeControlBackground background
    ) {
        requireActive();
        final ControlAppearanceTarget requested = Objects.requireNonNull(target, "target");
        Objects.requireNonNull(background, "background");
        if (requested instanceof ControlAppearanceTarget.ParameterLabel) {
            throw new UnsupportedOperationException(
                "ParameterLabel is overlay-only; native label-background authoring is unsupported."
            );
        }
        permissionChecker.check(
            PermissionIds.TURBOISM_CUBISM_MODEL_WRITE,
            "ui.control-appearance.set-native-background"
        );
        nativeAuthoring.setNativeBackground(requested, background);
    }

    private Optional<ControlAppearanceStyle> overlay(final ControlAppearanceTarget target) {
        if (target instanceof ControlAppearanceTarget.ParameterFolder value) {
            return coordinator.parameterFolder(value.id().value());
        }
        if (target instanceof ControlAppearanceTarget.PartLabel value) {
            return coordinator.partLabel(value.id().value());
        }
        if (target instanceof ControlAppearanceTarget.PartFolder value) {
            return coordinator.partFolder(value.id().value());
        }
        if (target instanceof ControlAppearanceTarget.DeformerLabel value) {
            return coordinator.deformerLabel(value.id().value());
        }
        if (target instanceof ControlAppearanceTarget.DeformerControlRow value) {
            return coordinator.deformerControlRow(value.id().value());
        }
        throw new IllegalArgumentException(
            "unsupported control appearance target: " + target.getClass().getName()
        );
    }

    private void requireActive() {
        if (!active.get()) {
            throw new IllegalStateException(
                "Control appearance registry is stale because the owning plugin is disabled."
            );
        }
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
