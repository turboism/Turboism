package dev.turboism.plugin.atlasfolders.layout;

import dev.turboism.sdk.cubism.textureatlas.*;
import java.util.*;

/** Splits overflowing folders recursively, retaining tree order and source pixel sizes. */
public final class FolderTextureAtlasPlanner {
    /** A single item failed even on the largest page; distinguish size from packing failure. */
    public static final class ItemFitFailure extends IllegalArgumentException {
        private final TextureAtlasLayoutItem item;
        private final int maxEdge;
        private final int margin;
        private final int padding;

        private ItemFitFailure(TextureAtlasLayoutItem item, int maxEdge, int margin, int padding) {
            super("Cannot place image " + item.textureId() + ": source=" + item.width() + "x" + item.height()
                    + ", outline=" + extent(item, 0) + "x" + extent(item, 1)
                    + ", page=" + maxEdge + ", margin=" + margin + ", padding=" + padding);
            this.item = item;
            this.maxEdge = maxEdge;
            this.margin = margin;
            this.padding = padding;
        }

        /** Returns the localized message key for geometric overflow or algorithm failure. */
        public String messageKey() {
            return extent(item, 0) + 2L * margin > maxEdge || extent(item, 1) + 2L * margin > maxEdge
                    ? "folders.itemTooLarge"
                    : "folders.itemPackingFailed";
        }

        /** Returns source and effective dimensions, page limits and the outline-source message key. */
        public Object[] messageArguments() {
            return new Object[] {
                item.textureId(),
                item.width(),
                item.height(),
                String.format(Locale.ROOT, "%.2f × %.2f", extent(item, 0), extent(item, 1)),
                maxEdge,
                margin,
                padding,
                item.outline() == null ? "folders.boundsFallback" : "folders.meshOutline"
            };
        }
    }

    /** A detached folder in palette order; images belong directly to this node. */
    public record Folder(String name, List<TextureAtlasLayoutItem> images, List<Folder> children) {
        public Folder {
            Objects.requireNonNull(name, "name");
            images = List.copyOf(images);
            children = List.copyOf(children);
        }
    }

    private final TextureAtlasLayoutPlanner packer;
    private final boolean automaticSize;

    public FolderTextureAtlasPlanner(TextureAtlasLayoutPlanner packer) {
        this(packer, true);
    }

    public FolderTextureAtlasPlanner(TextureAtlasLayoutPlanner packer, boolean automaticSize) {
        this.packer = Objects.requireNonNull(packer, "packer");
        this.automaticSize = automaticSize;
    }

    /** The maximum edge is a power of two; each output page is sized independently. */
    public TextureAtlasLayoutPlan plan(Folder root, int maxEdge, int maxPages, int margin, int padding) {
        if (maxEdge < 1 || maxEdge > 16384 || Integer.bitCount(maxEdge) != 1 || maxPages < 1) {
            throw new IllegalArgumentException("Invalid atlas budget.");
        }
        List<Page> pages = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (var item : flatten(root)) {
            if (!ids.add(item.textureId())) throw new IllegalArgumentException("Duplicate image ownership.");
        }
        // Folder order is a grouping preference, not a requirement to leave pages empty.
        splitDirect(root.name(), root.images(), maxEdge, maxPages, margin, padding, pages);
        for (Folder child : root.children()) split(child, maxEdge, maxPages, margin, padding, pages);
        if (pages.isEmpty()) throw new IllegalArgumentException("No images to arrange.");
        List<TextureAtlasPlacement> placements = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<TextureAtlasPageSize> sizes = new ArrayList<>();
        List<TextureAtlasPolygonPlan> polygonPages = new ArrayList<>();
        boolean outlined =
                pages.stream().anyMatch(page -> !page.plan().polygonPages().isEmpty());
        for (int index = 0; index < pages.size(); index++) {
            Page page = pages.get(index);
            names.add(String.format(java.util.Locale.ROOT, "%03d %s", index + 1, page.name()));
            sizes.add(new TextureAtlasPageSize(
                    page.plan().pageWidth(), page.plan().pageHeight()));
            if (outlined) {
                if (!page.plan().polygonPages().isEmpty())
                    polygonPages.add(page.plan().polygonPages().get(0));
                else
                    polygonPages.add(new TextureAtlasPolygonPlan(
                            page.plan().pageWidth(),
                            page.plan().pageHeight(),
                            1D,
                            page.plan().placements().stream()
                                    .map(p -> new TextureAtlasPolygonPlacement(p.textureId(), p.x(), p.y(), 0D, 1D))
                                    .toList(),
                            List.of(),
                            TextureAtlasLayoutBackend.AUTO,
                            Map.of()));
            }
            for (var p :
                    outlined ? List.<TextureAtlasPlacement>of() : page.plan().placements())
                placements.add(
                        new TextureAtlasPlacement(p.textureId(), index, p.x(), p.y(), p.width(), p.height(), false));
        }
        return new TextureAtlasLayoutPlan(
                sizes.stream().mapToInt(TextureAtlasPageSize::width).max().orElseThrow(),
                sizes.stream().mapToInt(TextureAtlasPageSize::height).max().orElseThrow(),
                pages.size(),
                names,
                placements,
                1D,
                sizes,
                polygonPages);
    }

