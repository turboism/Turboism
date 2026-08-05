package dev.turboism.plugin.atlasmaxrectsbssf.layout;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutConstraints;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutItem;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPlacement;

import java.math.BigInteger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        return plan(items, constraints, false);
    }

    public TextureAtlasLayoutPlan plan(
        final List<TextureAtlasLayoutItem> items,
        final TextureAtlasLayoutConstraints constraints,
        final boolean parallel
    ) {
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(constraints, "constraints");

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

        inputs.sort(ID_ORDER);
        for (TextureAtlasLayoutItem input : inputs) {
            if (pageCandidates(List.of(input), constraints, 0, false).isEmpty()) {
                throw new TextureAtlasPackingException(
                    input.textureId(),
                    TextureAtlasPackingException.Reason.ITEM_DOES_NOT_FIT
                );
            }
        }
        final SearchResult result = search(
            inputs,
            constraints,
            0,
            new HashMap<>(),
            parallel
        );
        if (result == null) {
            throw new TextureAtlasPackingException(
                inputs.get(0).textureId(),
                TextureAtlasPackingException.Reason.PAGE_BUDGET_EXHAUSTED
            );
        }
        final List<TextureAtlasPlacement> placements = new ArrayList<>(result.placements());
        placements.sort(Comparator.comparing(TextureAtlasPlacement::textureId));
        return new TextureAtlasLayoutPlan(
            constraints.pageWidth(), constraints.pageHeight(), result.pageCount(), placements
        );
    }

    private static SearchResult search(
        final List<TextureAtlasLayoutItem> remaining,
        final TextureAtlasLayoutConstraints constraints,
        final int pageIndex,
        final Map<SearchKey, SearchResult> memo,
        final boolean parallel
    ) {
        if (remaining.isEmpty()) return new SearchResult(pageIndex, List.of());
        final int pagesLeft = constraints.maxPages() - pageIndex;
        if (pagesLeft < 1) return null;
        final SearchKey key = new SearchKey(
            remaining.stream().map(TextureAtlasLayoutItem::textureId).toList(),
            pagesLeft
        );
        if (memo.containsKey(key)) return memo.get(key);

        SearchResult best = null;
        for (CandidatePlan page : pageCandidates(remaining, constraints, pageIndex, parallel)) {
            final Set<String> placedIds = page.plan().placements().stream()
                .map(TextureAtlasPlacement::textureId)
                .collect(java.util.stream.Collectors.toSet());
            final List<TextureAtlasLayoutItem> next = remaining.stream()
                .filter(item -> !placedIds.contains(item.textureId()))
                .toList();
            final SearchResult tail = search(next, constraints, pageIndex + 1, memo, parallel);
            if (tail == null) continue;
            final List<TextureAtlasPlacement> combined = new ArrayList<>(page.plan().placements());
            combined.addAll(tail.placements());
            final SearchResult candidate = new SearchResult(tail.pageCount(), List.copyOf(combined));
            if (best == null || SearchResult.ORDER.compare(candidate, best) < 0) best = candidate;
        }
        memo.put(key, best);
        return best;
    }

    private static List<CandidatePlan> pageCandidates(
        final List<TextureAtlasLayoutItem> items,
        final TextureAtlasLayoutConstraints constraints,
        final int pageIndex,
        final boolean parallel
    ) {
        final List<PreparedItem> prepared = prepare(items, constraints.itemPadding());
        final java.util.stream.IntStream orders = java.util.stream.IntStream.range(0, CANDIDATE_ORDERS.size());
        final List<CandidatePlan> computed = (parallel ? orders.parallel() : orders)
            .mapToObj(orderIndex -> candidateForOrder(prepared, constraints, orderIndex, pageIndex))
            .filter(candidate -> candidate != null && !candidate.plan().placements().isEmpty())
            .toList();
        final Map<List<String>, CandidatePlan> unique = new LinkedHashMap<>();
        for (CandidatePlan candidate : computed) {
            final List<String> ids = candidate.plan().placements().stream()
                .map(TextureAtlasPlacement::textureId).sorted().toList();
            unique.merge(ids, candidate, (left, right) ->
                CandidatePlan.ORDER.compare(left, right) <= 0 ? left : right);
        }
        final List<CandidatePlan> candidates = new ArrayList<>(unique.values());
        candidates.sort(CandidatePlan.ORDER);
        return candidates;
    }

    private static CandidatePlan candidateForOrder(
        final List<PreparedItem> prepared,
        final TextureAtlasLayoutConstraints constraints,
        final int orderIndex,
        final int pageIndex
    ) {
        final Comparator<TextureAtlasLayoutItem> order = CANDIDATE_ORDERS.get(orderIndex)
            .thenComparing(ID_ORDER);
        final List<PreparedItem> ordered = new ArrayList<>(prepared);
        ordered.sort(Comparator.comparing(PreparedItem::item, order));
        return tryCandidate(ordered, constraints, orderIndex, pageIndex);
    }

    private static CandidatePlan bestPage(
        final List<TextureAtlasLayoutItem> items,
        final TextureAtlasLayoutConstraints constraints,
        final int pageIndex
    ) {
        final List<PreparedItem> prepared = prepare(items, constraints.itemPadding());
        CandidatePlan best = null;
        for (int orderIndex = 0; orderIndex < CANDIDATE_ORDERS.size(); orderIndex++) {
            final Comparator<TextureAtlasLayoutItem> order = CANDIDATE_ORDERS.get(orderIndex)
                .thenComparing(ID_ORDER);
            final List<PreparedItem> ordered = new ArrayList<>(prepared);
            ordered.sort(Comparator.comparing(PreparedItem::item, order));
            final CandidatePlan candidate = tryCandidate(ordered, constraints, orderIndex, pageIndex);
            if (candidate != null && (best == null || CandidatePlan.ORDER.compare(candidate, best) < 0)) {
                best = candidate;
            }
        }
        return best;
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
        final int orderIndex,
        final int pageIndex
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
                continue;
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
                pageIndex,
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
            pageIndex + 1,
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
            .comparingInt((CandidatePlan candidate) -> candidate.plan().placements().size()).reversed()
            .thenComparing(CandidatePlan::freeArea)
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

    private record SearchKey(List<String> textureIds, int pagesLeft) {
    }

    private record SearchResult(int pageCount, List<TextureAtlasPlacement> placements) {
        private static final Comparator<SearchResult> ORDER = Comparator
            .comparingInt(SearchResult::pageCount)
            .thenComparing(SearchResult::placements, SearchResult::comparePlacements);

        private static int comparePlacements(
            final List<TextureAtlasPlacement> left,
            final List<TextureAtlasPlacement> right
        ) {
            final List<TextureAtlasPlacement> sortedLeft = new ArrayList<>(left);
            final List<TextureAtlasPlacement> sortedRight = new ArrayList<>(right);
            sortedLeft.sort(PLACEMENT_ORDER);
            sortedRight.sort(PLACEMENT_ORDER);
            for (int index = 0; index < Math.min(sortedLeft.size(), sortedRight.size()); index++) {
                final int compared = PLACEMENT_ORDER.compare(sortedLeft.get(index), sortedRight.get(index));
                if (compared != 0) return compared;
            }
            return Integer.compare(sortedLeft.size(), sortedRight.size());
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
