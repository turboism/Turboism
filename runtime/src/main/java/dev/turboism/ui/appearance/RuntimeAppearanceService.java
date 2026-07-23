package dev.turboism.ui.appearance;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.appearance.AppearanceApplyResult;
import dev.turboism.sdk.appearance.AppearanceRequest;
import dev.turboism.sdk.appearance.AppearanceRestoreResult;
import dev.turboism.sdk.appearance.AppearanceService;
import dev.turboism.sdk.appearance.AppearanceStatus;
import dev.turboism.sdk.permission.PermissionIds;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Plugin-scoped SDK view over the global runtime appearance coordinator. */
public final class RuntimeAppearanceService implements AppearanceService {

    private final String pluginId;
    private final long pluginGeneration;
    private final PermissionChecker permissionChecker;
    private final AppearanceCoordinator coordinator;

    public RuntimeAppearanceService(
        final String pluginId,
        final long pluginGeneration,
        final PermissionChecker permissionChecker,
        final AppearanceCoordinator coordinator
    ) {
        Objects.requireNonNull(pluginId, "pluginId");
        if (pluginId.isBlank()) {
            throw new IllegalArgumentException("pluginId must not be blank");
        }
        if (pluginGeneration < 0) {
            throw new IllegalArgumentException("pluginGeneration must not be negative");
        }
        this.pluginId = pluginId;
        this.pluginGeneration = pluginGeneration;
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    @Override
    public CompletionStage<AppearanceStatus> current() {
        return CompletableFuture.completedFuture(coordinator.current());
    }

    @Override
    public CompletionStage<AppearanceApplyResult> apply(final AppearanceRequest request) {
        permissionChecker.check(
            PermissionIds.TURBOISM_UI_APPEARANCE_MODIFY,
            "ui.appearance.apply"
        );
        return CompletableFuture.completedFuture(
            coordinator.apply(pluginId, pluginGeneration, request)
        );
    }

    @Override
    public CompletionStage<AppearanceRestoreResult> restoreOwnedAppearance() {
        permissionChecker.check(
            PermissionIds.TURBOISM_UI_APPEARANCE_MODIFY,
            "ui.appearance.restore"
        );
        return CompletableFuture.completedFuture(
            coordinator.restore(pluginId, pluginGeneration)
        );
    }
}
