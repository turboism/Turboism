package dev.turboism.adapter.cubism.mesh;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.mesh.MeshEditUiService;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.Registration;

import java.util.Objects;

/** Per-plugin permission and lifetime boundary over native mesh-edit UI authority. */
public final class AuthorizedMeshEditUiService implements MeshEditUiService {
    private final RuntimeMeshEditUiService delegate;
    private final PermissionChecker permissions;
    private final DisposableScope scope;

    public AuthorizedMeshEditUiService(
        final RuntimeMeshEditUiService delegate,
        final PermissionChecker permissions,
        final DisposableScope scope
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.scope = Objects.requireNonNull(scope, "scope");
    }

    @Override
    public Registration contributeMirrorAxisAngleControl(final MirrorAxisAngleControl contribution) {
        permissions.check(
            PermissionIds.TURBOISM_UI_PANEL_CONTRIBUTE,
            "ui.mesh-edit.mirror-axis-angle.contribute"
        );
        final Registration registration = delegate.contributeMirrorAxisAngleControl(contribution);
        scope.register(registration);
        return registration;
    }
}
