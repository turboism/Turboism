package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.textureatlas.*;
import dev.turboism.sdk.json.Json;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Pure bounded complete-atlas rectangle projection, validation and deterministic shelf planning. */
final class McpAtlasLayout {
    static final int MAX_ITEMS = 512, MAX_PAGES = 32, MAX_DIMENSION = 8192, MAX_VERTICES = 8192, MAX_BYTES = 240 * 1024;
    static final long MAX_PIXELS = 64L * 1024 * 1024;

    static Map<String, Object> project(TextureAtlasLayoutSnapshot snapshot) {
        var c = snapshot.constraints();
        if (c.singlePageOptions() != null
                || c.allowRotation()
                || c.allowScaling()
                || !snapshot.currentPlan().polygonPages().isEmpty()) throw new UnsupportedOperationException();
        if (c.pageWidth() > MAX_DIMENSION
                || c.pageHeight() > MAX_DIMENSION
                || c.maxPages() > MAX_PAGES
                || snapshot.items().size() > MAX_ITEMS) throw new Budget();
        var rows = new ArrayList<Map<String, Object>>();
        int vertices = 0;
        for (var item : snapshot.items()) {
            text(item.textureId(), 4096);
            if (item.width() > MAX_DIMENSION || item.height() > MAX_DIMENSION) throw new Budget();
            var row = new LinkedHashMap<String, Object>();
            row.put("textureId", item.textureId());
            row.put("width", item.width());
            row.put("height", item.height());
            String outlineHash = null;
            int count = 0;
            if (item.outline() != null) {
                var rings = new ArrayList<Object>();
                for (var ring : item.outline().rings()) {
                    count = Math.addExact(count, ring.length);
                    vertices = Math.addExact(vertices, ring.length);
                    if (vertices > MAX_VERTICES) throw new Budget();
                    rings.add(Arrays.stream(ring)
                            .map(point -> List.of(point[0], point[1]))
                            .toList());
                }
                outlineHash = McpWriteResults.digest(rings);
            }
            row.put("outlineHash", outlineHash);
            row.put("outlineVertexCount", count);
            rows.add(Collections.unmodifiableMap(row));
        }
        var result = Map.<String, Object>of(
                "documentId",
                text(snapshot.documentId(), 4096),
                "modelId",
                text(snapshot.modelId(), 4096),
                "atlasIdHash",
                McpWriteResults.digest(Map.of("nativeAtlasId", snapshot.atlasId())),
                "constraints",
                Map.of(
                        "pageWidth",
                        c.pageWidth(),
                        "pageHeight",
                        c.pageHeight(),
                        "edgeMargin",
                        c.edgeMargin(),
                        "itemPadding",
                        c.itemPadding(),
                        "maxPages",
                        c.maxPages()),
                "items",
                List.copyOf(rows),
                "currentPlan",
                project(snapshot.currentPlan()));
        budget(result);
        return result;
    }

    static Map<String, Object> project(TextureAtlasLayoutPlan plan) {
        if (plan.pageCount() > MAX_PAGES
                || plan.placements().size() > MAX_ITEMS
                || plan.pageWidth() > MAX_DIMENSION
                || plan.pageHeight() > MAX_DIMENSION) throw new Budget();
        if (!plan.polygonPages().isEmpty() || plan.scale() != 1D) throw new UnsupportedOperationException();
        plan.pageNames().forEach(n -> text(n, 128));
        var placements = plan.placements().stream()
                .sorted(Comparator.comparing(TextureAtlasPlacement::textureId))
                .map(p -> Map.<String, Object>of(
                        "textureId",
                        text(p.textureId(), 4096),
                        "pageIndex",
                        p.pageIndex(),
                        "x",
                        p.x(),
                        "y",
                        p.y(),
                        "width",
                        p.width(),
                        "height",
                        p.height(),
                        "rotated",
                        p.rotated()))
                .toList();
        return Map.of(
                "pageWidth",
                plan.pageWidth(),
                "pageHeight",
                plan.pageHeight(),
                "pageCount",
                plan.pageCount(),
                "pageNames",
                plan.pageNames(),
                "pageSizes",
                java.util.stream.IntStream.range(0, plan.pageCount())
                        .mapToObj(i -> Map.of(
                                "width",
                                plan.pageSize(i).width(),
                                "height",
                                plan.pageSize(i).height()))
                        .toList(),
                "placements",
                placements,
                "scale",
                1);
    }

