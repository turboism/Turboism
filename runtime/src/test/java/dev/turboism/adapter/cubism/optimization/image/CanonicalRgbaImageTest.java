package dev.turboism.adapter.cubism.optimization.image;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.AlphaComposite;
import java.awt.Point;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.awt.image.PixelInterleavedSampleModel;
import java.awt.image.Raster;
import java.awt.image.SinglePixelPackedSampleModel;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

class CanonicalRgbaImageTest {
    @Test
    void exhaustiveChannelsAndAlphaMatchOriginalSrcConversion() {
        for (int type : new int[] {BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_4BYTE_ABGR}) {
            BufferedImage source = new BufferedImage(256, 256, type);
            for (int a = 0; a < 256; a++)
                for (int r = 0; r < 256; r++) {
                    // Odd multipliers permute every green/blue value for every alpha.
                    source.setRGB(r, a, (a << 24) | (r << 16) | (((r * 61 + 17) & 255) << 8) | ((r * 37 + 91) & 255));
                }
            int[] before = source.getRGB(0, 0, 256, 256, null, 0, 256);
            BufferedImage prepared = CanonicalRgbaImage.prepare(source);
            assertNotNull(prepared);
            assertTrue(prepared.isAlphaPremultiplied());
            assertArrayEquals(reference(source), bytes(prepared));
            assertArrayEquals(before, source.getRGB(0, 0, 256, 256, null, 0, 256));
            BufferedImage again = CanonicalRgbaImage.prepare(source);
            assertNotSame(prepared, again);
            assertNotSame(bytes(prepared), bytes(again));
        }
    }

    @Test
    void translatedSubimageAndOddStrideRemainExact() {
        for (int type : new int[] {BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_4BYTE_ABGR}) {
            BufferedImage parent = new BufferedImage(311, 277, type);
            for (int y = 0; y < parent.getHeight(); y++)
                for (int x = 0; x < parent.getWidth(); x++) {
                    parent.setRGB(
                            x,
                            y,
                            ((x + y) & 255) << 24 | ((x * 17) & 255) << 16 | ((y * 29) & 255) << 8 | ((x * y) & 255));
                }
            BufferedImage child = parent.getSubimage(13, 9, 257, 256);
            BufferedImage prepared = CanonicalRgbaImage.prepare(child);
            assertNotNull(prepared);
            assertEquals(257, prepared.getWidth());
            assertEquals(256, prepared.getHeight());
            assertArrayEquals(reference(child), bytes(prepared));
        }
    }

    @Test
    void smallerAndThinTranslatedInputsRemainEquivalentAndIndependent() {
        for (int type : new int[] {BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_4BYTE_ABGR}) {
            for (int[] size : new int[][] {{64, 64}, {65, 64}, {1, 8192}, {8192, 1}, {4097, 17}, {255, 256}}) {
                int width = size[0], height = size[1];
                BufferedImage parent = new BufferedImage(width + 7, height + 3, type);
                BufferedImage source = parent.getSubimage(3, 2, width, height);
                for (int y = 0; y < height; y++)
                    for (int x = 0; x < width; x++) {
                        source.setRGB(
                                x,
                                y,
                                ((x + y) & 255) << 24
                                        | ((x * 17) & 255) << 16
                                        | ((y * 29) & 255) << 8
                                        | ((x * y) & 255));
                    }
                int[] before = source.getRGB(0, 0, width, height, null, 0, width);
                BufferedImage prepared = CanonicalRgbaImage.prepare(source);
                assertNotNull(prepared);
                assertArrayEquals(reference(source), bytes(prepared));
                assertArrayEquals(before, source.getRGB(0, 0, width, height, null, 0, width));
                assertNotSame(bytes(prepared), bytes(CanonicalRgbaImage.prepare(source)));
            }
        }
    }

