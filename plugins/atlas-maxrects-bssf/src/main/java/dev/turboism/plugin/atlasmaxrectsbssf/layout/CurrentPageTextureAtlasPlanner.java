package dev.turboism.plugin.atlasmaxrectsbssf.layout;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutConstraints;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutItem;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPlacement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.ToLongFunction;

/** Bounded current-page BSSF packing. It never searches or produces a subsequent page. */
public final class CurrentPageTextureAtlasPlanner {
    private static final List<Comparator<TextureAtlasLayoutItem>> ORDERS = List.of(
        descending(i -> (long) i.width() * i.height()),
        descending(TextureAtlasLayoutItem::height),
        descending(i -> Math.min(i.width(), i.height())),
        descending(i -> (long) i.width() + i.height()),
        descending(TextureAtlasLayoutItem::width),
        descending(i -> Math.max(i.width(), i.height()))
    );

    /** Plans this page only; inputs not present in placements are overflow. */
    public TextureAtlasLayoutPlan plan(List<TextureAtlasLayoutItem> items,
        TextureAtlasLayoutConstraints constraints, boolean parallel) {
        Objects.requireNonNull(constraints, "constraints");
        final List<TextureAtlasLayoutItem> inputs = List.copyOf(items);
        final HashSet<String> ids = new HashSet<>();
        for (var item : inputs) {
            if (!ids.add(item.textureId())) throw new IllegalArgumentException("Duplicate texture ID: " + item.textureId());
        }
        final double requestedScale = constraints.singlePageOptions() == null
            ? 1D : constraints.singlePageOptions().requestedScale();
        double scale = requestedScale == 0D ? automaticUpperBound(inputs, constraints) : requestedScale;
        Packed best = packing(inputs, constraints, scale, parallel);
        if (requestedScale == 0D && best.placements.size() != inputs.size()) {
            double low = 0D, high = scale;
            // Bounded legacy-style scale search, with immutable results. Never retain mutable
            // coordinates from a different trial and never shrink a user-specified fixed scale.
            final ArrayList<TextureAtlasLayoutItem> ordered = new ArrayList<>(inputs);
            ordered.sort(ORDERS.get(0));
            for (int iteration = 0; iteration < 8; iteration++) {
                final double trialScale = (low + high) / 2D;
                // Once a complete solution exists, partial trials cannot improve it. Test
                // Serial trials use area order only; parallel trials retain regional/fallback orders.
                // This bounded heuristic is not a proof of the maximum feasible scale.
                // Stop doomed passes immediately when only a complete result could improve best.
                final boolean allRequired = best.placements.size() == inputs.size();
                final Packed trial = parallel ? packing(inputs, constraints, trialScale, true, allRequired)
                    : pack(ordered, constraints, trialScale, allRequired);
                if (trial.placements.size() > best.placements.size()
                    || (trial.placements.size() == best.placements.size() && trialScale > scale)) {
                    best = trial;
                    scale = trialScale;
                }
                if (trial.placements.size() == inputs.size()) low = trialScale;
                else high = trialScale;
            }
            if (!parallel && best.placements.size() != inputs.size()) {
                final Packed refined = packing(inputs, constraints, scale, false);
                if (refined.betterThan(best)) best = refined;
            }
        }
        return toPlan(best, constraints, scale);
    }

    private static double automaticUpperBound(List<TextureAtlasLayoutItem> items, TextureAtlasLayoutConstraints c) {
        final double width = c.pageWidth() - 2D * c.edgeMargin();
        final double height = c.pageHeight() - 2D * c.edgeMargin();
        double bound = 1D, area = 0D;
        for (var item : items) {
            area += (double) item.width() * item.height();
            double edgeFit = Math.min(width / item.width(), height / item.height());
            if (c.allowRotation()) edgeFit = Math.max(edgeFit, Math.min(width / item.height(), height / item.width()));
            bound = Math.min(bound, edgeFit);
        }
        return area == 0 ? 1D : Math.min(bound, Math.sqrt(width * height / area));
    }

    private static TextureAtlasLayoutPlan toPlan(Packed packed, TextureAtlasLayoutConstraints c, double scale) {
        final List<TextureAtlasPlacement> placements = packed.placements.stream()
            .sorted(Comparator.comparing(p -> p.item.textureId()))
            .map(p -> new TextureAtlasPlacement(p.item.textureId(), 0, p.x, p.y, p.width, p.height, p.rotated))
            .toList();
        return TextureAtlasLayoutPlan.currentPage(c.pageWidth(), c.pageHeight(), placements, scale);
    }

    private static Packed packing(List<TextureAtlasLayoutItem> items, TextureAtlasLayoutConstraints c,
        double scale, boolean parallel) {
        return packing(items, c, scale, parallel, false);
    }

