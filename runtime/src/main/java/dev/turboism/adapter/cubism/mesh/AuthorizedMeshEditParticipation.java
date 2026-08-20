package dev.turboism.adapter.cubism.mesh;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.mesh.MeshEditParticipant;
import dev.turboism.sdk.cubism.mesh.MeshEditParticipation;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.Registration;

import java.util.Objects;

/** Per-plugin permission and lifetime boundary over mesh edit participation. */
public final class AuthorizedMeshEditParticipation implements MeshEditParticipation {

    private final RuntimeMeshEditParticipation delegate;
    private final PermissionChecker permissions;
    private final DisposableScope scope;

    public AuthorizedMeshEditParticipation(
        final RuntimeMeshEditParticipation delegate,
        final PermissionChecker permissions,
        final DisposableScope scope
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.scope = Objects.requireNonNull(scope, "scope");
    }

    @Override
    public Registration participate(final MeshEditParticipant participant) {
        // Participation folds deletions into a host edit, so it is a write, not an observation.
        permissions.check(
            PermissionIds.TURBOISM_CUBISM_MODEL_WRITE,
            "cubism.mesh.edit.participate"
        );
        final Registration registration = delegate.participate(participant);
        scope.register(registration);
        return registration;
    }
}