    private void split(Folder folder, int edge, int budget, int margin, int padding, List<Page> pages) {
        List<TextureAtlasLayoutItem> all = flatten(folder);
        if (all.isEmpty()) return;
        var single = smallest(all, edge, margin, padding);
        if (single != null) {
            enrich(pages, folder.name(), all, single, edge, budget, margin, padding);
        } else {
            splitDirect(folder.name(), folder.images(), edge, budget, margin, padding, pages);
            for (Folder child : folder.children()) split(child, edge, budget, margin, padding, pages);
        }
    }

    private void splitDirect(
            String name,
            List<TextureAtlasLayoutItem> items,
            int edge,
            int budget,
            int margin,
            int padding,
            List<Page> pages) {
        if (items.isEmpty()) return;
        var single = smallest(items, edge, margin, padding);
        if (single != null) {
            enrich(pages, name, items, single, edge, budget, margin, padding);
            return;
        }
        if (items.size() == 1) throw new ItemFitFailure(items.get(0), edge, margin, padding);
        // Fill a page greedily before creating another; binary splitting strands small groups.
        List<TextureAtlasLayoutItem> ordered = new ArrayList<>(items);
        ordered.sort(Comparator.comparingLong((TextureAtlasLayoutItem i) -> (long) i.width() * i.height())
                .reversed()
                .thenComparing(TextureAtlasLayoutItem::textureId));
        for (var item : ordered) {
            var fit = smallest(List.of(item), edge, margin, padding);
            if (fit == null) throw new ItemFitFailure(item, edge, margin, padding);
            enrich(pages, name, List.of(item), fit, edge, budget, margin, padding);
        }
    }

    private void enrich(
            List<Page> pages,
            String name,
            List<TextureAtlasLayoutItem> items,
            TextureAtlasLayoutPlan single,
            int edge,
            int budget,
            int margin,
            int padding) {
        for (int index = 0; index < pages.size(); index++) {
            var page = pages.get(index);
            var combined = new ArrayList<>(page.items());
            combined.addAll(items);
            var fit = smallest(combined, edge, margin, padding);
            if (fit != null) {
                pages.set(index, new Page(page.name(), fit, List.copyOf(combined)));
                return;
            }
        }
        if (items.size() == 1) {
            add(pages, new Page(name, single, List.copyOf(items)), budget);
            return;
        }
        // If the whole folder cannot join one page, reuse free space before adding a page.
        var remaining = new ArrayList<TextureAtlasLayoutItem>();
        for (var item : items.stream()
                .sorted(Comparator.comparingLong((TextureAtlasLayoutItem i) -> (long) i.width() * i.height())
                        .reversed()
                        .thenComparing(TextureAtlasLayoutItem::textureId))
                .toList()) {
            boolean placed = false;
            for (int index = 0; index < pages.size(); index++) {
                var page = pages.get(index);
                var combined = new ArrayList<>(page.items());
                combined.add(item);
                var fit = smallest(combined, edge, margin, padding);
                if (fit != null) {
                    pages.set(index, new Page(page.name(), fit, List.copyOf(combined)));
                    placed = true;
                    break;
                }
            }
            if (!placed) remaining.add(item);
        }
        if (!remaining.isEmpty()) {
            var fit = remaining.size() == items.size() ? single : smallest(remaining, edge, margin, padding);
            if (fit != null) add(pages, new Page(name, fit, List.copyOf(remaining)), budget);
            else splitDirect(name, remaining, edge, budget, margin, padding, pages);
        }
    }

