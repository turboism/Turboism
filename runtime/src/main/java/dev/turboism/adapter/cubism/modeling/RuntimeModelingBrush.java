package dev.turboism.adapter.cubism.modeling;

import dev.turboism.adapter.cubism.mesh.NativeMeshControls;
import dev.turboism.adapter.cubism.mesh.SelectionBrushRoute;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.mesh.SelectionMode;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.modeling.ModelingBrush;
import dev.turboism.sdk.cubism.modeling.ModelingTool;
import dev.turboism.sdk.permission.PermissionIds;
import java.util.List;
import java.util.function.BooleanSupplier;
import javax.swing.JComponent;

/** Ordinary-mode activation wrapper. Candidate ordinals never escape their press-time snapshot. */
final class RuntimeModelingBrush implements ModelingBrush {
    private final SelectionBrushRoute route;

    RuntimeModelingBrush(
            NativeModelingToolSessionResolver sessions,
            NativeModelingToolSessionResolver.Identity identity,
            BooleanSupplier current,
            Runnable deactivate,
            ModelingTool tool,
            PermissionChecker permissions) {
        final var resolver = sessions.resolver();
        final var selection = new NativeModelingPointSelectionAdapter(resolver, sessions, identity, current);
        route = new SelectionBrushRoute(new SelectionBrushRoute.Host() {
            private NativeModelingPointProjector.Snapshot snapshot;

            @Override
            public JComponent component() {
                return identity.component();
            }

            @Override
            public boolean nativeControlAt(int x, int y) {
                return NativeMeshControls.contains(resolver, identity.view(), x, y);
            }

            @Override
            public void beginStroke() {
                permissions.check(PermissionIds.TURBOISM_CUBISM_MODEL_READ, "modeling.brush.point-positions");
                snapshot = NativeModelingPointProjector.capture(resolver, identity);
            }

            @Override
            public void endStroke() {
                snapshot = null;
            }

            @Override
            public List<Point2> projectedVertices() {
                permissions.check(PermissionIds.TURBOISM_CUBISM_MODEL_READ, "modeling.brush.point-positions");
                if (snapshot == null || !selection.current(snapshot)) {
                    throw new IllegalStateException("ordinary brush candidate snapshot is stale");
                }
                return snapshot.positions();
            }

            @Override
            public void commitSelection(List<Integer> indices, SelectionMode mode) {
                permissions.check(PermissionIds.TURBOISM_CUBISM_MODEL_WRITE, "modeling.brush.select-points");
                selection.commit(snapshot, indices, mode);
            }

            @Override
            public SelectionMode selectionMode(boolean shift, boolean control, boolean alt) {
                return tool.strokeSelectionMode(shift, control, alt);
            }

            @Override
            public boolean revalidate() {
                return selection.current(snapshot);
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
}
