package dev.turboism.plugin.atlasdalsoo.layout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

import dev.turboism.plugin.atlasdalsoo.dalsoo.AutoScalePack;
import dev.turboism.plugin.atlasdalsoo.dalsoo.PolygonPack;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasItemLayoutPolicy;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutBackend;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutQuality;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasOutline;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonConstraints;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonItem;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonPlacement;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonPlan;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonPlanner;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasRotationMode;

/**
 * Polygon packing planner on the ported Dalsoo kernel.
 *
 * <p>Each item's outline is normalized into a single simple ring (disjoint rings
 * stitched by zero-width bridges; holes filled conservatively and reported), the
 * packing kernel then finds translations and rotations under the declared
 * {@link TextureAtlasRotationMode}. Per-item {@code preserve*} policy flags are
 * honored as kernel locks. {@code HOST_NATIVE} is a reserved backend: this planner
 * does not implement it and reports {@code overflow} for every item instead of
 * pretending support.</p>
 */
public final class DalsooPolygonPlanner implements TextureAtlasPolygonPlanner {

    /** Flattening tolerance for outline normalization, in page pixels. */
    private static final double FLATTEN_FLATNESS = 0.5;
    /** Near-rectangle tolerance used by the AUTO backend, in page pixels. */
    private static final double NEAR_RECT_TOLERANCE = 1.5;
    /** Maximum outline vertices per quality preset (RDP target). */
    private static final Map<TextureAtlasLayoutQuality, Double> SIMPLIFY_EPS = Map.of(
        TextureAtlasLayoutQuality.FAST, 1.5,
        TextureAtlasLayoutQuality.BALANCED, 0.6,
        TextureAtlasLayoutQuality.DENSE, 0.2
    );
    private static final Map<TextureAtlasLayoutQuality, Integer> AUTO_SCALE_MAX_TRY = Map.of(
        TextureAtlasLayoutQuality.FAST, 8,
        TextureAtlasLayoutQuality.BALANCED, 12,
        TextureAtlasLayoutQuality.DENSE, 20
    );
    private static final double AUTO_SCALE_TOLERANCE = 0.005;
    private static final double H_SKEW = 0.7;
    /**
     * Extra bin inset absorbing outline-flattening error so raw rings keep the
     * full configured margin from the page edge.
     */
    private static final double EDGE_SLACK = 0.3;

    private final BooleanSupplier cancelled;
    private final IntConsumer progress;
    private final RectPlanSupplier rectPlanner;
    private final boolean useAbey;

    /** Supplies the registered rectangle planner for AUTO/near-rect dispatch; may be null. */
    public interface RectPlanSupplier {
        TextureAtlasPolygonPlan planRects(List<TextureAtlasPolygonItem> items,
            TextureAtlasPolygonConstraints constraints, boolean parallel);
    }

    public DalsooPolygonPlanner() {
        this(null, null, null, true);
    }

    public DalsooPolygonPlanner(final BooleanSupplier cancelled,
        final IntConsumer progress, final RectPlanSupplier rectPlanner,
        final boolean useAbey) {
        this.cancelled = cancelled;
        this.progress = progress;
        this.rectPlanner = rectPlanner;
        this.useAbey = useAbey;
    }

    @Override
    public TextureAtlasPolygonPlan plan(final List<TextureAtlasPolygonItem> items,
        final TextureAtlasPolygonConstraints constraints) {
        return plan(items, constraints, false);
    }

    @Override
    public TextureAtlasPolygonPlan plan(final List<TextureAtlasPolygonItem> items,
        final TextureAtlasPolygonConstraints constraints, final boolean parallel) {
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(constraints, "constraints");
        if (constraints.backend() == TextureAtlasLayoutBackend.HOST_NATIVE) {
            // reserved slot: the host packer owns the page, there is no Turboism plan
            return new TextureAtlasPolygonPlan(constraints.pageWidth(),
                constraints.pageHeight(), constraints.automaticScale() ? 1
                    : constraints.requestedScale(),
                List.of(), items.stream().map(TextureAtlasPolygonItem::textureId).toList(),
                TextureAtlasLayoutBackend.HOST_NATIVE,
                Map.of("status", "delegated-to-host"));
        }
        final BackendChoice choice = chooseBackend(items, constraints);
        try {
            if (choice == BackendChoice.RECTANGLE && rectPlanner != null) {
                return rectPlanner.planRects(items, constraints, parallel);
            }
            return switch (choice) {
                case RECTANGLE -> packPolygon(items, constraints, parallel,
                    TextureAtlasLayoutBackend.DALSOO_POLYGON); // no rect planner installed
                case POLYGON -> packPolygon(items, constraints, parallel,
                    TextureAtlasLayoutBackend.DALSOO_POLYGON);
            };
        } catch (final dev.turboism.plugin.atlasdalsoo.dalsoo.PackingCancelledException e) {
            // cooperative cancellation: nothing was applied; report every item
            // that would need a placement as overflow instead of failing
            final List<String> overflow = new ArrayList<>();
            for (final TextureAtlasPolygonItem item : items) {
                final TextureAtlasItemLayoutPolicy policy = item.policy();
                if (policy == null || policy.participate() || item.currentlyPlaced()) {
                    overflow.add(item.textureId());
                }
            }
            return new TextureAtlasPolygonPlan(constraints.pageWidth(),
                constraints.pageHeight(), constraints.automaticScale() ? 1
                    : constraints.requestedScale(), List.of(), List.copyOf(overflow),
                TextureAtlasLayoutBackend.DALSOO_POLYGON,
                Map.of("cancelled", "true"));
        }
    }

