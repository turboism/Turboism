package dev.turboism.adapter.cubism.exportperf;

import java.awt.image.BufferedImage;
import java.awt.image.ComponentSampleModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.awt.image.SampleModel;
import java.awt.image.SinglePixelPackedSampleModel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Direct lossless 8-bit RGBA PNG encoder for packed {@code TYPE_INT_ARGB} pixel buffers.
 *
 * <p>Uses {@code SUB} scanline filtering and thread-local zlib compression buffers to bypass
 * {@code ImageIO} overhead while keeping decoded ARGB pixel output bit-identical.</p>
 */
public final class FastPngEncoder {

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] IHDR_TYPE = {'I', 'H', 'D', 'R'};
    private static final byte[] IDAT_TYPE = {'I', 'D', 'A', 'T'};
    private static final byte[] IEND_CHUNK = {
        0x00, 0x00, 0x00, 0x00, 'I', 'E', 'N', 'D', (byte) 0xAE, 0x42, 0x60, (byte) 0x82
    };
    private static final int IHDR_DATA_LENGTH = 13;
    private static final byte PNG_BIT_DEPTH = 8;
    private static final byte PNG_COLOR_TYPE_RGB = 2;
    private static final byte PNG_COLOR_TYPE_RGBA = 6;
    private static final byte PNG_COMPRESSION_DEFLATE = 0;
    private static final byte PNG_FILTER_ADAPTIVE = 0;
    private static final byte PNG_INTERLACE_NONE = 0;
    private static final byte SCANLINE_FILTER_SUB = 1;
    private static final int RGB_BYTES_PER_PIXEL = 3;
    private static final int BYTES_PER_PIXEL = 4;
    private static final int RGB_RED_MASK = 0x00FF0000;
    private static final int RGB_GREEN_MASK = 0x0000FF00;
    private static final int RGB_BLUE_MASK = 0x000000FF;
    private static final int DEFLATE_LEVEL = 2;
    private static final int SCRATCH_BUFFER_BYTES = 64 * 1024;
    private static final int INITIAL_IDAT_BYTES = 256 * 1024;
    private static final int BYTE_MASK = 0xFF;
    private static final int SHIFT_ALPHA = 24;
    private static final int SHIFT_RED = 16;
    private static final int SHIFT_GREEN = 8;

    private static final ThreadLocal<EncoderScratch> SCRATCH = ThreadLocal.withInitial(EncoderScratch::new);

    private FastPngEncoder() {}

    /**
     * Attempts to encode {@code image} directly into {@code output} as lossless RGBA PNG.
     *
     * @param image source image candidate
     * @param output target stream receiving encoded PNG bytes
     * @return {@code true} if direct encoding succeeded, {@code false} if fallback is needed
     * @throws IOException if writing to {@code output} fails
     */
    public static boolean writeIfSupported(final BufferedImage image, final OutputStream output) throws IOException {
        if (image == null || output == null || image.getType() != BufferedImage.TYPE_INT_ARGB) {
            return false;
        }
        final int[] pixels = extractPackedIntData(image);
        if (pixels == null) {
            return false;
        }
        encodeArgbToStream(pixels, image.getWidth(), image.getHeight(), output);
        return true;
    }

    /**
     * Returns whether {@link #writeLosslessIfSupported(BufferedImage, OutputStream)} supports {@code image}.
     *
     * @param image candidate image, nullable
     * @return {@code true} when direct lossless encoding is supported
     */
    public static boolean isLosslessSupported(final BufferedImage image) {
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
            return false;
        }
        return switch (image.getType()) {
            case BufferedImage.TYPE_INT_ARGB -> extractPackedIntData(image) != null;
            case BufferedImage.TYPE_INT_RGB -> extractIntRgbData(image) != null;
            case BufferedImage.TYPE_4BYTE_ABGR -> extractByteAbgrData(image) != null;
            case BufferedImage.TYPE_3BYTE_BGR -> extractByteBgrData(image) != null;
            default -> false;
        };
    }

    /**
     * Attempts lossless direct PNG encoding for packed ARGB, ABGR, RGB, and BGR images.
     *
     * @param image source image candidate
     * @param output target stream receiving encoded PNG bytes
     * @return {@code true} when encoded directly, {@code false} if fallback is needed
     * @throws IOException if writing to {@code output} fails
     */
    public static boolean writeLosslessIfSupported(final BufferedImage image, final OutputStream output)
            throws IOException {
        if (image == null || output == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
            return false;
        }
        final int width = image.getWidth();
        final int height = image.getHeight();
        switch (image.getType()) {
            case BufferedImage.TYPE_INT_ARGB -> {
                final int[] pixels = extractPackedIntData(image);
                if (pixels == null) {
                    return false;
                }
                encodeArgbToStream(pixels, width, height, output);
                return true;
            }
            case BufferedImage.TYPE_4BYTE_ABGR -> {
                final byte[] pixels = extractByteAbgrData(image);
                if (pixels == null) {
                    return false;
                }
                writePngChunks(
                        width,
                        height,
                        PNG_COLOR_TYPE_RGBA,
                        BYTES_PER_PIXEL,
                        (y, row) -> fillAbgrSubFilteredRow(pixels, y * width * BYTES_PER_PIXEL, width, row),
                        output);
                return true;
            }
            case BufferedImage.TYPE_INT_RGB -> {
                final int[] pixels = extractIntRgbData(image);
                if (pixels == null) {
                    return false;
                }
                writePngChunks(
                        width,
                        height,
                        PNG_COLOR_TYPE_RGB,
                        RGB_BYTES_PER_PIXEL,
                        (y, row) -> fillIntRgbSubFilteredRow(pixels, y * width, width, row),
                        output);
                return true;
            }
            case BufferedImage.TYPE_3BYTE_BGR -> {
                final byte[] pixels = extractByteBgrData(image);
                if (pixels == null) {
                    return false;
                }
                writePngChunks(
                        width,
                        height,
                        PNG_COLOR_TYPE_RGB,
                        RGB_BYTES_PER_PIXEL,
                        (y, row) -> fillByteBgrSubFilteredRow(pixels, y * width * RGB_BYTES_PER_PIXEL, width, row),
                        output);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * Encodes {@code image} directly into a PNG byte array if lossless pixel layout is supported.
     *
     * @param image source image candidate
     * @return complete PNG byte array, or {@code null} when fallback encoding is required
     * @throws IOException if stream serialization fails
     */
    public static byte[] encodeLosslessIfSupported(final BufferedImage image) throws IOException {
        if (!isLosslessSupported(image)) {
            return null;
        }
        final ExposedByteArrayOutputStream out = new ExposedByteArrayOutputStream(INITIAL_IDAT_BYTES);
        return writeLosslessIfSupported(image, out) ? out.toExactByteArray() : null;
    }

    /**
     * Encodes packed {@code 0xAARRGGBB} pixels directly into a standalone PNG byte array.
     *
     * @param pixels packed ARGB pixels in row-major order
     * @param width image width in pixels
     * @param height image height in pixels
     * @return complete encoded PNG byte payload
     * @throws IOException if stream serialization fails
     */
    public static byte[] encodeArgbToBytes(final int[] pixels, final int width, final int height) throws IOException {
        final ExposedByteArrayOutputStream out = new ExposedByteArrayOutputStream(INITIAL_IDAT_BYTES);
        encodeArgbToStream(pixels, width, height, out);
        return out.toExactByteArray();
    }

    /**
     * Encodes packed {@code 0xAARRGGBB} pixels into {@code output} as a valid RGBA PNG.
     *
     * @param pixels packed ARGB pixels in row-major order
     * @param width image width in pixels
     * @param height image height in pixels
     * @param output target stream receiving PNG bytes
     * @throws IOException if writing to {@code output} fails
     */
    public static void encodeArgbToStream(
            final int[] pixels, final int width, final int height, final OutputStream output) throws IOException {
        writePngChunks(
                width,
                height,
                PNG_COLOR_TYPE_RGBA,
                BYTES_PER_PIXEL,
                (y, row) -> fillSubFilteredRow(pixels, y * width, width, row),
                output);
    }

    private static void writePngChunks(
            final int width,
            final int height,
            final byte colorType,
            final int bytesPerPixel,
            final RowEncoder rowEncoder,
            final OutputStream output)
            throws IOException {
        final EncoderScratch scratch = SCRATCH.get();
        final int rowBytes = 1 + width * bytesPerPixel;
        final byte[] row = scratch.ensureRowBuffer(rowBytes);
        final Deflater deflater = scratch.deflater;
        final CRC32 crc = scratch.crc;
        final ExposedByteArrayOutputStream idatStream = scratch.idatStream;
        final byte[] ioBuf = scratch.deflateOut;

        output.write(PNG_SIGNATURE);
        writeIhdr(output, scratch.headerBuf, crc, width, height, colorType);

        deflater.reset();
        idatStream.reset();

        for (int y = 0; y < height; y++) {
            rowEncoder.encodeRow(y, row);
            deflater.setInput(row, 0, rowBytes);
            drainDeflater(deflater, ioBuf, idatStream, false);
        }
        deflater.finish();
        while (!deflater.finished()) {
            drainDeflater(deflater, ioBuf, idatStream, true);
        }

        writeIdat(output, scratch.headerBuf, crc, idatStream);
        output.write(IEND_CHUNK);
    }

    private static int[] extractPackedIntData(final BufferedImage image) {
        final int width = image.getWidth();
        final int height = image.getHeight();
        if (width <= 0 || height <= 0) {
            return null;
        }
        final DataBuffer buffer = image.getRaster().getDataBuffer();
        if (!(buffer instanceof DataBufferInt dataBuffer)
                || dataBuffer.getNumBanks() != 1
                || dataBuffer.getOffset() != 0) {
            return null;
        }
        final int[] pixels = dataBuffer.getData();
        if ((long) width * height > pixels.length) {
            return null;
        }
        final SampleModel model = image.getSampleModel();
        if (model instanceof SinglePixelPackedSampleModel spModel && spModel.getScanlineStride() != width) {
            return null;
        }
        return pixels;
    }

    private static int[] extractIntRgbData(final BufferedImage image) {
        final int[] pixels = extractPackedIntData(image);
        if (pixels == null || !(image.getSampleModel() instanceof SinglePixelPackedSampleModel spModel)) {
            return null;
        }
        final int[] masks = spModel.getBitMasks();
        if (masks.length < 3 || masks[0] != RGB_RED_MASK || masks[1] != RGB_GREEN_MASK || masks[2] != RGB_BLUE_MASK) {
            return null;
        }
        return pixels;
    }

    private static byte[] extractByteAbgrData(final BufferedImage image) {
        final int width = image.getWidth();
        final int height = image.getHeight();
        final DataBuffer buffer = image.getRaster().getDataBuffer();
        if (!(buffer instanceof DataBufferByte dataBuffer)
                || dataBuffer.getNumBanks() != 1
                || dataBuffer.getOffset() != 0) {
            return null;
        }
        final byte[] pixels = dataBuffer.getData();
        if ((long) width * height * BYTES_PER_PIXEL > pixels.length) {
            return null;
        }
        final SampleModel model = image.getSampleModel();
        if (!(model instanceof ComponentSampleModel csm)
                || csm.getPixelStride() != BYTES_PER_PIXEL
                || csm.getScanlineStride() != width * BYTES_PER_PIXEL) {
            return null;
        }
        final int[] bands = csm.getBandOffsets();
        if (bands.length != BYTES_PER_PIXEL || bands[0] != 3 || bands[1] != 2 || bands[2] != 1 || bands[3] != 0) {
            return null;
        }
        return pixels;
    }

    private static byte[] extractByteBgrData(final BufferedImage image) {
        final int width = image.getWidth();
        final int height = image.getHeight();
        final DataBuffer buffer = image.getRaster().getDataBuffer();
        if (!(buffer instanceof DataBufferByte dataBuffer)
                || dataBuffer.getNumBanks() != 1
                || dataBuffer.getOffset() != 0) {
            return null;
        }
        final byte[] pixels = dataBuffer.getData();
        if ((long) width * height * RGB_BYTES_PER_PIXEL > pixels.length) {
            return null;
        }
        final SampleModel model = image.getSampleModel();
        if (!(model instanceof ComponentSampleModel csm)
                || csm.getPixelStride() != RGB_BYTES_PER_PIXEL
                || csm.getScanlineStride() != width * RGB_BYTES_PER_PIXEL) {
            return null;
        }
        final int[] bands = csm.getBandOffsets();
        if (bands.length != RGB_BYTES_PER_PIXEL || bands[0] != 2 || bands[1] != 1 || bands[2] != 0) {
            return null;
        }
        return pixels;
    }

    private static void fillAbgrSubFilteredRow(
            final byte[] pixels, final int rowByteOffset, final int width, final byte[] row) {
        row[0] = SCANLINE_FILTER_SUB;
        int prevR = 0;
        int prevG = 0;
        int prevB = 0;
        int prevA = 0;
        int inPos = rowByteOffset;
        int outPos = 1;
        for (int x = 0; x < width; x++) {
            final int a = pixels[inPos++] & BYTE_MASK;
            final int b = pixels[inPos++] & BYTE_MASK;
            final int g = pixels[inPos++] & BYTE_MASK;
            final int r = pixels[inPos++] & BYTE_MASK;
            row[outPos++] = (byte) (r - prevR);
            row[outPos++] = (byte) (g - prevG);
            row[outPos++] = (byte) (b - prevB);
            row[outPos++] = (byte) (a - prevA);
            prevR = r;
            prevG = g;
            prevB = b;
            prevA = a;
        }
    }

    private static void fillIntRgbSubFilteredRow(
            final int[] pixels, final int rowOffset, final int width, final byte[] row) {
        row[0] = SCANLINE_FILTER_SUB;
        int prevR = 0;
        int prevG = 0;
        int prevB = 0;
        int outPos = 1;
        for (int x = 0; x < width; x++) {
            final int rgb = pixels[rowOffset + x];
            final int r = (rgb >>> SHIFT_RED) & BYTE_MASK;
            final int g = (rgb >>> SHIFT_GREEN) & BYTE_MASK;
            final int b = rgb & BYTE_MASK;
            row[outPos++] = (byte) (r - prevR);
            row[outPos++] = (byte) (g - prevG);
            row[outPos++] = (byte) (b - prevB);
            prevR = r;
            prevG = g;
            prevB = b;
        }
    }

    private static void fillByteBgrSubFilteredRow(
            final byte[] pixels, final int rowByteOffset, final int width, final byte[] row) {
        row[0] = SCANLINE_FILTER_SUB;
        int prevR = 0;
        int prevG = 0;
        int prevB = 0;
        int inPos = rowByteOffset;
        int outPos = 1;
        for (int x = 0; x < width; x++) {
            final int b = pixels[inPos++] & BYTE_MASK;
            final int g = pixels[inPos++] & BYTE_MASK;
            final int r = pixels[inPos++] & BYTE_MASK;
            row[outPos++] = (byte) (r - prevR);
            row[outPos++] = (byte) (g - prevG);
            row[outPos++] = (byte) (b - prevB);
            prevR = r;
            prevG = g;
            prevB = b;
        }
    }

    private static void fillSubFilteredRow(final int[] pixels, final int rowOffset, final int width, final byte[] row) {
        row[0] = SCANLINE_FILTER_SUB;
        int prevR = 0;
        int prevG = 0;
        int prevB = 0;
        int prevA = 0;
        int outPos = 1;
        for (int x = 0; x < width; x++) {
            final int argb = pixels[rowOffset + x];
            final int r = (argb >>> SHIFT_RED) & BYTE_MASK;
            final int g = (argb >>> SHIFT_GREEN) & BYTE_MASK;
            final int b = argb & BYTE_MASK;
            final int a = (argb >>> SHIFT_ALPHA) & BYTE_MASK;
            row[outPos++] = (byte) (r - prevR);
            row[outPos++] = (byte) (g - prevG);
            row[outPos++] = (byte) (b - prevB);
            row[outPos++] = (byte) (a - prevA);
            prevR = r;
            prevG = g;
            prevB = b;
            prevA = a;
        }
    }

    private static void drainDeflater(
            final Deflater deflater, final byte[] ioBuf, final ByteArrayOutputStream out, final boolean finish) {
        while (true) {
            final int written = finish
                    ? deflater.deflate(ioBuf, 0, ioBuf.length)
                    : (deflater.needsInput() ? 0 : deflater.deflate(ioBuf, 0, ioBuf.length));
            if (written <= 0) {
                if (!finish || deflater.finished()) {
                    break;
                }
            } else {
                out.write(ioBuf, 0, written);
            }
        }
    }

    private static void writeIhdr(
            final OutputStream output,
            final byte[] scratch17,
            final CRC32 crc,
            final int width,
            final int height,
            final byte colorType)
            throws IOException {
        writeIntBe(output, scratch17, IHDR_DATA_LENGTH);
        System.arraycopy(IHDR_TYPE, 0, scratch17, 0, IHDR_TYPE.length);
        putIntBe(scratch17, 4, width);
        putIntBe(scratch17, 8, height);
        scratch17[12] = PNG_BIT_DEPTH;
        scratch17[13] = colorType;
        scratch17[14] = PNG_COMPRESSION_DEFLATE;
        scratch17[15] = PNG_FILTER_ADAPTIVE;
        scratch17[16] = PNG_INTERLACE_NONE;
        output.write(scratch17, 0, 4 + IHDR_DATA_LENGTH);
        crc.reset();
        crc.update(scratch17, 0, 4 + IHDR_DATA_LENGTH);
        writeIntBe(output, scratch17, (int) crc.getValue());
    }

    private static void writeIdat(
            final OutputStream output,
            final byte[] scratch,
            final CRC32 crc,
            final ExposedByteArrayOutputStream idatStream)
            throws IOException {
        final int length = idatStream.size();
        writeIntBe(output, scratch, length);
        output.write(IDAT_TYPE);
        idatStream.writeTo(output);
        crc.reset();
        crc.update(IDAT_TYPE);
        idatStream.updateCrc(crc);
        writeIntBe(output, scratch, (int) crc.getValue());
    }

    private static void writeIntBe(final OutputStream out, final byte[] buf, final int value) throws IOException {
        putIntBe(buf, 0, value);
        out.write(buf, 0, 4);
    }

    private static void putIntBe(final byte[] buf, final int offset, final int value) {
        buf[offset] = (byte) (value >>> SHIFT_ALPHA);
        buf[offset + 1] = (byte) (value >>> SHIFT_RED);
        buf[offset + 2] = (byte) (value >>> SHIFT_GREEN);
        buf[offset + 3] = (byte) value;
    }

    private static final class EncoderScratch {
        private final Deflater deflater = new Deflater(DEFLATE_LEVEL);
        private final CRC32 crc = new CRC32();
        private final byte[] deflateOut = new byte[SCRATCH_BUFFER_BYTES];
        private final byte[] headerBuf = new byte[4 + IHDR_DATA_LENGTH];
        private final ExposedByteArrayOutputStream idatStream = new ExposedByteArrayOutputStream(INITIAL_IDAT_BYTES);
        private byte[] rowBuffer = new byte[0];

        byte[] ensureRowBuffer(final int size) {
            if (rowBuffer.length < size) {
                rowBuffer = new byte[size];
            }
            return rowBuffer;
        }
    }

    private static final class ExposedByteArrayOutputStream extends ByteArrayOutputStream {
        ExposedByteArrayOutputStream(final int initialCapacity) {
            super(initialCapacity);
        }

        void updateCrc(final CRC32 crc) {
            crc.update(buf, 0, count);
        }

        @Override
        public void writeTo(final OutputStream out) throws IOException {
            out.write(buf, 0, count);
        }

        byte[] toExactByteArray() {
            return count == buf.length ? buf : java.util.Arrays.copyOf(buf, count);
        }
    }

    @FunctionalInterface
    private interface RowEncoder {
        void encodeRow(int y, byte[] row);
    }
}
