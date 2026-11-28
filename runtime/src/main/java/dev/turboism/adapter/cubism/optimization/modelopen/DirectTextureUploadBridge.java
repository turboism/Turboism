package dev.turboism.adapter.cubism.optimization.modelopen;

import dev.turboism.core.runtime.work.FatalErrors;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.ComponentSampleModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.awt.image.SinglePixelPackedSampleModel;
import java.awt.image.WritableRaster;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.ref.SoftReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Packs sRGB Cubism images directly into bounded pooled direct buffers for JOGL texture upload.
 *
 * <p>Avoids intermediate power-of-two AWT scratch surfaces, {@code Graphics2D.drawImage}, and
 * {@code AWTTextureData} custom pixel conversion while preserving premultiplication and OpenGL state.
 */
public final class DirectTextureUploadBridge {

    static final int GL_TEXTURE_2D = 0x0DE1;
    static final int GL_RGBA = 0x1908;
    static final int GL_RGBA8 = 0x8058;
    static final int GL_UNSIGNED_BYTE = 0x1401;
    static final int GL_TEXTURE_MAG_FILTER = 0x2800;
    static final int GL_TEXTURE_MIN_FILTER = 0x2801;
    static final int GL_TEXTURE_WRAP_S = 0x2802;
    static final int GL_TEXTURE_WRAP_T = 0x2803;
    static final int GL_LINEAR = 0x2601;
    static final int GL_LINEAR_MIPMAP_LINEAR = 0x2703;
    static final int GL_CLAMP = 0x2900;
    static final int GL_CLAMP_TO_EDGE = 0x812F;
    static final int GL_UNPACK_ROW_LENGTH = 0x0CF2;
    static final int GL_UNPACK_SKIP_ROWS = 0x0CF3;
    static final int GL_UNPACK_SKIP_PIXELS = 0x0CF4;
    static final int GL_UNPACK_ALIGNMENT = 0x0CF5;

    private static final int BYTES_PER_PIXEL = 4;
    private static final int CHANNEL_COUNT = 4;
    private static final int MAX_CHANNEL_VALUE = 255;
    private static final int SHIFT_ALPHA = 24;
    private static final int SHIFT_RED = 16;
    private static final int SHIFT_GREEN = 8;
    private static final int CHANNEL_MASK = 0xFF;
    private static final int[] RGBA_BITS = {8, 8, 8, 8};
    private static final int[] EXPECTED_ABGR_BAND_OFFSETS = {3, 2, 1, 0};
    private static final int BORDER_ZERO = 0;
    private static final int UNPACK_ALIGNMENT_ONE = 1;
    private static final int UNPACK_ZERO = 0;
    private static final int TEX_COUNT_ONE = 1;
    private static final int MIP_LEVEL_BASE = 0;
    private static final int ZERO_OFFSET = 0;

    private static final int MAX_DIMENSION = 16_384;
    private static final long MAX_TOTAL_PIXELS = (long) MAX_DIMENSION * MAX_DIMENSION;
    static final int MAX_POOLED_BUFFER_BYTES = 67_108_864;
    private static final int MAX_POOLED_BUFFERS = 2;
    private static final int RESULT_ARRAY_LENGTH = 3;
    private static final long HANDLED_FLAG = 1L;
    private static final int TEX_ID_SHIFT = 32;
    private static final long NATIVE_FALLBACK_PACKED = 0L;

    private static final String MSG_CREATE_TEXTURE = "create texture";
    private static final String MSG_GENERATE_MIPMAP_GL3 = "generate mipmap (GL3)";
    private static final String EXT_GL_VERSION_1_2 = "GL_VERSION_1_2";
    private static final String GL_PROFILE_CLASS = "com.jogamp.opengl.GLProfile";
    private static final String GL_CLASS = "com.jogamp.opengl.GL";
    private static final String GL3_CLASS = "com.jogamp.opengl.GL3";
    private static final String TEXTURE_DATA_CLASS = "com.jogamp.opengl.util.texture.TextureData";
    private static final String TEXTURE_FLUSHER_CLASS = "com.jogamp.opengl.util.texture.TextureData$Flusher";
    private static final String TEXTURE_IO_CLASS = "com.jogamp.opengl.util.texture.TextureIO";
    private static final String TEXTURE_CLASS = "com.jogamp.opengl.util.texture.Texture";
    private static final String METHOD_GET_GL_PROFILE = "getGLProfile";
    private static final String METHOD_NEW_TEXTURE = "newTexture";
    private static final String METHOD_IS_AUTO_MIPMAP = "isUsingAutoMipmapGeneration";
    private static final String METHOD_IS_GL3 = "isGL3";
    private static final String METHOD_IS_GL2GL3 = "isGL2GL3";
    private static final String METHOD_IS_GL_CORE = "isGLcore";
    private static final String METHOD_IS_GL2 = "isGL2";
    private static final String METHOD_IS_EXTENSION_AVAILABLE = "isExtensionAvailable";
    private static final String METHOD_GET_TEXTURE_OBJECT = "getTextureObject";
    private static final String METHOD_GL_GEN_TEXTURES = "glGenTextures";
    private static final String METHOD_GL_DELETE_TEXTURES = "glDeleteTextures";
    private static final String METHOD_GL_BIND_TEXTURE = "glBindTexture";
    private static final String METHOD_GL_ENABLE = "glEnable";
    private static final String METHOD_GL_GENERATE_MIPMAP = "glGenerateMipmap";
    private static final String METHOD_GL_GET_INTEGERV = "glGetIntegerv";
    private static final String METHOD_GL_PIXEL_STOREI = "glPixelStorei";
    private static final String METHOD_GL_TEX_PARAMETERI = "glTexParameteri";
    private static final String METHOD_GL_TEX_IMAGE_2D = "glTexImage2D";
    private static final String METHOD_GL_TEX_SUB_IMAGE_2D = "glTexSubImage2D";
    private static final String METHOD_ENABLE = "enable";
    private static final String METHOD_BIND = "bind";

