package dev.turboism.adapter.cubism.optimization.psd;

import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Provides thread-safe, zero-per-scanline PackBits RLE decompression for Cubism PSD streams.
 *
 * <p>Eliminates the shared mutable static {@code int[] e} header race and per-scanline {@code byte[]}
 * allocations in {@code com.live2d.graphics.psd.d.c} while preserving exact token semantics.
 */
public final class PsdRleBridge {

    private static final int UINT16_BYTES = 2;
    private static final int BYTE_MASK = 0xFF;
    private static final int SHIFT_HIGH_BYTE = 8;
    private static final int INITIAL_HEADER_PADDING = 16;
    private static final int PACKBITS_MIN_FACTOR = 2;

    private static final String READER_WRAPPER_FIELD = "a";
    private static final String STREAM_BYTES_FIELD = "b";
    private static final String STREAM_POS_FIELD = "c";
    private static final String RESULT_CLASS = "com.live2d.graphics.psd.d.c$a";

    private static final Object HANDLES_LOCK = new Object();
    private static final Map<ClassLoader, Handles> HANDLES_BY_LOADER = Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile Handles lastHandles;

    private PsdRleBridge() {}

    /**
     * Decodes a single RLE-compressed PSD channel directly from the underlying stream buffer.
     *
     * @param psdReader active {@code com.live2d.graphics.psd.b} stream reader
     * @param width channel pixel width
     * @param height channel row count
     * @param out preallocated channel output array sized {@code width * height}
     * @return host {@code c$a} metadata instance, or {@code null} to trigger sequential fallback
     */
    public static Object tryDecodeChannel(final Object psdReader, final int width, final int height, final byte[] out) {
        if (psdReader == null || out == null || width < 0 || height < 0) {
            return null;
        }
        final long requiredPixels = (long) width * height;
        if (requiredPixels > out.length) {
            return null;
        }
        final Handles handles = resolveHandles(psdReader);
        if (handles == null) {
            return null;
        }
        try {
            final Object dataReader = handles.wrapperDataReader.get(psdReader);
            if (dataReader == null) {
                return null;
            }
            final byte[] streamBytes = (byte[]) handles.streamBytes.get(dataReader);
            final int startPos = handles.streamPos.getInt(dataReader);
            if (streamBytes == null || startPos < 0) {
                return null;
            }
            final DecodeResult decoded = decodeChannels(streamBytes, startPos, width, height, 1, new byte[][] {out});
            if (decoded == null) {
                return null;
            }
            handles.streamPos.setInt(dataReader, decoded.nextOffset());
            return handles.newResult.invoke(decoded.scanlineLengths(), decoded.rleHeaders());
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return null;
        }
    }

    /**
     * Decodes multi-channel merged PSD image data directly from the underlying stream buffer.
     *
     * @param psdReader active {@code com.live2d.graphics.psd.b} stream reader
     * @param width image width in pixels
     * @param height image height in pixels
     * @param channels planar channel count
     * @param outs preallocated outer channel table receiving newly allocated channel planes
     * @return host {@code c$a} metadata instance, or {@code null} to trigger sequential fallback
     */
    public static Object tryDecodeMerged(
            final Object psdReader, final int width, final int height, final int channels, final byte[][] outs) {
        if (psdReader == null || outs == null || width < 0 || height < 0 || channels <= 0 || outs.length < channels) {
            return null;
        }
        final Handles handles = resolveHandles(psdReader);
        if (handles == null) {
            return null;
        }
        try {
            final Object dataReader = handles.wrapperDataReader.get(psdReader);
            if (dataReader == null) {
                return null;
            }
            final byte[] streamBytes = (byte[]) handles.streamBytes.get(dataReader);
            final int startPos = handles.streamPos.getInt(dataReader);
            if (streamBytes == null || startPos < 0) {
                return null;
            }
            final long planeBytesLong = (long) width * height;
            if (planeBytesLong > Integer.MAX_VALUE) {
                return null;
            }
            final int planeBytes = (int) planeBytesLong;
            final byte[][] scratchOuts = new byte[channels][];
            for (int ch = 0; ch < channels; ch++) {
                scratchOuts[ch] = new byte[planeBytes];
            }
            final DecodeResult decoded = decodeChannels(streamBytes, startPos, width, height, channels, scratchOuts);
            if (decoded == null) {
                return null;
            }
            System.arraycopy(scratchOuts, 0, outs, 0, channels);
            handles.streamPos.setInt(dataReader, decoded.nextOffset());
            return handles.newResult.invoke(decoded.scanlineLengths(), decoded.rleHeaders());
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return null;
        }
    }

