package dev.turboism.ui.context;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.ui.contribution.EditorUiContribution;
import dev.turboism.ui.contribution.EditorUiContributionAuthority;
import dev.turboism.ui.contribution.EditorUiContributionIdentity;
import dev.turboism.ui.host.EditorUiFamily;
import dev.turboism.ui.host.RuntimeEditorUiHostLifecycle;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The runtime {@link ContextMenuRegistry} handed to one plugin.
 *
 * <p>Every contribution is permission-checked against
 * {@link PermissionIds#TURBOISM_UI_CONTEXT_MENU_CONTRIBUTE} before it reaches the shared
 * contribution authority, and is tagged with this registry's plugin id so contributions stay
 * attributable. Registrations are idempotent: closing one twice releases the authority
 * registration only once. The contribution list is copy-on-write, so reads are safe while
 * other threads contribute.
 */
public final class RuntimeContextMenuRegistry implements ContextMenuRegistry {

    private final PermissionChecker permissionChecker;
    private final String pluginId;
    private EditorUiContributionAuthority contributionAuthority;
    private final CopyOnWriteArrayList<StoredContribution> contributions = new CopyOnWriteArrayList<>();

    public RuntimeContextMenuRegistry(final PermissionChecker permissionChecker, final String pluginId) {
        this(
            permissionChecker,
            pluginId,
            new EditorUiContributionAuthority(new RuntimeEditorUiHostLifecycle())
        );
    }

    public RuntimeContextMenuRegistry(
        final PermissionChecker permissionChecker,
        final String pluginId,
        final EditorUiContributionAuthority contributionAuthority
    ) {
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker");
        this.pluginId = Objects.requireNonNull(pluginId, "pluginId");
        this.contributionAuthority = Objects.requireNonNull(
            contributionAuthority,
            "contributionAuthority"
        );
    }

    /**
     * Rebinds the authority that contributions are forwarded to.
     *
     * <p>Permitted only while nothing is registered through this registry, since already-live
     * contributions belong to the previous authority and cannot be migrated; rebinding to the
     * authority already in use is always allowed.
     *
     * @param authority the contribution authority to use from now on; must not be null
     * @throws IllegalStateException if contributions are live and {@code authority} differs from
     *                               the current one
     * @throws NullPointerException if {@code authority} is null
     */
    public synchronized void bindContributionAuthority(
        final EditorUiContributionAuthority authority
    ) {
        final EditorUiContributionAuthority requested = Objects.requireNonNull(authority, "authority");
        if (!contributions.isEmpty() && contributionAuthority != requested) {
            throw new IllegalStateException("context menu contribution authority is already in use");
        }
        contributionAuthority = requested;
    }

    @Override
    public Registration contribute(final ContextMenuContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");
        permissionChecker.check(PermissionIds.TURBOISM_UI_CONTEXT_MENU_CONTRIBUTE, contribution.id());
        final Registration authorityRegistration = contributionAuthority.contribute(
            new EditorUiContribution<>(
                new EditorUiContributionIdentity(
                    pluginId,
                    EditorUiFamily.CONTEXT_MENU,
                    contribution.id()
                ),
                contribution.priority(),
                contribution
            )
        );
        final StoredContribution stored = new StoredContribution(contribution, authorityRegistration);
        contributions.add(stored);
        return () -> {
            if (contributions.remove(stored)) {
                authorityRegistration.close();
            }
        };
    }

    /**
     * @return a snapshot of the contributions currently live through this registry, in
     *         registration order; later registrations and closures do not affect the returned list
     */
    public List<ContextMenuContribution> contributions() {
        return contributions.stream().map(StoredContribution::contribution).toList();
    }

    /**
     * @return the id of the plugin every contribution made through this registry is attributed to
     */
    public String pluginId() {
        return pluginId;
    }

    private record StoredContribution(
        ContextMenuContribution contribution,
        Registration registration
    ) {
        private StoredContribution {
            contribution = Objects.requireNonNull(contribution, "contribution");
            registration = Objects.requireNonNull(registration, "registration");
        }
    }
}