    private static final byte[][] PREMUL_LUT = buildPremulLut();
    private static final Object POOL_LOCK = new Object();
    private static final ArrayDeque<ByteBuffer> DIRECT_BUFFER_POOL = new ArrayDeque<>(MAX_POOLED_BUFFERS);
    private static final Object FALLBACK_LOCK = new Object();
    private static SoftReference<ByteBuffer> transientFallbackBuffer;
    private static final ThreadLocal<byte[]> ROW_SCRATCH = new ThreadLocal<>();
    private static final AtomicLong STRIPED_GL3_UPLOADS = new AtomicLong();
    private static final AtomicLong OVERSIZED_DIRECT_ALLOCATIONS = new AtomicLong();
    private static final AtomicLong FALLBACK_BUFFER_REUSES = new AtomicLong();

    private static volatile JoglHandles cachedHandles;

    private DirectTextureUploadBridge() {}

    /**
     * Uploads an unpadded image directly via JOGL {@code TextureData}, returning a status-packed
     * handle.
     *
     * <p>Returns {@code 0L} to delegate to the native path for unsupported color models or layouts.
     *
     * @param shader shader utility owner exposing OpenGL error verification
     * @param gl3 active JOGL {@code GL3} instance
     * @param image source AWT image to pack
     * @param mipmap whether mipmaps must be generated
     * @param flipY whether rows are uploaded bottom-to-top
     * @return bit-packed flag and texture ID, or {@code 0L} for native fallback
     */
    public static long tryUploadFlagged(
            final Object shader,
            final Object gl3,
            final BufferedImage image,
            final boolean mipmap,
            final boolean flipY) {
        if (shader == null || gl3 == null || image == null) {
            return NATIVE_FALLBACK_PACKED;
        }
        final int width = image.getWidth();
        final int height = image.getHeight();
        if (!isPackableLayout(image, width, height, width, height)) {
            return NATIVE_FALLBACK_PACKED;
        }
        final JoglHandles handles = resolveHandles(shader, gl3);
        if (handles == null) {
            return NATIVE_FALLBACK_PACKED;
        }
        try {
            final int texId = uploadPackedTexture(handles, shader, gl3, image, width, height, mipmap, flipY);
            return HANDLED_FLAG | ((long) texId << TEX_ID_SHIFT);
        } catch (final RuntimeException glException) {
            throw glException;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return NATIVE_FALLBACK_PACKED;
        }
    }

    /**
     * Aligns an image for mipmapping and uploads it via JOGL {@code TextureData}.
     *
     * <p>Writes {@code [texId, paddedWidthOrZero, paddedHeight]} to {@code resultOut} on success.
     *
     * @param shader shader utility owner exposing OpenGL error verification
     * @param gl3 active JOGL {@code GL3} instance
     * @param image source AWT image to pack
     * @param writableImageType Cubism {@code n.a()} buffered type code
     * @param mipmapLevel required power-of-two dimension alignment
     * @param resultOut three-element array receiving texture ID and optional padded dimensions
     * @return {@code true} if uploaded directly, {@code false} to delegate to the native path
     */
    public static boolean tryUploadMipmapAligned(
            final Object shader,
            final Object gl3,
            final BufferedImage image,
            final int writableImageType,
            final int mipmapLevel,
            final int[] resultOut) {
        if (shader == null
                || gl3 == null
                || image == null
                || resultOut == null
                || resultOut.length < RESULT_ARRAY_LENGTH) {
            return false;
        }
        if (mipmapLevel <= 0 || (mipmapLevel & (mipmapLevel - 1)) != 0) {
            return false;
        }
        final int srcW = image.getWidth();
        final int srcH = image.getHeight();
        if (srcW <= 0 || srcH <= 0) {
            return false;
        }
        final int alignedW = alignDimensionUp(srcW, mipmapLevel);
        final int alignedH = alignDimensionUp(srcH, mipmapLevel);
        final boolean padded = alignedW != srcW || alignedH != srcH;
        if (padded
                && writableImageType != BufferedImage.TYPE_INT_ARGB
                && writableImageType != BufferedImage.TYPE_INT_ARGB_PRE) {
            return false;
        }
        if (!isPackableLayout(image, srcW, srcH, alignedW, alignedH)) {
            return false;
        }
        final JoglHandles handles = resolveHandles(shader, gl3);
        if (handles == null) {
            return false;
        }
        try {
            final int texId = uploadPackedTexture(handles, shader, gl3, image, alignedW, alignedH, true, false);
            resultOut[0] = texId;
            resultOut[1] = padded ? alignedW : 0;
            resultOut[2] = alignedH;
            return true;
        } catch (final RuntimeException glException) {
            throw glException;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return false;
        }
    }

    /**
     * Rounds {@code size} up to a multiple of power-of-two {@code alignment}.
     *
     * @param size positive pixel dimension
     * @param alignment positive power-of-two alignment
     * @return aligned dimension
     */
    public static int alignDimensionUp(final int size, final int alignment) {
        return (size + alignment - 1) & ~(alignment - 1);
    }

    /**
     * Packs an sRGB image into premultiplied RGBA bytes inside {@code outBuffer} with zero-filled
     * padding.
     *
     * @param src source {@code BufferedImage}
     * @param dstWidth padded target texture width
     * @param dstHeight padded target texture height
     * @param flipY whether source rows are inverted vertically
     * @param outBuffer target RGBA byte buffer with capacity for {@code dstWidth * dstHeight * 4}
     *     bytes
     * @return {@code true} if the source image matches a supported fast packing layout
     */
    public static boolean packRgbaPremultiplied(
            final BufferedImage src,
            final int dstWidth,
            final int dstHeight,
            final boolean flipY,
            final ByteBuffer outBuffer) {
        return packRgbaPremultipliedBand(src, dstWidth, dstHeight, ZERO_OFFSET, dstHeight, flipY, outBuffer);
    }