    private static Packed packing(List<TextureAtlasLayoutItem> items, TextureAtlasLayoutConstraints c,
        double scale, boolean parallel, boolean allRequired) {
        // Small parallel requests first try serial packing to avoid unnecessary region scheduling.
        // 32 is a preflight limit, NOT a partition cutoff: a partial serial result must still
        // compete with the legacy-compatible regional candidate (partition admission stays 16).
        // At a fixed scale, full-input candidates tie on our count/content-area score.
        final Packed preflight = parallel && items.size() < 32
            ? bestPacking(items, c, scale, allRequired) : null;
        if (preflight != null && preflight.placements.size() == items.size()) return preflight;
        if (parallel) {
            final var regions = CurrentPageRegions.partition(items, c, scale);
            final var groups = regions.isEmpty() ? List.<List<TextureAtlasLayoutItem>>of()
                : CurrentPageRegions.assign(items, regions, c, scale);
            if (!groups.isEmpty()) {
                final List<Packed> results = java.util.stream.IntStream.range(0, regions.size()).parallel()
                    .mapToObj(index -> {
                        final var r = regions.get(index);
                        final var local = new TextureAtlasLayoutConstraints(r.width(), r.height(), c.edgeMargin(),
                            c.itemPadding(), 1, c.allowRotation(), c.allowScaling(), c.singlePageOptions());
                        final Packed packed = bestPacking(groups.get(index), local, scale, allRequired);
                        return new Packed(packed.placements.stream().map(p -> new Placed(p.item,
                            p.x + r.x(), p.y + r.y(), p.width, p.height, p.rotated)).toList(), packed.area);
                    }).toList();
                final Packed regional = new Packed(results.stream().flatMap(p -> p.placements.stream()).toList(),
                    results.stream().mapToLong(p -> p.area).sum());
                if (regional.placements.size() == items.size()) return regional;
                // A partition is an optimization, not permission to drop otherwise placeable inputs.
                final Packed serial = preflight != null ? preflight : bestPacking(items, c, scale, allRequired);
                return regional.betterThan(serial) ? regional : serial;
            }
        }
        return preflight != null ? preflight : bestPacking(items, c, scale, allRequired);
    }

    private static Packed bestPacking(List<TextureAtlasLayoutItem> items, TextureAtlasLayoutConstraints c,
        double scale, boolean allRequired) {
        Packed best = null;
        for (var comparator : ORDERS) {
            final ArrayList<TextureAtlasLayoutItem> ordered = new ArrayList<>(items);
            ordered.sort(comparator);
            final Packed candidate = pack(ordered, c, scale, allRequired);
            if (best == null || candidate.betterThan(best)) best = candidate;
            // At this scale all-input candidates have identical count and content area.
            // Later orders cannot improve our score; avoid five redundant BSSF passes.
            if (best.placements.size() == items.size()) break;
        }
        return best;
    }

    private static Packed pack(List<TextureAtlasLayoutItem> ordered, TextureAtlasLayoutConstraints c,
        double scale, boolean allRequired) {
        if (allRequired && !reservedAreaFits(ordered, c, scale)) return new Packed(List.of(), 0);
        final int padding = c.itemPadding();
        final ArrayList<Rect> free = new ArrayList<>();
        // A trailing reserved gap is not needed at the page edge. The extended free rectangle
        // accounts for that while every emitted content rectangle remains inside edgeMargin.
        free.add(new Rect(c.edgeMargin(), c.edgeMargin(),
            (long) c.pageWidth() - 2L * c.edgeMargin() + padding,
            (long) c.pageHeight() - 2L * c.edgeMargin() + padding));
        final ArrayList<Placed> placed = new ArrayList<>();
        long area = 0;
        for (var item : ordered) {
            final double scaledWidth = Math.ceil(item.width() * scale);
            final double scaledHeight = Math.ceil(item.height() * scale);
            if (scaledWidth < 1 || scaledHeight < 1 || scaledWidth > Integer.MAX_VALUE
                || scaledHeight > Integer.MAX_VALUE) {
                if (allRequired) return new Packed(List.of(), 0);
                continue;
            }
            final int width = (int) scaledWidth, height = (int) scaledHeight;
            Candidate best = null;
            for (Rect rect : free) {
                best = choose(best, candidate(rect, (long) width + padding, (long) height + padding, false));
                if (c.allowRotation() && width != height) {
                    best = choose(best, candidate(rect, (long) height + padding, (long) width + padding, true));
                }
            }
            if (best == null) {
                if (allRequired) return new Packed(List.of(), 0);
                continue;
            }
            final int placedWidth = best.rotated ? height : width;
            final int placedHeight = best.rotated ? width : height;
            placed.add(new Placed(item, Math.toIntExact(best.x), Math.toIntExact(best.y), placedWidth, placedHeight, best.rotated));
            area += (long) placedWidth * placedHeight;
            splitAndPrune(free, new Rect(best.x, best.y, (long) placedWidth + padding, (long) placedHeight + padding));
        }
        return new Packed(placed, area);
    }

