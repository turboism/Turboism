package dev.turboism.plugin.turbomesh.domain.sampler;

import dev.turboism.plugin.turbomesh.domain.DomainMask;
import dev.turboism.plugin.turbomesh.domain.InteriorSampler;
import dev.turboism.plugin.turbomesh.domain.PlacementSettings;
import dev.turboism.plugin.turbomesh.domain.RegionSegmenter;
import dev.turboism.plugin.turbomesh.domain.features.FeatureField;
import dev.turboism.plugin.turbomesh.domain.features.FeatureOptions;
import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.List;

/**
 * Density-driven interior placement: a fine candidate lattice is visited in seeded random order
 * and a candidate is kept only when it keeps its field-dependent clearance from every accepted
 * neighbor — dense where the feature field wants vertices, sparse where it does not. Fully
 * deterministic: the same mask, regions, field, settings, options and budget always yield the
 * same points. Budget-bounded: sampling stops at {@code budget} sites.
 */
public final class AdaptiveInteriorSampler {

    private AdaptiveInteriorSampler() {}

    /**
     * Places interior points across the union of {@code regions}.
     *
     * @param field feature field driving the local placement radius; density {@code d} maps to
     *     radius {@code rMax - (rMax - rMin) * d}
     * @param budget hard cap on returned points; the caller reserves boundary points first
     */
    public static List<Point2> sample(
            final DomainMask mask,
            final List<RegionSegmenter.Region> regions,
            final FeatureField field,
            final PlacementSettings settings,
            final FeatureOptions options,
            final int budget) {
        final List<Point2> out = new ArrayList<>();
        if (regions.isEmpty() || budget <= 0) {
            return out;
        }
        final int w = mask.width();
        final int h = mask.height();
        final boolean[] member = new boolean[w * h];
        int minX = Integer.MAX_VALUE;
        int maxX = 0;
        int minY = Integer.MAX_VALUE;
        int maxY = 0;
        for (final RegionSegmenter.Region region : regions) {
            for (final int cell : region.cells()) {
                member[cell] = true;
                final int x = cell % w;
                final int y = cell / w;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
        }
        if (minX > maxX) {
            return out;
        }

        final double cellUv = mask.cellUv();
        final double rMax = Math.max(settings.minSpacingUv() * 1.5, settings.interiorSpacingUv());
        final double rMin = Math.max(Math.max(settings.minSpacingUv() * 1.5, cellUv), rMax * options.minRadiusFactor());
        final double marginUv = settings.interiorMarginUv();
        final float[] dist = mask.distanceTransform();

        // Candidate lattice at the fine radius; density decides each candidate's clearance.
        final int step = Math.max(1, (int) Math.round(rMin / cellUv));
        final int gw = (maxX - minX) / step + 1;
        final int gh = (maxY - minY) / step + 1;
        final int[] latticeCell = new int[gw * gh];
        final double[] latticeR = new double[gw * gh];
        java.util.Arrays.fill(latticeCell, -1);
        final double[] origin = mask.cellCenterUv(0);
        final double u0 = origin[0] - 0.5 * cellUv;
        final double v0 = origin[1] - 0.5 * cellUv;
        int candidates = 0;
        for (int ly = 0; ly < gh; ly++) {
            for (int lx = 0; lx < gw; lx++) {
                final int x = minX + lx * step;
                final int y = minY + ly * step;
                final int cell = y * w + x;
                if (!member[cell]) {
                    continue;
                }
                if (dist[cell] * cellUv < marginUv) {
                    continue;
                }
                final double u = u0 + x * cellUv;
                final double v = v0 + y * cellUv;
                final double d = Math.max(0.0, Math.min(1.0, field.densityAt(u, v)));
                final double r = rMax - (rMax - rMin) * d;
                latticeCell[ly * gw + lx] = cell;
                latticeR[ly * gw + lx] = Math.max(rMin, r);
                candidates++;
            }
        }
        if (candidates == 0) {
            return out;
        }

        // Seeded random visit order (Fisher-Yates over lattice indices).
        final int[] order = new int[gw * gh];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        long rng = dev.turboism.plugin.turbomesh.domain.InteriorSampler.mix64(settings.seed() ^ 0xC0FFEE1234ABL);
        for (int i = order.length - 1; i > 0; i--) {
            rng = dev.turboism.plugin.turbomesh.domain.InteriorSampler.mix64(rng);
            final int j = (int) (InteriorSampler.toUnit(rng) % (i + 1));
            final int tmp = order[i];
            order[i] = order[j];
            order[j] = tmp;
        }

        // Two-stage greedy acceptance against accepted lattice neighbors, both passes in the same
        // seeded random order: stage 1 floors the radius at 0.55*rMax so coarse coverage lands
        // everywhere first; stage 2 re-walks the lattice at the full field radius and refines
        // only where the density wants more. Stage 2 inherits stage-1 acceptances, so refinement
        // never crowds coarse points. When
        // the feature field supplies a confident local direction, the disc becomes an ellipse:
        // clearance shrinks along the direction and grows across it, so accepted vertices chain
        // along artwork features instead of fighting them.
        final boolean[] accepted = new boolean[gw * gh];
        final boolean anisotropic = options.directionInfluence() > 0.0;
        final double stretch = 0.35 * options.directionInfluence();
        final int reach = (int) Math.ceil(2.0 * rMax * (1.0 + stretch) / (step * cellUv)) + 1;
        int acceptedCount = 0;
        final double[] stageFloors = {rMax * 0.55, 0.0};
        for (final double stageFloor : stageFloors) {
            for (final int li : order) {
                if (acceptedCount >= budget) {
                    break;
                }
                if (latticeCell[li] < 0 || accepted[li]) {
                    continue;
                }
                final int lx = li % gw;
                final int ly = li / gw;
                final int cell = latticeCell[li];
                final double u = u0 + (cell % w) * cellUv;
                final double v = v0 + (cell / w) * cellUv;
                final double r = Math.max(latticeR[li], stageFloor);
                final double influence = anisotropic
                        ? Math.min(1.0, field.directionConfidenceAt(u, v)) * options.directionInfluence()
                        : 0.0;
                final double theta = influence > 0.0 ? field.directionAt(u, v) : 0.0;
                final double cos = Math.cos(theta);
                final double sin = Math.sin(theta);
                boolean ok = true;
                for (int dy = -reach; dy <= reach && ok; dy++) {
                    for (int dx = -reach; dx <= reach && ok; dx++) {
                        final int nx = lx + dx;
                        final int ny = ly + dy;
                        if (nx < 0 || ny < 0 || nx >= gw || ny >= gh) {
                            continue;
                        }
                        final int ni = ny * gw + nx;
                        if (!accepted[ni]) {
                            continue;
                        }
                        final double du = (double) (lx - nx) * step * cellUv;
                        final double dv = (double) (ly - ny) * step * cellUv;
                        final double need = Math.max(r, latticeR[ni]);
                        double metric;
                        if (influence > 0.0) {
                            final double along = Math.abs(du * cos + dv * sin);
                            final double across = Math.abs(-du * sin + dv * cos);
                            final double rAlong = need * (1.0 - 0.5 * influence);
                            final double rAcross = need * (1.0 + 0.5 * influence);
                            metric = Math.hypot(along / rAlong, across / rAcross); // elliptical metric
                        } else {
                            metric = Math.hypot(du, dv) / need;
                        }
                        if (metric < 1.0) {
                            ok = false;
                        }
                    }
                }
                if (!ok) {
                    continue;
                }
                accepted[li] = true;
                acceptedCount++;
            }
        }
        // Deterministic fallback: a heavily directional field can over-thin coarse lattices in
        // nearly-flat areas. Re-run without anisotropy when the yield collapses; keep the denser
        // result. Same inputs always take the same branch.
        if (anisotropic && candidates > 40 && acceptedCount < candidates * 0.03) {
            return sample(mask, regions, isotropicView(field), settings, options.withDirectionInfluence(0.0), budget);
        }

        // Materialize accepted lattice points with a small deterministic jitter (well under the
        // min-spacing slack) so the output does not read as a visible grid.
        for (int ly = 0; ly < gh; ly++) {
            for (int lx = 0; lx < gw; lx++) {
                final int li = ly * gw + lx;
                if (latticeCell[li] < 0 || !accepted[li]) {
                    continue;
                }
                final int cell = latticeCell[li];
                final double u = u0 + (cell % w) * cellUv;
                final double v = v0 + (cell / w) * cellUv;
                rng = dev.turboism.plugin.turbomesh.domain.InteriorSampler.mix64(rng);
                final double ju = (InteriorSampler.toUnit(rng) - 0.5) * 0.5 * rMin;
                rng = dev.turboism.plugin.turbomesh.domain.InteriorSampler.mix64(rng);
                final double jv = (InteriorSampler.toUnit(rng) - 0.5) * 0.5 * rMin;
                final double pu = u + ju;
                final double pv = v + jv;
                final int at = mask.cellAtUv(pu, pv);
                if (at < 0 || !member[at]) {
                    out.add(new Point2((float) u, (float) v));
                } else {
                    out.add(new Point2((float) pu, (float) pv));
                }
            }
        }
        return out;
    }

    /** View of {@code field} with direction confidence zeroed — the isotropic fallback metric. */
    private static FeatureField isotropicView(final FeatureField field) {
        return new FeatureField() {
            @Override
            public double densityAt(final double u, final double v) {
                return field.densityAt(u, v);
            }

            @Override
            public double directionAt(final double u, final double v) {
                return field.directionAt(u, v);
            }

            @Override
            public double directionConfidenceAt(final double u, final double v) {
                return 0.0;
            }

            @Override
            public List<ConstraintSegment> hardEdges() {
                return field.hardEdges();
            }
        };
    }
}
