package dev.turboism.adapter.cubism.optimization.image;

import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.awt.image.DirectColorModel;
import java.awt.image.PixelInterleavedSampleModel;
import java.awt.image.SinglePixelPackedSampleModel;
import java.util.Arrays;

/** Prepares the exact premultiplied RGBA8 representation expected by reviewed JOGL texture data. */
public final class CanonicalRgbaImage {
    private static final ComponentColorModel RGBA = new ComponentColorModel(
            ColorSpace.getInstance(ColorSpace.CS_sRGB),
            new int[] {8, 8, 8, 8},
            true,
            true,
            Transparency.TRANSLUCENT,
            DataBuffer.TYPE_BYTE);
    // A complete, context-free channel/alpha table from the same JDK ColorModel,
    // rather than an approximate color or premultiplication formula.
    private static final byte[] PREMULTIPLIED = buildTable();
    // Only bypass getRGB for the JDK's ordinary raster implementations, never user overrides.
    private static final Class<?> INT_RASTER =
            new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).getRaster().getClass();
    private static final Class<?> BYTE_RASTER =
            new BufferedImage(1, 1, BufferedImage.TYPE_4BYTE_ABGR).getRaster().getClass();

    private CanonicalRgbaImage() {}

    /**
     * Returns a fresh upload-only image, or null for the unchanged native path.
     * Never mutates/retains the source. Small, oversized, custom and already-premultiplied images are excluded.
     * Admitted images are at most 8192 square: one fresh RGBA target is bounded to 256 MiB.
     */
    public static BufferedImage prepare(BufferedImage source) {
        if (source == null || source.getClass() != BufferedImage.class) return null;
        int type = source.getType();
        if ((type != BufferedImage.TYPE_INT_ARGB && type != BufferedImage.TYPE_4BYTE_ABGR)
                || source.isAlphaPremultiplied()
                || !source.getColorModel().getColorSpace().isCS_sRGB()) return null;
        int width = source.getWidth(), height = source.getHeight();
        long pixels = (long) width * height;
        if (!supportedSize(width, height)) return null;
        var raster = RGBA.createCompatibleWritableRaster(width, height);
        if (!(raster.getSampleModel() instanceof PixelInterleavedSampleModel model)
                || model.getPixelStride() != 4
                || model.getScanlineStride() != width * 4
                || !Arrays.equals(model.getBandOffsets(), new int[] {0, 1, 2, 3})
                || !(raster.getDataBuffer() instanceof DataBufferByte data)
                || data.getNumBanks() != 1
                || data.getOffset() != 0) return null;
        byte[] rgba = data.getData();
        if (rgba.length != pixels * 4) return null;
        if (!copyIntArgb(source, rgba) && !copyByteAbgr(source, rgba)) copyViaGetRgb(source, rgba);
        return new BufferedImage(RGBA, raster, true, null);
    }

    /** Scalar admission is testable without allocating a source and 256 MiB upload surface. */
    static boolean supportedSize(int width, int height) {
        long pixels = (long) width * height;
        return width > 0 && height > 0 && width <= 8192 && height <= 8192 && pixels >= 4096 && pixels <= 67_108_864L;
    }

    private static void copyViaGetRgb(BufferedImage source, byte[] rgba) {
        int width = source.getWidth(), height = source.getHeight();
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
    }

    private static boolean copyIntArgb(BufferedImage source, byte[] rgba) {
        var raster = source.getRaster();
        if (source.getType() != BufferedImage.TYPE_INT_ARGB
                || raster.getClass() != INT_RASTER
                || source.getColorModel().getClass() != DirectColorModel.class
                || raster.getSampleModel().getClass() != SinglePixelPackedSampleModel.class
                || raster.getDataBuffer().getClass() != DataBufferInt.class) return false;
        var model = (SinglePixelPackedSampleModel) raster.getSampleModel();
        var color = (DirectColorModel) source.getColorModel();
        var data = (DataBufferInt) raster.getDataBuffer();
        if (color.getRedMask() != 0x00ff0000
                || color.getGreenMask() != 0x0000ff00
                || color.getBlueMask() != 0x000000ff
                || color.getAlphaMask() != 0xff000000
                || !Arrays.equals(model.getBitMasks(), new int[] {0x00ff0000, 0x0000ff00, 0x000000ff, 0xff000000})
                || data.getNumBanks() != 1) return false;
        int width = source.getWidth(), height = source.getHeight(), stride = model.getScanlineStride();
        long start = (long) data.getOffset()
                - raster.getSampleModelTranslateX()
                - (long) raster.getSampleModelTranslateY() * stride;
        if (!withinArray(start, stride, width, height, data.getSize() + (long) data.getOffset())) return false;
        int[] argb = data.getData();
        if (!withinArray(start, stride, width, height, argb.length)) return false;
        int destination = 0;
        for (int y = 0; y < height; y++) {
            int position = (int) (start + (long) y * stride);
            for (int x = 0; x < width; x++) {
                int pixel = argb[position++], alpha = pixel >>> 24, base = alpha << 8;
                rgba[destination++] = PREMULTIPLIED[base | ((pixel >>> 16) & 255)];
                rgba[destination++] = PREMULTIPLIED[base | ((pixel >>> 8) & 255)];
                rgba[destination++] = PREMULTIPLIED[base | (pixel & 255)];
                rgba[destination++] = (byte) alpha;
            }
        }
        return true;
    }

    private static boolean copyByteAbgr(BufferedImage source, byte[] rgba) {
        var raster = source.getRaster();
        if (source.getType() != BufferedImage.TYPE_4BYTE_ABGR
                || raster.getClass() != BYTE_RASTER
                || source.getColorModel().getClass() != ComponentColorModel.class
                || raster.getSampleModel().getClass() != PixelInterleavedSampleModel.class
                || raster.getDataBuffer().getClass() != DataBufferByte.class) return false;
        var model = (PixelInterleavedSampleModel) raster.getSampleModel();
        var color = (ComponentColorModel) source.getColorModel();
        var data = (DataBufferByte) raster.getDataBuffer();
        if (model.getPixelStride() != 4
                || !Arrays.equals(model.getBandOffsets(), new int[] {3, 2, 1, 0})
                || !Arrays.equals(model.getBankIndices(), new int[] {0, 0, 0, 0})
                || !Arrays.equals(color.getComponentSize(), new int[] {8, 8, 8, 8})
                || data.getNumBanks() != 1
                // Some JDK byte rasters interpret nonzero buffer offsets differently from
                // SampleModel indexing. Preserve getRGB semantics for those unusual inputs.
                || data.getOffset() != 0) return false;
        int width = source.getWidth(), height = source.getHeight(), stride = model.getScanlineStride();
        long start = (long) data.getOffset()
                - (long) raster.getSampleModelTranslateX() * 4
                - (long) raster.getSampleModelTranslateY() * stride;
        if (!withinArray(start, stride, width * 4, height, data.getSize() + (long) data.getOffset())) return false;
        byte[] abgr = data.getData();
        if (!withinArray(start, stride, width * 4, height, abgr.length)) return false;
        int destination = 0;
        for (int y = 0; y < height; y++) {
            int position = (int) (start + (long) y * stride);
            for (int x = 0; x < width; x++) {
                int alpha = abgr[position] & 255, base = alpha << 8;
                rgba[destination++] = PREMULTIPLIED[base | (abgr[position + 3] & 255)];
                rgba[destination++] = PREMULTIPLIED[base | (abgr[position + 2] & 255)];
                rgba[destination++] = PREMULTIPLIED[base | (abgr[position + 1] & 255)];
                rgba[destination++] = (byte) alpha;
                position += 4;
            }
        }
        return true;
    }

    private static boolean withinArray(long start, int stride, int rowLength, int height, long length) {
        return start >= 0 && stride >= rowLength && start + (long) (height - 1) * stride + rowLength <= length;
    }

    private static byte[] buildTable() {
        byte[] table = new byte[65536], pixel = new byte[4];
        for (int alpha = 0; alpha < 256; alpha++)
            for (int channel = 0; channel < 256; channel++) {
                pixel = (byte[])
                        RGBA.getDataElements((alpha << 24) | (channel << 16) | (channel << 8) | channel, pixel);
                if (pixel[0] != pixel[1] || pixel[0] != pixel[2] || (pixel[3] & 255) != alpha) {
                    throw new IllegalStateException("unexpected standard RGBA8 component conversion");
                }
                table[(alpha << 8) | channel] = pixel[0];
            }
        return table;
    }
}
