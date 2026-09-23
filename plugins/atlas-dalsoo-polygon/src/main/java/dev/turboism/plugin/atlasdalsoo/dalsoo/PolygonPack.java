package dev.turboism.plugin.atlasdalsoo.dalsoo;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasRotationMode;

/**
 * Single-page polygon packer built on the ported Dalsoo kernel.
 *
 * <p>Fixed-position items become pre-placed obstacles; every other item is packed
 * with its own rotation candidates (issued angle for locked items,
 * {@code NONE}/{@code QUARTER}/{@code FREE} otherwise). The {@code abey} flag picks
 * the continuous-angle kernel, matching the Cubism 5.4 packing switch; the
 * Dalalah kernel uses the discrete candidate lists (FREE = 18 steps, as in 5.4).</p>
 */
public final class PolygonPack {

    /** Discrete FREE-rotation resolution: 18 candidates, as the 5.4 native packer uses. */
    private static final int FREE_ROTATION_STEPS = 18;

    private final double pageWidth;
    private final double pageHeight;
    private final double hSkew;
    private final boolean abey;
    private final TextureAtlasRotationMode rotationMode;
    private final Double segmentMaxLength;

    public PolygonPack(final double pageWidth, final double pageHeight,
        final TextureAtlasRotationMode rotationMode, final boolean abey,
        final double hSkew, final Double segmentMaxLength) {
        this.pageWidth = pageWidth;
        this.pageHeight = pageHeight;
        this.rotationMode = rotationMode;
        this.abey = abey;
        this.hSkew = hSkew;
        this.segmentMaxLength = segmentMaxLength;
    }

    /** Result of one packing attempt. */
    public static final class Result {
        public final List<PackOutcome> outcomes;
        public final List<Integer> unplacedIds;

        Result(final List<PackOutcome> outcomes, final List<Integer> unplacedIds) {
            this.outcomes = outcomes;
            this.unplacedIds = unplacedIds;
        }

        /** True when every item was placed. */
        public boolean complete() {
            return unplacedIds.isEmpty();
        }
    }

    /**
     * Packs the given source polygons into one page.
     *
     * <p>{@code sources} must already carry their item scale (fixed-scale items
     * pre-scaled, free items pre-scaled by the global plan scale). Fixed-position
     * items are inserted as obstacles at their issued transform and appear in the
     * outcomes with their issued cos/sin and translation.</p>
     */
    public Result pack(final List<SourcePoly> sources,
        final BooleanSupplier cancelled, final IntConsumer progress) {
        final List<SourcePoly> pending = new ArrayList<>();
        final List<double[][]> trigos = new ArrayList<>();
        final List<PackedPoly> obstacles = new ArrayList<>();
        final List<PackOutcome> fixedOutcomes = new ArrayList<>();
        for (final SourcePoly sp : sources) {
            if (sp.fixPosition) {
                if (!sp.issuedPlaced) {
                    continue; // opted out and not on the page: no obstacle, no outcome
                }
                final PackedPoly obstacle = new PackedPoly(sp.id, sp.inpts, sp.outpts, null);
                obstacle.fixRotateMove(sp.issuedCosSin, sp.issuedPosition);
                obstacle.place();
                obstacles.add(obstacle);
                fixedOutcomes.add(new PackOutcome(sp.id, sp.issuedCosSin, sp.issuedPosition));
            } else {
                pending.add(sp);
                trigos.add(rotationCandidates(sp));
            }
        }
        final Bin bin = new Bin(pending, obstacles, trigos, pageWidth, pageHeight,
            hSkew, segmentMaxLength, cancelled, progress);
        bin.pack(abey);
        final List<PackOutcome> outcomes = new ArrayList<>(fixedOutcomes);
        for (final PackedPoly p : bin.packedPolys()) {
            if (obstacles.contains(p)) {
                continue;
            }
            outcomes.add(new PackOutcome(p.id, p.trigo, p.position));
        }
        return new Result(outcomes, bin.unplacedIds());
    }

    /** Rotation candidates for one poly under the configured mode and its locks. */
    private double[][] rotationCandidates(final SourcePoly sp) {
        if (sp.fixRotate) {
            return new double[][] {sp.issuedCosSin};
        }
        return switch (rotationMode) {
            case NONE -> new double[][] {sp.issuedCosSin};
            case QUARTER -> {
                final double[][] out = new double[4][];
                final double base = Math.atan2(sp.issuedCosSin[1], sp.issuedCosSin[0]);
                for (int i = 0; i < 4; i++) {
                    out[i] = Geom.cosSin(base + i * Math.PI / 2);
                }
                yield out;
            }
            case FREE -> {
                final double[][] out = new double[FREE_ROTATION_STEPS][];
                final double base = Math.atan2(sp.issuedCosSin[1], sp.issuedCosSin[0]);
                for (int i = 0; i < FREE_ROTATION_STEPS; i++) {
                    out[i] = Geom.cosSin(base + i * 2 * Math.PI / FREE_ROTATION_STEPS);
                }
                yield out;
            }
        };
    }
}
