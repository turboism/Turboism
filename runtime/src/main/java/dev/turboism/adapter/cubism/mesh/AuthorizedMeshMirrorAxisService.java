package dev.turboism.adapter.cubism.mesh;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.mesh.MeshMirrorAxisService;
import dev.turboism.sdk.permission.PermissionIds;

import java.util.Objects;

/** Per-plugin permission boundary over the shared session-owned mirror-axis state. */
public final class AuthorizedMeshMirrorAxisService implements MeshMirrorAxisService {
    private final RuntimeMeshMirrorAxisService delegate;
    private final PermissionChecker permissions;

    public AuthorizedMeshMirrorAxisService(
        final RuntimeMeshMirrorAxisService delegate,
        final PermissionChecker permissions
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
    }

    @Override
    public float currentAngleDegrees() {
        permissions.check(PermissionIds.TURBOISM_CUBISM_MODEL_READ, "cubism.mesh.mirror-axis.read");
        return delegate.currentAngleDegrees();
    }

    @Override
    public void setCurrentAngleDegrees(final float angleDegrees) {
        permissions.check(PermissionIds.TURBOISM_CUBISM_MODEL_WRITE, "cubism.mesh.mirror-axis.write");
        delegate.setCurrentAngleDegrees(angleDegrees);
    }
}
