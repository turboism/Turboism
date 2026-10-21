package dev.turboism.plugin.atlasnesting.layout;

import java.util.Arrays;

/** Row-major bit grid. Positions and dimensions are in cells, not page pixels. */
final class BitMask {
    final int width;
    final int height;
    final int stride;
    final long[] words;
    private int[] probeRows;
    private short[] runStarts;
    private RowRuns[] freeRows;
    private int[] sameRowEnds;

    private record RowRuns(int[] starts, int[] ends, int maximum) {}

    int anchorX;
    int anchorY;
    int anchorLength;
    int[] filterX;
    int[] filterY;
    int[] filterLength;

    BitMask(final int width, final int height) {
        if (width <= 0 || height <= 0 || (long) ((width + 63L) / 64) * height > 16_777_216) {
            throw new IllegalArgumentException("Mask dimensions exceed the packing memory limit");
        }
        this.width = width;
        this.height = height;
        stride = (width + 63) >>> 6;
        words = new long[stride * height];
    }

    void span(final int y, final int from, final int to) {
        final int lo = Math.max(0, from), hi = Math.min(width - 1, to);
        if (y < 0 || y >= height || lo > hi) return;
        if (freeRows != null) freeRows[y] = null;
        sameRowEnds = null;
        final int first = lo >>> 6, last = hi >>> 6;
        final long left = -1L << (lo & 63), right = -1L >>> (63 - (hi & 63));
        if (first == last) {
            words[y * stride + first] |= left & right;
        } else {
            words[y * stride + first] |= left;
            Arrays.fill(words, y * stride + first + 1, y * stride + last, -1L);
            words[y * stride + last] |= right;
        }
    }

    boolean get(final int x, final int y) {
        return (words[y * stride + (x >>> 6)] & (1L << (x & 63))) != 0;
    }

    void prepare() {
        runStarts = width <= 65535 ? new short[Math.multiplyExact(width, height)] : null;
        final Integer[] rows = new Integer[height];
        final int[] counts = new int[height];
        final int[] longestX = new int[height], longestLength = new int[height];
        for (int y = 0; y < height; y++) {
            rows[y] = y;
            for (int w = 0; w < stride; w++) counts[y] += Long.bitCount(words[y * stride + w]);
            for (int x = next(y, 0, true); x < width; ) {
                final int end = next(y, x, false);
                if (runStarts != null) Arrays.fill(runStarts, y * width + x, y * width + end, (short) x);
                if (end - x > anchorLength) {
                    anchorLength = end - x;
                    anchorX = x;
                    anchorY = y;
                }
                if (end - x > longestLength[y]) {
                    longestLength[y] = end - x;
                    longestX[y] = x;
                }
                x = next(y, end, true);
            }
        }
        Arrays.sort(rows, (a, b) -> {
            final int c = Integer.compare(counts[b], counts[a]);
            return c == 0 ? Integer.compare(a, b) : c;
        });
        probeRows = Arrays.stream(rows).mapToInt(Integer::intValue).toArray();
        // Widely separated solid spans cheaply rule out candidate positions.
        // Each is a subset of the original mask, so rejected x ranges cannot fit.
        final int count = Math.min(4, height);
        filterX = new int[count];
        filterY = new int[count];
        filterLength = new int[count];
        for (int i = 0; i < count; i++) {
            final int from = i * height / count, to = (i + 1) * height / count;
            int row = from;
            for (int r = from + 1; r < to; r++) if (longestLength[r] > longestLength[row]) row = r;
            filterY[i] = row;
            filterX[i] = longestX[row];
            filterLength[i] = longestLength[row];
        }
    }

    int filterSkip(final BitMask item, final int x, final int y, final int xMax) {
        int skip = 0;
        for (int i = 0; i < item.filterY.length; i++) {
            final int length = item.filterLength[i];
            if (length == 0 || item.filterY[i] == item.anchorY) continue;
            final int found =
                    clearRun(y + item.filterY[i], x + item.filterX[i], xMax + item.filterX[i] + length - 1, length);
            if (found < 0) return xMax - x + 1;
            skip = Math.max(skip, found - item.filterX[i] - x);
        }
        return skip;
    }

    /** Inclusive last row with exactly the same occupied bits as this row. */
    int sameRowEnd(final int row) {
        if (sameRowEnds == null) {
            sameRowEnds = new int[height];
            sameRowEnds[height - 1] = height - 1;
            for (int y = height - 2; y >= 0; y--) {
                sameRowEnds[y] =
                        Arrays.equals(words, y * stride, (y + 1) * stride, words, (y + 1) * stride, (y + 2) * stride)
                                ? sameRowEnds[y + 1]
                                : y;
            }
        }
        return sameRowEnds[row];
    }

