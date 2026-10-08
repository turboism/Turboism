package dev.turboism.adapter.cubism.mesh;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.mesh.MeshEditor;
import dev.turboism.sdk.cubism.mesh.SelectionMode;
import dev.turboism.sdk.cubism.mesh.VertexSelection;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.permission.PermissionIds;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Objects;

/** Selection-only editor whose operations are guarded by one exact activation lease. */
final class SessionBoundMeshEditor implements MeshEditor {
    private final MeshToolCoordinator.ActivationLease lease;
    private final PermissionChecker permissions;

    SessionBoundMeshEditor(final MeshToolCoordinator.ActivationLease lease, final PermissionChecker permissions) {
        this.lease = Objects.requireNonNull(lease, "lease");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
    }

    @Override
    public Drawable mesh() {
        requireRead("mesh.mesh");
        final Drawable current = currentSession().drawable();
        if (current == null) throw new IllegalStateException("active mesh drawable is unavailable");
        return (Drawable) Proxy.newProxyInstance(
                Drawable.class.getClassLoader(), new Class<?>[] {Drawable.class}, (proxy, method, arguments) -> {
                    requireRead("mesh.drawable." + method.getName());
                    final Drawable target = currentSession().drawable();
                    if (target == null) throw new IllegalStateException("active mesh drawable is unavailable");
                    try {
                        return method.invoke(target, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    @Override
    public VertexSelection selection() {
        requireRead("mesh.selection");
        return Objects.requireNonNull(currentSession().selection(), "session.selection()");
    }

    @Override
    public void select(final VertexSelection selection) {
        select(selection, SelectionMode.REPLACE);
    }

    @Override
    public void select(final VertexSelection selection, final SelectionMode mode) {
        lease.requireCurrent();
        permissions.check(PermissionIds.TURBOISM_CUBISM_MODEL_WRITE, "mesh.select");
        currentSession().select(Objects.requireNonNull(selection, "selection"), Objects.requireNonNull(mode, "mode"));
    }

    private MeshToolCoordinator.Session currentSession() {
        return lease.session();
    }

    private void requireRead(final String operation) {
        lease.requireCurrent();
        permissions.check(PermissionIds.TURBOISM_CUBISM_MODEL_READ, operation);
    }
}