    @Test
    void paddedOffsetBuffersAndNestedSubimagesRemainExact() {
        var random = new Random(0x52474241);
        for (int type : new int[] {BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_4BYTE_ABGR}) {
            for (int trial = 0; trial < 12; trial++) {
                int width = 80 + trial, height = 75 + trial, offset = trial % 2 == 0 ? 0 : 11 + trial;
                var color = new BufferedImage(1, 1, type).getColorModel();
                int stride = type == BufferedImage.TYPE_INT_ARGB ? width + 13 : width * 4 + 13;
                int size = stride * height;
                var data = type == BufferedImage.TYPE_INT_ARGB
                        ? new DataBufferInt(new int[size + offset + 17], size, offset)
                        : new DataBufferByte(new byte[size + offset + 17], size, offset);
                var model = type == BufferedImage.TYPE_INT_ARGB
                        ? new SinglePixelPackedSampleModel(DataBuffer.TYPE_INT, width, height, stride, new int[] {
                            0x00ff0000, 0x0000ff00, 0x000000ff, 0xff000000
                        })
                        : new PixelInterleavedSampleModel(
                                DataBuffer.TYPE_BYTE, width, height, 4, stride, new int[] {3, 2, 1, 0});
                var raster = Raster.createWritableRaster(model, data, new Point());
                var parent = new BufferedImage(color, raster, false, null);
                assertEquals(type, parent.getType());
                for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) parent.setRGB(x, y, random.nextInt());
                var source = parent.getSubimage(3, 2, width - 5, height - 4).getSubimage(4, 3, 64, 64);
                int[] before = parent.getRGB(0, 0, width, height, null, 0, width);
                // Compare with the former getRGB path: Java2D drawImage can interpret
                // nonzero DataBuffer offsets differently on these constructed rasters.
                var normalized = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
                normalized.setRGB(0, 0, 64, 64, source.getRGB(0, 0, 64, 64, null, 0, 64), 0, 64);
                assertArrayEquals(reference(normalized), bytes(CanonicalRgbaImage.prepare(source)));
                assertArrayEquals(before, parent.getRGB(0, 0, width, height, null, 0, width));
            }
        }
    }

    @Test
    void scalarBudgetIncludes8192SquareButRejectsOversizeAndOverflowWithoutAllocations() {
        assertTrue(CanonicalRgbaImage.supportedSize(8192, 8192));
        assertTrue(CanonicalRgbaImage.supportedSize(8192, 4097));
        assertTrue(CanonicalRgbaImage.supportedSize(4097, 8192));
        assertTrue(CanonicalRgbaImage.supportedSize(64, 64));
        assertTrue(CanonicalRgbaImage.supportedSize(1, 4096));
        assertFalse(CanonicalRgbaImage.supportedSize(63, 65));
        assertFalse(CanonicalRgbaImage.supportedSize(8193, 8192));
        assertFalse(CanonicalRgbaImage.supportedSize(8192, 8193));
        assertFalse(CanonicalRgbaImage.supportedSize(0, 8192));
        assertFalse(CanonicalRgbaImage.supportedSize(8192, -1));
        assertFalse(CanonicalRgbaImage.supportedSize(Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertEquals(256L * 1024 * 1024, 4L * 8192 * 8192);
    }

    @Test
    void newlyAdmittedLargeArgbAndAbgrMatchEveryReferenceByteWithoutMutatingSource() {
        // Just above the former 32-Mpixel limit. Full 8192-square behavior is additionally
        // covered by real-host shadow validation; do not require a >512 MiB unit-test heap.
        verifyLargeImage(BufferedImage.TYPE_INT_ARGB);
        verifyLargeImage(BufferedImage.TYPE_4BYTE_ABGR);
    }

    private static void verifyLargeImage(int type) {
        int width = 8192, height = 4097;
        var tile = new BufferedImage(64, 64, type);
        for (int y = 0; y < 64; y++)
            for (int x = 0; x < 64; x++)
                tile.setRGB(
                        x,
                        y,
                        (((x + y * 64) & 255) << 24)
                                | (((x * 61 + y) & 255) << 16)
                                | (((y * 37 + x) & 255) << 8)
                                | ((x * 17 + y * 29) & 255));
        int[] input = tile.getRGB(0, 0, 64, 64, null, 0, 64);
        byte[] expectedTile = reference(tile);
        var source = new BufferedImage(width, height, type);
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            for (int block = 0; block < width / 64; block++)
                System.arraycopy(input, (y & 63) * 64, row, block * 64, 64);
            source.setRGB(0, y, width, 1, row, 0, width);
        }
        var prepared = CanonicalRgbaImage.prepare(source);
        assertNotNull(prepared);
        assertTrue(prepared.isAlphaPremultiplied());
        byte[] actual = bytes(prepared), expectedRow = new byte[width * 4];
        assertEquals(4L * width * height, actual.length);
        for (int y = 0; y < height; y++) {
            for (int block = 0; block < width / 64; block++)
                System.arraycopy(expectedTile, (y & 63) * 256, expectedRow, block * 256, 256);
            int position = y * width * 4;
            assertEquals(
                    -1,
                    Arrays.mismatch(
                            expectedRow, 0, expectedRow.length, actual, position, position + expectedRow.length));
            source.getRGB(0, y, width, 1, row, 0, width);
            for (int x = 0; x < width; x++) if (row[x] != input[(y & 63) * 64 + (x & 63)]) fail("large source mutated");
        }
    }

    @Test
    void unsupportedTypesSizesAndSubclassesFallThroughWithoutForeignCalls() {
        assertNull(CanonicalRgbaImage.prepare(null));
        assertNull(CanonicalRgbaImage.prepare(new BufferedImage(63, 65, BufferedImage.TYPE_INT_ARGB)));
        assertNull(CanonicalRgbaImage.prepare(new BufferedImage(8193, 1, BufferedImage.TYPE_INT_ARGB)));
        assertNull(CanonicalRgbaImage.prepare(new BufferedImage(1, 8193, BufferedImage.TYPE_INT_ARGB)));
        assertNull(CanonicalRgbaImage.prepare(new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB_PRE)));
        assertNull(CanonicalRgbaImage.prepare(new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB)));
        assertNull(CanonicalRgbaImage.prepare(new BufferedImage(256, 256, BufferedImage.TYPE_BYTE_GRAY)));
        assertNull(CanonicalRgbaImage.prepare(new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB) {
            @Override
            public int getType() {
                throw new AssertionError("foreign image must not be queried");
            }
        }));
    }

    private static byte[] reference(BufferedImage source) {
        // Exactly the reviewed JOGL custom-conversion ColorModel and Src drawing operation.
        var model = new ComponentColorModel(
                ColorSpace.getInstance(ColorSpace.CS_sRGB),
                new int[] {8, 8, 8, 8},
                true,
                true,
                Transparency.TRANSLUCENT,
                DataBuffer.TYPE_BYTE);
        var target = new BufferedImage(
                model, model.createCompatibleWritableRaster(source.getWidth(), source.getHeight()), true, null);
        var graphics = target.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return bytes(target);
    }

    private static byte[] bytes(BufferedImage image) {
        return ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
    }
}
