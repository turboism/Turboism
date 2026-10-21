package dev.turboism.plugin.atlasnesting.layout;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonPlacement;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Exact-rectangle shortcut. No raster allocation or dependency on another plugin. */
final class RectanglePacker {
    record Box(String id, Rectangle2D bounds, double angle, double scale, boolean rotate) {}

    record Packed(List<TextureAtlasPolygonPlacement> placements, List<String> overflow, int height) {}

    private record Rect(long x, long y, long width, long height) {
        long right() {
            return x + width;
        }

        long bottom() {
            return y + height;
        }

        boolean intersects(Rect b) {
            return x < b.right() && right() > b.x && y < b.bottom() && bottom() > b.y;
        }

        boolean contains(Rect b) {
            return x <= b.x && y <= b.y && right() >= b.right() && bottom() >= b.bottom();
        }
    }

    private record Candidate(Rect used, boolean rotate, long shortWaste, long longWaste) {}

    static Packed pack(List<Box> boxes, int width, int height, int margin, int gap) {
        Packed best = null;
        double bestArea = -1;
        // Same bounded family of aspect/area orders as a conventional BSSF.
        for (int order = 0; order < 6; order++) {
            final int metric = order;
            final var sorted = new ArrayList<>(boxes);
            sorted.sort(Comparator.<Box>comparingDouble(b -> metric(b, metric))
                    .reversed()
                    .thenComparing(Box::id));
            final var free = new ArrayList<Rect>();
            free.add(new Rect(margin, margin, (long) width - 2L * margin + gap, (long) height - 2L * margin + gap));
            final var placements = new ArrayList<TextureAtlasPolygonPlacement>();
            final var overflow = new ArrayList<String>();
            int usedHeight = 0;
            double area = 0;
            for (Box box : sorted) {
                if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Nesting interrupted");
                final double w = Math.max(1, Math.ceil(box.bounds().getWidth() - 1e-9)),
                        h = Math.max(1, Math.ceil(box.bounds().getHeight() - 1e-9));
                Candidate found = null;
                if (w <= Integer.MAX_VALUE && h <= Integer.MAX_VALUE) {
                    for (Rect r : free) {
                        found = choose(found, candidate(r, (long) w + gap, (long) h + gap, false));
                        if (box.rotate() && w != h)
                            found = choose(found, candidate(r, (long) h + gap, (long) w + gap, true));
                    }
                }
                if (found == null) {
                    overflow.add(box.id());
                    continue;
                }
                final Rect used = found.used();
                final var b = box.bounds();
                final double x = used.x() - (found.rotate() ? -b.getMaxY() : b.getMinX());
                final double y = used.y() - (found.rotate() ? b.getMinX() : b.getMinY());
                placements.add(new TextureAtlasPolygonPlacement(
                        box.id(), x, y, box.angle() + (found.rotate() ? 90 : 0), box.scale()));
                area += w * h;
                usedHeight = Math.max(usedHeight, (int) (used.bottom() - gap));
                split(free, used);
            }
            if (best == null
                    || placements.size() > best.placements().size()
                    || placements.size() == best.placements().size() && area > bestArea) {
                best = new Packed(List.copyOf(placements), List.copyOf(overflow), usedHeight);
                bestArea = area;
            }
            if (overflow.isEmpty()) break;
        }
        return best;
    }

    private static double metric(Box b, int order) {
        final double w = b.bounds().getWidth(), h = b.bounds().getHeight();
        return switch (order) {
            case 0 -> w * h;
            case 1 -> h;
            case 2 -> Math.min(w, h);
            case 3 -> w + h;
            case 4 -> w;
            default -> Math.max(w, h);
        };
    }

    private static Candidate candidate(Rect r, long w, long h, boolean rotate) {
        if (w > r.width() || h > r.height()) return null;
        return new Candidate(
                new Rect(r.x(), r.y(), w, h),
                rotate,
                Math.min(r.width() - w, r.height() - h),
                Math.max(r.width() - w, r.height() - h));
    }

    private static Candidate choose(Candidate a, Candidate b) {
        if (a == null) return b;
        if (b == null) return a;
        int c = Long.compare(a.shortWaste(), b.shortWaste());
        if (c == 0) c = Long.compare(a.longWaste(), b.longWaste());
        if (c == 0) c = Long.compare(a.used().y(), b.used().y());
        if (c == 0) c = Long.compare(a.used().x(), b.used().x());
        if (c == 0) c = Boolean.compare(a.rotate(), b.rotate());
        return c <= 0 ? a : b;
    }

    private static void split(ArrayList<Rect> free, Rect used) {
        int unchanged = free.size();
        for (int i = free.size() - 1; i >= 0; i--) {
            final Rect r = free.get(i);
            if (!r.intersects(used)) continue;
            free.remove(i);
            unchanged--;
            if (used.x() > r.x()) free.add(new Rect(r.x(), r.y(), used.x() - r.x(), r.height()));
            if (used.right() < r.right()) free.add(new Rect(used.right(), r.y(), r.right() - used.right(), r.height()));
            if (used.y() > r.y()) free.add(new Rect(r.x(), r.y(), r.width(), used.y() - r.y()));
            if (used.bottom() < r.bottom())
                free.add(new Rect(r.x(), used.bottom(), r.width(), r.bottom() - used.bottom()));
        }
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

    private RectanglePacker() {}
}
