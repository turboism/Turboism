package dev.turboism.adapter.cubism.mesh;

import dev.turboism.sdk.cubism.mesh.MeshBrush;
import dev.turboism.sdk.cubism.mesh.MeshEditor;
import dev.turboism.sdk.cubism.mesh.MeshTool;
import dev.turboism.sdk.cubism.mesh.MeshToolContext;
import java.util.Objects;

/** Activation-bound SDK context. Brush ownership is closed before plugin deactivation. */
final class RuntimeMeshToolContext implements MeshToolContext, AutoCloseable {
    private final MeshEditor editor;
    private final MeshToolCoordinator.ActivationLease lease;
    private final Runnable deactivate;
    private final MeshTool tool;
    private final dev.turboism.permissions.PermissionChecker permissions;
    private RuntimeSelectionBrush brush;
    private boolean closed;

    RuntimeMeshToolContext(
            final MeshEditor editor,
            final MeshToolCoordinator.ActivationLease lease,
            final Runnable deactivate,
            final MeshTool tool,
            final dev.turboism.permissions.PermissionChecker permissions) {
        this.editor = Objects.requireNonNull(editor, "editor");
        this.lease = Objects.requireNonNull(lease, "lease");
        this.deactivate = Objects.requireNonNull(deactivate, "deactivate");
        this.tool = Objects.requireNonNull(tool, "tool");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
    }

    @Override
    public MeshEditor editor() {
        lease.requireCurrent();
        return editor;
    }

    @Override
    public synchronized MeshBrush selectionBrush() {
        lease.requireCurrent();
        if (closed) throw new IllegalStateException("mesh-tool context is closed");
        if (brush == null) {
            final RuntimeSelectionBrush candidate =
                    RuntimeSelectionBrush.production(editor, lease, deactivate, tool, permissions);
            try {
                candidate.install();
                brush = candidate;
            } catch (RuntimeException | Error failure) {
                candidate.close();
                throw failure;
            }
        }
        return brush;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (brush != null) brush.close();
    }
}