    private enum BackendChoice {RECTANGLE, POLYGON}

    private BackendChoice chooseBackend(final List<TextureAtlasPolygonItem> items,
        final TextureAtlasPolygonConstraints constraints) {
        if (constraints.backend() == TextureAtlasLayoutBackend.DALSOO_POLYGON) {
            return BackendChoice.POLYGON;
        }
        if (constraints.backend() == TextureAtlasLayoutBackend.MAXRECTS_RECTANGLE) {
            return BackendChoice.RECTANGLE;
        }
        // AUTO: route only when every participating outline is near-rectangular
        boolean allNearRect = true;
        for (final TextureAtlasPolygonItem item : items) {
            final TextureAtlasItemLayoutPolicy policy = item.policy();
            if (policy != null && !policy.participate()) {
                continue;
            }
            final TextureAtlasOutline outline = item.outline();
            for (final double[][] ring : outline.rings()) {
                if (!OutlineGeometry.isNearRect(ring, NEAR_RECT_TOLERANCE)) {
                    allNearRect = false;
                    break;
                }
            }
            if (!allNearRect) {
                break;
            }
        }
        return allNearRect ? BackendChoice.RECTANGLE : BackendChoice.POLYGON;
    }

    private TextureAtlasPolygonPlan packPolygon(final List<TextureAtlasPolygonItem> items,
        final TextureAtlasPolygonConstraints constraints, final boolean parallel,
        final TextureAtlasLayoutBackend backend) {
        final double simplifyEps = SIMPLIFY_EPS.get(constraints.quality());
        final Map<String, String> diagnostics = new LinkedHashMap<>();
        final List<AutoScalePack.Prepared> prepared = new ArrayList<>();
        int stitched = 0;
        int fallbackRect = 0;
        int index = 0;
        for (final TextureAtlasPolygonItem item : items) {
            final String textureId = item.textureId();
            final TextureAtlasItemLayoutPolicy policy = item.policy() == null
                ? TextureAtlasItemLayoutPolicy.participating(textureId)
                : item.policy();
            if (!policy.participate() && !item.currentlyPlaced()) {
                index++;
                continue; // off-page non-participant: neither packed nor an obstacle
            }
            final List<double[][]> rings = new ArrayList<>();
            for (final double[][] ring : item.outline().rings()) {
                final double[][] simplified = OutlineGeometry.simplify(ring, simplifyEps);
                // degenerate (collinear/zero-area) rings cannot seed a hull and
                // would abort the whole pack; drop them to the rect fallback
                if (simplified.length >= 3
                    && Math.abs(OutlineGeometry.signedArea(simplified)) >= 1e-6) {
                    rings.add(simplified);
                }
            }
            double[][] merged;
            if (rings.isEmpty()) {
                merged = rectRing(item.width(), item.height());
                fallbackRect++;
            } else {
                if (rings.size() > 1) {
                    stitched += rings.size() - 1;
                }
                merged = OutlineGeometry.stitch(rings);
            }
            // conservative buffer: half margin each side => >= margin between
            // raw outlines; the bin is inset by the same half margin so raw
            // outlines keep a full margin from the page edge
            final double[][] buffered = OutlineGeometry.dilate(merged,
                constraints.margin() * 0.5);
            final double[] matrix = item.currentMatrix();
            final double[] issuedCosSin = matrix == null ? new double[] {1, 0}
                : normalizeCosSin(item.currentAngleDeg());
            final double inset = binInset(constraints.margin());
            final double[] issuedPosition = matrix == null ? null
                : new double[] {matrix[4] - inset, matrix[5] - inset};
            // a placed non-participant keeps its whole issued transform (host ALL lock)
            final boolean excluded = !policy.participate();
            final boolean fixedPosition = item.currentlyPlaced()
                && (excluded || policy.preservePosition());
            prepared.add(new AutoScalePack.Prepared(index, textureId, merged, buffered,
                fixedPosition, policy.preserveAngle() || fixedPosition,
                policy.preserveScale() || excluded, issuedCosSin, issuedPosition,
                item.currentlyPlaced(), item.currentScale()));
            index++;
        }
        if (stitched > 0) {
            diagnostics.put("stitchedRegions", String.valueOf(stitched));
        }
        if (fallbackRect > 0) {
            diagnostics.put("fallbackRectItems", String.valueOf(fallbackRect));
        }

        final boolean abey = useAbey
            && constraints.rotationMode() == TextureAtlasRotationMode.FREE;
        final double inset = binInset(constraints.margin());
        if (parallel) {
            return planParallel(prepared, constraints, abey, inset, diagnostics, backend);
        }
        final VariantResult vr = runOnce(prepared, constraints, abey, inset);
        return toPlan(vr.result, vr.scale, prepared, constraints, diagnostics,
            backend, inset);
    }