    static TextureAtlasLayoutPlan parse(Object raw) {
        var map = fields(raw, "pageWidth", "pageHeight", "pageCount", "pageNames", "pageSizes", "placements", "scale");
        if (!(map.get("scale") instanceof Number n) || new BigDecimal(n.toString()).compareTo(BigDecimal.ONE) != 0)
            throw new IllegalArgumentException();
        var names = list(map.get("pageNames"), MAX_PAGES).stream()
                .map(v -> text(v, 128))
                .toList();
        var sizes = list(map.get("pageSizes"), MAX_PAGES).stream()
                .map(v -> {
                    var row = fields(v, "width", "height");
                    return new TextureAtlasPageSize(integer(row.get("width")), integer(row.get("height")));
                })
                .toList();
        var placements = list(map.get("placements"), MAX_ITEMS).stream()
                .map(v -> {
                    var row = fields(v, "textureId", "pageIndex", "x", "y", "width", "height", "rotated");
                    if (!(row.get("rotated") instanceof Boolean rotation)) throw new IllegalArgumentException();
                    return new TextureAtlasPlacement(
                            text(row.get("textureId"), 4096),
                            integer(row.get("pageIndex")),
                            integer(row.get("x")),
                            integer(row.get("y")),
                            integer(row.get("width")),
                            integer(row.get("height")),
                            rotation);
                })
                .toList();
        return new TextureAtlasLayoutPlan(
                integer(map.get("pageWidth")),
                integer(map.get("pageHeight")),
                integer(map.get("pageCount")),
                names,
                placements,
                1D,
                sizes);
    }

    static void validate(TextureAtlasLayoutSnapshot source, TextureAtlasLayoutPlan plan, long maxPixels) {
        project(source);
        project(plan);
        var c = source.constraints();
        if (plan.pageCount() > c.maxPages()
                || plan.pageNames().size() != plan.pageCount()
                || plan.pageSizes().size() != plan.pageCount()) throw new IllegalArgumentException();
        long pixels = 0;
        int maxWidth = 0, maxHeight = 0;
        for (var size : plan.pageSizes()) {
            if (size.width() > MAX_DIMENSION || size.height() > MAX_DIMENSION) throw new Budget();
            pixels += (long) size.width() * size.height();
            maxWidth = Math.max(maxWidth, size.width());
            maxHeight = Math.max(maxHeight, size.height());
        }
        if (maxWidth != plan.pageWidth() || maxHeight != plan.pageHeight()) throw new IllegalArgumentException();
        if (maxPixels < 1 || maxPixels > MAX_PIXELS || pixels > maxPixels) throw new Budget();
        var items = new HashMap<String, TextureAtlasLayoutItem>();
        source.items().forEach(i -> items.put(i.textureId(), i));
        var seen = new HashSet<String>();
        var pages = new HashSet<Integer>();
        for (var placement : plan.placements()) {
            var item = items.get(placement.textureId());
            if (item == null
                    || !seen.add(placement.textureId())
                    || placement.rotated()
                    || placement.width() != item.width()
                    || placement.height() != item.height()) throw new IllegalArgumentException();
            var page = plan.pageSize(placement.pageIndex());
            if (placement.x() < c.edgeMargin()
                    || placement.y() < c.edgeMargin()
                    || (long) placement.x() + placement.width() + c.edgeMargin() > page.width()
                    || (long) placement.y() + placement.height() + c.edgeMargin() > page.height())
                throw new IllegalArgumentException();
            pages.add(placement.pageIndex());
        }
        if (!seen.equals(items.keySet())
                || (!items.isEmpty() && pages.size() != plan.pageCount())
                || (items.isEmpty() && plan.pageCount() != 1)) throw new IllegalArgumentException();
        for (int i = 0; i < plan.placements().size(); i++)
            for (int j = i + 1; j < plan.placements().size(); j++) {
                var a = plan.placements().get(i);
                var b = plan.placements().get(j);
                long gap = c.itemPadding();
                if (a.pageIndex() == b.pageIndex()
                        && (long) a.x() < (long) b.x() + b.width() + gap
                        && (long) b.x() < (long) a.x() + a.width() + gap
                        && (long) a.y() < (long) b.y() + b.height() + gap
                        && (long) b.y() < (long) a.y() + a.height() + gap) throw new IllegalArgumentException();
            }
        budget(project(plan));
    }

