package dev.turboism.adapter.cubism.modelopen;

import dev.turboism.core.runtime.work.FatalErrors;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Decodes standard 8-bit non-interlaced PNG payloads directly into {@code TYPE_INT_ARGB}.
 *
 * <p>Eliminates {@code ImageIO}'s intermediate {@code TYPE_4BYTE_ABGR} raster and second-pass
 * pixel conversion copy, cutting texture decode heap traffic by over 50% while staying bit-identical
 * to {@code ImageIO.read} + {@code TYPE_INT_ARGB} conversion.</p>
 */
public final class FastPngDecoder {

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final int CHUNK_IHDR = 0x49484452;
    private static final int CHUNK_IDAT = 0x49444154;
    private static final int CHUNK_IEND = 0x49454E44;
    private static final int CHUNK_ICCP = 0x69434350;
    private static final int CHUNK_TRNS = 0x74524E53;
    private static final int IHDR_LENGTH = 13;
    private static final int BIT_DEPTH_8 = 8;
    private static final int COLOR_RGB = 2;
    private static final int COLOR_RGBA = 6;
    private static final int FILTER_NONE = 0;
    private static final int FILTER_SUB = 1;
    private static final int FILTER_UP = 2;
    private static final int FILTER_AVERAGE = 3;
    private static final int FILTER_PAETH = 4;
    private static final long MAX_DECODE_PIXELS = 67_108_864L;
    private static final int BYTE_MASK = 0xFF;
    private static final int OPAQUE_ALPHA = 0xFF00_0000;
    private static final int SHIFT_ALPHA = 24;
    private static final int SHIFT_RED = 16;
    private static final int SHIFT_GREEN = 8;

    private static final ThreadLocal<DecoderScratch> SCRATCH = ThreadLocal.withInitial(DecoderScratch::new);

    private FastPngDecoder() {}

