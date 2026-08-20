package dev.turboism.ui.toolbar;

import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.ui.contribution.EditorUiContribution;
import dev.turboism.ui.contribution.EditorUiContributionAuthority;
import dev.turboism.ui.contribution.EditorUiContributionIdentity;
import dev.turboism.ui.host.EditorUiFamily;
import dev.turboism.ui.host.RuntimeEditorUiHostLifecycle;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The runtime implementation of {@link MainToolbarRegistry} handed to one plugin.
 *
 * <p>Scoped to a single plugin id: every contribution it accepts is attributed to that plugin,
 * checked against {@link PermissionIds} through the {@link PermissionChecker}, and applied to the
 * host through the {@link RuntimeScheduler} rather than on the caller's thread.
 *
 * <p>Localization ownership is one-shot: the first of {@code bindLocalization} or
 * {@code lockWithoutLocalization} wins and any later disagreeing call is refused, so label keys
 * cannot be resolved against a swapped-in bundle.
 */
public final class RuntimeMainToolbarRegistry implements MainToolbarRegistry {

    private static final String UI_TASK_TYPE = "ui.schedule";
    private static final String DEFAULT_CAPABILITY = "none";

    private final PermissionChecker permissionChecker;
    private final RuntimeScheduler scheduler;
    private final String pluginId;
    private static final String LOCALIZATION_OWNERSHIP_LOCKED = "localization ownership is already locked";

    private final Optional<ToolbarVisibilitySink> visibilitySink;
    private EditorUiContributionAuthority contributionAuthority;
    private final Map<String, StoredContribution> contributions = new ConcurrentHashMap<>();
    private PluginLocalization localization;
    private boolean localizationLocked;

    public RuntimeMainToolbarRegistry(
        final PermissionChecker permissionChecker,
        final RuntimeScheduler scheduler,
        final String pluginId
    ) {
        this(
            permissionChecker,
            scheduler,
            pluginId,
            null,
            new EditorUiContributionAuthority(new RuntimeEditorUiHostLifecycle())
        );
    }

    public RuntimeMainToolbarRegistry(
        final PermissionChecker permissionChecker,
        final RuntimeScheduler scheduler,
        final String pluginId,
        final ToolbarVisibilitySink visibilitySink
    ) {
        this(
            permissionChecker,
            scheduler,
            pluginId,
            visibilitySink,
            new EditorUiContributionAuthority(new RuntimeEditorUiHostLifecycle())
        );
    }

    public RuntimeMainToolbarRegistry(
        final PermissionChecker permissionChecker,
        final RuntimeScheduler scheduler,
        final String pluginId,
        final ToolbarVisibilitySink visibilitySink,
        final EditorUiContributionAuthority contributionAuthority
    ) {
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.pluginId = requireText(pluginId, "pluginId");
        this.visibilitySink = Optional.ofNullable(visibilitySink);
        this.contributionAuthority = Objects.requireNonNull(
            contributionAuthority,
            "contributionAuthority"
        );
    }

    /**
     * Rebinds the authority this registry routes contributions through, typically when a new
     * Editor UI host generation is installed.
     *
     * <p>Only safe while nothing is contributed: with contributions already registered, switching
     * to a different authority would strand them, so it is refused. Rebinding the same authority
     * is always allowed.
     *
     * @param authority the authority to route through
     * @throws NullPointerException if {@code authority} is {@code null}
     * @throws IllegalStateException if contributions exist and {@code authority} differs from the
     *     current one
     */
    public synchronized void bindContributionAuthority(
        final EditorUiContributionAuthority authority
    ) {
        final EditorUiContributionAuthority requested = Objects.requireNonNull(authority, "authority");
        if (!contributions.isEmpty() && contributionAuthority != requested) {
            throw new IllegalStateException("main toolbar contribution authority is already in use");
        }
        contributionAuthority = requested;
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
        return contributeNormalized(
            requireText(contribution.contributionId(), "contributionId"),
            contribution.order(),
            resolveLabel(contribution)
        );
    }

    @Override
    public Registration contributeButton(final MainToolbarButtonContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");
        return contributeNormalized(
            requireText(contribution.contributionId(), "contributionId"),
            contribution.order(),
            resolveButtonLabels(contribution)
        );
    }

    private Registration contributeNormalized(
        final String id,
        final int order,
        final Object descriptor
    ) {
        permissionChecker.check(
            PermissionIds.TURBOISM_UI_TOOLBAR_MAIN_CONTRIBUTE,
            "ui.main-toolbar.contribute"
        );
        final StoredContribution stored = new StoredContribution(descriptor);
        final StoredContribution previous = contributions.put(id, stored);
        if (previous != null) {
            previous.registration().close();
        }
        final Registration authorityRegistration;
        try {
            authorityRegistration = contributionAuthority.contribute(new EditorUiContribution<>(
                new EditorUiContributionIdentity(pluginId, EditorUiFamily.MAIN_TOOLBAR, id),
                order,
                descriptor
            ));
        } catch (RuntimeException | Error failure) {
            contributions.remove(id, stored);
            throw failure;
        }
        stored.bind(authorityRegistration);
        dispatchVisibilityUpdate(id);
        return new ToolbarRegistration(id, stored);
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

    private MainToolbarButtonContribution resolveButtonLabels(
        final MainToolbarButtonContribution contribution
    ) {
        final PluginLocalization pluginLocalization = lockLocalizationForContribution();
        if (pluginLocalization == null) {
            return contribution;
        }
        return new MainToolbarButtonContribution(
            contribution.contributionId(),
            contribution.actionId(),
            pluginLocalization.text(requireText(contribution.labelKey(), "labelKey")),
            pluginLocalization.text(requireText(contribution.tooltipKey(), "tooltipKey")),
            contribution.icons(),
            contribution.placement(),
            contribution.order()
        );
    }

    private synchronized PluginLocalization lockLocalizationForContribution() {
        localizationLocked = true;
        return localization;
    }

    private void dispatchVisibilityUpdate(final String contributionId) {
        final List<MainToolbarContribution> snapshot = contributions.values().stream()
            .map(StoredContribution::descriptor)
            .map(RuntimeMainToolbarRegistry::legacyView)
            .toList();
        scheduler.dispatch(task(contributionId), () -> updateVisibility(snapshot));
    }

    private static MainToolbarContribution legacyView(final Object descriptor) {
        if (descriptor instanceof MainToolbarContribution contribution) {
            return contribution;
        }
        if (descriptor instanceof MainToolbarButtonContribution button) {
            return button.toLegacyContribution();
        }
        throw new IllegalStateException("Unsupported main toolbar contribution descriptor");
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
        private final StoredContribution stored;
        private boolean closed;

        private ToolbarRegistration(final String id, final StoredContribution stored) {
            this.id = id;
            this.stored = stored;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (contributions.remove(id, stored)) {
                stored.registration().close();
            }
            dispatchVisibilityUpdate(id);
        }
    }

    private static final class StoredContribution {
        private final Object descriptor;
        private Registration registration;

        private StoredContribution(final Object descriptor) {
            this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        }

        private Object descriptor() {
            return descriptor;
        }

        private void bind(final Registration authorityRegistration) {
            registration = Objects.requireNonNull(authorityRegistration, "authorityRegistration");
        }

        private Registration registration() {
            if (registration == null) {
                throw new IllegalStateException("main toolbar contribution registration is not bound");
            }
            return registration;
        }
    }
}
