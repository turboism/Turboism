package dev.turboism.core.menu;

import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.ui.contribution.EditorUiContribution;
import dev.turboism.ui.contribution.EditorUiContributionAuthority;
import dev.turboism.ui.contribution.EditorUiContributionIdentity;
import dev.turboism.ui.host.EditorUiFamily;
import dev.turboism.ui.host.RuntimeEditorUiHostLifecycle;

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
    private EditorUiContributionAuthority contributionAuthority;
    private final Map<String, ContributionHolder> contributions = new ConcurrentHashMap<>();

    public RuntimeMenuRegistry(
        final RuntimeScheduler scheduler,
        final String pluginId,
        final PermissionChecker permissionChecker
    ) {
        this(
            scheduler::dispatch,
            pluginId,
            permissionChecker,
            new EditorUiContributionAuthority(new RuntimeEditorUiHostLifecycle())
        );
    }

    public RuntimeMenuRegistry(
        final RuntimeScheduler scheduler,
        final String pluginId,
        final PermissionChecker permissionChecker,
        final EditorUiContributionAuthority contributionAuthority
    ) {
        this(scheduler::dispatch, pluginId, permissionChecker, contributionAuthority);
    }

    RuntimeMenuRegistry(
        final BiConsumer<PluginTask, Runnable> dispatcher,
        final String pluginId,
        final PermissionChecker permissionChecker
    ) {
        this(
            dispatcher,
            pluginId,
            permissionChecker,
            new EditorUiContributionAuthority(new RuntimeEditorUiHostLifecycle())
        );
    }

    RuntimeMenuRegistry(
        final BiConsumer<PluginTask, Runnable> dispatcher,
        final String pluginId,
        final PermissionChecker permissionChecker,
        final EditorUiContributionAuthority contributionAuthority
    ) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.pluginId = requireText(pluginId, "pluginId");
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker");
        this.contributionAuthority = Objects.requireNonNull(
            contributionAuthority,
            "contributionAuthority"
        );
    }

    /**
     * Swaps in the authority that owns this plugin's menu contributions. Permitted only while no
     * contributions are live, or when rebinding the same authority, so existing menu entries can never
     * be reassigned to a different owner.
     *
     * @param authority the editor UI contribution authority to bind
     * @throws NullPointerException when {@code authority} is null
     * @throws IllegalStateException when contributions are already registered under another authority
     */
    public synchronized void bindContributionAuthority(
        final EditorUiContributionAuthority authority
    ) {
        final EditorUiContributionAuthority requested = Objects.requireNonNull(authority, "authority");
        if (!contributions.isEmpty() && contributionAuthority != requested) {
            throw new IllegalStateException("menu contribution authority is already in use");
        }
        contributionAuthority = requested;
    }

    @Override
    public Registration contribute(final MenuContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");
        permissionChecker.check(PermissionIds.TURBOISM_UI_MENU_CONTRIBUTE, "menu.contribute");
        final String id = contribution.actionId();
        final ContributionHolder holder = new ContributionHolder(contribution);
        final ContributionHolder previous = contributions.put(id, holder);
        if (previous != null) {
            previous.registration().close();
        }
        final Registration authorityRegistration;
        try {
            authorityRegistration = contributionAuthority.contribute(new EditorUiContribution<>(
                new EditorUiContributionIdentity(pluginId, EditorUiFamily.MENU, id),
                contribution.order(),
                contribution
            ));
        } catch (RuntimeException | Error failure) {
            contributions.remove(id, holder);
            throw failure;
        }
        holder.bind(authorityRegistration);
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
            if (contributions.remove(id, holder)) {
                holder.registration().close();
            }
            dispatcher.accept(task(id), RuntimeMenuRegistry.this::updateVisibility);
        }
    }

    private static final class ContributionHolder {
        private final MenuContribution contribution;
        private Registration registration;

        ContributionHolder(final MenuContribution contribution) {
            this.contribution = Objects.requireNonNull(contribution, "contribution");
        }

        void bind(final Registration authorityRegistration) {
            if (registration != null) {
                throw new IllegalStateException("menu contribution registration is already bound");
            }
            registration = Objects.requireNonNull(authorityRegistration, "authorityRegistration");
        }

        Registration registration() {
            if (registration == null) {
                throw new IllegalStateException("menu contribution registration is not bound");
            }
            return registration;
        }
    }
}
