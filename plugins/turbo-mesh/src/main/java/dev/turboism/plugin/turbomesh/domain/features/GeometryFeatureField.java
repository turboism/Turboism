package dev.turboism.plugin.turbomesh.domain.features;

import dev.turboism.plugin.turbomesh.domain.DomainMask;
import java.util.List;

/**
 * {@link FeatureField} backed by the cell-field {@link MeshFeatureAnalyzer.Analysis}: bilinear
 * density lookup, nearest-cell direction with a confidence gate, and an explicit hard-edge list
 * supplied by the constraint builder.
 */
public final class GeometryFeatureField implements FeatureField {

    private final MeshFeatureAnalyzer.Analysis analysis;
    private final List<ConstraintSegment> hardEdges;

    public GeometryFeatureField(final MeshFeatureAnalyzer.Analysis analysis, final List<ConstraintSegment> hardEdges) {
        this.analysis = analysis;
        this.hardEdges = List.copyOf(hardEdges);
    }

    @Override
    public double densityAt(final double u, final double v) {
        final DomainMask mask = analysis.mask();
        final int cell = mask.cellAtUv(u, v);
        if (cell < 0) {
            return 0.0;
        }
        final int w = mask.width();
        final int h = mask.height();
        final double[] center = mask.cellCenterUv(cell);
        final double cellUv = mask.cellUv();
        // Fractional offsets inside the cell, for bilinear weighting over the 2x2 neighborhood.
        final double fu = (u - center[0]) / cellUv + 0.5;
        final double fv = (v - center[1]) / cellUv + 0.5;
        final int x = cell % w;
        final int y = cell / w;
        final int x1 = fu >= 0.0 ? Math.min(w - 1, x + 1) : Math.max(0, x - 1);
        final int y1 = fv >= 0.0 ? Math.min(h - 1, y + 1) : Math.max(0, y - 1);
        final double tx = Math.abs(fu);
        final double ty = Math.abs(fv);
        double sum = 0.0;
        double weight = 0.0;
        for (final int c : new int[] {y * w + x, y * w + x1, y1 * w + x, y1 * w + x1}) {
            final double wx = (c % w == x1) ? tx : 1.0 - tx;
            final double wy = (c / w == y1) ? ty : 1.0 - ty;
            final double wgt = wx * wy;
            if (wgt <= 0.0) {
                continue;
            }
            sum += wgt * analysis.density()[c];
            weight += wgt;
        }
        return weight > 0.0 ? sum / weight : 0.0;
    }

    @Override
    public double directionAt(final double u, final double v) {
        final int cell = analysis.mask().cellAtUv(u, v);
        return cell < 0 ? 0.0 : analysis.direction()[cell];
    }

    @Override
    public double directionConfidenceAt(final double u, final double v) {
        final int cell = analysis.mask().cellAtUv(u, v);
        return cell < 0 ? 0.0 : analysis.coherence()[cell];
    }

    @Override
    public List<ConstraintSegment> hardEdges() {
        return hardEdges;
    }
}
