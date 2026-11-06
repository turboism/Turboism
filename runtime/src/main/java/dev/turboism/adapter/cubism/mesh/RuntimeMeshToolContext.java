package dev.turboism.adapter.cubism.mesh;

import dev.turboism.sdk.cubism.mesh.MeshBrush;
import dev.turboism.sdk.cubism.mesh.MeshCanvasSession;
import dev.turboism.sdk.cubism.mesh.MeshEditor;
import dev.turboism.sdk.cubism.mesh.MeshTool;
import dev.turboism.sdk.cubism.mesh.MeshToolContext;
import java.util.Objects;

/** Activation-bound SDK context. Brush and canvas ownership is closed before plugin deactivation. */
final class RuntimeMeshToolContext implements MeshToolContext, AutoCloseable {
    private final MeshEditor editor;
    private final MeshToolCoordinator.ActivationLease lease;
    private final Runnable deactivate;
    private final MeshTool tool;
    private final dev.turboism.permissions.PermissionChecker permissions;
    private MeshBrush brush;
    private MeshCanvasSession canvasSession;
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

    /**
     * Returns the runtime canvas session for this exact activation.
     *
     * <p>The capability half of admission is the {@code cubism.mesh.point-and-edge-layout}
     * declaration carried by this tool's registration, so a plugin that declares it receives the
     * session whoever it is; a plugin that does not is refused before any permission is checked.
     * The permission half is enforced by the session on every operation.</p>
     */
    @Override
    public synchronized MeshCanvasSession canvasSession() {
        lease.requireCurrent();
        if (closed) throw new IllegalStateException("mesh-tool context is closed");
        if (canvasSession == null) {
            final RuntimeMeshCanvasSession candidate = RuntimeMeshCanvasSession.production(
                    editor, lease, deactivate, tool, permissions, lease.pointAndEdgeLayoutGranted());
            try {
                candidate.install();
                canvasSession = candidate;
            } catch (RuntimeException | Error failure) {
                candidate.close();
                throw failure;
            }
        }
        return canvasSession;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        RuntimeException first = null;
        if (brush != null) {
            try {
                brush.close();
            } catch (Exception failure) {
                first = new IllegalStateException("failed to close mesh brush handle", failure);
            }
            brush = null;
        }
        if (canvasSession != null) {
            final MeshCanvasSession current = canvasSession;
            canvasSession = null;
            try {
                current.close();
            } catch (RuntimeException failure) {
                first = first == null ? failure : append(first, failure);
            }
        }
        if (first != null) throw first;
    }

    private static RuntimeException append(final RuntimeException first, final RuntimeException next) {
        first.addSuppressed(next);
        return first;
    }
}
