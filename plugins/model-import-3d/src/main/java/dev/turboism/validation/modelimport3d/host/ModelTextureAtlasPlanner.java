package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutItem;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPlacement;
import java.util.*;
import java.util.function.IntFunction;

/**
 * Deterministic texture atlas sizing and packing planner.
 *
 * <p>Preserves source texture resolution at 1:1 scale, deduplicates shared textures,
 * plans deterministic power-of-two page sizes by evaluating total memory footprint
 * and page count, splits into multiple pages when beneficial or necessary, and creates
 * new atlases without being constrained by existing page dimensions.</p>
 */
final class ModelTextureAtlasPlanner {

    private ModelTextureAtlasPlanner() {}

    record Options(int maxAtlasEdge, int maxPages, long maxTotalPixels, int edgeMargin, int itemPadding) {
        public static final Options DEFAULT = new Options(4096, 32, 134_217_728L, 2, 2);

        public Options {
            if (maxAtlasEdge < 256 || (maxAtlasEdge & (maxAtlasEdge - 1)) != 0) {
                throw new IllegalArgumentException("maxAtlasEdge must be a power of two >= 256");
            }
            if (maxPages < 1) throw new IllegalArgumentException("maxPages must be positive");
            if (maxTotalPixels < 1) throw new IllegalArgumentException("maxTotalPixels must be positive");
            if (edgeMargin < 0 || itemPadding < 0) {
                throw new IllegalArgumentException("margin and padding must be non-negative");
            }
        }
    }

    record PlannedAtlas(
            int pageWidth,
            int pageHeight,
            int pageCount,
            List<String> pageNames,
            List<TextureAtlasPlacement> placements,
            double scale) {
        PlannedAtlas {
            pageNames = List.copyOf(pageNames);
            placements = List.copyOf(placements);
        }
    }

    private record Candidate(int pageSize, int pageCount, List<TextureAtlasPlacement> placements, long totalPixels) {}

    static PlannedAtlas plan(List<TextureAtlasLayoutItem> items, Options options) {
        return plan(items, options, i -> "3D Model Atlas " + (i + 1));
    }

