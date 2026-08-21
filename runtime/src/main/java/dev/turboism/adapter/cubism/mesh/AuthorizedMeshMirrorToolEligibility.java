package dev.turboism.adapter.cubism.mesh;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.mesh.MeshEditTool;
import dev.turboism.sdk.cubism.mesh.MeshMirrorToolEligibility;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.Registration;

import java.util.Objects;
import java.util.Set;

/** Per-plugin permission and lifetime boundary over mirror-tool eligibility policy. */
public final class AuthorizedMeshMirrorToolEligibility implements MeshMirrorToolEligibility {

    private final RuntimeMeshMirrorToolEligibility delegate;
    private final PermissionChecker permissions;
    private final DisposableScope scope;

    public AuthorizedMeshMirrorToolEligibility(
        final RuntimeMeshMirrorToolEligibility delegate,
        final PermissionChecker permissions,
        final DisposableScope scope
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.scope = Objects.requireNonNull(scope, "scope");
    }

    @Override
    public Registration extendEligibleTools(final Set<MeshEditTool> tools) {
        permissions.check(
            PermissionIds.TURBOISM_CUBISM_MODEL_WRITE,
            "cubism.mesh.mirror-tool-eligibility.extend"
        );
        final Registration registration = delegate.extendEligibleTools(tools);
        try {
            return scope.register(registration);
        } catch (RuntimeException | Error failure) {
            registration.close();
            throw failure;
        }
    }
}
