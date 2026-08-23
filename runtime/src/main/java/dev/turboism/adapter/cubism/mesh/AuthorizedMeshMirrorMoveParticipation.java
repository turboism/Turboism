package dev.turboism.adapter.cubism.mesh;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.mesh.MeshMirrorMoveParticipation;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.Registration;

import java.util.Objects;

/** Per-plugin permission and lifetime boundary over mirror movement policy. */
public final class AuthorizedMeshMirrorMoveParticipation implements MeshMirrorMoveParticipation {

    private final RuntimeMeshMirrorMoveParticipation delegate;
    private final PermissionChecker permissions;
    private final DisposableScope scope;

    public AuthorizedMeshMirrorMoveParticipation(
        final RuntimeMeshMirrorMoveParticipation delegate,
        final PermissionChecker permissions,
        final DisposableScope scope
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.scope = Objects.requireNonNull(scope, "scope");
    }

    @Override
    public Registration participate() {
        permissions.check(
            PermissionIds.TURBOISM_CUBISM_MODEL_WRITE,
            "cubism.mesh.mirror-move.participate"
        );
        final Registration registration = delegate.participate();
        try {
            return scope.register(registration);
        } catch (RuntimeException | Error failure) {
            registration.close();
            throw failure;
        }
    }
}