    static PlannedAtlas plan(
            List<TextureAtlasLayoutItem> items, Options options, IntFunction<String> pageNameProvider) {
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(pageNameProvider, "pageNameProvider");

        if (items.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }

        // Deduplicate items by textureId; validate that duplicate IDs do not have conflicting dimensions
        var uniqueMap = new LinkedHashMap<String, TextureAtlasLayoutItem>();
        for (var item : items) {
            var existing = uniqueMap.putIfAbsent(item.textureId(), item);
            if (existing != null && (existing.width() != item.width() || existing.height() != item.height())) {
                throw new IllegalArgumentException(
                        "Conflicting dimensions for duplicate textureId " + item.textureId() + ": "
                                + existing.width() + "x" + existing.height() + " vs "
                                + item.width() + "x" + item.height());
            }
        }
        var uniqueItems = new ArrayList<>(uniqueMap.values());

        int margin = options.edgeMargin();
        int padding = options.itemPadding();

        // Oversized images are rejected. Images that need the edge margin area
        // receive an exclusive page, with no neighboring image to bleed into.
        for (var item : uniqueItems) {
            if (item.width() > options.maxAtlasEdge() || item.height() > options.maxAtlasEdge()) {
                throw new ModelImportPlugin.LocalizedIllegalArgumentException(
                        "error.atlas-oversized", "模型贴图尺寸超过宿主图集支持的上限。");
            }
        }

        // Deterministic sorting: height desc, width desc, textureId asc
        var sortedItems = uniqueItems.stream()
                .sorted(Comparator.comparingInt(TextureAtlasLayoutItem::height)
                        .reversed()
                        .thenComparing(Comparator.comparingInt(TextureAtlasLayoutItem::width)
                                .reversed())
                        .thenComparing(TextureAtlasLayoutItem::textureId))
                .toList();

        // Smallest power-of-two that fits the largest single image
        int minSize = 256;
        for (var item : sortedItems) {
            int required = needsExclusivePage(item, options.maxAtlasEdge(), margin)
                    ? options.maxAtlasEdge()
                    : Math.toIntExact(Math.max((long) item.width(), item.height()) + margin * 2L);
            while (minSize < required) {
                minSize *= 2;
            }
        }

        // Evaluate all candidate square sizes S in [minSize, maxAtlasEdge]
        var candidates = new ArrayList<Candidate>();
        for (int candidateSize = minSize; candidateSize <= options.maxAtlasEdge(); candidateSize *= 2) {
            var placements = packShelf(sortedItems, candidateSize, candidateSize, options.maxPages(), margin, padding);
            if (placements != null) {
                int pageCount = 0;
                for (var p : placements) {
                    pageCount = Math.max(pageCount, p.pageIndex() + 1);
                }
                long totalPixels = (long) pageCount * candidateSize * candidateSize;
                if (pageCount <= options.maxPages() && totalPixels <= options.maxTotalPixels()) {
                    candidates.add(new Candidate(candidateSize, pageCount, placements, totalPixels));
                }
            }
        }

        if (candidates.isEmpty()) {
            throw new ModelImportPlugin.LocalizedIllegalStateException("error.atlas-budget-exceeded", "纹理图集超出内存或页数预算。");
        }

        // Select the most balanced candidate:
        // Avoid blindly pursuing 1 page if it wastes excessive memory (e.g. > 1.5x minimum total pixels)
        long minTotalPixels =
                candidates.stream().mapToLong(Candidate::totalPixels).min().orElseThrow();
        long pixelThreshold = (long) (minTotalPixels * 1.5);

        var efficientCandidates = candidates.stream()
                .filter(c -> c.totalPixels() <= pixelThreshold)
                .toList();

        var chosen = efficientCandidates.stream()
                .min(Comparator.comparingInt(Candidate::pageCount)
                        .thenComparingLong(Candidate::totalPixels)
                        .thenComparingInt(Candidate::pageSize))
                .orElse(candidates.get(0));

        var pageNames = new ArrayList<String>(chosen.pageCount());
        for (int i = 0; i < chosen.pageCount(); i++) {
            pageNames.add(pageNameProvider.apply(i));
        }

        return new PlannedAtlas(
                chosen.pageSize(), chosen.pageSize(), chosen.pageCount(), pageNames, chosen.placements(), 1.0);
    }

    private static boolean needsExclusivePage(TextureAtlasLayoutItem item, int edge, int margin) {
        return (long) item.width() + 2L * margin > edge || (long) item.height() + 2L * margin > edge;
    }

    private static List<TextureAtlasPlacement> packShelf(
            List<TextureAtlasLayoutItem> items, int width, int height, int maxPages, int margin, int padding) {
        long right = (long) width - margin;
        long bottom = (long) height - margin;
        long x = margin, y = margin, rowHeight = 0;
        int page = 0;
        var placements = new ArrayList<TextureAtlasPlacement>(items.size());
        var shared = new ArrayList<TextureAtlasLayoutItem>();
        for (var item : items) {
            if (needsExclusivePage(item, width, margin)) {
                if (item.width() > width || item.height() > height || page >= maxPages) return null;
                placements.add(
                        new TextureAtlasPlacement(item.textureId(), page++, 0, 0, item.width(), item.height(), false));
            } else shared.add(item);
        }
        if (!shared.isEmpty() && page >= maxPages) return null;

        for (var item : shared) {
            if (item.width() > right - margin || item.height() > bottom - margin) {
                return null;
            }
            if (x + item.width() > right) {
                x = margin;
                y += rowHeight + padding;
                rowHeight = 0;
            }
            if (y + item.height() > bottom) {
                page++;
                if (page >= maxPages) return null;
                x = margin;
                y = margin;
                rowHeight = 0;
            }
            placements.add(new TextureAtlasPlacement(
                    item.textureId(),
                    page,
                    Math.toIntExact(x),
                    Math.toIntExact(y),
                    item.width(),
                    item.height(),
                    false));
            x += item.width() + padding;
            rowHeight = Math.max(rowHeight, item.height());
        }
        return placements;
    }
}
