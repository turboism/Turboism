package dev.turboism.adapter.cubism.mesh;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.mesh.MeshEdgeRef;
import dev.turboism.sdk.cubism.mesh.MeshEditResult;
import dev.turboism.sdk.cubism.mesh.MeshEditService;
import dev.turboism.sdk.cubism.mesh.MeshPointRef;
import dev.turboism.sdk.cubism.mesh.MeshSnapshot;
import dev.turboism.sdk.permission.PermissionIds;

import java.util.List;
import java.util.Objects;

/** Per-plugin permission boundary over plugin-initiated mesh authoring. */
public final class AuthorizedMeshEditService implements MeshEditService {

    private final RuntimeMeshEditService delegate;
    private final PermissionChecker permissions;

    public AuthorizedMeshEditService(
        final RuntimeMeshEditService delegate,
        final PermissionChecker permissions
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
    }

    @Override
    public MeshSnapshot snapshot() {
        permissions.check(PermissionIds.TURBOISM_CUBISM_MODEL_READ, "cubism.mesh.edit.snapshot");
        return delegate.snapshot();
    }

    @Override
    public MeshEditResult deletePoints(final List<MeshPointRef> points) {
        permissions.check(PermissionIds.TURBOISM_CUBISM_MODEL_WRITE, "cubism.mesh.edit.delete-points");
        return delegate.deletePoints(points);
    }

    @Override
    public MeshEditResult deleteEdges(final List<MeshEdgeRef> edges) {
        permissions.check(PermissionIds.TURBOISM_CUBISM_MODEL_WRITE, "cubism.mesh.edit.delete-edges");
        return delegate.deleteEdges(edges);
    }
}
