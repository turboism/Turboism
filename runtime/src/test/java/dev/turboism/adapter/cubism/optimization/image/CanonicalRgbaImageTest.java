package dev.turboism.adapter.cubism.optimization.image;

import java.awt.AlphaComposite;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CanonicalRgbaImageTest {
    @Test void exhaustiveChannelsAndAlphaMatchOriginalSrcConversion() {
        for (int type : new int[]{BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_4BYTE_ABGR}) {
            BufferedImage source = new BufferedImage(256, 256, type);
            for (int a = 0; a < 256; a++) for (int r = 0; r < 256; r++) {
                // Odd multipliers permute every green/blue value for every alpha.
                source.setRGB(r, a, (a << 24) | (r << 16) | (((r * 61 + 17) & 255) << 8) | ((r * 37 + 91) & 255));
            }
            int[] before = source.getRGB(0,0,256,256,null,0,256);
            BufferedImage prepared = CanonicalRgbaImage.prepare(source);
            assertNotNull(prepared);
            assertTrue(prepared.isAlphaPremultiplied());
            assertArrayEquals(reference(source), bytes(prepared));
            assertArrayEquals(before, source.getRGB(0,0,256,256,null,0,256));
            BufferedImage again = CanonicalRgbaImage.prepare(source);
            assertNotSame(prepared, again);
            assertNotSame(bytes(prepared), bytes(again));
        }
    }

    @Test void translatedSubimageAndOddStrideRemainExact() {
        for (int type : new int[]{BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_4BYTE_ABGR}) {
            BufferedImage parent = new BufferedImage(311, 277, type);
            for (int y = 0; y < parent.getHeight(); y++) for (int x = 0; x < parent.getWidth(); x++) {
                parent.setRGB(x,y,((x+y)&255)<<24 | ((x*17)&255)<<16 | ((y*29)&255)<<8 | ((x*y)&255));
            }
            BufferedImage child = parent.getSubimage(13, 9, 257, 256);
            BufferedImage prepared = CanonicalRgbaImage.prepare(child);
            assertNotNull(prepared);
            assertEquals(257, prepared.getWidth()); assertEquals(256, prepared.getHeight());
            assertArrayEquals(reference(child), bytes(prepared));
        }
    }

    @Test void smallerAndThinTranslatedInputsRemainEquivalentAndIndependent() {
        for (int type : new int[]{BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_4BYTE_ABGR}) {
            for (int[] size : new int[][]{{64,64}, {65,64}, {1,8192}, {8192,1}, {4097,17}, {255,256}}) {
                int width = size[0], height = size[1];
                BufferedImage parent = new BufferedImage(width + 7, height + 3, type);
                BufferedImage source = parent.getSubimage(3, 2, width, height);
                for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                    source.setRGB(x, y, ((x + y) & 255) << 24 | ((x * 17) & 255) << 16
                        | ((y * 29) & 255) << 8 | ((x * y) & 255));
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

    @Test void unsupportedTypesSizesAndSubclassesFallThroughWithoutForeignCalls() {
        assertNull(CanonicalRgbaImage.prepare(null));
        assertNull(CanonicalRgbaImage.prepare(new BufferedImage(63,65,BufferedImage.TYPE_INT_ARGB)));
        assertNull(CanonicalRgbaImage.prepare(new BufferedImage(8193,1,BufferedImage.TYPE_INT_ARGB)));
        assertNull(CanonicalRgbaImage.prepare(new BufferedImage(8192,4097,BufferedImage.TYPE_INT_ARGB)));
        assertNull(CanonicalRgbaImage.prepare(new BufferedImage(256,256,BufferedImage.TYPE_INT_ARGB_PRE)));
        assertNull(CanonicalRgbaImage.prepare(new BufferedImage(256,256,BufferedImage.TYPE_INT_RGB)));
        assertNull(CanonicalRgbaImage.prepare(new BufferedImage(256,256,BufferedImage.TYPE_BYTE_GRAY)));
        assertNull(CanonicalRgbaImage.prepare(new BufferedImage(256,256,BufferedImage.TYPE_INT_ARGB) {
            @Override public int getType() {throw new AssertionError("foreign image must not be queried");}
        }));
    }

    private static byte[] reference(BufferedImage source) {
        // Exactly the reviewed JOGL custom-conversion ColorModel and Src drawing operation.
        var model = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_sRGB), new int[]{8,8,8,8},
            true, true, Transparency.TRANSLUCENT, DataBuffer.TYPE_BYTE);
        var target = new BufferedImage(model, model.createCompatibleWritableRaster(source.getWidth(),source.getHeight()), true, null);
        var graphics = target.createGraphics();
        try {graphics.setComposite(AlphaComposite.Src);graphics.drawImage(source,0,0,null);}
        finally {graphics.dispose();}
        return bytes(target);
    }
    private static byte[] bytes(BufferedImage image) {return ((DataBufferByte)image.getRaster().getDataBuffer()).getData();}
}
