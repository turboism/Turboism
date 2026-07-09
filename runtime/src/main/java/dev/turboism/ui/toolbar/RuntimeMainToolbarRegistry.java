package dev.turboism.ui.toolbar;

import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class RuntimeMainToolbarRegistry implements MainToolbarRegistry {

    private static final String UI_TASK_TYPE = "ui.schedule";
    private static final String DEFAULT_CAPABILITY = "none";

    private final PermissionChecker permissionChecker;
    private final RuntimeScheduler scheduler;
    private final String pluginId;
    private final Map<String, MainToolbarContribution> contributions = new ConcurrentHashMap<>();

    public RuntimeMainToolbarRegistry(
        final PermissionChecker permissionChecker,
        final RuntimeScheduler scheduler,
        final String pluginId
    ) {
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.pluginId = requireText(pluginId, "pluginId");
    }

    @Override
    public Registration contribute(final MainToolbarContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");
        permissionChecker.check(PermissionIds.TURBOISM_UI_TOOLBAR_MAIN_CONTRIBUTE, "ui.main-toolbar.contribute");
        final String id = requireText(contribution.contributionId(), "contributionId");
        contributions.put(id, contribution);
        dispatchVisibilityUpdate(id);
        return new ToolbarRegistration(id, contribution);
    }

    boolean isRegistered(final String contributionId) {
        return contributions.containsKey(contributionId);
    }

    int registrationCount() {
        return contributions.size();
    }

    private void dispatchVisibilityUpdate(final String contributionId) {
        scheduler.dispatch(task(contributionId), this::updateVisibility);
    }

    private void updateVisibility() {
    }

    private PluginTask task(final String contributionId) {
        return new PluginTask(UI_TASK_TYPE, pluginId, "main toolbar visibility for " + contributionId, DEFAULT_CAPABILITY);
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private final class ToolbarRegistration implements Registration {
        private final String id;
        private final MainToolbarContribution contribution;
        private boolean closed;

        private ToolbarRegistration(final String id, final MainToolbarContribution contribution) {
            this.id = id;
            this.contribution = contribution;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            contributions.remove(id, contribution);
            dispatchVisibilityUpdate(id);
        }
    }
}
