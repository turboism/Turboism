package dev.turboism.plugin.turbomesh.domain.features;

import dev.turboism.plugin.turbomesh.domain.constraints.MeshConstraintBuilder.MeshConstraints;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.Point2;
import java.util.List;

/**
 * Pure SVG diagnostics for one feature-aware placement: density heatmap, hard edges, protected
 * corners, accepted interior sites, and the output triangles. Output is a self-contained SVG
 * string (UV space, y flipped) — used by tests and offline replay, never attached to the host UI.
 */
public final class FeatureDiagnostics {

    private FeatureDiagnostics() {}

    /**
     * Renders the placement diagnostics at {@code size} by {@code size} pixels.
     *
     * @param geometry final replacement geometry (positions used as UV here — callers pass a
     *     geometry whose positions are UV-space, e.g. from tests)
     * @param field feature field behind the placement; may be {@code null} to skip the heatmap
     * @param constraints explicit protection set; may be {@code null}
     * @param corners detected corners; may be {@code null}
     */
    public static String svg(
            final ArtMeshGeometry geometry,
            final FeatureField field,
            final MeshConstraints constraints,
            final List<MeshFeatureAnalyzer.Corner> corners,
            final int size) {
        final StringBuilder s = new StringBuilder(1 << 16);
        s.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"")
                .append(size)
                .append("\" height=\"")
                .append(size)
                .append("\" viewBox=\"0 0 1 1\">\n");
        // Density heatmap on a 48x48 probe grid.
        if (field != null) {
            final int probes = 48;
            final double cell = 1.0 / probes;
            for (int py = 0; py < probes; py++) {
                for (int px = 0; px < probes; px++) {
                    final double u = (px + 0.5) * cell;
                    final double v = (py + 0.5) * cell;
                    final double d = field.densityAt(u, v);
                    if (d <= 0.01) {
                        continue;
                    }
                    s.append("<rect x=\"")
                            .append(fmt(u - cell / 2))
                            .append("\" y=\"")
                            .append(fmt(1.0 - v - cell / 2))
                            .append("\" width=\"")
                            .append(fmt(cell))
                            .append("\" height=\"")
                            .append(fmt(cell))
                            .append("\" fill=\"#3050c0\" fill-opacity=\"")
                            .append(fmt(Math.min(0.55, d * 0.55)))
                            .append("\"/>\n");
                }
            }
        }
        // Hard edges.
        if (constraints != null) {
            for (final FeatureField.ConstraintSegment e : constraints.hardEdges()) {
                if (e.kind() == FeatureField.ConstraintSegment.Kind.CORNER) {
                    continue; // zero-length struts render as corner dots below
                }
                s.append("<line x1=\"")
                        .append(fmt(e.a().x()))
                        .append("\" y1=\"")
                        .append(fmt(1.0 - e.a().y()))
                        .append("\" x2=\"")
                        .append(fmt(e.b().x()))
                        .append("\" y2=\"")
                        .append(fmt(1.0 - e.b().y()))
                        .append("\" stroke=\"")
                        .append(color(e.kind()))
                        .append("\" stroke-width=\"0.0025\"/>\n");
            }
        }
        // Output triangles.
        final List<Integer> idx = geometry.triangleIndices();
        for (int i = 0; i + 2 < idx.size(); i += 3) {
            final Point2 a = geometry.positions().get(idx.get(i));
            final Point2 b = geometry.positions().get(idx.get(i + 1));
            final Point2 c = geometry.positions().get(idx.get(i + 2));
            s.append("<polygon points=\"")
                    .append(fmt(a.x()))
                    .append(',')
                    .append(fmt(1.0 - a.y()))
                    .append(' ')
                    .append(fmt(b.x()))
                    .append(',')
                    .append(fmt(1.0 - b.y()))
                    .append(' ')
                    .append(fmt(c.x()))
                    .append(',')
                    .append(fmt(1.0 - c.y()))
                    .append("\" fill=\"none\" stroke=\"#20a020\" stroke-width=\"0.0012\"/>\n");
        }
        // Protected corners.
        if (corners != null) {
            for (final MeshFeatureAnalyzer.Corner corner : corners) {
                s.append("<circle cx=\"")
                        .append(fmt(corner.at().x()))
                        .append("\" cy=\"")
                        .append(fmt(1.0 - corner.at().y()))
                        .append("\" r=\"0.006\" fill=\"#e03030\"/>\n");
            }
        }
        s.append("</svg>\n");
        return s.toString();
    }

    private static String color(final FeatureField.ConstraintSegment.Kind kind) {
        return switch (kind) {
            case BOUNDARY -> "#101010";
            case HOLE -> "#c07010";
            case CORNER -> "#e03030";
            case STRUCTURE -> "#1060c0";
        };
    }

    private static String fmt(final double value) {
        return String.format(java.util.Locale.ROOT, "%.5f", value);
    }
}
