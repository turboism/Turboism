package dev.turboism.validation.modelupdate;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;

/** Immutable raw pixels from the actual native GL readback, without row padding. */
final class FrameReadback {
    private final int width, height, format, type;
    private final int[] words;
    private FrameReadback(int width, int height, int format, int type, int[] words) {
        this.width = width; this.height = height; this.format = format; this.type = type; this.words = words;
    }
    static FrameReadback capture(Buffer data, int width, int height, int format, int type,
                                 int rowLength, int alignment, int skipRows, int skipPixels) {
        if (width <= 0 || height <= 0 || (long) width * height > 16_777_216L
            || (format != 6408 && format != 32993) || (type != 5121 && type != 33639 && type != 32821)
            || rowLength < 0 || skipRows < 0 || skipPixels < 0
            || (alignment != 1 && alignment != 2 && alignment != 4 && alignment != 8)
            || !(data instanceof ByteBuffer || data instanceof IntBuffer)) {
            throw new IllegalArgumentException("unsupported readback layout");
        }
        long rowBytes = (long) (rowLength == 0 ? width : rowLength) * 4;
        long stride = (rowBytes + alignment - 1) / alignment * alignment;
        long first = (long) skipRows * stride + (long) skipPixels * 4;
        long required = first + (long) (height - 1) * stride + (long) width * 4;
        long available = (long) data.remaining() * (data instanceof IntBuffer ? 4 : 1);
        if (required > available || required > Integer.MAX_VALUE || stride % 4 != 0) {
            throw new IllegalArgumentException("readback exceeds buffer");
        }
        ByteBuffer bytes = data instanceof ByteBuffer b ? b.duplicate().order(ByteOrder.nativeOrder()) : null;
        int[] words = new int[width * height];
        for (int y = 0; y < height; y++) {
            int row = (int) (first + y * stride);
            for (int x = 0; x < width; x++) {
                words[y * width + x] = bytes != null ? bytes.getInt(data.position() + row + x * 4)
                    : ((IntBuffer) data).get(data.position() + row / 4 + x);
            }
        }
        return new FrameReadback(width, height, format, type, words);
    }
    int pixels() { return words.length; }
    int distinctPixels() {
        HashSet<Integer> unique = new HashSet<>();
        for (int value : words) { unique.add(value); if (unique.size() >= 64) break; }
        return unique.size();
    }
    boolean samePixels(FrameReadback other) {
        return width == other.width && height == other.height && format == other.format && type == other.type
            && Arrays.equals(words, other.words);
    }
    String digest() throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        ByteBuffer row = ByteBuffer.allocate(width * 4);
        for (int y = 0; y < height; y++) {
            row.clear();
            for (int x = 0; x < width; x++) row.putInt(words[y * width + x]);
            sha.update(row.array());
        }
        return HexFormat.of().formatHex(sha.digest());
    }
}
