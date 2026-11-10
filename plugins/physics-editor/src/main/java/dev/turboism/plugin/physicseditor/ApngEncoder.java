package dev.turboism.plugin.physicseditor;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Streams an animated PNG without any external encoder.
 *
 * <p>Animated PNG is the second export format because it needs no optimizer and no extra program:
 * the whole animation is written here, frame by frame, and the frame count is patched into the
 * animation control chunk when the last frame lands. Every frame covers the full canvas with blend
 * op SOURCE and dispose op NONE, which keeps the writer simple and the output unambiguous.</p>
 *
 * <p>Frames must all have the same size. Call {@link #close()} exactly once; it patches the frame
 * count and writes the trailing chunk.</p>
 */
final class ApngEncoder implements AutoCloseable {

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final int CHANNEL_COUNT = 4;
    private static final int BIT_DEPTH = 8;
    private static final int COLOR_TYPE_RGBA = 6;
    private static final int COMPRESSION_DEFLATE = 0;
    private static final int FILTER_ADAPTIVE = 0;
    private static final int INTERLACE_NONE = 0;
    private static final int DISPOSE_OP_NONE = 0;
    private static final int BLEND_OP_SOURCE = 0;
    private static final int DEFAULT_DELAY_DENOMINATOR = 1000;
    private static final int FILTER_TYPE_NONE = 0;
    private static final int CHUNK_OVERHEAD = 12;
    private static final int ACTL_SEQUENCE_LENGTH = 8;
    private static final int BUFFER_FLOOR_BYTES = 1 << 16;
    private static final String ANIMATION_CONTROL_TYPE = "acTL";

    private final Path destination;
    private final int width;
    private final int height;
    private final DataOutputStream output;
    private final CRC32 crc = new CRC32();
    private final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
    private final long animationControlDataOffset;
    /** Bytes written so far, so the frame-count patch lands on the animation chunk data. */
    private long position;

    private int sequence;
    private int frameCount;
    private boolean closed;

    /**
     * Rewrites a recorded GIF as an animated PNG.
     *
     * <p>The recorder always captures into an intermediate GIF, so both formats share one capture
     * path and only the export step differs. Frames keep the captured size and gain the given delay.</p>
     *
     * @param source recorded GIF
     * @param destination file to write
     * @param delayMillis per-frame delay
     * @throws IOException when either file cannot be read or written
     */
    static void write(final Path source, final Path destination, final int delayMillis) throws IOException {
        try (var input = javax.imageio.ImageIO.createImageInputStream(source.toFile())) {
            final var reader =
                    javax.imageio.ImageIO.getImageReadersByFormatName("gif").next();
            try {
                reader.setInput(input);
                final int frames = reader.getNumImages(true);
                if (frames <= 0) throw new IOException("recorded animation has no frames");
                final java.awt.image.BufferedImage first = reader.read(0);
                try (var encoder = new ApngEncoder(destination, first.getWidth(), first.getHeight())) {
                    encoder.append(first, delayMillis);
                    for (int index = 1; index < frames; index++) {
                        encoder.append(reader.read(index), delayMillis);
                    }
                }
            } finally {
                reader.dispose();
            }
        }
    }

    /**
     * Opens the file and writes the header plus a zero frame count.
     *
     * @param destination file to write; an existing file is truncated
     * @param width frame width in pixels
     * @param height frame height in pixels
     * @throws IOException when the file cannot be created
     */
    ApngEncoder(final Path destination, final int width, final int height) throws IOException {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("frame size must be positive");
        this.destination = destination;
        this.width = width;
        this.height = height;
        final OutputStream file = new BufferedOutputStream(Files.newOutputStream(destination), 1 << 16);
        this.output = new DataOutputStream(file);
        output.write(PNG_SIGNATURE);
        position = PNG_SIGNATURE.length;
        writeHeader();
        // The patch target is the animation chunk's data, which starts after its length and type.
        animationControlDataOffset = position + 4L + ANIMATION_CONTROL_TYPE.length();
        writeChunk(ANIMATION_CONTROL_TYPE, animationControl(0));
        output.flush();
    }

    /**
     * Appends one frame.
     *
     * @param frame pixels to append; must match the declared size
     * @param delayMillis how long the frame stays visible
     * @throws IOException when the frame cannot be written
     */
    void append(final java.awt.image.BufferedImage frame, final int delayMillis) throws IOException {
        if (closed) throw new IllegalStateException("animated PNG is already closed");
        if (frame.getWidth() != width || frame.getHeight() != height) {
            throw new IOException("frame size changed during recording");
        }
        final byte[] control = frameControl(sequence++, delayMillis);
        if (frameCount == 0) {
            writeChunk("fcTL", control);
            writeChunk("IDAT", compressedFrame(frame));
        } else {
            writeChunk("fcTL", control);
            final byte[] data = compressedFrame(frame);
            final byte[] framed = new byte[4 + data.length];
            writeInt(framed, 0, sequence++);
            System.arraycopy(data, 0, framed, 4, data.length);
            writeChunk("fdAT", framed);
        }
        frameCount++;
        output.flush();
    }

    /**
     * Writes the real frame count over the placeholder and appends the trailing chunk.
     *
     * @throws IOException when the file cannot be finished
     */
    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
            writeChunk("IEND", new byte[0]);
            output.flush();
            deflater.end();
            output.close();
            // Patch after the stream closes: one writer owns the file at a time.
            patchAnimationControl();
        } finally {
            if (!closed) deflater.end();
        }
    }

    /**
     * Writes the final frame count into the animation chunk and refreshes that chunk's checksum.
     *
     * @throws IOException when the file cannot be reopened for the patch
     */
    private void patchAnimationControl() throws IOException {
        final byte[] data = animationControl(frameCount);
        final byte[] type = ANIMATION_CONTROL_TYPE.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        crc.reset();
        crc.update(type);
        crc.update(data);
        try (var file = new java.io.RandomAccessFile(destination.toFile(), "rw")) {
            file.seek(animationControlDataOffset);
            file.write(data);
            file.writeInt((int) crc.getValue());
        }
    }

    private void writeHeader() throws IOException {
        final byte[] header = new byte[13];
        writeInt(header, 0, width);
        writeInt(header, 4, height);
        header[8] = (byte) BIT_DEPTH;
        header[9] = (byte) COLOR_TYPE_RGBA;
        header[10] = (byte) COMPRESSION_DEFLATE;
        header[11] = (byte) FILTER_ADAPTIVE;
        header[12] = (byte) INTERLACE_NONE;
        writeChunk("IHDR", header);
    }

    private byte[] animationControl(final int frames) {
        final byte[] data = new byte[ACTL_SEQUENCE_LENGTH];
        writeInt(data, 0, frames);
        writeInt(data, 4, 0);
        return data;
    }

    private byte[] frameControl(final int sequenceNumber, final int delayMillis) {
        final byte[] data = new byte[26];
        writeInt(data, 0, sequenceNumber);
        writeInt(data, 4, width);
        writeInt(data, 8, height);
        writeInt(data, 12, 0);
        writeInt(data, 16, 0);
        final int delay = Math.max(0, delayMillis);
        data[20] = (byte) (delay >> 8);
        data[21] = (byte) delay;
        data[22] = (byte) (DEFAULT_DELAY_DENOMINATOR >> 8);
        data[23] = (byte) DEFAULT_DELAY_DENOMINATOR;
        data[24] = (byte) DISPOSE_OP_NONE;
        data[25] = (byte) BLEND_OP_SOURCE;
        return data;
    }

    /** Encodes one frame as filter type NONE scanlines behind a fresh deflate stream. */
    private byte[] compressedFrame(final java.awt.image.BufferedImage frame) throws IOException {
        final int stride = width * CHANNEL_COUNT;
        final byte[] raw = new byte[(stride + 1) * height];
        final int[] row = new int[width];
        int offset = 0;
        for (int y = 0; y < height; y++) {
            frame.getRGB(0, y, width, 1, row, 0, width);
            raw[offset++] = (byte) FILTER_TYPE_NONE;
            for (int x = 0; x < width; x++) {
                final int argb = row[x];
                raw[offset++] = (byte) (argb >> 16);
                raw[offset++] = (byte) (argb >> 8);
                raw[offset++] = (byte) argb;
                raw[offset++] = (byte) (argb >>> 24);
            }
        }
        deflater.reset();
        deflater.setInput(raw);
        deflater.finish();
        final var compressed = new java.io.ByteArrayOutputStream(Math.max(BUFFER_FLOOR_BYTES, raw.length / 4));
        final byte[] buffer = new byte[BUFFER_FLOOR_BYTES];
        while (!deflater.finished()) {
            final int produced = deflater.deflate(buffer);
            compressed.write(buffer, 0, produced);
        }
        return compressed.toByteArray();
    }

    private void writeChunk(final String type, final byte[] data) throws IOException {
        position += CHUNK_OVERHEAD + data.length;
        output.writeInt(data.length);
        final byte[] name = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        output.write(name);
        output.write(data);
        crc.reset();
        crc.update(name);
        crc.update(data);
        output.writeInt((int) crc.getValue());
    }

    private static void writeInt(final byte[] target, final int offset, final int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }
}
