package dev.turboism.ui.settings;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.settings.SettingsContribution;
import dev.turboism.sdk.ui.settings.SettingsRegistry;

import java.util.Objects;

/** Permission-checked plugin-scoped view over the shared settings contribution store. */
public final class RuntimeSettingsRegistry implements SettingsRegistry {
    private final SettingsContributionStore store;
    private final String pluginId;
    private final PermissionChecker permissionChecker;
    private final DisposableScope disposableScope;

    public RuntimeSettingsRegistry(
        final SettingsContributionStore store,
        final String pluginId,
        final PermissionChecker permissionChecker,
        final DisposableScope disposableScope
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.pluginId = Objects.requireNonNull(pluginId, "pluginId");
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker");
        this.disposableScope = Objects.requireNonNull(disposableScope, "disposableScope");
    }

    @Override
    public Registration contribute(final SettingsContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");
        permissionChecker.check(
            PermissionIds.TURBOISM_UI_SETTINGS_CONTRIBUTE,
            "ui.settings.contribute"
        );
        final Registration registration = store.register(pluginId, contribution);
        disposableScope.register(registration);
        return registration;
    }
}
