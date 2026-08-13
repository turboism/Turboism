package dev.turboism.plugin.atlasmaxrectsbssf.layout;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutConstraints;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutItem;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPlacement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Legacy Part-size policy over the shared deterministic MaxRects planner. */
public final class PartBucketTextureAtlasPlanner {

    private static final Comparator<TextureAtlasLayoutItem> DISTRIBUTION_ORDER = Comparator
        .comparingLong((TextureAtlasLayoutItem item) -> (long) item.width() * item.height())
        .reversed()
        .thenComparing(TextureAtlasLayoutItem::textureId);
    private static final double[] TARGET_FILL = {0.72, 0.80, 0.88};

    private final MaxRectsBssfTextureAtlasPlanner planner = new MaxRectsBssfTextureAtlasPlanner();

    public TextureAtlasLayoutPlan plan(
        final List<TextureAtlasLayoutItem> items,
        final TextureAtlasLayoutConstraints constraints
    ) {
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(constraints, "constraints");
        final List<List<TextureAtlasLayoutItem>> buckets = List.of(
            new ArrayList<>(), new ArrayList<>(), new ArrayList<>()
        );
        final double pageArea = (double) constraints.pageWidth() * constraints.pageHeight();
        for (TextureAtlasLayoutItem item : List.copyOf(items)) {
            Objects.requireNonNull(item, "item");
            final double edgeRatio = Math.max(
                (double) item.width() / constraints.pageWidth(),
                (double) item.height() / constraints.pageHeight()
            );
            final double areaRatio = ((double) item.width() * item.height()) / pageArea;
            buckets.get(edgeRatio >= 0.52 || areaRatio >= 0.18 ? 0
                : edgeRatio >= 0.26 || areaRatio >= 0.045 ? 1 : 2).add(item);
        }

        final List<TextureAtlasPlacement> placements = new ArrayList<>();
        int pageOffset = 0;
        for (int bucketIndex = 0; bucketIndex < buckets.size(); bucketIndex++) {
            final List<TextureAtlasLayoutItem> bucket = buckets.get(bucketIndex);
            if (bucket.isEmpty()) continue;
            final int plannedPages;
            final List<List<TextureAtlasLayoutItem>> assigned;
            try {
                plannedPages = estimatedPages(bucket, constraints, TARGET_FILL[bucketIndex]);
                if (pageOffset + plannedPages > constraints.maxPages()) {
                    throw packingFailure(bucket);
                }
                assigned = distribute(bucket, plannedPages, constraints.itemPadding());
            } catch (ArithmeticException failure) {
                throw reservedSizeFailure(bucket);
            }
            for (int groupIndex = 0; groupIndex < assigned.size(); groupIndex++) {
                final List<TextureAtlasLayoutItem> pageItems = assigned.get(groupIndex);
                if (pageItems.isEmpty()) continue;
                final int laterGroups = assigned.size() - groupIndex - 1;
                final int laterBuckets = nonEmptyBucketCount(buckets, bucketIndex + 1);
                final int availablePages = constraints.maxPages() - pageOffset - laterGroups - laterBuckets;
                if (availablePages < 1) throw packingFailure(bucket);
                final TextureAtlasLayoutPlan groupPlan = planner.plan(
                    pageItems,
                    new TextureAtlasLayoutConstraints(
                        constraints.pageWidth(), constraints.pageHeight(),
                        constraints.edgeMargin(), constraints.itemPadding(), availablePages,
                        constraints.allowRotation(), constraints.allowScaling()
                    )
                );
                for (TextureAtlasPlacement placement : groupPlan.placements()) {
                    placements.add(new TextureAtlasPlacement(
                        placement.textureId(), pageOffset + placement.pageIndex(),
                        placement.x(), placement.y(), placement.width(), placement.height(), placement.rotated()
                    ));
                }
                pageOffset += groupPlan.pageCount();
            }
        }
        placements.sort(Comparator.comparing(TextureAtlasPlacement::textureId));
        return new TextureAtlasLayoutPlan(
            constraints.pageWidth(), constraints.pageHeight(), Math.max(1, pageOffset), placements
        );
    }

    private static int nonEmptyBucketCount(
        final List<List<TextureAtlasLayoutItem>> buckets,
        final int start
    ) {
        int count = 0;
        for (int index = start; index < buckets.size(); index++) {
            if (!buckets.get(index).isEmpty()) count++;
        }
        return count;
    }

    private static int estimatedPages(
        final List<TextureAtlasLayoutItem> items,
        final TextureAtlasLayoutConstraints constraints,
        final double targetFill
    ) {
        long totalArea = 0;
        for (TextureAtlasLayoutItem item : items) {
            totalArea = Math.addExact(totalArea, paddedArea(item, constraints.itemPadding()));
        }
        final double capacity = Math.max(
            1.0,
            (double) constraints.pageWidth() * constraints.pageHeight() * targetFill
        );
        return Math.min(items.size(), Math.max(1, (int) Math.ceil(totalArea / capacity)));
    }

    private static List<List<TextureAtlasLayoutItem>> distribute(
        final List<TextureAtlasLayoutItem> items,
        final int pageCount,
        final int padding
    ) {
        final List<TextureAtlasLayoutItem> ordered = new ArrayList<>(items);
        ordered.sort(DISTRIBUTION_ORDER);
        final List<List<TextureAtlasLayoutItem>> pages = new ArrayList<>(pageCount);
        final long[] areas = new long[pageCount];
        for (int index = 0; index < pageCount; index++) pages.add(new ArrayList<>());
        for (TextureAtlasLayoutItem item : ordered) {
            int target = 0;
            for (int index = 1; index < pageCount; index++) {
                if (areas[index] < areas[target]) target = index;
            }
            pages.get(target).add(item);
            areas[target] = Math.addExact(areas[target], paddedArea(item, padding));
        }
        return pages;
    }

    private static long paddedArea(final TextureAtlasLayoutItem item, final int padding) {
        final long width = Math.addExact((long) item.width(), Math.multiplyExact(2L, padding));
        final long height = Math.addExact((long) item.height(), Math.multiplyExact(2L, padding));
        return Math.multiplyExact(width, height);
    }

    private static TextureAtlasPackingException packingFailure(final List<TextureAtlasLayoutItem> items) {
        return new TextureAtlasPackingException(
            items.stream().map(TextureAtlasLayoutItem::textureId).sorted().findFirst().orElse("<empty>"),
            TextureAtlasPackingException.Reason.PAGE_BUDGET_EXHAUSTED
        );
    }


    private static TextureAtlasPackingException reservedSizeFailure(
        final List<TextureAtlasLayoutItem> items
    ) {
        return new TextureAtlasPackingException(
            items.stream().map(TextureAtlasLayoutItem::textureId).sorted().findFirst().orElse("<empty>"),
            TextureAtlasPackingException.Reason.INVALID_RESERVED_SIZE
        );
    }
}
