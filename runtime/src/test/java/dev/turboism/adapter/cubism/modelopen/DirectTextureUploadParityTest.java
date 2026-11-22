package dev.turboism.adapter.cubism.modelopen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferInt;
import java.awt.image.Raster;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class DirectTextureUploadParityTest {

    @Test
    void packsArgbAbgrAndPremulExhaustivelyIncludingPaddingAndFlipY() {
        for (final int type :
                new int[] {BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_4BYTE_ABGR, BufferedImage.TYPE_INT_ARGB_PRE
                }) {
            final BufferedImage src = new BufferedImage(128, 64, type);
            for (int y = 0; y < src.getHeight(); y++) {
                final int a = (y * 4) & 0xFF;
                for (int x = 0; x < src.getWidth(); x++) {
                    final int r = (x * 2) & 0xFF;
                    final int g = (x * 13 + y * 7) & 0xFF;
                    final int b = (x * 29 + 11) & 0xFF;
                    if (type == BufferedImage.TYPE_INT_ARGB_PRE) {
                        final int pr = (r * a + 127) / 255;
                        final int pg = (g * a + 127) / 255;
                        final int pb = (b * a + 127) / 255;
                        ((DataBufferInt) src.getRaster().getDataBuffer()).getData()[y * src.getWidth() + x] =
                                (a << 24) | (pr << 16) | (pg << 8) | pb;
                    } else {
                        src.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
                    }
                }
            }

            final int dstW = 160;
            final int dstH = 96;
            final ByteBuffer direct = ByteBuffer.allocateDirect(dstW * dstH * 4);
            assertTrue(DirectTextureUploadBridge.packRgbaPremultiplied(src, dstW, dstH, false, direct));
            final byte[] actual = new byte[dstW * dstH * 4];
            direct.get(actual);
            assertArrayEquals(referencePremulRgba(src, dstW, dstH, false), actual);

            direct.clear();
            assertTrue(DirectTextureUploadBridge.packRgbaPremultiplied(
                    src, src.getWidth(), src.getHeight(), true, direct));
            final byte[] actualFlipped = new byte[src.getWidth() * src.getHeight() * 4];
            direct.get(actualFlipped);
            assertArrayEquals(referencePremulRgba(src, src.getWidth(), src.getHeight(), true), actualFlipped);
        }
    }

    @Test
    void handlesSubimageOffsetsAndRejectsUnsupportedColorSpaces() {
        final BufferedImage parent = new BufferedImage(47, 39, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < parent.getHeight(); y++) {
            for (int x = 0; x < parent.getWidth(); x++) {
                parent.setRGB(x, y, ((x + y * 5) & 0xFF) << 24 | ((x * 19) & 0xFF) << 16 | (y * 9) & 0xFF);
            }
        }
        final BufferedImage sub = parent.getSubimage(7, 5, 29, 23);
        final int dstW = 32;
        final int dstH = 32;
        final ByteBuffer out = ByteBuffer.allocateDirect(dstW * dstH * 4);
        assertTrue(DirectTextureUploadBridge.packRgbaPremultiplied(sub, dstW, dstH, false, out));
        final byte[] actual = new byte[dstW * dstH * 4];
        out.get(actual);
        assertArrayEquals(referencePremulRgba(sub, dstW, dstH, false), actual);

        final BufferedImage gray = new BufferedImage(16, 16, BufferedImage.TYPE_BYTE_GRAY);
        assertFalse(DirectTextureUploadBridge.packRgbaPremultiplied(
                gray, 16, 16, false, ByteBuffer.allocateDirect(16 * 16 * 4)));
        assertEquals(0L, DirectTextureUploadBridge.tryUploadFlagged(new Object(), new Object(), gray, false, false));
    }

    @Test
    void fastAbgrAndBgrConversionMatchesJava2dDrawImageBitIdentically() {
        final BufferedImage abgrParent = new BufferedImage(40, 30, BufferedImage.TYPE_4BYTE_ABGR);
        for (int y = 0; y < abgrParent.getHeight(); y++) {
            for (int x = 0; x < abgrParent.getWidth(); x++) {
                abgrParent.setRGB(
                        x, y, ((y * 17) & 0xFF) << 24 | ((x * 23) & 0xFF) << 16 | ((x ^ y) & 0xFF) << 8 | 0x5A);
            }
        }
        final BufferedImage abgrSub = abgrParent.getSubimage(5, 3, 27, 21);
        final int[] refAbgr = drawReferenceArgb(abgrSub);
        final BufferedImage fastAbgr = ImageDecodeBridge.tryConvertToIntArgb(abgrSub, BufferedImage.TYPE_INT_ARGB);
        assertNotNull(fastAbgr);
        assertEquals(BufferedImage.TYPE_INT_ARGB, fastAbgr.getType());
        assertArrayEquals(refAbgr, fastAbgr.getRGB(0, 0, 27, 21, null, 0, 27));

        final BufferedImage bgr = new BufferedImage(33, 19, BufferedImage.TYPE_3BYTE_BGR);
        for (int y = 0; y < bgr.getHeight(); y++) {
            for (int x = 0; x < bgr.getWidth(); x++) {
                bgr.setRGB(x, y, 0xFF000000 | ((x * 11) & 0xFF) << 16 | ((y * 17) & 0xFF) << 8 | ((x + y) & 0xFF));
            }
        }
        final int[] refBgr = drawReferenceArgb(bgr);
        final BufferedImage fastBgr = ImageDecodeBridge.tryConvertToIntArgb(bgr, BufferedImage.TYPE_INT_ARGB);
        assertNotNull(fastBgr);
        assertArrayEquals(refBgr, fastBgr.getRGB(0, 0, 33, 19, null, 0, 33));

        // Non-sRGB / gray falls back cleanly.
        assertNull(ImageDecodeBridge.tryConvertToIntArgb(
                new BufferedImage(8, 8, BufferedImage.TYPE_BYTE_GRAY), BufferedImage.TYPE_INT_ARGB));
    }

    private static int[] drawReferenceArgb(final BufferedImage src) {
        final BufferedImage dst = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = dst.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return dst.getRGB(0, 0, src.getWidth(), src.getHeight(), null, 0, src.getWidth());
    }

    private static byte[] referencePremulRgba(
            final BufferedImage src, final int dstW, final int dstH, final boolean flipY) {
        final ComponentColorModel rgbaPremul = new ComponentColorModel(
                ColorSpace.getInstance(ColorSpace.CS_sRGB),
                new int[] {8, 8, 8, 8},
                true,
                true,
                Transparency.TRANSLUCENT,
                DataBuffer.TYPE_BYTE);
        final BufferedImage canvas =
                new BufferedImage(rgbaPremul, rgbaPremul.createCompatibleWritableRaster(dstW, dstH), true, null);
        final Graphics2D g = canvas.createGraphics();
        g.setComposite(AlphaComposite.Src);
        if (flipY) {
            g.translate(0.0, src.getHeight());
            g.scale(1.0, -1.0);
        }
        g.drawImage(src, 0, 0, null);
        g.dispose();
        final Raster r = canvas.getRaster();
        return ((java.awt.image.DataBufferByte) r.getDataBuffer()).getData();
    }
}
