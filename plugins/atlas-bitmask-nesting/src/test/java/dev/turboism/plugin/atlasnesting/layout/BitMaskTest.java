package dev.turboism.plugin.atlasnesting.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class BitMaskTest {
    @Test
    void shiftedWordCollisionAndEverySkippedPositionMatchExhaustiveOracle() {
        final Random random = new Random(5019);
        for (int sample = 0; sample < 90; sample++) {
            final int width = new int[] {63, 64, 65, 127, 128, 129, 257}[sample % 7];
            final BitMask board = new BitMask(width, 18);
            final BitMask item = new BitMask(1 + random.nextInt(Math.min(width, 95)), 1 + random.nextInt(8));
            for (int i = 0; i < 25; i++) {
                final int x = random.nextInt(width);
                board.span(random.nextInt(board.height), x, x + random.nextInt(15));
            }
            for (int i = 0; i < 15; i++) {
                final int x = random.nextInt(item.width);
                item.span(random.nextInt(item.height), x, x + random.nextInt(13));
            }
            item.prepare();
            for (int y = 0; y < board.height; y++) {
                for (int length = 1; length <= Math.min(30, width); length++) {
                    int expectedRun = -1;
                    for (int x = 2; x + length <= width; x++) {
                        boolean clear = true;
                        for (int d = 0; d < length; d++) clear &= !board.get(x + d, y);
                        if (clear) {
                            expectedRun = x;
                            break;
                        }
                    }
                    assertEquals(expectedRun, board.clearRun(y, 2, width - 1, length));
                }
            }
            for (int y = 0; y + item.height <= board.height; y++) {
                for (int x = 0; x + item.width <= board.width; x++) {
                    final int skip = board.collisionSkip(item, x, y);
                    final int filtered = board.filterSkip(item, x, y, board.width - item.width);
                    for (int dx = 0; dx < filtered && x + dx + item.width <= width; dx++) {
                        assertTrue(overlap(board, item, x + dx, y), "filter crossed a feasible position");
                    }
                    assertEquals(overlap(board, item, x, y), skip != 0, "sample=" + sample + " x=" + x);
                    for (int dx = 1; dx < skip && x + dx + item.width <= width; dx++) {
                        assertTrue(overlap(board, item, x + dx, y), "skip crossed a feasible position");
                    }
                }
            }
            final int px = random.nextInt(width - item.width + 1), py = 3;
            final boolean[][] expected = new boolean[board.height][width];
            for (int y = 0; y < board.height; y++) {
                for (int x = 0; x < width; x++) {
                    expected[y][x] = board.get(x, y)
                            || (x >= px
                                    && x < px + item.width
                                    && y >= py
                                    && y < py + item.height
                                    && item.get(x - px, y - py));
                }
            }
            board.add(item, px, py);
            for (int y = 0; y < board.height; y++) {
                for (int x = 0; x < width; x++) assertEquals(expected[y][x], board.get(x, y));
                final int length = 1 + random.nextInt(Math.min(30, width));
                int first = -1;
                for (int x = 0; x + length <= width; x++) {
                    boolean clear = true;
                    for (int dx = 0; dx < length; dx++) clear &= !expected[y][x + dx];
                    if (clear) {
                        first = x;
                        break;
                    }
                }
                assertEquals(first, board.clearRun(y, 0, width - 1, length), "cached row after add");
                int last = y;
                while (last + 1 < board.height && java.util.Arrays.equals(expected[y], expected[last + 1])) last++;
                assertEquals(last, board.sameRowEnd(y), "row group after add");
            }
        }
    }

    private static boolean overlap(final BitMask board, final BitMask item, final int x, final int y) {
        for (int iy = 0; iy < item.height; iy++) {
            for (int ix = 0; ix < item.width; ix++) if (item.get(ix, iy) && board.get(x + ix, y + iy)) return true;
        }
        return false;
    }
}