    /**
     * Decodes PackBits RLE scanlines across {@code channels} without shared mutable state.
     *
     * <p>Matches Cubism {@code com.live2d.graphics.psd.d.c} semantics where positive headers copy
     * {@code op + 1} literal bytes and non-positive headers ({@code -128..0}) repeat {@code -op + 1}.
     *
     * @param stream source PSD byte array
     * @param startOffset byte index where the uint16 scanline length table starts
     * @param width expected decoded bytes per scanline
     * @param height scanline count per channel
     * @param channels number of contiguous planar channels
     * @param channelOuts caller-provided output arrays sized at least {@code width * height}
     * @return decoded scanline table and headers with end offset, or {@code null} on malformed input
     */
    public static DecodeResult decodeChannels(
            final byte[] stream,
            final int startOffset,
            final int width,
            final int height,
            final int channels,
            final byte[][] channelOuts) {
        if (stream == null || channelOuts == null || startOffset < 0 || width < 0 || height < 0 || channels <= 0) {
            return null;
        }
        if (channelOuts.length < channels) {
            return null;
        }
        final long totalRowsLong = (long) channels * height;
        if (totalRowsLong > Integer.MAX_VALUE) {
            return null;
        }
        final int totalRows = (int) totalRowsLong;
        final long headerBytesLong = totalRowsLong * UINT16_BYTES;
        if (headerBytesLong > stream.length - startOffset) {
            return null;
        }
        final int[] scanlineLengths = new int[totalRows];
        int pos = startOffset;
        long totalCompressedLong = 0L;
        for (int r = 0; r < totalRows; r++) {
            final int len = ((stream[pos] & BYTE_MASK) << SHIFT_HIGH_BYTE) | (stream[pos + 1] & BYTE_MASK);
            pos += UINT16_BYTES;
            scanlineLengths[r] = len;
            totalCompressedLong += len;
        }
        if (totalCompressedLong > stream.length - pos || totalCompressedLong > Integer.MAX_VALUE) {
            return null;
        }
        final int initialHeaderCap =
                (int) Math.max(INITIAL_HEADER_PADDING, totalCompressedLong / PACKBITS_MIN_FACTOR + 1);
        int[] headers = new int[initialHeaderCap];
        int headerCount = 0;

        for (int ch = 0; ch < channels; ch++) {
            final byte[] out = channelOuts[ch];
            if (out == null || (long) width * height > out.length) {
                return null;
            }
            for (int y = 0; y < height; y++) {
                final int scanLen = scanlineLengths[ch * height + y];
                final int scanEnd = pos + scanLen;
                int outPos = y * width;
                final int rowEnd = outPos + width;
                while (pos < scanEnd) {
                    final int op = stream[pos++];
                    if (headerCount == headers.length) {
                        headers = Arrays.copyOf(headers, headers.length * 2);
                    }
                    headers[headerCount++] = op;
                    if (op > 0) {
                        final int runLen = op + 1;
                        if (pos + runLen > scanEnd || outPos + runLen > rowEnd) {
                            return null;
                        }
                        System.arraycopy(stream, pos, out, outPos, runLen);
                        pos += runLen;
                        outPos += runLen;
                    } else {
                        if (pos >= scanEnd) {
                            return null;
                        }
                        final byte value = stream[pos++];
                        final int repeatCount = -op + 1;
                        if (outPos + repeatCount > rowEnd) {
                            return null;
                        }
                        Arrays.fill(out, outPos, outPos + repeatCount, value);
                        outPos += repeatCount;
                    }
                }
                if (outPos != rowEnd) {
                    return null;
                }
            }
        }
        final int[] exactHeaders = Arrays.copyOf(headers, headerCount);
        return new DecodeResult(scanlineLengths, exactHeaders, pos);
    }

    private static Handles resolveHandles(final Object psdReader) {
        final ClassLoader loader = psdReader.getClass().getClassLoader();
        final Handles cached = lastHandles;
        if (cached != null && cached.loader == loader) {
            return cached;
        }
        synchronized (HANDLES_LOCK) {
            final Handles mapped = HANDLES_BY_LOADER.get(loader);
            if (mapped != null) {
                lastHandles = mapped;
                return mapped;
            }
            try {
                final Class<?> readerClass = psdReader.getClass();
                final Field wrapperDataReader = readerClass.getDeclaredField(READER_WRAPPER_FIELD);
                wrapperDataReader.setAccessible(true);

                final Class<?> dataReaderClass = wrapperDataReader.getType();
                final Field streamBytes = dataReaderClass.getDeclaredField(STREAM_BYTES_FIELD);
                streamBytes.setAccessible(true);
                final Field streamPos = dataReaderClass.getDeclaredField(STREAM_POS_FIELD);
                streamPos.setAccessible(true);

                final Class<?> resultClass = Class.forName(RESULT_CLASS, false, loader);
                final Constructor<?> resultCtor = resultClass.getConstructor(int[].class, int[].class);
                final MethodHandle newResult = MethodHandles.publicLookup().unreflectConstructor(resultCtor);

                final Handles created = new Handles(loader, wrapperDataReader, streamBytes, streamPos, newResult);
                HANDLES_BY_LOADER.put(loader, created);
                lastHandles = created;
                return created;
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                return null;
            }
        }
    }

    /** Result of decoding a contiguous multi-channel PackBits RLE block. */
    public record DecodeResult(int[] scanlineLengths, int[] rleHeaders, int nextOffset) {}

    private record Handles(
            ClassLoader loader, Field wrapperDataReader, Field streamBytes, Field streamPos, MethodHandle newResult) {}
}
