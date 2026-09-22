package dev.turboism.plugin.atlasdalsoo.dalsoo;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasRotationMode;

/**
 * Fixed-scale and automatic-scale packing driver, mirroring the Cubism 5.4
 * {@code AtlasAutoLayoutPacking} entry points ({@code fixedScalePacking} /
 * {@code autoScalePacking}).
 *
 * <p>Automatic scale runs a deterministic bisection over the uniform item scale
 * and keeps the largest attempt whose plan fits every participating item;
 * {@code maxTry} bounds the refinement depth and {@code tolerance} the relative
 * scale step below which the search stops.</p>
 */
public final class AutoScalePack {

    /** Outline preparation for one item: raw and margin-buffered rings at scale 1. */
    public record Prepared(int id, String textureId, double[][] inpts,
        double[][] outHalf, boolean fixPosition, boolean fixRotate,
        boolean fixScale, double[] issuedCosSin, double[] issuedPosition,
        boolean issuedPlaced, double issuedScale) {
    }

    private final double pageWidth;
    private final double pageHeight;
    private final TextureAtlasRotationMode rotationMode;
    private final boolean abey;
    private final double hSkew;
    private final Double segmentMaxLength;

    public AutoScalePack(final double pageWidth, final double pageHeight,
        final TextureAtlasRotationMode rotationMode, final boolean abey,
        final double hSkew, final Double segmentMaxLength) {
        this.pageWidth = pageWidth;
        this.pageHeight = pageHeight;
        this.rotationMode = rotationMode;
        this.abey = abey;
        this.hSkew = hSkew;
        this.segmentMaxLength = segmentMaxLength;
    }

    public static final class Outcome {
        public final double scale;
        public final PolygonPack.Result result;
        public final int attempts;

        Outcome(final double scale, final PolygonPack.Result result, final int attempts) {
            this.scale = scale;
            this.result = result;
            this.attempts = attempts;
        }
    }

    /** Packs at an exact uniform scale; free items get it, fixed-scale items keep theirs. */
    public PolygonPack.Result fixedScalePack(final List<Prepared> items,
        final double scale, final BooleanSupplier cancelled,
        final IntConsumer progress) {
        return new PolygonPack(pageWidth, pageHeight, rotationMode, abey, hSkew,
            segmentMaxLength).pack(sources(items, scale), cancelled, progress);
    }

    /**
     * Finds the largest uniform scale whose plan places every participating
     * item, scanning a deterministic geometric ladder from the optimistic upper
     * bound down to {@code tolerance} (at most {@code maxTry} attempts).
     *
     * <p>Packing success is not monotone in scale — the vertex-contact kernel
     * can leave items unplaced at any scale — so this does not bisect on
     * {@code complete()}. The first complete attempt wins (largest scale, since
     * the ladder descends); otherwise the result with fewest unplaced items is
     * returned, ties preferring the larger scale.</p>
     */
    public Outcome autoScalePack(final List<Prepared> items, final double tolerance,
        final int maxTry, final BooleanSupplier cancelled,
        final DoubleConsumer progress) {
        final double hi = initialUpperBound(items);
        final int tries = Math.max(1, maxTry);
        // geometric ladder: hi, hi*k, ..., ~tolerance
        final double floor = Math.max(tolerance, 1e-4);
        final double k = tries <= 1 ? 1
            : Math.pow(Math.min(floor / Math.max(hi, floor), 1.0), 1.0 / (tries - 1));
        PolygonPack.Result best = null;
        double bestScale = hi;
        int attempts = 0;
        for (int i = 0; i < tries; i++) {
            final double scale = i == 0 ? hi : Math.max(hi * Math.pow(k, i), floor);
            final PolygonPack.Result attempt = new PolygonPack(pageWidth, pageHeight,
                rotationMode, abey, hSkew, segmentMaxLength)
                .pack(sources(items, scale), cancelled, null);
            attempts++;
            if (progress != null) {
                progress.accept((double) attempts / tries);
            }
            if (attempt.complete()) {
                return new Outcome(scale, attempt, attempts);
            }
            if (best == null
                || attempt.unplacedIds.size() < best.unplacedIds.size()) {
                best = attempt;
                bestScale = scale;
            }
        }
        return new Outcome(bestScale, best, attempts);
    }

    private double initialUpperBound(final List<Prepared> items) {
        double freeArea = 0;
        for (final Prepared p : items) {
            if (p.fixPosition()) {
                continue; // obstacles do not participate in the scale search
            }
            final double area = Geom.area(p.inpts());
            freeArea += p.fixScale() ? area * p.issuedScale() * p.issuedScale() : area;
        }
        final double pageArea = pageWidth * pageHeight;
        // optimistic bound: free outlines alone filling the page exactly; the
        // issued scale contract caps automatic scale at 1 (items never upscale)
        return freeArea <= 0 ? 1.0 : Math.min(1.0, Math.sqrt(pageArea / freeArea));
    }

    /** Scales each item's rings by its effective scale (item scale when fixScale). */
    private List<SourcePoly> sources(final List<Prepared> items, final double scale) {
        final List<SourcePoly> out = new ArrayList<>(items.size());
        for (final Prepared p : items) {
            final double s = p.fixScale() ? p.issuedScale() : scale;
            out.add(SourcePoly.builder(p.id(), p.textureId(),
                    scaleRing(p.inpts(), s), scaleRing(p.outHalf(), s))
                .fixPosition(p.fixPosition())
                .fixRotate(p.fixRotate())
                .fixScale(p.fixScale())
                .issuedCosSin(p.issuedCosSin())
                .issuedPosition(p.issuedPosition())
                .issuedPlaced(p.issuedPlaced())
                .build());
        }
        return out;
    }

    private static double[][] scaleRing(final double[][] ring, final double s) {
        final double[][] out = new double[ring.length][];
        for (int i = 0; i < ring.length; i++) {
            out[i] = new double[] {ring[i][0] * s, ring[i][1] * s};
        }
        return out;
    }
}
