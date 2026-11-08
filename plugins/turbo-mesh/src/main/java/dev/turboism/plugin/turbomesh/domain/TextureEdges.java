package dev.turboism.plugin.turbomesh.domain;

import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Extracts interior feature lines from a texture-driven {@link DomainMask}: line-art
 * centerlines (dark or bright ridges) plus colour-region boundaries. Line art is handled on
 * its ridge mask so a thin stroke yields ONE polyline through its middle — never a double
 * edge per stroke flank. Gradient edges hugging a detected stroke are suppressed for the same
 * reason.
 *
 * <p>Pipeline: ridge/boundary cell masks → suppress boundary cells touching strokes →
 * Zhang–Suen thinning → 8-connectivity graph tracing into polylines between junctions and
 * endpoints → length/contrast filtering → Douglas–Peucker simplify → arc-length resample.
 * Output coordinates are UV cell centres.
 */
public final class TextureEdges {

    /** One traced feature line; {@code closed} chains are loops (first == last implicit). */
    public record EdgeChain(List<Point2> points, boolean closed) {}

    /** Chains plus the pre-thinning feature-cell mask for interior-fill exclusion zones. */
    public record Extraction(List<EdgeChain> chains, boolean[] featureCells) {}

    private TextureEdges() {}

    /** Whether the mask carries texture channels; geometry masks yield no edges. */
    public static boolean supported(final DomainMask mask) {
        return mask.hasTextureChannels();
    }