    private VariantResult runOnce(final List<AutoScalePack.Prepared> prepared,
        final TextureAtlasPolygonConstraints constraints, final boolean abey,
        final double inset) {
        final AutoScalePack pack = new AutoScalePack(
            binExtent(constraints.pageWidth(), inset),
            binExtent(constraints.pageHeight(), inset),
            constraints.rotationMode(), abey, H_SKEW, null);
        if (constraints.automaticScale()) {
            final AutoScalePack.Outcome outcome = pack.autoScalePack(prepared,
                AUTO_SCALE_TOLERANCE, AUTO_SCALE_MAX_TRY.get(constraints.quality()),
                cancelled, null);
            return new VariantResult(outcome.result, outcome.scale,
                coverage(outcome.result, prepared), "serial");
        }
        final PolygonPack.Result result = pack.fixedScalePack(prepared,
            constraints.requestedScale(), cancelled, progress);
        return new VariantResult(result, constraints.requestedScale(),
            coverage(result, prepared), "serial");
    }

    /**
     * Deterministic parallel planning: fixed config variants run concurrently and
     * the best result is chosen by a total order (fewest unplaced, then coverage,
     * then variant index), so output does not depend on completion order.
     */
    private TextureAtlasPolygonPlan planParallel(final List<AutoScalePack.Prepared> prepared,
        final TextureAtlasPolygonConstraints constraints, final boolean abey,
        final double inset, final Map<String, String> diagnostics,
        final TextureAtlasLayoutBackend backend) {
        final List<Variant> variants = List.of(
            new Variant(abey, 0.7, "abey-h0.7"),
            new Variant(false, 0.7, "dalalah-h0.7"),
            new Variant(abey, 0.3, "abey-h0.3"),
            new Variant(false, 0.3, "dalalah-h0.3")
        );
        final ExecutorService pool = Executors.newFixedThreadPool(
            Math.min(4, Runtime.getRuntime().availableProcessors()));
        try {
            final List<Future<VariantResult>> futures = new ArrayList<>();
            for (final Variant variant : variants) {
                futures.add(pool.submit(() -> runVariant(prepared, constraints, variant, inset)));
            }
            VariantResult best = null;
            for (int i = 0; i < futures.size(); i++) {
                final VariantResult vr;
                try {
                    vr = futures.get(i).get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("polygon packing interrupted", e);
                } catch (java.util.concurrent.ExecutionException e) {
                    if (e.getCause() instanceof RuntimeException re) {
                        throw re;
                    }
                    throw new IllegalStateException("polygon packing failed", e.getCause());
                }
                if (best == null || compare(vr, best) < 0) {
                    best = vr;
                }
            }
            diagnostics.put("parallelVariant", best.name);
            return toPlan(best.result, best.scale, prepared, constraints, diagnostics,
                backend, inset);
        } finally {
            pool.shutdownNow();
        }
    }

    private record VariantResult(PolygonPack.Result result, double scale,
        double coverage, String name) {
    }

