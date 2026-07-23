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

    public List<ContextMenuContribution> contributions() {
        return contributions.stream().map(StoredContribution::contribution).toList();
    }

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