    /**
     * Full extraction: traced feature chains plus the pre-thinning feature-cell mask so the
     * planner can exclude a margin around every edge from interior fill.
     */
    public static Extraction extractWithMask(final DomainMask mask, final PlacementSettings s) {
        final int w = mask.width();
        final int h = mask.height();
        final int n = w * h;
        final boolean[] line = lineArtMask(mask, s);
        final boolean[] bnd = boundaryMask(mask, s);
        // A dark stroke produces a gradient lobe on each flank — drop boundary cells that
        // touch the stroke mask so only the centerline remains.
        for (int i = 0; i < n; i++) {
            if (!line[i]) {
                continue;
            }
            final int x = i % w;
            final int y = i / w;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    final int nx = x + dx;
                    final int ny = y + dy;
                    if (nx >= 0 && nx < w && ny >= 0 && ny < h) {
                        bnd[ny * w + nx] = false;
                    }
                }
            }
        }
        final boolean[] feature = new boolean[n];
        for (int i = 0; i < n; i++) {
            feature[i] = line[i] || bnd[i];
        }
        // Suppress the one-cell shell along the alpha boundary: anti-aliased silhouette pixels
        // produce junk luminance there, and the outline is already owned by the boundary ring.
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                final int c = y * w + x;
                if (!feature[c]) {
                    continue;
                }
                for (int dy = -1; dy <= 1 && feature[c]; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        final int nx = x + dx;
                        final int ny = y + dy;
                        if (nx < 0 || nx >= w || ny < 0 || ny >= h || !mask.solidCell(ny * w + nx)) {
                            feature[c] = false;
                            break;
                        }
                    }
                }
            }
        }
        thin(feature, w, h);
        final List<EdgeChain> chains = trace(feature, mask, s);
        return new Extraction(chains, feature);
    }

    /** Convenience: chains only. */
    public static List<EdgeChain> extract(final DomainMask mask, final PlacementSettings s) {
        return extractWithMask(mask, s).chains();
    }

    /**
     * Ridge mask: a solid cell whose luminance stands out from its 5×5 neighbourhood by at
     * least {@code edgeMinContrast}. Works both directions so dark strokes on light fills and
     * bright strokes on dark fills both produce centerlines.
     */
    private static boolean[] lineArtMask(final DomainMask mask, final PlacementSettings s) {
        final int w = mask.width();
        final int h = mask.height();
        final boolean[] line = new boolean[w * h];
        final float contrast = (float) s.edgeMinContrast();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                final int c = y * w + x;
                if (!mask.solidCell(c)) {
                    continue;
                }
                final float me = mask.luminanceAt(c);
                float hi = me;
                float lo = me;
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        if (dx == 0 && dy == 0) {
                            continue;
                        }
                        final int nx = x + dx;
                        final int ny = y + dy;
                        if (nx < 0 || nx >= w || ny < 0 || ny >= h) {
                            continue;
                        }
                        final int nc = ny * w + nx;
                        if (!mask.solidCell(nc)) {
                            continue;
                        }
                        final float nl = mask.luminanceAt(nc);
                        hi = Math.max(hi, nl);
                        lo = Math.min(lo, nl);
                    }
                }
                final boolean darkRidge = me <= hi - contrast && me <= lo + (hi - lo) * 0.45f;
                final boolean brightRidge = me >= lo + contrast && me >= hi - (hi - lo) * 0.45f;
                line[c] = darkRidge || brightRidge;
            }
        }
        return line;
    }

    /**
     * Colour-boundary mask: marks both cells of every 4-adjacent solid pair whose RGB
     * distance exceeds {@code edgeColorDistance} (normalized, 0..√3 → scaled) or whose luma
     * jump clears half the ridge contrast — catches iso-luminant hue boundaries.
     */
    private static boolean[] boundaryMask(final DomainMask mask, final PlacementSettings s) {
        final int w = mask.width();
        final int h = mask.height();
        final boolean[] bnd = new boolean[w * h];
        final double colorThr = s.edgeColorDistance() * 255.0;
        final float lumaThr = (float) (s.edgeMinContrast() * 0.75);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                final int c = y * w + x;
                if (!mask.solidCell(c)) {
                    continue;
                }
                if (x + 1 < w && contrastAcross(mask, c, c + 1, colorThr, lumaThr)) {
                    bnd[c] = bnd[c + 1] = true;
                }
                if (y + 1 < h && contrastAcross(mask, c, c + w, colorThr, lumaThr)) {
                    bnd[c] = bnd[c + w] = true;
                }
            }
        }
        return bnd;
    }

    private static boolean contrastAcross(
            final DomainMask mask, final int a, final int b, final double colorThr, final float lumaThr) {
        if (!mask.solidCell(b)) {
            return false;
        }
        if (Math.abs(mask.luminanceAt(a) - mask.luminanceAt(b)) > lumaThr) {
            return true;
        }
        final int ca = mask.rgbAt(a);
        final int cb = mask.rgbAt(b);
        final int dr = ((ca >>> 16) & 0xFF) - ((cb >>> 16) & 0xFF);
        final int dg = ((ca >>> 8) & 0xFF) - ((cb >>> 8) & 0xFF);
        final int db = (ca & 0xFF) - (cb & 0xFF);
        return Math.sqrt(dr * dr + dg * dg + db * db) > colorThr;
    }

    /** Zhang–Suen thinning; shrinks the feature mask to single-cell-wide centerlines. */
    private static void thin(final boolean[] m, final int w, final int h) {
        boolean changed = true;
        int guard = 64;
        while (changed && guard-- > 0) {
            changed = false;
            for (int step = 0; step < 2; step++) {
                final List<Integer> kill = new ArrayList<>();
                for (int y = 1; y + 1 < h; y++) {
                    for (int x = 1; x + 1 < w; x++) {
                        final int c = y * w + x;
                        if (!m[c]) {
                            continue;
                        }
                        // p2..p9 clockwise from north
                        final boolean p2 = m[c - w], p3 = m[c - w + 1], p4 = m[c + 1];
                        final boolean p5 = m[c + w + 1], p6 = m[c + w], p7 = m[c + w - 1];
                        final boolean p8 = m[c - 1], p9 = m[c - w - 1];
                        final boolean[] p = {p2, p3, p4, p5, p6, p7, p8, p9};
                        int nb = 0;
                        int transitions = 0;
                        for (int i = 0; i < 8; i++) {
                            if (p[i]) {
                                nb++;
                            }
                            if (!p[i] && p[(i + 1) % 8]) {
                                transitions++;
                            }
                        }
                        if (nb < 2 || nb > 6 || transitions != 1) {
                            continue;
                        }
                        if (step == 0) {
                            if (p2 && p4 && p6) {
                                continue;
                            }
                            if (p4 && p6 && p8) {
                                continue;
                            }
                        } else {
                            if (p2 && p4 && p8) {
                                continue;
                            }
                            if (p2 && p6 && p8) {
                                continue;
                            }
                        }
                        kill.add(c);
                    }
                }
                if (!kill.isEmpty()) {
                    changed = true;
                    for (final int c : kill) {
                        m[c] = false;
                    }
                }
            }
        }
    }

    /**
     * Traces the thinned mask into polylines: nodes are endpoints (degree 1) and junctions
     * (degree ≥ 3); each maximal walk between two nodes becomes a chain. Remaining pure
     * loops are closed chains. Chains shorter than {@code edgeMinLengthUv} are dropped.
     */
    private static List<EdgeChain> trace(final boolean[] feature, final DomainMask mask, final PlacementSettings s) {
        final int w = mask.width();
        final int h = mask.height();
        final int n = w * h;
        final int[] deg = new int[n];
        final List<List<Integer>> adj = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            adj.add(new ArrayList<>(3));
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                final int c = y * w + x;
                if (!feature[c]) {
                    continue;
                }
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) {
                            continue;
                        }
                        final int nx = x + dx;
                        final int ny = y + dy;
                        if (nx >= 0 && nx < w && ny >= 0 && ny < h && feature[ny * w + nx]) {
                            adj.get(c).add(ny * w + nx);
                        }
                    }
                }
                deg[c] = adj.get(c).size();
            }
        }
        final boolean[] visitedEdge = new boolean[n * 4]; // pairIndex space
        final List<CellChain> raw = new ArrayList<>();
        // Walk from every node along each unvisited arm.
        for (int c = 0; c < n; c++) {
            if (!feature[c] || deg[c] == 2) {
                continue; // only endpoints/junctions seed chains; deg 0 handled below
            }
            for (final int nb : adj.get(c)) {
                if (edgeSeen(visitedEdge, c, nb, w)) {
                    continue;
                }
                final List<Integer> cells = new ArrayList<>();
                cells.add(c);
                int prev = c;
                int cur = nb;
                markEdge(visitedEdge, c, cur, w);
                cells.add(cur);
                while (deg[cur] == 2) {
                    int nxt = -1;
                    for (final int k : adj.get(cur)) {
                        if (k != prev) {
                            nxt = k;
                            break;
                        }
                    }
                    if (nxt < 0 || edgeSeen(visitedEdge, cur, nxt, w)) {
                        break;
                    }
                    markEdge(visitedEdge, cur, nxt, w);
                    prev = cur;
                    cur = nxt;
                    cells.add(cur);
                }
                raw.add(new CellChain(cells, false));
            }
        }
        // Pure loops: feature cells never visited and not reached from a node.
        for (int c = 0; c < n; c++) {
            if (!feature[c]) {
                continue;
            }
            boolean visited = false;
            for (final int nb : adj.get(c)) {
                if (edgeSeen(visitedEdge, c, nb, w)) {
                    visited = true;
                    break;
                }
            }
            if (visited) {
                continue;
            }
            final List<Integer> cells = new ArrayList<>();
            cells.add(c);
            int prev = -1;
            int cur = c;
            while (true) {
                int nxt = -1;
                for (final int k : adj.get(cur)) {
                    if (k != prev && !edgeSeen(visitedEdge, cur, k, w)) {
                        nxt = k;
                        break;
                    }
                }
                if (nxt < 0) {
                    break;
                }
                markEdge(visitedEdge, cur, nxt, w);
                prev = cur;
                cur = nxt;
                cells.add(cur);
            }
            if (cells.size() >= 3) {
                raw.add(new CellChain(cells, true));
            }
        }
        // Length + point filtering
        final double minLen = s.edgeMinLengthUv();
        final List<CellChain> keptCells = new ArrayList<>();
        for (final CellChain ch : raw) {
            double len = 0;
            for (int i = 0; i + 1 < ch.cells().size(); i++) {
                final double[] a = mask.cellCenterUv(ch.cells().get(i));
                final double[] b = mask.cellCenterUv(ch.cells().get(i + 1));
                len += Math.hypot(b[0] - a[0], b[1] - a[1]);
            }
            if (len >= minLen) {
                keptCells.add(ch);
            }
        }
        final List<EdgeChain> kept = new ArrayList<>();
        for (final CellChain ch : stitch(keptCells, mask, s.edgeStitchCells())) {
            kept.add(chainOf(ch.cells(), mask, ch.closed()));
        }
        // Simplify then resample
        final List<EdgeChain> result = new ArrayList<>();
        for (final EdgeChain ch : kept) {
            final List<Point2> simplified = douglasPeucker(ch.points(), s.edgeDpToleranceUv(), ch.closed());
            final List<Point2> resampled = resampleChain(simplified, ch.closed(), s);
            if (resampled.size() >= (ch.closed() ? 3 : 2)) {
                result.add(new EdgeChain(resampled, ch.closed()));
            }
        }
        return result;
    }

    private static boolean edgeSeen(final boolean[] seen, final int a, final int b, final int w) {
        return seen[pairIndex(a, b, w)];
    }

    private static void markEdge(final boolean[] seen, final int a, final int b, final int w) {
        seen[pairIndex(a, b, w)] = true;
    }

    /** Unique index for the undirected grid edge {@code a-b} (8-connected neighbours). */
    private static int pairIndex(final int a, final int b, final int w) {
        final int lo = Math.min(a, b);
        final int delta = Math.abs(a - b); // 1, w-1, w, w+1
        final int dir = delta == 1 ? 0 : delta == w - 1 ? 1 : delta == w ? 2 : 3;
        return lo * 4 + dir;
    }

    private static EdgeChain chainOf(final List<Integer> cells, final DomainMask mask, final boolean closed) {
        final List<Point2> pts = new ArrayList<>(cells.size());
        for (final int c : cells) {
            final double[] uv = mask.cellCenterUv(c);
            pts.add(new Point2((float) uv[0], (float) uv[1]));
        }
        return new EdgeChain(pts, closed);
    }

    /**
     * Raw cell-level chain kept for the stitching pass (before UV conversion).
     */
    private record CellChain(List<Integer> cells, boolean closed) {}

    /**
     * Joins fragmented open chains: when two endpoints of different chains sit within
     * {@code radiusCells} mask cells AND both outward tangents continue roughly in each
     * other's direction, the chains merge into one. Repeats on the merged result until no
     * pair qualifies. Long colour boundaries dropped for a few low-contrast cells — or cut
     * by a crossing feature into junction-ended arms — become continuous again; hairpins
     * and corner arms are not merged because their tangents point away from each other.
     */
    private static List<CellChain> stitch(final List<CellChain> chains, final DomainMask mask, final int radiusCells) {
        if (radiusCells <= 0 || chains.size() < 2) {
            return chains;
        }
        final double rUv = radiusCells * mask.cellUv();
        final List<CellChain> work = new ArrayList<>(chains);
        final List<List<Point2>> uv = new ArrayList<>(chains.size());
        for (final CellChain ch : chains) {
            final List<Point2> pts = new ArrayList<>(ch.cells().size());
            for (final int c : ch.cells()) {
                final double[] p = mask.cellCenterUv(c);
                pts.add(new Point2((float) p[0], (float) p[1]));
            }
            uv.add(pts);
        }
        for (int guard = work.size() * 2; guard > 0; guard--) {
            double best = rUv;
            int bi = -1;
            int bj = -1;
            int bEnd = -1; // 0 = end(i)->start(j), 1 = end(i)->end(j), 2 = start(i)->start(j), 3 = start(i)->end(j)
            for (int i = 0; i < work.size(); i++) {
                final CellChain a = work.get(i);
                if (a.closed()) {
                    continue;
                }
                for (int j = i + 1; j < work.size(); j++) {
                    final CellChain b = work.get(j);
                    if (b.closed()) {
                        continue;
                    }
                    for (int end = 0; end < 4; end++) {
                        final boolean aEnd = (end & 2) == 0; // 0,1 use end of a
                        final boolean bUsesEnd = (end & 1) != 0; // 1,3 use end of b
                        final List<Point2> pa = uv.get(i);
                        final List<Point2> pb = uv.get(j);
                        if (pa.size() < 2 || pb.size() < 2) {
                            continue;
                        }
                        final Point2 ea = aEnd ? pa.get(pa.size() - 1) : pa.get(0);
                        final Point2 eb = bUsesEnd ? pb.get(pb.size() - 1) : pb.get(0);
                        final double dx = eb.x() - ea.x();
                        final double dy = eb.y() - ea.y();
                        final double d = Math.hypot(dx, dy);
                        if (d >= best || d < 1e-9) {
                            continue;
                        }
                        // Outward tangents at the involved endpoints.
                        final Point2 ta = aEnd ? sub(ea, pa.get(pa.size() - 2)) : sub(ea, pa.get(1));
                        final Point2 tb = bUsesEnd ? sub(eb, pb.get(pb.size() - 2)) : sub(eb, pb.get(1));
                        // Smooth continuation: a's outward tangent must point across the
                        // connector INTO b, i.e. the two outward tangents are anti-parallel.
                        if (dot(ta, tb) > -0.55 || dot(ta, dx, dy) <= 0.5 || dot(tb, -dx, -dy) <= 0.5) {
                            continue; // tangents don't continue each other's direction
                        }
                        best = d;
                        bi = i;
                        bj = j;
                        bEnd = end;
                    }
                }
            }
            if (bi < 0) {
                break;
            }
            final CellChain a = work.get(bi);
            final CellChain b = work.get(bj);
            final boolean useAEnd = (bEnd & 2) == 0;
            final boolean useBEnd = (bEnd & 1) != 0;
            // Orient a so its connecting tip is at the END, b so its tip is at the START.
            final List<Integer> left = new ArrayList<>(a.cells());
            if (!useAEnd) {
                Collections.reverse(left);
            }
            final List<Integer> right = new ArrayList<>(b.cells());
            if (useBEnd) {
                Collections.reverse(right);
            }
            left.addAll(right);
            work.remove(bj);
            uv.remove(bj);
            work.remove(bi);
            uv.remove(bi);
            final List<Point2> pts = new ArrayList<>(left.size());
            for (final int c : left) {
                final double[] p = mask.cellCenterUv(c);
                pts.add(new Point2((float) p[0], (float) p[1]));
            }
            work.add(new CellChain(left, false));
            uv.add(pts);
        }
        return work;
    }

    private static Point2 sub(final Point2 a, final Point2 b) {
        return new Point2(a.x() - b.x(), a.y() - b.y());
    }

    private static double dot(final Point2 a, final Point2 b) {
        return dot(a, b.x(), b.y());
    }

    private static double dot(final Point2 t, final double dx, final double dy) {
        final double lt = Math.hypot(t.x(), t.y());
        final double lc = Math.hypot(dx, dy);
        if (lt < 1e-12 || lc < 1e-12) {
            return 0;
        }
        return (t.x() * dx + t.y() * dy) / (lt * lc);
    }

    /**
     * Douglas–Peucker over an open chain; closed chains are simplified on their loop.
     *
     * <p>Shared by domain callers that carry pixel-space polylines, because float coordinates
     * stay far below every placement tolerance in use.</p>
     */
    public static List<Point2> douglasPeucker(final List<Point2> pts, final double tol, final boolean closed) {
        if (pts.size() <= 2) {
            return new ArrayList<>(pts);
        }
        final List<Point2> work = new ArrayList<>(pts);
        if (closed) {
            // Start the open-chain simplification at the vertex farthest from vertex 0.
            double best = -1;
            int pivot = 0;
            for (int i = 1; i < pts.size(); i++) {
                final double d = Math.hypot(
                        pts.get(i).x() - pts.get(0).x(),
                        pts.get(i).y() - pts.get(0).y());
                if (d > best) {
                    best = d;
                    pivot = i;
                }
            }
            work.clear();
            for (int i = 0; i <= pts.size(); i++) {
                work.add(pts.get((pivot + i) % pts.size()));
            }
        }
        final boolean[] keep = new boolean[work.size()];
        keep[0] = true;
        keep[work.size() - 1] = true;
        final Deque<int[]> stack = new ArrayDeque<>();
        stack.push(new int[] {0, work.size() - 1});
        while (!stack.isEmpty()) {
            final int[] span = stack.pop();
            final Point2 a = work.get(span[0]);
            final Point2 b = work.get(span[1]);
            double worst = -1;
            int wi = -1;
            for (int i = span[0] + 1; i < span[1]; i++) {
                final double d = distToSeg(work.get(i), a, b);
                if (d > worst) {
                    worst = d;
                    wi = i;
                }
            }
            if (worst > tol && wi > 0) {
                keep[wi] = true;
                stack.push(new int[] {span[0], wi});
                stack.push(new int[] {wi, span[1]});
            }
        }
        final List<Point2> out = new ArrayList<>();
        for (int i = 0; i < work.size(); i++) {
            if (keep[i]) {
                out.add(work.get(i));
            }
        }
        if (closed && out.size() > 1) {
            out.remove(out.size() - 1); // duplicated loop start
        }
        return out;
    }

    /**
     * Resample a simplified chain: cap each emitted segment at maxBoundarySpacingUv (halving
     * recursively) and enforce minSpacingUv between consecutive emitted points.
     */
    private static List<Point2> resampleChain(final List<Point2> pts, final boolean closed, final PlacementSettings s) {
        final double maxGap = s.maxBoundarySpacingUv();
        final double minGap = s.minSpacingUv();
        final List<Point2> out = new ArrayList<>();
        final int segments = closed ? pts.size() : pts.size() - 1;
        for (int i = 0; i < segments; i++) {
            final Point2 a = pts.get(i);
            final Point2 b = pts.get((i + 1) % pts.size());
            if (out.isEmpty()) {
                out.add(a);
            }
            final double len = Math.hypot(b.x() - a.x(), b.y() - a.y());
            final int parts = Math.max(1, (int) Math.ceil(len / maxGap));
            for (int k = 1; k <= parts; k++) {
                final double t = (double) k / parts;
                final Point2 p = k == parts && !closed
                        ? b
                        : new Point2((float) (a.x() + t * (b.x() - a.x())), (float) (a.y() + t * (b.y() - a.y())));
                final Point2 last = out.get(out.size() - 1);
                if (Math.hypot(p.x() - last.x(), p.y() - last.y()) < minGap && k < parts) {
                    continue; // too dense — skip the interior sample
                }
                out.add(p);
            }
        }
        if (closed && out.size() > 1) {
            final Point2 first = out.get(0);
            final Point2 last = out.get(out.size() - 1);
            if (Math.hypot(first.x() - last.x(), first.y() - last.y()) < minGap) {
                out.remove(out.size() - 1);
            }
        }
        return out;
    }

    private static double distToSeg(final Point2 p, final Point2 a, final Point2 b) {
        final double ex = b.x() - a.x();
        final double ey = b.y() - a.y();
        final double len2 = ex * ex + ey * ey;
        if (len2 < 1.0e-18) {
            return Math.hypot(p.x() - a.x(), p.y() - a.y());
        }
        double t = ((p.x() - a.x()) * ex + (p.y() - a.y()) * ey) / len2;
        t = Math.max(0.0, Math.min(1.0, t));
        return Math.hypot(p.x() - (a.x() + t * ex), p.y() - (a.y() + t * ey));
    }
}