    /**
     * Attempts to decode {@code pngBytes} directly into a {@link BufferedImage#TYPE_INT_ARGB} image.
     *
     * @param pngBytes complete raw PNG payload
     * @return decoded {@code TYPE_INT_ARGB} image, or {@code null} when fallback to ImageIO is needed
     */
    public static BufferedImage tryDecodeToIntArgb(final byte[] pngBytes) {
        if (pngBytes == null || pngBytes.length < PNG_SIGNATURE.length + 12 + IHDR_LENGTH) {
            return null;
        }
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (pngBytes[i] != PNG_SIGNATURE[i]) {
                return null;
            }
        }
        try {
            return decodeValidatedPng(pngBytes);
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return null;
        }
    }

    private static BufferedImage decodeValidatedPng(final byte[] data) throws DataFormatException {
        final int len = data.length;
        int pos = PNG_SIGNATURE.length;
        if (pos + 8 > len) {
            return null;
        }
        final int ihdrLen = readIntBe(data, pos);
        final int ihdrType = readIntBe(data, pos + 4);
        if (ihdrLen != IHDR_LENGTH || ihdrType != CHUNK_IHDR || pos + 12 + ihdrLen > len) {
            return null;
        }
        final int ihdrStart = pos + 8;
        final int width = readIntBe(data, ihdrStart);
        final int height = readIntBe(data, ihdrStart + 4);
        final int bitDepth = data[ihdrStart + 8] & BYTE_MASK;
        final int colorType = data[ihdrStart + 9] & BYTE_MASK;
        final int compression = data[ihdrStart + 10] & BYTE_MASK;
        final int filterMethod = data[ihdrStart + 11] & BYTE_MASK;
        final int interlace = data[ihdrStart + 12] & BYTE_MASK;

        if (width <= 0
                || height <= 0
                || (long) width * height > MAX_DECODE_PIXELS
                || bitDepth != BIT_DEPTH_8
                || compression != 0
                || filterMethod != 0
                || interlace != 0) {
            return null;
        }
        final int bpp = bytesPerPixel(colorType);
        if (bpp == 0) {
            return null;
        }

        // Scan chunk directory before allocating target image so unsupported iCCP/tRNS bail out zero-cost.
        pos += 12 + ihdrLen;
        int idatCount = 0;
        int firstIdatPos = -1;
        while (pos + 12 <= len) {
            final int chunkLen = readIntBe(data, pos);
            final int chunkType = readIntBe(data, pos + 4);
            if (chunkLen < 0 || pos + 12L + chunkLen > len) {
                return null;
            }
            if (chunkType == CHUNK_ICCP || chunkType == CHUNK_TRNS) {
                return null;
            }
            if (chunkType == CHUNK_IDAT) {
                if (firstIdatPos < 0) {
                    firstIdatPos = pos;
                }
                idatCount++;
            } else if (chunkType == CHUNK_IEND) {
                break;
            }
            pos += 12 + chunkLen;
        }
        if (idatCount == 0) {
            return null;
        }

        final int rowBytes = width * bpp;
        final int rawRowLen = 1 + rowBytes;
        final DecoderScratch scratch = SCRATCH.get();
        scratch.ensureCapacity(rawRowLen);
        byte[] cur = scratch.curRow;
        byte[] prev = scratch.prevRow;
        Arrays.fill(prev, 0, rawRowLen, (byte) 0);

        final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        final int[] dst = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();

        final Inflater inflater = scratch.inflater;
        inflater.reset();
        int chunkPos = firstIdatPos;
        int chunkRemaining = 0;

        for (int y = 0; y < height; y++) {
            int filled = 0;
            while (filled < rawRowLen) {
                if (inflater.needsInput()) {
                    while (chunkRemaining == 0) {
                        if (chunkPos + 12 > len) {
                            return null;
                        }
                        final int cLen = readIntBe(data, chunkPos);
                        final int cType = readIntBe(data, chunkPos + 4);
                        if (cType == CHUNK_IDAT) {
                            if (cLen > 0) {
                                inflater.setInput(data, chunkPos + 8, cLen);
                                chunkRemaining = cLen;
                            }
                            chunkPos += 12 + cLen;
                            break;
                        }
                        if (cType == CHUNK_IEND) {
                            return null;
                        }
                        chunkPos += 12 + cLen;
                    }
                    chunkRemaining = 0;
                }
                final int n = inflater.inflate(cur, filled, rawRowLen - filled);
                if (n == 0) {
                    if (inflater.finished() || inflater.needsDictionary()) {
                        return null;
                    }
                } else {
                    filled += n;
                }
            }

            final int filterType = cur[0] & BYTE_MASK;
            if (!unfilterScanline(filterType, cur, prev, rowBytes, bpp)) {
                return null;
            }
            packScanlineToArgb(colorType, cur, dst, y * width, width);

            final byte[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        scratch.curRow = cur;
        scratch.prevRow = prev;
        return image;
    }

    private static int bytesPerPixel(final int colorType) {
        return switch (colorType) {
            case COLOR_RGB -> 3;
            case COLOR_RGBA -> 4;
            default -> 0;
        };
    }

    private static boolean unfilterScanline(
            final int filterType, final byte[] cur, final byte[] prev, final int rowBytes, final int bpp) {
        final int end = 1 + rowBytes;
        switch (filterType) {
            case FILTER_NONE -> {
                return true;
            }
            case FILTER_SUB -> {
                for (int i = 1 + bpp; i < end; i++) {
                    cur[i] = (byte) ((cur[i] & BYTE_MASK) + (cur[i - bpp] & BYTE_MASK));
                }
                return true;
            }
            case FILTER_UP -> {
                for (int i = 1; i < end; i++) {
                    cur[i] = (byte) ((cur[i] & BYTE_MASK) + (prev[i] & BYTE_MASK));
                }
                return true;
            }
            case FILTER_AVERAGE -> {
                for (int i = 1; i < 1 + bpp; i++) {
                    cur[i] = (byte) ((cur[i] & BYTE_MASK) + ((prev[i] & BYTE_MASK) >>> 1));
                }
                for (int i = 1 + bpp; i < end; i++) {
                    final int left = cur[i - bpp] & BYTE_MASK;
                    final int up = prev[i] & BYTE_MASK;
                    cur[i] = (byte) ((cur[i] & BYTE_MASK) + ((left + up) >>> 1));
                }
                return true;
            }
            case FILTER_PAETH -> {
                for (int i = 1; i < 1 + bpp; i++) {
                    cur[i] = (byte) ((cur[i] & BYTE_MASK) + (prev[i] & BYTE_MASK));
                }
                for (int i = 1 + bpp; i < end; i++) {
                    final int a = cur[i - bpp] & BYTE_MASK;
                    final int b = prev[i] & BYTE_MASK;
                    final int c = prev[i - bpp] & BYTE_MASK;
                    final int p = a + b - c;
                    final int pa = Math.abs(p - a);
                    final int pb = Math.abs(p - b);
                    final int pc = Math.abs(p - c);
                    final int pred = (pa <= pb && pa <= pc) ? a : (pb <= pc ? b : c);
                    cur[i] = (byte) ((cur[i] & BYTE_MASK) + pred);
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private static void packScanlineToArgb(
            final int colorType, final byte[] cur, final int[] dst, final int dstOffset, final int width) {
        int srcPos = 1;
        switch (colorType) {
            case COLOR_RGBA -> {
                for (int x = 0; x < width; x++) {
                    final int r = cur[srcPos++] & BYTE_MASK;
                    final int g = cur[srcPos++] & BYTE_MASK;
                    final int b = cur[srcPos++] & BYTE_MASK;
                    final int a = cur[srcPos++] & BYTE_MASK;
                    dst[dstOffset + x] = (a << SHIFT_ALPHA) | (r << SHIFT_RED) | (g << SHIFT_GREEN) | b;
                }
            }
            case COLOR_RGB -> {
                for (int x = 0; x < width; x++) {
                    final int r = cur[srcPos++] & BYTE_MASK;
                    final int g = cur[srcPos++] & BYTE_MASK;
                    final int b = cur[srcPos++] & BYTE_MASK;
                    dst[dstOffset + x] = OPAQUE_ALPHA | (r << SHIFT_RED) | (g << SHIFT_GREEN) | b;
                }
            }
            default -> throw new IllegalStateException("Unsupported colorType: " + colorType);
        }
    }

    private static int readIntBe(final byte[] buf, final int offset) {
        return ((buf[offset] & BYTE_MASK) << SHIFT_ALPHA)
                | ((buf[offset + 1] & BYTE_MASK) << SHIFT_RED)
                | ((buf[offset + 2] & BYTE_MASK) << SHIFT_GREEN)
                | (buf[offset + 3] & BYTE_MASK);
    }

    private static final class DecoderScratch {
        private final Inflater inflater = new Inflater();
        private byte[] curRow = new byte[0];
        private byte[] prevRow = new byte[0];

        void ensureCapacity(final int needed) {
            if (curRow.length < needed) {
                curRow = new byte[needed];
                prevRow = new byte[needed];
            }
        }
    }
}