    /**
     * Packs rows {@code [yStart, yStart + bandRows)} of an sRGB image into premultiplied RGBA bytes
     * inside {@code outBuffer}, matching full-frame padding and vertical flip coordinates.
     *
     * @param src source {@code BufferedImage}
     * @param dstWidth padded target texture width
     * @param dstHeight padded target texture height
     * @param yStart inclusive first destination row to pack
     * @param bandRows number of destination rows to pack in this strip
     * @param flipY whether source rows are inverted vertically
     * @param outBuffer target RGBA byte buffer sized for at least {@code dstWidth * bandRows * 4} bytes
     * @return {@code true} if the source image matches a supported fast packing layout
     */
    static boolean packRgbaPremultipliedBand(
            final BufferedImage src,
            final int dstWidth,
            final int dstHeight,
            final int yStart,
            final int bandRows,
            final boolean flipY,
            final ByteBuffer outBuffer) {
        if (src == null || outBuffer == null || yStart < 0 || bandRows <= 0 || yStart + bandRows > dstHeight) {
            return false;
        }
        final int srcW = src.getWidth();
        final int srcH = src.getHeight();
        if (!isPackableLayout(src, srcW, srcH, dstWidth, dstHeight)) {
            return false;
        }
        final long requiredLong = (long) dstWidth * bandRows * BYTES_PER_PIXEL;
        if (requiredLong > Integer.MAX_VALUE || outBuffer.capacity() < (int) requiredLong) {
            return false;
        }
        final int requiredBytes = (int) requiredLong;
        final int imageType = src.getType();
        final WritableRaster raster = src.getRaster();
        final int transX = raster.getSampleModelTranslateX();
        final int transY = raster.getSampleModelTranslateY();

        outBuffer.clear();
        if (imageType == BufferedImage.TYPE_INT_ARGB || imageType == BufferedImage.TYPE_INT_ARGB_PRE) {
            if (!(raster.getSampleModel() instanceof SinglePixelPackedSampleModel sppsm)
                    || !(raster.getDataBuffer() instanceof DataBufferInt dbInt)
                    || dbInt.getNumBanks() != 1) {
                return false;
            }
            final int stride = sppsm.getScanlineStride();
            final int baseOffset = dbInt.getOffset() - transY * stride - transX;
            packIntArgbBand(
                    dbInt.getData(),
                    baseOffset,
                    stride,
                    srcW,
                    srcH,
                    dstWidth,
                    yStart,
                    bandRows,
                    flipY,
                    imageType == BufferedImage.TYPE_INT_ARGB_PRE,
                    outBuffer);
        } else if (imageType == BufferedImage.TYPE_4BYTE_ABGR) {
            if (!(raster.getSampleModel() instanceof ComponentSampleModel csm)
                    || csm.getPixelStride() != BYTES_PER_PIXEL
                    || !Arrays.equals(csm.getBandOffsets(), EXPECTED_ABGR_BAND_OFFSETS)
                    || !(raster.getDataBuffer() instanceof DataBufferByte dbByte)
                    || dbByte.getNumBanks() != 1) {
                return false;
            }
            final int stride = csm.getScanlineStride();
            final int baseOffset = dbByte.getOffset() - transY * stride - transX * BYTES_PER_PIXEL;
            packByteAbgrBand(
                    dbByte.getData(), baseOffset, stride, srcW, srcH, dstWidth, yStart, bandRows, flipY, outBuffer);
        } else {
            return false;
        }
        outBuffer.flip();
        if (outBuffer.limit() != requiredBytes) {
            return false;
        }
        return true;
    }

    private static boolean isPackableLayout(
            final BufferedImage src, final int srcW, final int srcH, final int dstW, final int dstH) {
        if (srcW <= 0 || srcH <= 0 || dstW < srcW || dstH < srcH) {
            return false;
        }
        final long dstPixels = (long) dstW * dstH;
        if (dstPixels <= 0L || dstPixels > MAX_TOTAL_PIXELS) {
            return false;
        }
        final int type = src.getType();
        if (type != BufferedImage.TYPE_INT_ARGB
                && type != BufferedImage.TYPE_4BYTE_ABGR
                && type != BufferedImage.TYPE_INT_ARGB_PRE) {
            return false;
        }
        if (src.getColorModel() == null || !src.getColorModel().getColorSpace().isCS_sRGB()) {
            return false;
        }
        final WritableRaster raster = src.getRaster();
        if (raster == null) {
            return false;
        }
        if (type == BufferedImage.TYPE_4BYTE_ABGR) {
            return raster.getSampleModel() instanceof ComponentSampleModel csm
                    && csm.getPixelStride() == BYTES_PER_PIXEL
                    && Arrays.equals(csm.getBandOffsets(), EXPECTED_ABGR_BAND_OFFSETS)
                    && raster.getDataBuffer() instanceof DataBufferByte db
                    && db.getNumBanks() == 1;
        }
        return raster.getSampleModel() instanceof SinglePixelPackedSampleModel
                && raster.getDataBuffer() instanceof DataBufferInt db
                && db.getNumBanks() == 1;
    }

