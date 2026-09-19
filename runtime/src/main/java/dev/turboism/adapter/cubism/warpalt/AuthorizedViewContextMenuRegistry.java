package dev.turboism.adapter.cubism.warpalt;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.viewcontext.ViewContextMenuRegistry;

import java.util.Objects;

/** Per-plugin permission boundary over the canvas-strip button surface. */
public final class AuthorizedViewContextMenuRegistry implements ViewContextMenuRegistry {

    private final RuntimeViewContextMenuRegistry delegate;
    private final PermissionChecker permissions;

    public AuthorizedViewContextMenuRegistry(
        final RuntimeViewContextMenuRegistry delegate,
        final PermissionChecker permissions
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
    }

    @Override
    public Registration contributeMenuItem(final ViewContextMenuRegistry.MenuItemContribution contribution) {
        permissions.check(
            dev.turboism.sdk.permission.PermissionIds.TURBOISM_UI_TOOLBAR_CONTRIBUTE,
            "viewcontext.button.contribute"
        );
        return delegate.contributeMenuItem(contribution);
    }

    @Override
    public void setText(final String contributionId, final String text) {
        delegate.setText(contributionId, text);
    }
}
