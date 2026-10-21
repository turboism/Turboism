package dev.turboism.plugin.atlasmaxrectsbssf.layout;

import dev.turboism.sdk.cubism.textureatlas.*;
import java.util.*;

/** Splits overflowing folders recursively, retaining tree order and source pixel sizes. */
public final class FolderTextureAtlasPlanner {
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

    public FolderTextureAtlasPlanner() {
        this(new MaxRectsBssfTextureAtlasPlanner()::plan);
    }

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
        // The synthetic root never merges unrelated top-level folders.
        splitDirect(root.name(), root.images(), maxEdge, maxPages, margin, padding, pages);
        for (Folder child : root.children()) split(child, maxEdge, maxPages, margin, padding, pages);
        if (pages.isEmpty()) throw new IllegalArgumentException("No images to arrange.");
        List<TextureAtlasPlacement> placements = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<TextureAtlasPageSize> sizes = new ArrayList<>();
        for (int index = 0; index < pages.size(); index++) {
            Page page = pages.get(index);
            names.add(String.format(java.util.Locale.ROOT, "%03d %s", index + 1, page.name()));
            sizes.add(new TextureAtlasPageSize(
                    page.plan().pageWidth(), page.plan().pageHeight()));
            for (var p : page.plan().placements())
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
                sizes);
    }

    private void split(Folder folder, int edge, int budget, int margin, int padding, List<Page> pages) {
        List<TextureAtlasLayoutItem> all = flatten(folder);
        if (all.isEmpty()) return;
        var single = smallest(all, edge, margin, padding);
        if (single != null) {
            add(pages, new Page(folder.name(), single), budget);
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
            add(pages, new Page(name, single), budget);
            return;
        }
        if (items.size() == 1)
            throw new IllegalArgumentException(
                    "Image exceeds maximum page size: " + items.get(0).textureId());
        // A leaf has no folder boundary left: divide its largest-first image list.
        List<TextureAtlasLayoutItem> ordered = new ArrayList<>(items);
        ordered.sort(Comparator.comparingLong((TextureAtlasLayoutItem i) -> (long) i.width() * i.height())
                .reversed()
                .thenComparing(TextureAtlasLayoutItem::textureId));
        int middle = ordered.size() / 2;
        splitDirect(name, ordered.subList(0, middle), edge, budget, margin, padding, pages);
        splitDirect(name, ordered.subList(middle, ordered.size()), edge, budget, margin, padding, pages);
    }

    private TextureAtlasLayoutPlan smallest(List<TextureAtlasLayoutItem> items, int edge, int margin, int padding) {
        List<TextureAtlasPageSize> candidates = new ArrayList<>();
        long area = items.stream().mapToLong(i -> (long) i.width() * i.height()).sum();
        for (int w = 1; w <= edge; w *= 2) {
            for (int h = 1; h <= edge; h *= 2) {
                if (!automaticSize && (w != edge || h != edge)) continue;
                if (w <= 2L * margin || h <= 2L * margin || (long) w * h < area) continue;
                final int width = w, height = h;
                if (items.stream().anyMatch(i -> i.width() + 2L * margin > width || i.height() + 2L * margin > height))
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

    private record Page(String name, TextureAtlasLayoutPlan plan) {}
}