    private static void packIntArgbBand(
            final int[] pixels,
            final int baseOffset,
            final int stride,
            final int srcW,
            final int srcH,
            final int dstW,
            final int yStart,
            final int bandRows,
            final boolean flipY,
            final boolean alreadyPremultiplied,
            final ByteBuffer outBuffer) {
        final int dstRowBytes = dstW * BYTES_PER_PIXEL;
        final byte[] row = borrowRowScratch(dstRowBytes);
        final int imageEnd = Math.min(yStart + bandRows, srcH);
        for (int dstY = yStart; dstY < imageEnd; dstY++) {
            final int srcY = flipY ? (srcH - 1 - dstY) : dstY;
            final int rowStart = baseOffset + srcY * stride;
            int outIdx = 0;
            for (int x = 0; x < srcW; x++) {
                final int argb = pixels[rowStart + x];
                final int a = (argb >>> SHIFT_ALPHA) & CHANNEL_MASK;
                final int r = (argb >>> SHIFT_RED) & CHANNEL_MASK;
                final int g = (argb >>> SHIFT_GREEN) & CHANNEL_MASK;
                final int b = argb & CHANNEL_MASK;
                if (alreadyPremultiplied) {
                    row[outIdx++] = (byte) r;
                    row[outIdx++] = (byte) g;
                    row[outIdx++] = (byte) b;
                } else {
                    final byte[] lut = PREMUL_LUT[a];
                    row[outIdx++] = lut[r];
                    row[outIdx++] = lut[g];
                    row[outIdx++] = lut[b];
                }
                row[outIdx++] = (byte) a;
            }
            if (dstW > srcW) {
                Arrays.fill(row, outIdx, dstRowBytes, (byte) 0);
            }
            outBuffer.put(row, 0, dstRowBytes);
        }
        final int imageRowsWritten = Math.max(0, imageEnd - yStart);
        final int zeroRowCount = bandRows - imageRowsWritten;
        if (zeroRowCount > 0) {
            writeZeroRows(outBuffer, row, dstRowBytes, zeroRowCount);
        }
    }

    private static void packByteAbgrBand(
            final byte[] pixels,
            final int baseOffset,
            final int stride,
            final int srcW,
            final int srcH,
            final int dstW,
            final int yStart,
            final int bandRows,
            final boolean flipY,
            final ByteBuffer outBuffer) {
        final int dstRowBytes = dstW * BYTES_PER_PIXEL;
        final byte[] row = borrowRowScratch(dstRowBytes);
        final int imageEnd = Math.min(yStart + bandRows, srcH);
        for (int dstY = yStart; dstY < imageEnd; dstY++) {
            final int srcY = flipY ? (srcH - 1 - dstY) : dstY;
            int srcIdx = baseOffset + srcY * stride;
            int outIdx = 0;
            for (int x = 0; x < srcW; x++) {
                final int a = pixels[srcIdx++] & CHANNEL_MASK;
                final int b = pixels[srcIdx++] & CHANNEL_MASK;
                final int g = pixels[srcIdx++] & CHANNEL_MASK;
                final int r = pixels[srcIdx++] & CHANNEL_MASK;
                final byte[] lut = PREMUL_LUT[a];
                row[outIdx++] = lut[r];
                row[outIdx++] = lut[g];
                row[outIdx++] = lut[b];
                row[outIdx++] = (byte) a;
            }
            if (dstW > srcW) {
                Arrays.fill(row, outIdx, dstRowBytes, (byte) 0);
            }
            outBuffer.put(row, 0, dstRowBytes);
        }
        final int imageRowsWritten = Math.max(0, imageEnd - yStart);
        final int zeroRowCount = bandRows - imageRowsWritten;
        if (zeroRowCount > 0) {
            writeZeroRows(outBuffer, row, dstRowBytes, zeroRowCount);
        }
    }

    private static void writeZeroRows(
            final ByteBuffer outBuffer, final byte[] zeroRow, final int dstRowBytes, final int zeroRowCount) {
        Arrays.fill(zeroRow, 0, dstRowBytes, (byte) 0);
        for (int i = 0; i < zeroRowCount; i++) {
            outBuffer.put(zeroRow, 0, dstRowBytes);
        }
    }

    private static byte[] borrowRowScratch(final int minBytes) {
        byte[] row = ROW_SCRATCH.get();
        if (row == null || row.length < minBytes) {
            row = new byte[minBytes];
            ROW_SCRATCH.set(row);
        }
        return row;
    }

    private static int uploadPackedTexture(
            final JoglHandles handles,
            final Object shader,
            final Object gl3,
            final BufferedImage image,
            final int dstW,
            final int dstH,
            final boolean mipmap,
            final boolean flipY)
            throws Throwable {
        final int requiredBytes = dstW * dstH * BYTES_PER_PIXEL;
        if (requiredBytes > MAX_POOLED_BUFFER_BYTES && canUseStripedGl3(handles, gl3)) {
            return uploadStripedGl3(handles, shader, gl3, image, dstW, dstH, mipmap, flipY);
        }
        final ByteBuffer pooled = borrowDirectBuffer(requiredBytes);
        try {
            if (!packRgbaPremultiplied(image, dstW, dstH, flipY, pooled)) {
                throw new IllegalStateException("Unexpected image layout during direct RGBA packing");
            }
            final ByteBuffer view =
                    pooled.capacity() == requiredBytes ? pooled : pooled.slice().order(ByteOrder.nativeOrder());
            final Object profile = handles.getGlProfile.invoke(gl3);
            final Object textureData = handles.newTextureData.invoke(
                    profile,
                    GL_RGBA8,
                    dstW,
                    dstH,
                    BORDER_ZERO,
                    GL_RGBA,
                    GL_UNSIGNED_BYTE,
                    mipmap,
                    false,
                    false,
                    (Buffer) view,
                    null);
            final Object texture = handles.newTexture.invoke(gl3, textureData);
            if (handles.checkGlError != null) {
                handles.checkGlError.invoke(shader, gl3, MSG_CREATE_TEXTURE, false);
            }
            final boolean autoMipmap = (boolean) handles.isUsingAutoMipmapGeneration.invoke(texture);
            final boolean isGl3 = (boolean) handles.isGl3.invoke(gl3);
            if (mipmap && !autoMipmap && isGl3) {
                final int rawTexObj = (int) handles.getTextureObjectNoArg.invoke(texture);
                handles.glBindTexture.invoke(gl3, GL_TEXTURE_2D, rawTexObj);
                handles.glGenerateMipmap.invoke(gl3, GL_TEXTURE_2D);
                if (handles.checkGlError != null) {
                    handles.checkGlError.invoke(shader, gl3, MSG_GENERATE_MIPMAP_GL3, false);
                }
            }
            handles.enableTexture.invoke(texture, gl3);
            handles.bindTexture.invoke(texture, gl3);
            return (int) handles.getTextureObject.invoke(texture, gl3);
        } finally {
            returnDirectBuffer(pooled);
        }
    }

