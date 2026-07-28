package dev.turboism.plugin.textureatlas.layout;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutConstraints;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutItem;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPlacement;

import java.math.BigInteger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Deterministic single-page MaxRects planner using Best Short Side Fit. */
public final class MaxRectsBssfTextureAtlasPlanner {

    private static final Comparator<TextureAtlasLayoutItem> ID_ORDER =
        Comparator.comparing(TextureAtlasLayoutItem::textureId);
    private static final List<Comparator<TextureAtlasLayoutItem>> CANDIDATE_ORDERS = List.of(
        descending(item -> (long) item.width() * item.height()),
        descending(item -> 2L * ((long) item.width() + item.height())),
        descending(TextureAtlasLayoutItem::width),
        descending(TextureAtlasLayoutItem::height),
        descending(item -> Math.max(item.width(), item.height()))
    );
    private static final Comparator<TextureAtlasPlacement> PLACEMENT_ORDER = Comparator
        .comparingInt(TextureAtlasPlacement::pageIndex)
        .thenComparingInt(TextureAtlasPlacement::y)
        .thenComparingInt(TextureAtlasPlacement::x)
        .thenComparing(TextureAtlasPlacement::textureId);

    public TextureAtlasLayoutPlan plan(
        final List<TextureAtlasLayoutItem> items,
        final TextureAtlasLayoutConstraints constraints
    ) {
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(constraints, "constraints");
        if (constraints.maxPages() != 1) {
            throw new IllegalArgumentException("This Preview tracer supports exactly one atlas page.");
        }

        final List<TextureAtlasLayoutItem> inputs = new ArrayList<>(List.copyOf(items));
        rejectDuplicateIds(inputs);
        final List<PreparedItem> prepared = prepare(inputs, constraints.itemPadding());
        if (prepared.isEmpty()) {
            return new TextureAtlasLayoutPlan(
                constraints.pageWidth(),
                constraints.pageHeight(),
                1,
                List.of()
            );
        }

        CandidatePlan best = null;
        for (int orderIndex = 0; orderIndex < CANDIDATE_ORDERS.size(); orderIndex++) {
            final Comparator<TextureAtlasLayoutItem> order = CANDIDATE_ORDERS.get(orderIndex)
                .thenComparing(ID_ORDER);
            final List<PreparedItem> ordered = new ArrayList<>(prepared);
            ordered.sort(Comparator.comparing(PreparedItem::item, order));
            final CandidatePlan candidate = tryCandidate(ordered, constraints, orderIndex);
            if (candidate != null && (best == null || CandidatePlan.ORDER.compare(candidate, best) < 0)) {
                best = candidate;
            }
        }

        if (best == null) {
            throw new TextureAtlasPackingException(
                inputs.stream().map(TextureAtlasLayoutItem::textureId).sorted().findFirst().orElse("<empty>"),
                TextureAtlasPackingException.Reason.NO_CANDIDATE_PLAN
            );
        }
        return best.plan();
    }

    private static List<PreparedItem> prepare(
        final List<TextureAtlasLayoutItem> items,
        final int padding
    ) {
        final List<PreparedItem> prepared = new ArrayList<>();
        for (TextureAtlasLayoutItem item : items) {
            final long reservedWidth = (long) item.width() + padding;
            final long reservedHeight = (long) item.height() + padding;
            if (reservedWidth > Integer.MAX_VALUE || reservedHeight > Integer.MAX_VALUE) {
                throw new TextureAtlasPackingException(
                    item.textureId(),
                    TextureAtlasPackingException.Reason.INVALID_RESERVED_SIZE
                );
            }
            prepared.add(new PreparedItem(item, (int) reservedWidth, (int) reservedHeight));
        }
        return List.copyOf(prepared);
    }

    private static CandidatePlan tryCandidate(
        final List<PreparedItem> ordered,
        final TextureAtlasLayoutConstraints constraints,
        final int orderIndex
    ) {
        final int usableX = constraints.edgeMargin();
        final int usableY = constraints.edgeMargin();
        final int usableWidth = constraints.pageWidth() - 2 * constraints.edgeMargin();
        final int usableHeight = constraints.pageHeight() - 2 * constraints.edgeMargin();
        final List<Rect> freeRects = new ArrayList<>();
        freeRects.add(new Rect(usableX, usableY, usableWidth, usableHeight));
        final List<TextureAtlasPlacement> placements = new ArrayList<>();

        for (PreparedItem prepared : ordered) {
            final Candidate candidate = bestCandidate(
                freeRects,
                prepared.reservedWidth(),
                prepared.reservedHeight()
            );
            if (candidate == null) {
                return null;
            }
            final Rect used = new Rect(
                candidate.x(),
                candidate.y(),
                prepared.reservedWidth(),
                prepared.reservedHeight()
            );
            splitFreeRects(freeRects, used);
            pruneContained(freeRects);
            final TextureAtlasLayoutItem item = prepared.item();
            placements.add(new TextureAtlasPlacement(
                item.textureId(),
                0,
                candidate.x(),
                candidate.y(),
                item.width(),
                item.height(),
                false
            ));
        }

        placements.sort(Comparator.comparing(TextureAtlasPlacement::textureId));
        final TextureAtlasLayoutPlan plan = new TextureAtlasLayoutPlan(
            constraints.pageWidth(),
            constraints.pageHeight(),
            1,
            placements
        );
        return new CandidatePlan(plan, freeArea(freeRects), orderIndex);
    }

