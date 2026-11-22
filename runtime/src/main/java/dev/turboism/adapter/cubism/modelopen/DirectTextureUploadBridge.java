package dev.turboism.adapter.cubism.modelopen;

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
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Arrays;

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

    private static final int MAX_DIMENSION = 16_384;
    private static final long MAX_TOTAL_PIXELS = (long) MAX_DIMENSION * MAX_DIMENSION;
    private static final int MAX_POOLED_BUFFER_BYTES = 67_108_864;
    private static final int MAX_POOLED_BUFFERS = 2;
    private static final int RESULT_ARRAY_LENGTH = 3;
    private static final long HANDLED_FLAG = 1L;
    private static final int TEX_ID_SHIFT = 32;
    private static final long NATIVE_FALLBACK_PACKED = 0L;

    private static final String MSG_CREATE_TEXTURE = "create texture";
    private static final String MSG_GENERATE_MIPMAP_GL3 = "generate mipmap (GL3)";
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
    private static final String METHOD_GET_TEXTURE_OBJECT = "getTextureObject";
    private static final String METHOD_GL_BIND_TEXTURE = "glBindTexture";
    private static final String METHOD_GL_GENERATE_MIPMAP = "glGenerateMipmap";
    private static final String METHOD_ENABLE = "enable";
    private static final String METHOD_BIND = "bind";

    private static final byte[][] PREMUL_LUT = buildPremulLut();
    private static final Object POOL_LOCK = new Object();
    private static final ArrayDeque<ByteBuffer> DIRECT_BUFFER_POOL = new ArrayDeque<>(MAX_POOLED_BUFFERS);
    private static final ThreadLocal<byte[]> ROW_SCRATCH = new ThreadLocal<>();

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
        if (src == null || outBuffer == null) {
            return false;
        }
        final int srcW = src.getWidth();
        final int srcH = src.getHeight();
        if (!isPackableLayout(src, srcW, srcH, dstWidth, dstHeight)) {
            return false;
        }
        final long requiredLong = (long) dstWidth * dstHeight * BYTES_PER_PIXEL;
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
            packIntArgb(
                    dbInt.getData(),
                    baseOffset,
                    stride,
                    srcW,
                    srcH,
                    dstWidth,
                    dstHeight,
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
            packByteAbgr(dbByte.getData(), baseOffset, stride, srcW, srcH, dstWidth, dstHeight, flipY, outBuffer);
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

    private static void packIntArgb(
            final int[] pixels,
            final int baseOffset,
            final int stride,
            final int srcW,
            final int srcH,
            final int dstW,
            final int dstH,
            final boolean flipY,
            final boolean alreadyPremultiplied,
            final ByteBuffer outBuffer) {
        final int dstRowBytes = dstW * BYTES_PER_PIXEL;
        final byte[] row = borrowRowScratch(dstRowBytes);
        for (int y = 0; y < srcH; y++) {
            final int srcY = flipY ? (srcH - 1 - y) : y;
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
        if (dstH > srcH) {
            writeZeroRows(outBuffer, row, dstRowBytes, dstH - srcH);
        }
    }

    private static void packByteAbgr(
            final byte[] pixels,
            final int baseOffset,
            final int stride,
            final int srcW,
            final int srcH,
            final int dstW,
            final int dstH,
            final boolean flipY,
            final ByteBuffer outBuffer) {
        final int dstRowBytes = dstW * BYTES_PER_PIXEL;
        final byte[] row = borrowRowScratch(dstRowBytes);
        for (int y = 0; y < srcH; y++) {
            final int srcY = flipY ? (srcH - 1 - y) : y;
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
        if (dstH > srcH) {
            writeZeroRows(outBuffer, row, dstRowBytes, dstH - srcH);
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
        }
        return ByteBuffer.allocateDirect(requiredBytes).order(ByteOrder.nativeOrder());
    }

    private static void returnDirectBuffer(final ByteBuffer buffer) {
        if (buffer == null || buffer.capacity() > MAX_POOLED_BUFFER_BYTES) {
            return;
        }
        buffer.clear();
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

            final JoglHandles resolved = new JoglHandles(
                    loader,
                    getGlProfile,
                    newTextureData,
                    newTexture,
                    isAutoMipmap,
                    isGl3,
                    getTextureObjectNoArg,
                    glBindTexture,
                    glGenerateMipmap,
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
            MethodHandle getTextureObjectNoArg,
            MethodHandle glBindTexture,
            MethodHandle glGenerateMipmap,
            MethodHandle enableTexture,
            MethodHandle bindTexture,
            MethodHandle getTextureObject,
            MethodHandle checkGlError) {}
}
