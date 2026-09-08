package dev.turboism.plugin.atlasmaxrectsbssf.layout;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutConstraints;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutItem;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Legacy aspect-distribution partition and area-balanced assignment, limited to this page. */
final class CurrentPageRegions {
    private CurrentPageRegions() { }

    static List<Region> partition(List<TextureAtlasLayoutItem> items, TextureAtlasLayoutConstraints c, double scale) {
        if (items.size() < 16) return List.of();
        int wide = 0, tall = 0;
        double tallArea = 0, squareArea = 0, totalArea = 0;
        for (var item : items) {
            double w = item.width() * scale + c.itemPadding(), h = item.height() * scale + c.itemPadding();
            double ratio = w / h, area = w * h;
            totalArea += area;
            if (ratio > 1.3) wide++;
            else if (ratio < 0.77) { tall++; tallArea += area; }
            else squareArea += area;
        }
        final int width = c.pageWidth(), height = c.pageHeight();
        final List<Region> regions;
        if ((double) wide / items.size() > 0.55) {
            regions = List.of(new Region(0, 0, width, height / 2), new Region(0, height / 2, width, height - height / 2));
        } else if ((double) tall / items.size() > 0.55) {
            regions = List.of(new Region(0, 0, width / 2, height), new Region(width / 2, 0, width - width / 2, height));
        } else {
            final double fraction = Math.max(0.35, Math.min(0.65, (tallArea + squareArea * 0.5) / totalArea));
            final int x = Math.max(1, (int) (width * fraction)), y = height / 2;
            regions = List.of(new Region(0, 0, x, y), new Region(x, 0, width - x, y),
                new Region(0, y, x, height - y), new Region(x, y, width - x, height - y));
        }
        for (var region : regions) {
            if (region.width <= 2L * c.edgeMargin() || region.height <= 2L * c.edgeMargin()) return List.of();
        }
        return regions;
    }

    static List<List<TextureAtlasLayoutItem>> assign(List<TextureAtlasLayoutItem> items,
        List<Region> regions, TextureAtlasLayoutConstraints c, double scale) {
        final List<List<TextureAtlasLayoutItem>> groups = new ArrayList<>();
        final double[] remaining = new double[regions.size()];
        for (int i = 0; i < regions.size(); i++) {
            groups.add(new ArrayList<>());
            remaining[i] = (double) regions.get(i).width * regions.get(i).height;
        }
        final ArrayList<TextureAtlasLayoutItem> ordered = new ArrayList<>(items);
        ordered.sort(Comparator.<TextureAtlasLayoutItem>comparingDouble(i ->
            (i.width() * scale + c.itemPadding()) * (i.height() * scale + c.itemPadding()))
            .reversed().thenComparing(TextureAtlasLayoutItem::textureId));
        for (var item : ordered) {
            final double w = Math.ceil(item.width() * scale), h = Math.ceil(item.height() * scale);
            int best = -1;
            for (int i = 0; i < regions.size(); i++) {
                final Region r = regions.get(i);
                final long usableW = r.width - 2L * c.edgeMargin(), usableH = r.height - 2L * c.edgeMargin();
                final boolean fits = (w <= usableW && h <= usableH)
                    || (c.allowRotation() && h <= usableW && w <= usableH);
                if (fits && (best < 0 || remaining[i] > remaining[best])) best = i;
            }
            // Unlike legacy's arbitrary assignment, decline the partition when an image needs
            // to cross its boundaries. The caller then packs the whole current page, not another page.
            if (best < 0) return List.of();
            groups.get(best).add(item);
            remaining[best] -= (w + c.itemPadding()) * (h + c.itemPadding());
        }
        return groups;
    }

    record Region(int x, int y, int width, int height) { }
}