    private static BigInteger freeArea(final List<Rect> freeRects) {
        BigInteger area = BigInteger.ZERO;
        for (Rect freeRect : freeRects) {
            area = area.add(BigInteger.valueOf(freeRect.width())
                .multiply(BigInteger.valueOf(freeRect.height())));
        }
        return area;
    }

    private static void rejectDuplicateIds(final List<TextureAtlasLayoutItem> items) {
        final Set<String> ids = new HashSet<>();
        for (TextureAtlasLayoutItem item : items) {
            Objects.requireNonNull(item, "item");
            if (!ids.add(item.textureId())) {
                throw new IllegalArgumentException("Duplicate texture ID: " + item.textureId());
            }
        }
    }

    private static Candidate bestCandidate(
        final List<Rect> freeRects,
        final int width,
        final int height
    ) {
        Candidate best = null;
        for (Rect free : freeRects) {
            if (width > free.width() || height > free.height()) {
                continue;
            }
            final int widthWaste = free.width() - width;
            final int heightWaste = free.height() - height;
            final Candidate candidate = new Candidate(
                free.x(),
                free.y(),
                Math.min(widthWaste, heightWaste),
                Math.max(widthWaste, heightWaste)
            );
            if (best == null || Candidate.ORDER.compare(candidate, best) < 0) {
                best = candidate;
            }
        }
        return best;
    }

    private static void splitFreeRects(final List<Rect> freeRects, final Rect used) {
        final List<Rect> replacements = new ArrayList<>();
        for (Rect free : freeRects) {
            if (!free.intersects(used)) {
                replacements.add(free);
                continue;
            }
            if (used.x() > free.x()) {
                replacements.add(new Rect(free.x(), free.y(), used.x() - free.x(), free.height()));
            }
            if (used.right() < free.right()) {
                replacements.add(new Rect(used.right(), free.y(), free.right() - used.right(), free.height()));
            }
            if (used.y() > free.y()) {
                replacements.add(new Rect(free.x(), free.y(), free.width(), used.y() - free.y()));
            }
            if (used.bottom() < free.bottom()) {
                replacements.add(new Rect(free.x(), used.bottom(), free.width(), free.bottom() - used.bottom()));
            }
        }
        freeRects.clear();
        replacements.stream()
            .filter(Rect::hasArea)
            .forEach(freeRects::add);
    }

    private static void pruneContained(final List<Rect> freeRects) {
        for (int left = 0; left < freeRects.size(); left++) {
            for (int right = left + 1; right < freeRects.size(); right++) {
                final Rect first = freeRects.get(left);
                final Rect second = freeRects.get(right);
                if (first.contains(second)) {
                    freeRects.remove(right--);
                } else if (second.contains(first)) {
                    freeRects.remove(left--);
                    break;
                }
            }
        }
    }

    private static Comparator<TextureAtlasLayoutItem> descending(
        final java.util.function.ToLongFunction<TextureAtlasLayoutItem> metric
    ) {
        return Comparator.comparingLong(metric).reversed();
    }

    private record PreparedItem(
        TextureAtlasLayoutItem item,
        int reservedWidth,
        int reservedHeight
    ) {
    }

    private record Candidate(int x, int y, int shortSideWaste, int longSideWaste) {
        private static final Comparator<Candidate> ORDER = Comparator
            .comparingInt(Candidate::shortSideWaste)
            .thenComparingInt(Candidate::longSideWaste)
            .thenComparingInt(Candidate::y)
            .thenComparingInt(Candidate::x);
    }

    private record CandidatePlan(TextureAtlasLayoutPlan plan, BigInteger freeArea, int orderIndex) {
        private static final Comparator<CandidatePlan> ORDER = Comparator
            .comparing(CandidatePlan::freeArea)
            .thenComparing(CandidatePlan::plan, CandidatePlan::comparePlans)
            .thenComparingInt(CandidatePlan::orderIndex);

        private static int comparePlans(
            final TextureAtlasLayoutPlan left,
            final TextureAtlasLayoutPlan right
        ) {
            final List<TextureAtlasPlacement> leftPlacements = new ArrayList<>(left.placements());
            final List<TextureAtlasPlacement> rightPlacements = new ArrayList<>(right.placements());
            leftPlacements.sort(PLACEMENT_ORDER);
            rightPlacements.sort(PLACEMENT_ORDER);
            for (int index = 0; index < leftPlacements.size(); index++) {
                final int compared = PLACEMENT_ORDER.compare(
                    leftPlacements.get(index),
                    rightPlacements.get(index)
                );
                if (compared != 0) {
                    return compared;
                }
            }
            return 0;
        }
    }

    private record Rect(int x, int y, int width, int height) {
        private int right() {
            return x + width;
        }

        private int bottom() {
            return y + height;
        }

        private boolean hasArea() {
            return width > 0 && height > 0;
        }

        private boolean intersects(final Rect other) {
            return x < other.right()
                && right() > other.x()
                && y < other.bottom()
                && bottom() > other.y();
        }

        private boolean contains(final Rect other) {
            return other.x() >= x
                && other.y() >= y
                && other.right() <= right()
                && other.bottom() <= bottom();
        }
    }
}
