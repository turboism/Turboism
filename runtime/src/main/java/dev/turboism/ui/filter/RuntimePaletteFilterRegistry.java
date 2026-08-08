package dev.turboism.ui.filter;

import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.filter.PaletteFilterRegistry;
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

/** Per-plugin registry for palette tab filter-box contributions. */
public final class RuntimePaletteFilterRegistry implements PaletteFilterRegistry {

    private static final String UI_TASK_TYPE = "ui.schedule";
    private static final String DEFAULT_CAPABILITY = "none";
    private static final String LOCALIZATION_OWNERSHIP_LOCKED = "localization ownership is already locked";

    private final PermissionChecker permissionChecker;
    private final RuntimeScheduler scheduler;
    private final String pluginId;
    private Optional<PaletteFilterVisibilitySink> visibilitySink;
    private EditorUiContributionAuthority contributionAuthority;
    private final Map<String, StoredContribution> contributions = new ConcurrentHashMap<>();
    private PluginLocalization localization;
    private boolean localizationLocked;

    public RuntimePaletteFilterRegistry(
        final PermissionChecker permissionChecker,
        final RuntimeScheduler scheduler,
        final String pluginId
    ) {
        this(permissionChecker, scheduler, pluginId, null, new EditorUiContributionAuthority(new RuntimeEditorUiHostLifecycle()));
    }

    public RuntimePaletteFilterRegistry(
        final PermissionChecker permissionChecker,
        final RuntimeScheduler scheduler,
        final String pluginId,
        final PaletteFilterVisibilitySink visibilitySink
    ) {
        this(permissionChecker, scheduler, pluginId, visibilitySink, new EditorUiContributionAuthority(new RuntimeEditorUiHostLifecycle()));
    }

    public RuntimePaletteFilterRegistry(
        final PermissionChecker permissionChecker,
        final RuntimeScheduler scheduler,
        final String pluginId,
        final PaletteFilterVisibilitySink visibilitySink,
        final EditorUiContributionAuthority contributionAuthority
    ) {
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.pluginId = requireText(pluginId, "pluginId");
        this.visibilitySink = Optional.ofNullable(visibilitySink);
        this.contributionAuthority = Objects.requireNonNull(contributionAuthority, "contributionAuthority");
    }

    public synchronized void bindContributionAuthority(final EditorUiContributionAuthority authority) {
        final EditorUiContributionAuthority requested = Objects.requireNonNull(authority, "authority");
        if (!contributions.isEmpty() && contributionAuthority != requested) {
            throw new IllegalStateException("palette filter contribution authority is already in use");
        }
        contributionAuthority = requested;
    }

    /** Binds the session-level palette filter host that owns real host attachment. */
    public synchronized void bindVisibilitySink(final PaletteFilterVisibilitySink sink) {
        Objects.requireNonNull(sink, "sink");
        if (visibilitySink.isPresent()) {
            throw new IllegalStateException("palette filter visibility sink is already bound");
        }
        visibilitySink = Optional.of(sink);
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

    /** Locks this registry to raw placeholder keys when no localization service is available. */
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
    public Registration contribute(final PaletteFilterContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");
        permissionChecker.check(PermissionIds.TURBOISM_UI_TOOLBAR_PALETTE_CONTRIBUTE, "ui.palette-filter.contribute");
        final String id = requireText(contribution.contributionId(), "contributionId");
        requireText(contribution.paletteId(), "paletteId");
        final PaletteFilterContribution resolved = resolvePlaceholder(contribution);
        final StoredContribution stored = new StoredContribution(resolved);
        final StoredContribution previous = contributions.put(id, stored);
        if (previous != null) {
            previous.registration().close();
        }
        final Registration authorityRegistration;
        try {
            authorityRegistration = contributionAuthority.contribute(new EditorUiContribution<>(
                new EditorUiContributionIdentity(pluginId, EditorUiFamily.PALETTE_FILTER, id),
                resolved.order(),
                resolved
            ));
        } catch (RuntimeException | Error failure) {
            contributions.remove(id, stored);
            throw failure;
        }
        stored.bind(authorityRegistration);
        dispatchVisibilityUpdate(resolved);
        return new FilterRegistration(id, stored);
    }

    boolean isRegistered(final String contributionId) {
        return contributions.containsKey(contributionId);
    }

    int registrationCount() {
        return contributions.size();
    }

    private PaletteFilterContribution resolvePlaceholder(final PaletteFilterContribution contribution) {
        final PluginLocalization pluginLocalization = lockLocalizationForContribution();
        if (pluginLocalization == null) {
            return contribution;
        }
        return new PaletteFilterContribution(
            contribution.contributionId(),
            contribution.paletteId(),
            pluginLocalization.text(requireText(contribution.placeholderKey(), "placeholderKey")),
            contribution.order()
        );
    }

    private synchronized PluginLocalization lockLocalizationForContribution() {
        localizationLocked = true;
        return localization;
    }

    private void dispatchVisibilityUpdate(final PaletteFilterContribution contribution) {
        final List<PaletteFilterContribution> snapshot = contributions.values().stream()
            .map(StoredContribution::contribution)
            .toList();
        scheduler.dispatch(task(contribution), () -> updateVisibility(snapshot));
    }

    private void updateVisibility(final List<PaletteFilterContribution> snapshot) {
        visibilitySink.ifPresent(sink -> sink.onPaletteFilterVisibilityChanged(pluginId, snapshot));
    }

    private PluginTask task(final PaletteFilterContribution contribution) {
        return new PluginTask(
            UI_TASK_TYPE,
            pluginId,
            "palette filter visibility for " + contribution.paletteId() + ":" + contribution.contributionId(),
            DEFAULT_CAPABILITY
        );
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private final class FilterRegistration implements Registration {
        private final String id;
        private final StoredContribution stored;
        private boolean closed;

        private FilterRegistration(final String id, final StoredContribution stored) {
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
            dispatchVisibilityUpdate(stored.contribution());
        }
    }

    private static final class StoredContribution {
        private final PaletteFilterContribution contribution;
        private Registration registration;

        private StoredContribution(final PaletteFilterContribution contribution) {
            this.contribution = Objects.requireNonNull(contribution, "contribution");
        }

        private PaletteFilterContribution contribution() {
            return contribution;
        }

        private void bind(final Registration authorityRegistration) {
            registration = Objects.requireNonNull(authorityRegistration, "authorityRegistration");
        }

        private Registration registration() {
            if (registration == null) {
                throw new IllegalStateException("palette filter contribution registration is not bound");
            }
            return registration;
        }
    }
}
