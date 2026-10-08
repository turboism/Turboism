package dev.turboism.adapter.cubism.mesh;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.mesh.MeshBrush;
import dev.turboism.sdk.cubism.mesh.MeshEditor;
import dev.turboism.sdk.cubism.mesh.MeshTool;
import dev.turboism.sdk.cubism.mesh.SelectionMode;
import dev.turboism.sdk.cubism.mesh.VertexSelection;
import dev.turboism.sdk.cubism.model.Point2;
import java.util.List;
import java.util.Objects;
import javax.swing.JComponent;

/** Mesh activation wrapper over the shared runtime brush input/overlay route. */
final class RuntimeSelectionBrush implements MeshBrush {
    private final SelectionBrushRoute route;

    RuntimeSelectionBrush(final Host host) {
        route = new SelectionBrushRoute(host);
    }
    /**
     * Resolves the stroke mode a contributing tool declares for the exact press-time modifiers.
     *
     * <p>The runtime keeps no modifier policy of its own. A tool that declares nothing returns the
     * additive framework default, which preserves the behaviour of tools written before this
     * contract existed.</p>
     */
    static SelectionMode strokeModeFor(
            final MeshTool tool, final boolean shiftDown, final boolean controlDown, final boolean altDown) {
        final SelectionMode mode =
                Objects.requireNonNull(tool, "tool").strokeSelectionMode(shiftDown, controlDown, altDown);
        return mode == null ? SelectionMode.ADD : mode;
    }

    static RuntimeSelectionBrush production(
            final MeshEditor editor,
            final MeshToolCoordinator.ActivationLease lease,
            final Runnable deactivate,
            final MeshTool tool,
            final dev.turboism.permissions.PermissionChecker permissions) {
        Objects.requireNonNull(editor, "editor");
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(deactivate, "deactivate");
        lease.requireCurrent();
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(permissions, "permissions");
        if (!(lease.session() instanceof NativeMeshToolSession session)) {
            throw new IllegalStateException("selection brush requires an exact native mesh-tool session");
        }
        final VerifiedMemberResolver resolver = session.resolver();
        final Object rawComponent = session.identity().component();
        if (resolver == null || !(rawComponent instanceof JComponent component)) {
            throw new IllegalStateException("selection brush exact component or resolver is unavailable");
        }
        return new RuntimeSelectionBrush(new Host() {
            @Override
            public JComponent component() {
                lease.requireCurrent();
                return component;
            }

            @Override
            public boolean nativeControlAt(final int x, final int y) {
                lease.requireCurrent();
                return NativeMeshControls.contains(resolver, session.identity().modelingView(), x, y);
            }

            @Override
            public List<Point2> projectedVertices() {
                lease.requireCurrent();
                permissions.check(
                        dev.turboism.sdk.permission.PermissionIds.TURBOISM_CUBISM_MODEL_READ,
                        "mesh.brush.vertex-positions");
                return NativeMeshVertexProjector.project(resolver, session.identity());
            }

            @Override
            public void commitSelection(final List<Integer> indices, final SelectionMode mode) {
                lease.requireCurrent();
                editor.select(new VertexSelection(indices), mode);
            }

            @Override
            public SelectionMode selectionMode(
                    final boolean shiftDown, final boolean controlDown, final boolean altDown) {
                return strokeModeFor(tool, shiftDown, controlDown, altDown);
            }

            @Override
            public boolean revalidate() {
                return lease.revalidate();
            }

            @Override
            public void deactivate() {
                deactivate.run();
            }
        });
    }

    void install() {
        route.install();
    }

    @Override
    public int radiusPixels() {
        return route.radiusPixels();
    }

    @Override
    public void setRadiusPixels(int value) {
        route.setRadiusPixels(value);
    }

    @Override
    public void close() {
        route.close();
    }

    JComponent overlayForTests() {
        return route.overlayForTests();
    }

    boolean previewVisibleForTests() {
        return route.previewVisibleForTests();
    }

    interface Host extends SelectionBrushRoute.Host {}
}