    private static boolean canUseStripedGl3(final JoglHandles handles, final Object gl3) throws Throwable {
        if (!handles.supportsStripedGl3()) {
            return false;
        }
        return (boolean) handles.isGl3.invoke(gl3);
    }

    private static int uploadStripedGl3(
            final JoglHandles handles,
            final Object shader,
            final Object gl3,
            final BufferedImage image,
            final int dstW,
            final int dstH,
            final boolean mipmap,
            final boolean flipY)
            throws Throwable {
        final int rowBytes = dstW * BYTES_PER_PIXEL;
        final int stripRows = Math.max(1, MAX_POOLED_BUFFER_BYTES / rowBytes);
        final int maxBandRows = Math.min(dstH, stripRows);
        final int stripBufferBytes = maxBandRows * rowBytes;
        final int[] texIds = new int[TEX_COUNT_ONE];
        handles.glGenTextures.invoke(gl3, TEX_COUNT_ONE, texIds, ZERO_OFFSET);
        final int texId = texIds[ZERO_OFFSET];
        if (texId <= 0) {
            throw new IllegalStateException("glGenTextures returned invalid texture id: " + texId);
        }
        try {
            handles.glBindTexture.invoke(gl3, GL_TEXTURE_2D, texId);
            handles.glTexImage2D.invoke(
                    gl3,
                    GL_TEXTURE_2D,
                    MIP_LEVEL_BASE,
                    GL_RGBA8,
                    dstW,
                    dstH,
                    BORDER_ZERO,
                    GL_RGBA,
                    GL_UNSIGNED_BYTE,
                    (Buffer) null);
            uploadStripedBandsWithSavedUnpackState(handles, gl3, image, dstW, dstH, stripRows, stripBufferBytes, flipY);
            final int minFilter = mipmap ? GL_LINEAR_MIPMAP_LINEAR : GL_LINEAR;
            handles.glTexParameteri.invoke(gl3, GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, minFilter);
            handles.glTexParameteri.invoke(gl3, GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            final boolean clampToEdge = (boolean) handles.isExtensionAvailable.invoke(gl3, EXT_GL_VERSION_1_2)
                    || !(boolean) handles.isGl2.invoke(gl3);
            final int wrapMode = clampToEdge ? GL_CLAMP_TO_EDGE : GL_CLAMP;
            handles.glTexParameteri.invoke(gl3, GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, wrapMode);
            handles.glTexParameteri.invoke(gl3, GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, wrapMode);
            if (handles.checkGlError != null) {
                handles.checkGlError.invoke(shader, gl3, MSG_CREATE_TEXTURE, false);
            }
            if (mipmap) {
                handles.glGenerateMipmap.invoke(gl3, GL_TEXTURE_2D);
                if (handles.checkGlError != null) {
                    handles.checkGlError.invoke(shader, gl3, MSG_GENERATE_MIPMAP_GL3, false);
                }
            }
            // Match JOGL Texture.enable(gl) / bind(gl): enable only outside core profile, leave texture bound.
            final boolean isGlCore = handles.isGlCore != null && (boolean) handles.isGlCore.invoke(gl3);
            if (!isGlCore && handles.glEnable != null) {
                handles.glEnable.invoke(gl3, GL_TEXTURE_2D);
            }
            handles.glBindTexture.invoke(gl3, GL_TEXTURE_2D, texId);
            STRIPED_GL3_UPLOADS.incrementAndGet();
            return texId;
        } catch (final Throwable failure) {
            cleanupFailedTexture(handles, gl3, texIds, failure);
            throw failure;
        }
    }

    private static void uploadStripedBandsWithSavedUnpackState(
            final JoglHandles handles,
            final Object gl3,
            final BufferedImage image,
            final int dstW,
            final int dstH,
            final int stripRows,
            final int stripBufferBytes,
            final boolean flipY)
            throws Throwable {
        final int[] prevAlignment = new int[TEX_COUNT_ONE];
        handles.glGetIntegerv.invoke(gl3, GL_UNPACK_ALIGNMENT, prevAlignment, ZERO_OFFSET);
        final boolean isGl2Gl3 = handles.isGl2Gl3 != null && (boolean) handles.isGl2Gl3.invoke(gl3);
        int[] prevRowLength = null;
        int[] prevSkipRows = null;
        int[] prevSkipPixels = null;
        if (isGl2Gl3) {
            prevRowLength = new int[TEX_COUNT_ONE];
            prevSkipRows = new int[TEX_COUNT_ONE];
            prevSkipPixels = new int[TEX_COUNT_ONE];
            handles.glGetIntegerv.invoke(gl3, GL_UNPACK_ROW_LENGTH, prevRowLength, ZERO_OFFSET);
            handles.glGetIntegerv.invoke(gl3, GL_UNPACK_SKIP_ROWS, prevSkipRows, ZERO_OFFSET);
            handles.glGetIntegerv.invoke(gl3, GL_UNPACK_SKIP_PIXELS, prevSkipPixels, ZERO_OFFSET);
        }
        final ByteBuffer stripBuffer = borrowDirectBuffer(stripBufferBytes);
        try {
            handles.glPixelStorei.invoke(gl3, GL_UNPACK_ALIGNMENT, UNPACK_ALIGNMENT_ONE);
            if (isGl2Gl3) {
                handles.glPixelStorei.invoke(gl3, GL_UNPACK_ROW_LENGTH, UNPACK_ZERO);
                handles.glPixelStorei.invoke(gl3, GL_UNPACK_SKIP_ROWS, UNPACK_ZERO);
                handles.glPixelStorei.invoke(gl3, GL_UNPACK_SKIP_PIXELS, UNPACK_ZERO);
            }
            for (int y = 0; y < dstH; y += stripRows) {
                final int bandRows = Math.min(stripRows, dstH - y);
                if (!packRgbaPremultipliedBand(image, dstW, dstH, y, bandRows, flipY, stripBuffer)) {
                    throw new IllegalStateException("Unexpected image layout during striped RGBA packing");
                }
                final ByteBuffer view = stripBuffer.limit() == stripBuffer.capacity()
                        ? stripBuffer
                        : stripBuffer.slice().order(ByteOrder.nativeOrder());
                handles.glTexSubImage2D.invoke(
                        gl3,
                        GL_TEXTURE_2D,
                        MIP_LEVEL_BASE,
                        ZERO_OFFSET,
                        y,
                        dstW,
                        bandRows,
                        GL_RGBA,
                        GL_UNSIGNED_BYTE,
                        (Buffer) view);
            }
        } finally {
            returnDirectBuffer(stripBuffer);
            restoreUnpackState(handles, gl3, prevAlignment[0], isGl2Gl3, prevRowLength, prevSkipRows, prevSkipPixels);
        }
    }

    private static void restoreUnpackState(
            final JoglHandles handles,
            final Object gl3,
            final int prevAlignment,
            final boolean isGl2Gl3,
            final int[] prevRowLength,
            final int[] prevSkipRows,
            final int[] prevSkipPixels) {
        try {
            handles.glPixelStorei.invoke(gl3, GL_UNPACK_ALIGNMENT, prevAlignment);
            if (isGl2Gl3) {
                handles.glPixelStorei.invoke(gl3, GL_UNPACK_ROW_LENGTH, prevRowLength[ZERO_OFFSET]);
                handles.glPixelStorei.invoke(gl3, GL_UNPACK_SKIP_ROWS, prevSkipRows[ZERO_OFFSET]);
                handles.glPixelStorei.invoke(gl3, GL_UNPACK_SKIP_PIXELS, prevSkipPixels[ZERO_OFFSET]);
            }
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
    }

    private static void cleanupFailedTexture(
            final JoglHandles handles, final Object gl3, final int[] texIds, final Throwable rootCause) {
        try {
            handles.glBindTexture.invoke(gl3, GL_TEXTURE_2D, UNPACK_ZERO);
        } catch (final Throwable suppressed) {
            FatalErrors.rethrowIfFatal(suppressed);
            rootCause.addSuppressed(suppressed);
        }
        try {
            handles.glDeleteTextures.invoke(gl3, TEX_COUNT_ONE, texIds, ZERO_OFFSET);
        } catch (final Throwable suppressed) {
            FatalErrors.rethrowIfFatal(suppressed);
            rootCause.addSuppressed(suppressed);
        }
    }

    /**
     * Releases any transient oversized fallback buffer held outside the 64 MiB buffer pool.
     */
    public static void clearTransientFallbackBuffer() {
        synchronized (FALLBACK_LOCK) {
            transientFallbackBuffer = null;
        }
    }

    static long stripedGl3UploadCount() {
        return STRIPED_GL3_UPLOADS.get();
    }

    static long oversizedDirectAllocationCount() {
        return OVERSIZED_DIRECT_ALLOCATIONS.get();
    }

    static long fallbackBufferReuseCount() {
        return FALLBACK_BUFFER_REUSES.get();
    }

    static int pooledBufferCount() {
        synchronized (POOL_LOCK) {
            return DIRECT_BUFFER_POOL.size();
        }
    }

    static boolean hasTransientFallbackBuffer() {
        synchronized (FALLBACK_LOCK) {
            return transientFallbackBuffer != null && transientFallbackBuffer.get() != null;
        }
    }

    private static ByteBuffer borrowDirectBuffer(final int requiredBytes) {
        if (requiredBytes <= MAX_POOLED_BUFFER_BYTES) {
            synchronized (POOL_LOCK) {
                ByteBuffer best = null;
                for (final ByteBuffer candidate : DIRECT_BUFFER_POOL) {
                    if (candidate.capacity() >= requiredBytes
                            && (best == null || candidate.capacity() < best.capacity())) {
                        best = candidate;
                    }
                }
                if (best != null) {
                    DIRECT_BUFFER_POOL.remove(best);
                    return best;
                }
                if (!DIRECT_BUFFER_POOL.isEmpty()) {
                    DIRECT_BUFFER_POOL.pollFirst();
                }
            }
            return ByteBuffer.allocateDirect(requiredBytes).order(ByteOrder.nativeOrder());
        }
        return borrowOversizedFallbackBuffer(requiredBytes);
    }

    private static ByteBuffer borrowOversizedFallbackBuffer(final int requiredBytes) {
        synchronized (FALLBACK_LOCK) {
            final SoftReference<ByteBuffer> ref = transientFallbackBuffer;
            if (ref != null) {
                transientFallbackBuffer = null;
                final ByteBuffer existing = ref.get();
                if (existing != null && existing.capacity() >= requiredBytes) {
                    existing.clear();
                    FALLBACK_BUFFER_REUSES.incrementAndGet();
                    return existing;
                }
            }
        }
        OVERSIZED_DIRECT_ALLOCATIONS.incrementAndGet();
        return ByteBuffer.allocateDirect(requiredBytes).order(ByteOrder.nativeOrder());
    }

    private static void returnDirectBuffer(final ByteBuffer buffer) {
        if (buffer == null) {
            return;
        }
        buffer.clear();
        if (buffer.capacity() > MAX_POOLED_BUFFER_BYTES) {
            synchronized (FALLBACK_LOCK) {
                final ByteBuffer current = transientFallbackBuffer == null ? null : transientFallbackBuffer.get();
                if (current == null || current.capacity() < buffer.capacity()) {
                    transientFallbackBuffer = new SoftReference<>(buffer);
                }
            }
            return;
        }
        synchronized (POOL_LOCK) {
            if (DIRECT_BUFFER_POOL.size() < MAX_POOLED_BUFFERS) {
                DIRECT_BUFFER_POOL.offerLast(buffer);
            } else {
                // Keep larger reusable buffer slots bounded by count and byte limit.
                final ByteBuffer smallest = findSmallestPooled();
                if (smallest != null && smallest.capacity() < buffer.capacity()) {
                    DIRECT_BUFFER_POOL.remove(smallest);
                    DIRECT_BUFFER_POOL.offerLast(buffer);
                }
            }
        }
    }

    private static ByteBuffer findSmallestPooled() {
        ByteBuffer smallest = null;
        for (final ByteBuffer candidate : DIRECT_BUFFER_POOL) {
            if (smallest == null || candidate.capacity() < smallest.capacity()) {
                smallest = candidate;
            }
        }
        return smallest;
    }

    private static byte[][] buildPremulLut() {
        final ComponentColorModel premulModel = new ComponentColorModel(
                ColorSpace.getInstance(ColorSpace.CS_sRGB),
                RGBA_BITS,
                true,
                true,
                Transparency.TRANSLUCENT,
                DataBuffer.TYPE_BYTE);
        final byte[][] table = new byte[MAX_CHANNEL_VALUE + 1][MAX_CHANNEL_VALUE + 1];
        final byte[] encoded = new byte[CHANNEL_COUNT];
        for (int a = 0; a <= MAX_CHANNEL_VALUE; a++) {
            final int alphaBits = a << SHIFT_ALPHA;
            for (int c = 0; c <= MAX_CHANNEL_VALUE; c++) {
                premulModel.getDataElements(alphaBits | (c << SHIFT_RED), encoded);
                table[a][c] = encoded[0];
            }
        }
        return table;
    }

    private static JoglHandles resolveHandles(final Object shader, final Object gl3) {
        final JoglHandles current = cachedHandles;
        if (current != null && current.loader == gl3.getClass().getClassLoader()) {
            return current;
        }
        try {
            final ClassLoader loader = gl3.getClass().getClassLoader();
            final Class<?> glProfileClass = Class.forName(GL_PROFILE_CLASS, false, loader);
            final Class<?> glClass = Class.forName(GL_CLASS, false, loader);
            final Class<?> gl3Class = Class.forName(GL3_CLASS, false, loader);
            final Class<?> textureDataClass = Class.forName(TEXTURE_DATA_CLASS, false, loader);
            final Class<?> flusherClass = Class.forName(TEXTURE_FLUSHER_CLASS, false, loader);
            final Class<?> textureIoClass = Class.forName(TEXTURE_IO_CLASS, false, loader);
            final Class<?> textureClass = Class.forName(TEXTURE_CLASS, false, loader);

            final MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            final MethodHandle getGlProfile =
                    lookup.findVirtual(glClass, METHOD_GET_GL_PROFILE, MethodType.methodType(glProfileClass));
            final Constructor<?> ctor = textureDataClass.getConstructor(
                    glProfileClass,
                    int.class,
                    int.class,
                    int.class,
                    int.class,
                    int.class,
                    int.class,
                    boolean.class,
                    boolean.class,
                    boolean.class,
                    Buffer.class,
                    flusherClass);
            final MethodHandle newTextureData = lookup.unreflectConstructor(ctor);
            final MethodHandle newTexture = lookup.findStatic(
                    textureIoClass, METHOD_NEW_TEXTURE, MethodType.methodType(textureClass, glClass, textureDataClass));
            final MethodHandle isAutoMipmap =
                    lookup.findVirtual(textureClass, METHOD_IS_AUTO_MIPMAP, MethodType.methodType(boolean.class));
            final MethodHandle isGl3 = lookup.findVirtual(glClass, METHOD_IS_GL3, MethodType.methodType(boolean.class));
            final MethodHandle getTextureObjectNoArg =
                    lookup.findVirtual(textureClass, METHOD_GET_TEXTURE_OBJECT, MethodType.methodType(int.class));
            final MethodHandle glBindTexture = lookup.findVirtual(
                    glClass, METHOD_GL_BIND_TEXTURE, MethodType.methodType(void.class, int.class, int.class));
            final MethodHandle glGenerateMipmap = lookup.findVirtual(
                    glClass, METHOD_GL_GENERATE_MIPMAP, MethodType.methodType(void.class, int.class));
            final MethodHandle enableTexture =
                    lookup.findVirtual(textureClass, METHOD_ENABLE, MethodType.methodType(void.class, glClass));
            final MethodHandle bindTexture =
                    lookup.findVirtual(textureClass, METHOD_BIND, MethodType.methodType(void.class, glClass));
            final MethodHandle getTextureObject = lookup.findVirtual(
                    textureClass, METHOD_GET_TEXTURE_OBJECT, MethodType.methodType(int.class, glClass));
            final MethodHandle checkGlError = findShaderGlErrorMethod(lookup, shader.getClass(), glClass);

            final MethodHandle isGl2Gl3 =
                    findOptionalVirtual(lookup, glClass, METHOD_IS_GL2GL3, MethodType.methodType(boolean.class));
            final MethodHandle isGlCore =
                    findOptionalVirtual(lookup, glClass, METHOD_IS_GL_CORE, MethodType.methodType(boolean.class));
            final MethodHandle isGl2 =
                    findOptionalVirtual(lookup, glClass, METHOD_IS_GL2, MethodType.methodType(boolean.class));
            final MethodHandle isExtensionAvailable = findOptionalVirtual(
                    lookup, glClass, METHOD_IS_EXTENSION_AVAILABLE, MethodType.methodType(boolean.class, String.class));
            final MethodHandle glGenTextures = findOptionalVirtual(
                    lookup,
                    glClass,
                    METHOD_GL_GEN_TEXTURES,
                    MethodType.methodType(void.class, int.class, int[].class, int.class));
            final MethodHandle glDeleteTextures = findOptionalVirtual(
                    lookup,
                    glClass,
                    METHOD_GL_DELETE_TEXTURES,
                    MethodType.methodType(void.class, int.class, int[].class, int.class));
            final MethodHandle glEnable = findOptionalVirtual(
                    lookup, glClass, METHOD_GL_ENABLE, MethodType.methodType(void.class, int.class));
            final MethodHandle glGetIntegerv = findOptionalVirtual(
                    lookup,
                    glClass,
                    METHOD_GL_GET_INTEGERV,
                    MethodType.methodType(void.class, int.class, int[].class, int.class));
            final MethodHandle glPixelStorei = findOptionalVirtual(
                    lookup, glClass, METHOD_GL_PIXEL_STOREI, MethodType.methodType(void.class, int.class, int.class));
            final MethodHandle glTexParameteri = findOptionalVirtual(
                    lookup,
                    glClass,
                    METHOD_GL_TEX_PARAMETERI,
                    MethodType.methodType(void.class, int.class, int.class, int.class));
            final MethodHandle glTexImage2D = findOptionalVirtualAny(
                    lookup,
                    METHOD_GL_TEX_IMAGE_2D,
                    MethodType.methodType(
                            void.class,
                            int.class,
                            int.class,
                            int.class,
                            int.class,
                            int.class,
                            int.class,
                            int.class,
                            int.class,
                            Buffer.class),
                    gl3Class,
                    glClass);
            final MethodHandle glTexSubImage2D = findOptionalVirtualAny(
                    lookup,
                    METHOD_GL_TEX_SUB_IMAGE_2D,
                    MethodType.methodType(
                            void.class,
                            int.class,
                            int.class,
                            int.class,
                            int.class,
                            int.class,
                            int.class,
                            int.class,
                            int.class,
                            Buffer.class),
                    gl3Class,
                    glClass);

            final JoglHandles resolved = new JoglHandles(
                    loader,
                    getGlProfile,
                    newTextureData,
                    newTexture,
                    isAutoMipmap,
                    isGl3,
                    isGl2Gl3,
                    isGlCore,
                    isGl2,
                    isExtensionAvailable,
                    getTextureObjectNoArg,
                    glGenTextures,
                    glDeleteTextures,
                    glBindTexture,
                    glEnable,
                    glGenerateMipmap,
                    glGetIntegerv,
                    glPixelStorei,
                    glTexParameteri,
                    glTexImage2D,
                    glTexSubImage2D,
                    enableTexture,
                    bindTexture,
                    getTextureObject,
                    checkGlError);
            cachedHandles = resolved;
            return resolved;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return null;
        }
    }

    private static MethodHandle findOptionalVirtual(
            final MethodHandles.Lookup lookup,
            final Class<?> targetClass,
            final String name,
            final MethodType methodType) {
        try {
            return lookup.findVirtual(targetClass, name, methodType);
        } catch (final NoSuchMethodException | IllegalAccessException ignored) {
            return null;
        }
    }

    private static MethodHandle findOptionalVirtualAny(
            final MethodHandles.Lookup lookup,
            final String name,
            final MethodType methodType,
            final Class<?>... candidates) {
        for (final Class<?> candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            final MethodHandle handle = findOptionalVirtual(lookup, candidate, name, methodType);
            if (handle != null) {
                return handle;
            }
        }
        return null;
    }

    private static MethodHandle findShaderGlErrorMethod(
            final MethodHandles.Lookup lookup, final Class<?> shaderClass, final Class<?> glClass) {
        try {
            final Method m = shaderClass.getMethod("a", glClass, String.class, boolean.class);
            if (m.getReturnType() == int.class) {
                return lookup.unreflect(m);
            }
        } catch (final NoSuchMethodException | IllegalAccessException ignored) {
            // Optional diagnostic error check method not present on mock targets.
        }
        return null;
    }

    private record JoglHandles(
            ClassLoader loader,
            MethodHandle getGlProfile,
            MethodHandle newTextureData,
            MethodHandle newTexture,
            MethodHandle isUsingAutoMipmapGeneration,
            MethodHandle isGl3,
            MethodHandle isGl2Gl3,
            MethodHandle isGlCore,
            MethodHandle isGl2,
            MethodHandle isExtensionAvailable,
            MethodHandle getTextureObjectNoArg,
            MethodHandle glGenTextures,
            MethodHandle glDeleteTextures,
            MethodHandle glBindTexture,
            MethodHandle glEnable,
            MethodHandle glGenerateMipmap,
            MethodHandle glGetIntegerv,
            MethodHandle glPixelStorei,
            MethodHandle glTexParameteri,
            MethodHandle glTexImage2D,
            MethodHandle glTexSubImage2D,
            MethodHandle enableTexture,
            MethodHandle bindTexture,
            MethodHandle getTextureObject,
            MethodHandle checkGlError) {

        boolean supportsStripedGl3() {
            return isGl3 != null
                    && isGl2 != null
                    && isExtensionAvailable != null
                    && glGenTextures != null
                    && glDeleteTextures != null
                    && glBindTexture != null
                    && glGenerateMipmap != null
                    && glGetIntegerv != null
                    && glPixelStorei != null
                    && glTexParameteri != null
                    && glTexImage2D != null
                    && glTexSubImage2D != null;
        }
    }
}