    static TextureAtlasLayoutPlan shelf(TextureAtlasLayoutSnapshot source, long maxPixels) {
        project(source);
        var c = source.constraints();
        var items = new ArrayList<>(source.items());
        items.sort(Comparator.comparingInt(TextureAtlasLayoutItem::height)
                .reversed()
                .thenComparing(
                        Comparator.comparingInt(TextureAtlasLayoutItem::width).reversed())
                .thenComparing(TextureAtlasLayoutItem::textureId));
        var placements = new ArrayList<TextureAtlasPlacement>();
        int page = 0;
        long x = c.edgeMargin(), y = c.edgeMargin(), rowHeight = 0;
        for (var item : items) {
            if ((long) item.width() + 2L * c.edgeMargin() > c.pageWidth()
                    || (long) item.height() + 2L * c.edgeMargin() > c.pageHeight()) throw new DoesNotFit();
            if (x + item.width() + c.edgeMargin() > c.pageWidth()) {
                x = c.edgeMargin();
                y += rowHeight + c.itemPadding();
                rowHeight = 0;
            }
            if (y + item.height() + c.edgeMargin() > c.pageHeight()) {
                page++;
                x = c.edgeMargin();
                y = c.edgeMargin();
                rowHeight = 0;
            }
            if (page >= c.maxPages() || page >= MAX_PAGES) throw new DoesNotFit();
            placements.add(new TextureAtlasPlacement(
                    item.textureId(), page, (int) x, (int) y, item.width(), item.height(), false));
            x += item.width() + (long) c.itemPadding();
            rowHeight = Math.max(rowHeight, item.height());
        }
        int count = page + 1;
        var names = java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> "MCP Atlas " + (i + 1))
                .toList();
        var sizes = Collections.nCopies(count, new TextureAtlasPageSize(c.pageWidth(), c.pageHeight()));
        var result = new TextureAtlasLayoutPlan(c.pageWidth(), c.pageHeight(), count, names, placements, 1D, sizes);
        validate(source, result, maxPixels);
        return result;
    }

    static long pixels(TextureAtlasLayoutPlan plan) {
        long sum = 0;
        for (int i = 0; i < plan.pageCount(); i++)
            sum += (long) plan.pageSize(i).width() * plan.pageSize(i).height();
        return sum;
    }

    static int budget(Map<String, ?> value) {
        int size = Json.stringify(value).getBytes(StandardCharsets.UTF_8).length;
        if (size > MAX_BYTES) throw new Budget();
        return size;
    }

    static String text(Object value, int max) {
        if (!(value instanceof String s)
                || s.isBlank()
                || s.length() > max
                || s.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
        return s;
    }

    static int integer(Object value) {
        if (!(value instanceof Number n)) throw new IllegalArgumentException();
        return new BigDecimal(n.toString()).intValueExact();
    }

    static Map<?, ?> fields(Object value, String... names) {
        if (!(value instanceof Map<?, ?> map) || !map.keySet().equals(Set.of(names)))
            throw new IllegalArgumentException();
        return map;
    }

    static List<?> list(Object value, int max) {
        if (!(value instanceof List<?> list) || list.size() > max) throw new IllegalArgumentException();
        return list;
    }

    static final class Budget extends RuntimeException {}

    static final class DoesNotFit extends RuntimeException {}
}
