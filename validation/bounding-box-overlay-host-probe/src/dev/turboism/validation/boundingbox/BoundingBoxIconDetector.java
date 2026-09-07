package dev.turboism.validation.boundingbox;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic screen-image detector for the two validation-only overlay icons.
 *
 * <p>The probe plugin contributes exactly two bounding-box overlay buttons whose
 * 16x16 PNG icons are solid magenta ({@code #FF00FF}) and solid cyan
 * ({@code #00FFFF}). On the exact host they are laid out in one column, button A
 * above button B, on a native 40-unit vertical step: the established 5.2.03
 * diagnostic screenshot shows A/B at 24x23 pixels with centers 40 px apart
 * (same column, {@code dx=0}). The detector finds connected components of pixels
 * within a bounded per-channel tolerance of each color, accepts only components
 * whose bounding box lies in a bounded size band (covers bounded DPI scaling of
 * the icon) with a roughly square aspect ratio, and then requires exactly one
 * accepted candidate per icon whose centers form a vertically aligned pair in
 * forward order (A above B, same column, bounded vertical separation). Any other
 * outcome fails closed with a reason; the probe never clicks guessed or
 * ambiguous coordinates.</p>
 */
public final class BoundingBoxIconDetector {

    /** Pure magenta: button A icon color (RGB, alpha ignored). */
    public static final int COLOR_A = 0xFF00FF;
    /** Pure cyan: button B icon color (RGB, alpha ignored). */
    public static final int COLOR_B = 0x00FFFF;

    /** Bounded per-channel tolerance applied to every pixel of a candidate. */
    public static final int TOLERANCE = 30;
    /** Accepted candidate side range (pixels); 16px icon at DPI 1x-2x plus slop. */
    public static final int MIN_SIDE = 8;
    public static final int MAX_SIDE = 64;
    /** Accepted candidate aspect ratio range (width/height). */
    public static final double MIN_RATIO = 0.5;
    public static final double MAX_RATIO = 2.0;
    /** Pair alignment: both centers must lie in the same overlay column. */
    public static final int MAX_PAIR_DX = 12;
    /**
     * Pair separation: forward vertical order (A above B) with a bounded step.
     * The exact observed host layout places the two buttons 40 px apart on a
     * native 40-unit step; the band spans half to double that step (20..80 px)
     * so a bounded DPI range of the same layout stays admissible, while overlap
     * or near-touch (too small) and unpaired spurious blobs (too large) fail
     * closed.
     */
    public static final int MIN_PAIR_DY = 20;
    public static final int MAX_PAIR_DY = 80;
    /** The two buttons must be the same size within this per-side delta. */
    public static final int MAX_SIZE_DELTA = 12;
    /** Fail-fast ceiling for accepted components on a pathological screen. */
    public static final int MAX_ACCEPTED_COMPONENTS = 256;
    /** Margin (px) around the pair union bounding box for the absence context band. */
    public static final int CONTEXT_MARGIN = 24;
    /** Minimum fraction of compared surrounding pixels that must stay within tolerance. */
    public static final double CONTEXT_MATCH_MIN_FRACTION = 0.95;
    /** Sentinel stored at masked-out icon positions inside a context band. */
    public static final int CONTEXT_EXCLUDED = Integer.MIN_VALUE;

    private BoundingBoxIconDetector() {
    }

    /** One accepted icon candidate: centroid plus bounding-box size. */
    public record Center(int x, int y, int width, int height) {
    }

    /** The unique accepted pair: button A then button B (forward vertical order). */
    public record Pair(Center a, Center b) {
    }

    /**
     * Detection outcome. {@code pair} is non-null only when exactly one accepted
     * candidate exists per icon and the pair checks pass; otherwise {@code reason}
     * describes the fail-closed cause and the counts record how many accepted
     * candidates were found per color.
     */
    public record Result(int aCount, int bCount, Pair pair, String reason) {

        static Result pair(final Pair value) {
            return new Result(1, 1, value, null);
        }

        static Result fail(final int aCount, final int bCount, final String reason) {
            return new Result(aCount, bCount, null, reason);
        }
    }

    /**
     * Bounded snapshot of the screen around the detected pair with the two icon
     * boxes masked out. Binds post-close absence to the pre-click context: an
     * occluding or changed window cannot satisfy detach because the surrounding
     * pixels must stay within tolerance of this baseline.
     */
    public record ContextRegion(int originX, int originY, int width, int height, int[] rgb) {

        public ContextRegion {
            if (width <= 0 || height <= 0 || rgb.length != width * height) {
                throw new IllegalArgumentException("invalid absence context region");
            }
        }
    }

    /**
     * Detects the unique vertical pair, or fails closed. Absence of both icons is
     * reported as a {@code reason} of {@code "absent"} with both counts zero.
     */
    public static Result detect(final BufferedImage image) {
        final List<Center> aCandidates = candidates(image, COLOR_A);
        final List<Center> bCandidates = candidates(image, COLOR_B);
        if (aCandidates.size() == 1 && bCandidates.size() == 1) {
            final Center a = aCandidates.get(0);
            final Center b = bCandidates.get(0);
            final String pairFailure = pairFailure(a, b);
            if (pairFailure == null) {
                return Result.pair(new Pair(a, b));
            }
            return Result.fail(1, 1, pairFailure);
        }
        if (aCandidates.isEmpty() && bCandidates.isEmpty()) {
            return Result.fail(0, 0, "absent");
        }
        return Result.fail(
            aCandidates.size(),
            bCandidates.size(),
            "candidate-counts a=" + aCandidates.size() + " b=" + bCandidates.size()
        );
    }

    /**
     * Captures the bounded band around the pair, masking out both icon boxes,
     * from the last positive frame. The band is bounded by the detector's own
     * pair bounds (dy ≤ 80, sides ≤ 64) plus the context margin.
     */
    public static ContextRegion captureContext(final BufferedImage image, final Pair pair) {
        final int aLeft = pair.a().x() - pair.a().width() / 2;
        final int aTop = pair.a().y() - pair.a().height() / 2;
        final int aRight = aLeft + pair.a().width();
        final int aBottom = aTop + pair.a().height();
        final int bLeft = pair.b().x() - pair.b().width() / 2;
        final int bTop = pair.b().y() - pair.b().height() / 2;
        final int bRight = bLeft + pair.b().width();
        final int bBottom = bTop + pair.b().height();
        final int left = Math.max(0, Math.min(aLeft, bLeft) - CONTEXT_MARGIN);
        final int top = Math.max(0, Math.min(aTop, bTop) - CONTEXT_MARGIN);
        final int right = Math.min(image.getWidth(), Math.max(aRight, bRight) + CONTEXT_MARGIN);
        final int bottom = Math.min(image.getHeight(), Math.max(aBottom, bBottom) + CONTEXT_MARGIN);
        final int width = right - left;
        final int height = bottom - top;
        final int[] rgb = new int[width * height];
        int index = 0;
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                final boolean inA = x >= aLeft && x < aRight && y >= aTop && y < aBottom;
                final boolean inB = x >= bLeft && x < bRight && y >= bTop && y < bBottom;
                rgb[index++] = (inA || inB) ? CONTEXT_EXCLUDED : image.getRGB(x, y);
            }
        }
        return new ContextRegion(left, top, width, height, rgb);
    }

    /**
     * True when a sufficient fraction of the non-excluded surrounding pixels of
     * the current frame still match the baseline within the bounded tolerance;
     * false on occlusion or any other context change.
     */
    public static boolean contextMatches(final BufferedImage image, final ContextRegion region) {
        long compared = 0;
        long matched = 0;
        for (int dy = 0; dy < region.height(); dy++) {
            final int y = region.originY() + dy;
            if (y < 0 || y >= image.getHeight()) {
                continue;
            }
            for (int dx = 0; dx < region.width(); dx++) {
                final int x = region.originX() + dx;
                if (x < 0 || x >= image.getWidth()) {
                    continue;
                }
                final int expected = region.rgb()[dy * region.width() + dx];
                if (expected == CONTEXT_EXCLUDED) {
                    continue;
                }
                compared++;
                if (matches(image.getRGB(x, y), expected)) {
                    matched++;
                }
            }
        }
        return compared > 0 && (double) matched / compared >= CONTEXT_MATCH_MIN_FRACTION;
    }

    /**
     * Fail-closed vertical pair contract: forward order (B strictly below A),
     * same-column alignment within the bounded side band, bounded vertical
     * separation around the native 40-unit step, and comparable component sizes.
     */
    private static String pairFailure(final Center a, final Center b) {
        final int dx = b.x() - a.x();
        final int dy = b.y() - a.y();
        if (dy <= 0) {
            return "pair-reversed dy=" + dy;
        }
        if (Math.abs(dx) > MAX_PAIR_DX) {
            return "pair-misaligned dx=" + dx + " limit=" + MAX_PAIR_DX;
        }
        if (dy < MIN_PAIR_DY || dy > MAX_PAIR_DY) {
            return "pair-spacing dy=" + dy + " bounds=" + MIN_PAIR_DY + ".." + MAX_PAIR_DY;
        }
        if (Math.abs(a.width() - b.width()) > MAX_SIZE_DELTA
            || Math.abs(a.height() - b.height()) > MAX_SIZE_DELTA) {
            return "pair-size-mismatch a=" + a.width() + "x" + a.height()
                + " b=" + b.width() + "x" + b.height();
        }
        return null;
    }

    /**
     * Accepted connected components of pixels matching the target color within
     * tolerance: bounded square-ish blobs only, in stable raster order.
     */
    private static List<Center> candidates(final BufferedImage image, final int color) {
        final int width = image.getWidth();
        final int height = image.getHeight();
        final int[] rgb = image.getRGB(0, 0, width, height, null, 0, width);
        final byte[] visited = new byte[rgb.length];
        final List<Center> accepted = new ArrayList<>();
        final int[] queueX = new int[rgb.length];
        final int[] queueY = new int[rgb.length];
        for (int y = 0; y < height && accepted.size() <= MAX_ACCEPTED_COMPONENTS; y++) {
            for (int x = 0; x < width; x++) {
                final int index = y * width + x;
                if (visited[index] != 0 || !matches(rgb[index], color)) {
                    continue;
                }
                int head = 0;
                int tail = 0;
                queueX[tail] = x;
                queueY[tail] = y;
                tail++;
                visited[index] = 1;
                int minX = x;
                int maxX = x;
                int minY = y;
                int maxY = y;
                long sumX = 0;
                long sumY = 0;
                long count = 0;
                while (head < tail) {
                    final int cx = queueX[head];
                    final int cy = queueY[head];
                    head++;
                    sumX += cx;
                    sumY += cy;
                    count++;
                    if (cx < minX) {
                        minX = cx;
                    }
                    if (cx > maxX) {
                        maxX = cx;
                    }
                    if (cy < minY) {
                        minY = cy;
                    }
                    if (cy > maxY) {
                        maxY = cy;
                    }
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            if (dx == 0 && dy == 0) {
                                continue;
                            }
                            final int nx = cx + dx;
                            final int ny = cy + dy;
                            if (nx < 0 || nx >= width || ny < 0 || ny >= height) {
                                continue;
                            }
                            final int neighbor = ny * width + nx;
                            if (visited[neighbor] != 0 || !matches(rgb[neighbor], color)) {
                                continue;
                            }
                            visited[neighbor] = 1;
                            queueX[tail] = nx;
                            queueY[tail] = ny;
                            tail++;
                        }
                    }
                }
                final int componentWidth = maxX - minX + 1;
                final int componentHeight = maxY - minY + 1;
                if (!acceptedSize(componentWidth, componentHeight)) {
                    continue;
                }
                accepted.add(new Center(
                    (int) (sumX / count),
                    (int) (sumY / count),
                    componentWidth,
                    componentHeight
                ));
            }
        }
        return accepted;
    }

    private static boolean acceptedSize(final int width, final int height) {
        if (width < MIN_SIDE || width > MAX_SIDE || height < MIN_SIDE || height > MAX_SIDE) {
            return false;
        }
        final double ratio = (double) width / height;
        return ratio >= MIN_RATIO && ratio <= MAX_RATIO;
    }

    /** Channel-wise RGB match within the bounded tolerance; alpha is ignored. */
    private static boolean matches(final int pixel, final int color) {
        final int pr = (pixel >> 16) & 0xFF;
        final int pg = (pixel >> 8) & 0xFF;
        final int pb = pixel & 0xFF;
        final int cr = (color >> 16) & 0xFF;
        final int cg = (color >> 8) & 0xFF;
        final int cb = color & 0xFF;
        return Math.abs(pr - cr) <= TOLERANCE
            && Math.abs(pg - cg) <= TOLERANCE
            && Math.abs(pb - cb) <= TOLERANCE;
    }
}
