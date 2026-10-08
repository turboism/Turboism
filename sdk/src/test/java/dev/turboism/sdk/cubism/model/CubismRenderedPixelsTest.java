package dev.turboism.sdk.cubism.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CubismRenderedPixelsTest {

    @Test
    void constructorCopiesInputAndAccessorsCopyOutput() {
        final int[] source = new int[6];
        source[3] = 0xFFABCDEF;
        final CubismRenderedPixels pixels = new CubismRenderedPixels(3, 2, source);
        source[3] = 0;
        assertEquals(0xFFABCDEF, pixels.argbAt(0, 1));
        final int[] out = pixels.argb();
        assertNotSame(source, out);
        out[3] = 0;
        assertEquals(0xFFABCDEF, pixels.argbAt(0, 1));
        assertArrayEquals(new int[]{0, 0, 0, 0xFFABCDEF, 0, 0}, pixels.argb());
    }

    @Test
    void rejectsInvalidDimensionsAndLength() {
        final int[] four = new int[4];
        assertThrows(IllegalArgumentException.class,
            () -> new CubismRenderedPixels(0, 2, new int[0]));
        assertThrows(IllegalArgumentException.class,
            () -> new CubismRenderedPixels(2, 0, new int[0]));
        assertThrows(IllegalArgumentException.class,
            () -> new CubismRenderedPixels(2049, 1, new int[2049]));
        assertThrows(IllegalArgumentException.class,
            () -> new CubismRenderedPixels(2, 2, new int[3]));
        assertThrows(IllegalArgumentException.class,
            () -> new CubismRenderedPixels(2, 2, new int[5]));
        assertThrows(NullPointerException.class,
            () -> new CubismRenderedPixels(2, 2, null));
    }

    @Test
    void rejectsPixelBudgetOverflow() {
        assertThrows(IllegalArgumentException.class,
            () -> new CubismRenderedPixels(2048, 2049, new int[0]));
        // 2048x2048 = 4_194_304 is the accepted maximum.
        new CubismRenderedPixels(2048, 2048, new int[4_194_304]);
    }

    @Test
    void argbAtIsStrictlyBounded() {
        final CubismRenderedPixels pixels = new CubismRenderedPixels(2, 2, new int[4]);
        assertThrows(IndexOutOfBoundsException.class, () -> pixels.argbAt(-1, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> pixels.argbAt(2, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> pixels.argbAt(0, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> pixels.argbAt(0, -1));
    }
}