    private VariantResult runVariant(final List<AutoScalePack.Prepared> prepared,
        final TextureAtlasPolygonConstraints constraints, final Variant variant,
        final double inset) {
        final AutoScalePack pack = new AutoScalePack(
            binExtent(constraints.pageWidth(), inset),
            binExtent(constraints.pageHeight(), inset),
            constraints.rotationMode(), variant.abey(), variant.hSkew(), null);
        if (constraints.automaticScale()) {
            final AutoScalePack.Outcome outcome = pack.autoScalePack(prepared,
                AUTO_SCALE_TOLERANCE, AUTO_SCALE_MAX_TRY.get(constraints.quality()),
                cancelled, null);
            return new VariantResult(outcome.result, outcome.scale,
                coverage(outcome.result, prepared), variant.name());
        }
        final PolygonPack.Result result = pack.fixedScalePack(prepared,
            constraints.requestedScale(), cancelled, null);
        return new VariantResult(result, constraints.requestedScale(),
            coverage(result, prepared), variant.name());
    }

    private record Variant(boolean abey, double hSkew, String name) {
    }

    private static double coverage(final PolygonPack.Result result,
        final List<AutoScalePack.Prepared> prepared) {
        double placed = 0;
        final java.util.Set<Integer> placedIds = new java.util.HashSet<>();
        for (final dev.turboism.plugin.atlasdalsoo.dalsoo.PackOutcome o : result.outcomes) {
            placedIds.add(o.id);
        }
        for (final AutoScalePack.Prepared p : prepared) {
            if (placedIds.contains(p.id())) {
                placed += Math.abs(OutlineGeometry.signedArea(p.inpts()));
            }
        }
        return placed;
    }

    private static int compare(final VariantResult a, final VariantResult b) {
        int c = Integer.compare(a.result.unplacedIds.size(), b.result.unplacedIds.size());
        if (c != 0) {
            return c;
        }
        c = -Double.compare(a.coverage, b.coverage); // higher coverage wins
        if (c != 0) {
            return c;
        }
        c = -Double.compare(a.scale, b.scale); // larger scale wins
        if (c != 0) {
            return c;
        }
        return a.name.compareTo(b.name); // deterministic tie-break
    }

    private TextureAtlasPolygonPlan toPlan(final PolygonPack.Result result,
        final double planScale, final List<AutoScalePack.Prepared> prepared,
        final TextureAtlasPolygonConstraints constraints,
        final Map<String, String> diagnostics, final TextureAtlasLayoutBackend backend,
        final double inset) {
        final List<TextureAtlasPolygonPlacement> placements = new ArrayList<>();
        final List<String> overflow = new ArrayList<>();
        final Map<Integer, String> idToTexture = new HashMap<>();
        final Map<Integer, AutoScalePack.Prepared> byId = new HashMap<>();
        for (final AutoScalePack.Prepared p : prepared) {
            idToTexture.put(p.id(), p.textureId());
            byId.put(p.id(), p);
        }
        for (final dev.turboism.plugin.atlasdalsoo.dalsoo.PackOutcome o : result.outcomes) {
            final String textureId = idToTexture.get(o.id);
            final AutoScalePack.Prepared src = byId.get(o.id);
            if (textureId == null || src == null) {
                continue;
            }
            final double scale = src.fixScale() ? src.issuedScale() : planScale;
            placements.add(new TextureAtlasPolygonPlacement(textureId,
                o.trans[0] + inset, o.trans[1] + inset, o.angleDeg(), scale));
        }
        for (final int unplaced : result.unplacedIds) {
            final String textureId = idToTexture.get(unplaced);
            if (textureId != null) {
                overflow.add(textureId);
            }
        }
        diagnostics.put("kernel", "dalsoo");
        diagnostics.put("autoScaleAttempts",
            constraints.automaticScale() ? "bisect" : "fixed");
        return new TextureAtlasPolygonPlan(constraints.pageWidth(),
            constraints.pageHeight(), planScale, List.copyOf(placements),
            List.copyOf(overflow), backend, diagnostics);
    }

    /**
     * Bin coordinate inset: the full margin plus {@link #EDGE_SLACK}. Buffered
     * rings are confined to the inset bin, so raw rings keep at least the
     * configured margin from the page edge (the slack absorbs flattening error
     * in the buffered ring).
     */
    private static double binInset(final int margin) {
        return margin <= 0 ? 0 : margin + EDGE_SLACK;
    }

    /**
     * Usable bin extent for one axis: page extent minus the inset on both
     * sides. Never below 1 so the kernel still has a degenerate bin instead of
     * a negative extent.
     */
    private static double binExtent(final int pageExtent, final double inset) {
        return Math.max(1.0, pageExtent - inset * 2);
    }

    private static double[][] rectRing(final double w, final double h) {
        return new double[][] {{0, 0}, {w, 0}, {w, h}, {0, h}};
    }

    private static double[] normalizeCosSin(final double angleDeg) {
        final double r = Math.toRadians(angleDeg);
        return new double[] {Math.cos(r), Math.sin(r)};
    }
}
