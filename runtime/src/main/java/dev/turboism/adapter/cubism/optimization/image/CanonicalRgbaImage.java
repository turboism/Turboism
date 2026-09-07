package dev.turboism.adapter.cubism.optimization.image;

import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.PixelInterleavedSampleModel;
import java.util.Arrays;

/** Prepares the exact premultiplied RGBA8 representation expected by reviewed JOGL texture data. */
public final class CanonicalRgbaImage {
    private static final ComponentColorModel RGBA = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_sRGB),
        new int[]{8, 8, 8, 8}, true, true, Transparency.TRANSLUCENT, DataBuffer.TYPE_BYTE);
    // A complete, context-free channel/alpha table from the same JDK ColorModel,
    // rather than an approximate color or premultiplication formula.
    private static final byte[] PREMULTIPLIED = buildTable();

    private CanonicalRgbaImage() { }

    /**
     * Returns a fresh upload-only image, or null for the unchanged native path.
     * Never mutates/retains the source. Small, oversized, custom and already-premultiplied images are excluded.
     */
    public static BufferedImage prepare(BufferedImage source) {
        if (source == null || source.getClass() != BufferedImage.class) return null;
        int type = source.getType();
        if ((type != BufferedImage.TYPE_INT_ARGB && type != BufferedImage.TYPE_4BYTE_ABGR)
            || source.isAlphaPremultiplied() || !source.getColorModel().getColorSpace().isCS_sRGB()) return null;
        int width = source.getWidth(), height = source.getHeight();
        long pixels = (long) width * height;
        if (width < 1 || height < 1 || width > 4096 || height > 4096 || pixels < 65536 || pixels > 16_777_216L) return null;
        var raster = RGBA.createCompatibleWritableRaster(width, height);
        if (!(raster.getSampleModel() instanceof PixelInterleavedSampleModel model)
            || model.getPixelStride() != 4 || model.getScanlineStride() != width * 4
            || !Arrays.equals(model.getBandOffsets(), new int[]{0, 1, 2, 3})
            || !(raster.getDataBuffer() instanceof DataBufferByte data) || data.getNumBanks() != 1 || data.getOffset() != 0) return null;
        byte[] rgba = data.getData();
        if (rgba.length != pixels * 4) return null;
        int[] row = new int[width];
        int destination = 0;
        for (int y = 0; y < height; y++) {
            source.getRGB(0, y, width, 1, row, 0, width);
            for (int argb : row) {
                int alpha = argb >>> 24, base = alpha << 8;
                rgba[destination++] = PREMULTIPLIED[base | ((argb >>> 16) & 255)];
                rgba[destination++] = PREMULTIPLIED[base | ((argb >>> 8) & 255)];
                rgba[destination++] = PREMULTIPLIED[base | (argb & 255)];
                rgba[destination++] = (byte) alpha;
            }
        }
        return new BufferedImage(RGBA, raster, true, null);
    }

    private static byte[] buildTable() {
        byte[] table = new byte[65536], pixel = new byte[4];
        for (int alpha = 0; alpha < 256; alpha++) for (int channel = 0; channel < 256; channel++) {
            pixel = (byte[]) RGBA.getDataElements((alpha << 24) | (channel << 16) | (channel << 8) | channel, pixel);
            if (pixel[0] != pixel[1] || pixel[0] != pixel[2] || (pixel[3] & 255) != alpha) {
                throw new IllegalStateException("unexpected standard RGBA8 component conversion");
            }
            table[(alpha << 8) | channel] = pixel[0];
        }
        return table;
    }
}