    private TextureAtlasLayoutPlan smallest(List<TextureAtlasLayoutItem> items, int edge, int margin, int padding) {
        List<TextureAtlasPageSize> candidates = new ArrayList<>();
        double area =
                items.stream().mapToDouble(FolderTextureAtlasPlanner::area).sum();
        for (int w = 1; w <= edge; w *= 2) {
            for (int h = 1; h <= edge; h *= 2) {
                if (!automaticSize && (w != edge || h != edge)) continue;
                if (w <= 2L * margin || h <= 2L * margin || (long) w * h < area) continue;
                final int width = w, height = h;
                if (items.stream()
                        .anyMatch(i -> extent(i, 0) + 2L * margin > width || extent(i, 1) + 2L * margin > height))
                    continue;
                candidates.add(new TextureAtlasPageSize(w, h));
            }
        }
        candidates.sort(Comparator.comparingLong((TextureAtlasPageSize s) -> (long) s.width() * s.height())
                .thenComparingInt(s -> Math.max(s.width(), s.height()))
                .thenComparingInt(TextureAtlasPageSize::width));
        for (var size : candidates) {
            try {
                var result = packer.plan(items, constraints(size.width(), size.height(), 1, margin, padding));
                if (result.scale() != 1D
                        || result.pageCount() != 1
                        || result.pageWidth() != size.width()
                        || result.pageHeight() != size.height()) {
                    throw new IllegalArgumentException("Planner changed source scale or requested page size.");
                }
                var ids = items.stream()
                        .map(TextureAtlasLayoutItem::textureId)
                        .collect(java.util.stream.Collectors.toSet());
                if (!result.polygonPages().isEmpty()) {
                    var polygon = result.polygonPages().get(0);
                    var placedIds = polygon.placements().stream()
                            .map(TextureAtlasPolygonPlacement::textureId)
                            .collect(java.util.stream.Collectors.toSet());
                    if (placedIds.equals(ids) && polygon.placements().size() == items.size()) return result;
                }
                if (result.placements().size() == items.size()
                        && result.placements().stream().allMatch(p -> ids.contains(p.textureId()) && !p.rotated()))
                    return result;
            } catch (TextureAtlasPackingException failure) {
                if (failure.reason() != TextureAtlasPackingException.Reason.PAGE_BUDGET_EXHAUSTED
                        && failure.reason() != TextureAtlasPackingException.Reason.ITEM_DOES_NOT_FIT) throw failure;
            }
        }
        return null;
    }

    private static double extent(TextureAtlasLayoutItem item, int axis) {
        if (item.outline() == null) return axis == 0 ? item.width() : item.height();
        var values = item.outline().rings().stream()
                .flatMap(Arrays::stream)
                .mapToDouble(point -> point[axis])
                .summaryStatistics();
        return values.getMax() - values.getMin();
    }

    private static double area(TextureAtlasLayoutItem item) {
        if (item.outline() == null) return (double) item.width() * item.height();
        double area = 0;
        for (var ring : item.outline().rings()) {
            double sum = 0;
            for (int i = 0; i < ring.length; i++) {
                var a = ring[i];
                var b = ring[(i + 1) % ring.length];
                sum += a[0] * b[1] - b[0] * a[1];
            }
            area += Math.abs(sum) * .5;
        }
        return area;
    }

    private static TextureAtlasLayoutConstraints constraints(int w, int h, int budget, int margin, int padding) {
        return new TextureAtlasLayoutConstraints(w, h, margin, padding, budget, false, false);
    }

    private static List<TextureAtlasLayoutItem> flatten(Folder folder) {
        List<TextureAtlasLayoutItem> items = new ArrayList<>(folder.images());
        for (var child : folder.children()) items.addAll(flatten(child));
        return items;
    }

    private static void add(List<Page> pages, Page page, int budget) {
        if (pages.size() >= budget) throw new IllegalArgumentException("Atlas page budget exhausted.");
        pages.add(page);
    }

    private record Page(String name, TextureAtlasLayoutPlan plan, List<TextureAtlasLayoutItem> items) {}
}