    /** First free horizontal run of at least length, clipped to an inclusive limit. */
    int clearRun(final int y, final int start, final int limit, final int length) {
        final RowRuns runs = freeRuns(y);
        if (runs.maximum() < length) return -1;
        int lo = 0, hi = runs.ends().length;
        while (lo < hi) {
            final int mid = (lo + hi) >>> 1;
            if (runs.ends()[mid] <= start) lo = mid + 1;
            else hi = mid;
        }
        for (int i = lo; i < runs.starts().length; i++) {
            final int x = Math.max(start, runs.starts()[i]);
            if (x + length - 1 > limit) return -1;
            if (runs.ends()[i] - x >= length) return x;
        }
        return -1;
    }

    private RowRuns freeRuns(final int y) {
        if (freeRows == null) freeRows = new RowRuns[height];
        if (freeRows[y] != null) return freeRows[y];
        final int endRow = sameRowEnd(y);
        if (freeRows[endRow] != null) {
            Arrays.fill(freeRows, y, endRow, freeRows[endRow]);
            return freeRows[y];
        }
        int[] starts = new int[16], ends = new int[16];
        int count = 0, maximum = 0;
        for (int x = next(y, 0, false); x < width; ) {
            final int end = next(y, x, true);
            if (count == starts.length) {
                starts = Arrays.copyOf(starts, count * 2);
                ends = Arrays.copyOf(ends, count * 2);
            }
            starts[count] = x;
            ends[count++] = end;
            maximum = Math.max(maximum, end - x);
            x = next(y, end, false);
        }
        final RowRuns result = new RowRuns(Arrays.copyOf(starts, count), Arrays.copyOf(ends, count), maximum);
        Arrays.fill(freeRows, y, endRow + 1, result);
        return result;
    }

    private int next(final int y, final int start, final boolean occupied) {
        if (start >= width) return width;
        int w = start >>> 6;
        long bits = occupied ? words[y * stride + w] : ~words[y * stride + w];
        bits &= -1L << (start & 63);
        while (bits == 0 && w + 1 < stride) {
            w++;
            bits = occupied ? words[y * stride + w] : ~words[y * stride + w];
        }
        return bits == 0 ? width : Math.min(width, w * 64 + Long.numberOfTrailingZeros(bits));
    }

    /**
     * Zero means no collision. A positive result is a PROVEN lower bound on the
     * horizontal move needed to clear one intersecting pair of solid runs. Unlike
     * a coarse occupancy heuristic it never skips a feasible integer position.
     */
    int collisionSkip(final BitMask item, final int x, final int y) {
        if (x < 0 || y < 0 || x + item.width > width || y + item.height > height) {
            throw new IllegalArgumentException("Collision query outside canvas");
        }
        final int shift = x & 63, baseWord = x >>> 6;
        for (int rowIndex = 0; rowIndex < item.height; rowIndex++) {
            final int row = item.probeRows == null ? rowIndex : item.probeRows[rowIndex];
            final int target = (y + row) * stride + baseWord;
            for (int w = 0; w < item.stride; w++) {
                final long bits = item.words[row * item.stride + w];
                if (bits == 0) continue;
                long hit = words[target + w] & (bits << shift);
                int cell = (baseWord + w) * 64;
                if (hit == 0 && shift != 0 && (bits >>> (64 - shift)) != 0) {
                    hit = words[target + w + 1] & (bits >>> (64 - shift));
                    cell += 64;
                }
                if (hit != 0) {
                    cell += Long.numberOfTrailingZeros(hit);
                    final int local = cell - x;
                    return runEnd(cell, y + row) - item.runStart(local, row) - x + 1;
                }
            }
        }
        return 0;
    }

    private int runStart(final int x, final int y) {
        if (runStarts != null) return Short.toUnsignedInt(runStarts[y * width + x]);
        int w = x >>> 6;
        final int offset = y * stride;
        long zeros = ~words[offset + w] & (-1L >>> (63 - (x & 63)));
        while (zeros == 0 && w > 0) zeros = ~words[offset + --w];
        return zeros == 0 ? 0 : w * 64 + 64 - Long.numberOfLeadingZeros(zeros);
    }

    private int runEnd(final int x, final int y) {
        if (freeRows != null) {
            final int[] starts = freeRuns(y).starts();
            final int found = Arrays.binarySearch(starts, x);
            final int next = found >= 0 ? found : -found - 1;
            return next < starts.length ? starts[next] - 1 : width - 1;
        }
        int w = x >>> 6;
        final int offset = y * stride;
        long zeros = ~words[offset + w] & (-1L << (x & 63));
        while (zeros == 0 && w + 1 < stride) zeros = ~words[offset + ++w];
        return zeros == 0 ? width - 1 : Math.min(width, w * 64 + Long.numberOfTrailingZeros(zeros)) - 1;
    }

    void add(final BitMask item, final int x, final int y) {
        sameRowEnds = null;
        final int shift = x & 63, base = x >>> 6;
        for (int row = 0; row < item.height; row++) {
            if (freeRows != null) freeRows[y + row] = null;
            final int target = (y + row) * stride + base;
            for (int w = 0; w < item.stride; w++) {
                final long bits = item.words[row * item.stride + w];
                words[target + w] |= bits << shift;
                if (shift != 0 && (bits >>> (64 - shift)) != 0) {
                    words[target + w + 1] |= bits >>> (64 - shift);
                }
            }
        }
    }
}
