package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.textureatlas.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;

/** SDK-only complete atlas layout. Every issued image is retained at its original size. */
final class AppleAtlasLayout {
    private AppleAtlasLayout() { }

    /** Shelf packing with a reflected alternative for actual re-layout verification. */
    static TextureAtlasLayoutPlan plan(TextureAtlasLayoutSnapshot snapshot, boolean reflected) {
        Objects.requireNonNull(snapshot, "snapshot");
        var c = snapshot.constraints();
        if (c.singlePageOptions() != null || c.allowRotation() || c.allowScaling()) {
            throw new IllegalArgumentException("complete fixed-size atlas target required");
        }
        if (snapshot.items().isEmpty()) throw new IllegalArgumentException("atlas has no images");
        // Use long arithmetic: SDK dimensions are positive ints, but their sums need not fit int.
        long margin = Math.max(2, c.edgeMargin()), padding = Math.max(2, c.itemPadding());
        long right = (long)c.pageWidth() - margin, bottom = (long)c.pageHeight() - margin;
        long x = margin, y = margin, rowHeight = 0;
        int page = 0;
        var placements = new ArrayList<TextureAtlasPlacement>();
        var items = snapshot.items().stream().sorted(Comparator
            .comparingInt(TextureAtlasLayoutItem::height).reversed()
            .thenComparing(TextureAtlasLayoutItem::textureId)).toList();
        for (var image : items) {
            if (image.width() > right - margin || image.height() > bottom - margin) {
                throw new IllegalArgumentException("image exceeds atlas size: " + image.textureId());
            }
            if (x + image.width() > right) {
                x = margin; y += rowHeight + padding; rowHeight = 0;
            }
            if (y + image.height() > bottom) {
                if (++page >= c.maxPages()) throw new IllegalArgumentException("atlas page budget exceeded");
                x = margin; y = margin; rowHeight = 0;
            }
            long px = reflected ? (long)c.pageWidth() - x - image.width() : x;
            long py = reflected ? (long)c.pageHeight() - y - image.height() : y;
            placements.add(new TextureAtlasPlacement(image.textureId(), page, Math.toIntExact(px),
                Math.toIntExact(py), image.width(), image.height(), false));
            x += image.width() + padding;
            rowHeight = Math.max(rowHeight, image.height());
        }
        return new TextureAtlasLayoutPlan(c.pageWidth(), c.pageHeight(), page + 1, placements);
    }

    /** Planning completes before apply; errors never submit a partial set of images. */
    static TextureAtlasLayoutApplyResult apply(TextureAtlasLayoutService service, boolean reflected) {
        var snapshot = Objects.requireNonNull(service, "service").current()
            .orElseThrow(() -> new IllegalStateException("active model has no available atlas target"));
        return service.apply(snapshot.target(), plan(snapshot, reflected));
    }
}