    private static boolean reservedAreaFits(List<TextureAtlasLayoutItem> items,
        TextureAtlasLayoutConstraints c, double scale) {
        final int padding = c.itemPadding();
        final long width = (long) c.pageWidth() - 2L * c.edgeMargin() + padding;
        final long height = (long) c.pageHeight() - 2L * c.edgeMargin() + padding;
        // This is only an optimization. Skip its area arithmetic for expanded domains
        // whose product could overflow long; coordinate-based packing remains exact.
        if (width > Integer.MAX_VALUE || height > Integer.MAX_VALUE) return true;
        long remaining = width * height;
        for (var item : items) {
            final double w = Math.ceil(item.width() * scale) + padding;
            final double h = Math.ceil(item.height() * scale) + padding;
            if (w > Integer.MAX_VALUE || h > Integer.MAX_VALUE) return false;
            final long area = (long) w * (long) h;
            if (area > remaining) return false;
            remaining -= area;
        }
        return true;
    }

    private static Candidate candidate(Rect rect, long width, long height, boolean rotated) {
        if (width > rect.width || height > rect.height) return null;
        return new Candidate(rect.x, rect.y, rotated,
            Math.min(rect.width - width, rect.height - height), Math.max(rect.width - width, rect.height - height));
    }

    private static Candidate choose(Candidate a, Candidate b) {
        if (a == null) return b;
        if (b == null) return a;
        return Candidate.ORDER.compare(a, b) <= 0 ? a : b;
    }

    private static void splitAndPrune(ArrayList<Rect> free, Rect used) {
        int unchanged = free.size();
        for (int i = free.size() - 1; i >= 0; i--) {
            final Rect r = free.get(i);
            if (!r.intersects(used)) continue;
            free.remove(i);
            unchanged--;
            if (used.x > r.x) free.add(new Rect(r.x, r.y, used.x - r.x, r.height));
            if (used.right() < r.right()) free.add(new Rect(used.right(), r.y, r.right() - used.right(), r.height));
            if (used.y > r.y) free.add(new Rect(r.x, r.y, r.width, used.y - r.y));
            if (used.bottom() < r.bottom()) free.add(new Rect(r.x, used.bottom(), r.width, r.bottom() - used.bottom()));
        }
        // The unchanged prefix was already containment-pruned by the preceding insertion.
        // Only pairs involving a new split can introduce containment; preserve the same
        // ordering and tie removal as the full quadratic pass without rechecking old pairs.
        for (int i = 0; i < free.size(); i++) {
            for (int j = Math.max(i + 1, unchanged); j < free.size(); j++) {
                if (free.get(i).contains(free.get(j))) free.remove(j--);
                else if (free.get(j).contains(free.get(i))) {
                    free.remove(i);
                    if (i < unchanged) unchanged--;
                    i--;
                    break;
                }
            }
        }
    }

    private static Comparator<TextureAtlasLayoutItem> descending(ToLongFunction<TextureAtlasLayoutItem> metric) {
        return Comparator.comparingLong(metric).reversed().thenComparing(TextureAtlasLayoutItem::textureId);
    }

    private record Rect(long x, long y, long width, long height) {
        long right() { return (long) x + width; }
        long bottom() { return (long) y + height; }
        boolean intersects(Rect other) { return x < other.right() && right() > other.x && y < other.bottom() && bottom() > other.y; }
        boolean contains(Rect other) { return x <= other.x && y <= other.y && right() >= other.right() && bottom() >= other.bottom(); }
    }
    private record Candidate(long x, long y, boolean rotated, long shortWaste, long longWaste) {
        static final Comparator<Candidate> ORDER = Comparator.comparingLong(Candidate::shortWaste)
            .thenComparingLong(Candidate::longWaste).thenComparingLong(Candidate::y)
            .thenComparingLong(Candidate::x).thenComparing(Candidate::rotated);
    }
    private record Placed(TextureAtlasLayoutItem item, int x, int y, int width, int height, boolean rotated) { }
    private record Packed(List<Placed> placements, long area) {
        boolean betterThan(Packed other) {
            return placements.size() > other.placements.size()
                || (placements.size() == other.placements.size() && area > other.area);
        }
    }
}
