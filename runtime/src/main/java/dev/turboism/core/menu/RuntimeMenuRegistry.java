package dev.turboism.core.menu;

import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.Registration;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Runtime implementation of {@link MenuRegistry}.
 * <p>
 * Contributions are keyed by their {@link MenuContribution#actionId() action id}.
 * Registering or unregistering a contribution dispatches a visibility update through
 * the {@link RuntimeScheduler} so the host UI thread is never blocked by registry work.
 * This class intentionally contains no Swing/AWT/host UI tree mutation logic.
 */
public final class RuntimeMenuRegistry implements MenuRegistry {

    private static final String UI_TASK_TYPE = "ui.schedule";
    private static final String DEFAULT_CAPABILITY = "none";

    private final BiConsumer<PluginTask, Runnable> dispatcher;
    private final String pluginId;
    private final PermissionChecker permissionChecker;
    private final Map<String, ContributionHolder> contributions = new ConcurrentHashMap<>();

    public RuntimeMenuRegistry(final RuntimeScheduler scheduler, final String pluginId) {
        this(scheduler::dispatch, pluginId, PermissionChecker.allowAll());
    }

    public RuntimeMenuRegistry(
        final RuntimeScheduler scheduler,
        final String pluginId,
        final PermissionChecker permissionChecker
    ) {
        this(scheduler::dispatch, pluginId, permissionChecker);
    }

    RuntimeMenuRegistry(final BiConsumer<PluginTask, Runnable> dispatcher, final String pluginId) {
        this(dispatcher, pluginId, PermissionChecker.allowAll());
    }

    RuntimeMenuRegistry(
        final BiConsumer<PluginTask, Runnable> dispatcher,
        final String pluginId,
        final PermissionChecker permissionChecker
    ) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.pluginId = requireText(pluginId, "pluginId");
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker");
    }

    @Override
    public Registration contribute(final MenuContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");
        permissionChecker.check(PermissionIds.TURBOISM_UI_MENU_CONTRIBUTE, "menu.contribute");
        final String id = contribution.actionId();
        final ContributionHolder holder = new ContributionHolder(contribution);
        contributions.put(id, holder);
        dispatcher.accept(task(id), this::updateVisibility);
        return new MenuRegistration(id, holder);
    }

    /**
     * Visibility updates are intentionally a no-op at this phase. The only work performed
     * here is routing through the {@link RuntimeScheduler}; actual host UI tree mutation is
     * handled by platform-specific adapter code outside this registry.
     */
    private void updateVisibility() {
        // Phase 1: host UI bridge is not yet wired. The routing itself is the tested behavior.
    }

    boolean isRegistered(final String actionId) {
        return contributions.containsKey(actionId);
    }

    int registrationCount() {
        return contributions.size();
    }

    private PluginTask task(final String actionId) {
        return new PluginTask(UI_TASK_TYPE, pluginId, "menu visibility for " + actionId, DEFAULT_CAPABILITY);
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private final class MenuRegistration implements Registration {
        private final String id;
        private final ContributionHolder holder;
        private boolean closed;

        MenuRegistration(final String id, final ContributionHolder holder) {
            this.id = id;
            this.holder = holder;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            contributions.remove(id, holder);
            dispatcher.accept(task(id), RuntimeMenuRegistry.this::updateVisibility);
        }
    }

    private static final class ContributionHolder {
        private final MenuContribution contribution;

        ContributionHolder(final MenuContribution contribution) {
            this.contribution = Objects.requireNonNull(contribution, "contribution");
        }
    }
}
