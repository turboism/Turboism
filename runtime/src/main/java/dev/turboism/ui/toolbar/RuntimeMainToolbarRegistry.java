package dev.turboism.ui.toolbar;

import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class RuntimeMainToolbarRegistry implements MainToolbarRegistry {

    private static final String UI_TASK_TYPE = "ui.schedule";
    private static final String DEFAULT_CAPABILITY = "none";

    private final PermissionChecker permissionChecker;
    private final RuntimeScheduler scheduler;
    private final String pluginId;
    private static final String LOCALIZATION_OWNERSHIP_LOCKED = "localization ownership is already locked";

    private final Optional<ToolbarVisibilitySink> visibilitySink;
    private final Map<String, MainToolbarContribution> contributions = new ConcurrentHashMap<>();
    private PluginLocalization localization;
    private boolean localizationLocked;

    public RuntimeMainToolbarRegistry(
        final PermissionChecker permissionChecker,
        final RuntimeScheduler scheduler,
        final String pluginId
    ) {
        this(permissionChecker, scheduler, pluginId, null);
    }

    public RuntimeMainToolbarRegistry(
        final PermissionChecker permissionChecker,
        final RuntimeScheduler scheduler,
        final String pluginId,
        final ToolbarVisibilitySink visibilitySink
    ) {
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.pluginId = requireText(pluginId, "pluginId");
        this.visibilitySink = Optional.ofNullable(visibilitySink);
    }

    /** Binds the localization context owned by this registry's contributing plugin. */
    public synchronized void bindLocalization(final PluginLocalization pluginLocalization) {
        final PluginLocalization requested = Objects.requireNonNull(pluginLocalization, "pluginLocalization");
        if (!localizationLocked) {
            localization = requested;
            localizationLocked = true;
            return;
        }
        if (localization != requested) {
            throw new IllegalStateException(LOCALIZATION_OWNERSHIP_LOCKED);
        }
    }

    /** Locks this registry to raw label keys when no localization service is available. */
    public synchronized void lockWithoutLocalization() {
        if (!localizationLocked) {
            localizationLocked = true;
            return;
        }
        if (localization != null) {
            throw new IllegalStateException(LOCALIZATION_OWNERSHIP_LOCKED);
        }
    }

    @Override
    public Registration contribute(final MainToolbarContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");
        permissionChecker.check(PermissionIds.TURBOISM_UI_TOOLBAR_MAIN_CONTRIBUTE, "ui.main-toolbar.contribute");
        final String id = requireText(contribution.contributionId(), "contributionId");
        final MainToolbarContribution resolved = resolveLabel(contribution);
        contributions.put(id, resolved);
        dispatchVisibilityUpdate(id);
        return new ToolbarRegistration(id, resolved);
    }

    boolean isRegistered(final String contributionId) {
        return contributions.containsKey(contributionId);
    }

    int registrationCount() {
        return contributions.size();
    }

    private MainToolbarContribution resolveLabel(final MainToolbarContribution contribution) {
        final PluginLocalization pluginLocalization = lockLocalizationForContribution();
        if (pluginLocalization == null) {
            return contribution;
        }
        return new MainToolbarContribution(
            contribution.contributionId(),
            contribution.actionId(),
            pluginLocalization.text(requireText(contribution.labelKey(), "labelKey")),
            contribution.iconResourcePath(),
            contribution.anchor(),
            contribution.order()
        );
    }

    private synchronized PluginLocalization lockLocalizationForContribution() {
        localizationLocked = true;
        return localization;
    }

    private void dispatchVisibilityUpdate(final String contributionId) {
        final List<MainToolbarContribution> snapshot = List.copyOf(contributions.values());
        scheduler.dispatch(task(contributionId), () -> updateVisibility(snapshot));
    }

    private void updateVisibility(final List<MainToolbarContribution> snapshot) {
        visibilitySink.ifPresent(sink -> sink.onMainToolbarVisibilityChanged(pluginId, snapshot));
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
