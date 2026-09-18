package dev.turboism.validation.modelupdate;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;

/** Immutable pixels from native GL readback or explicitly tagged canvas ARGB capture. */
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
    /** Canvas-composited ARGB, not claimed to be an uninterpreted GL readback. */
    static FrameReadback fromArgb(int width, int height, int[] pixels) {
        if (width <= 0 || height <= 0 || (long) width * height > 16_777_216L
            || pixels == null || pixels.length != (long) width * height) {
            throw new IllegalArgumentException("invalid canvas pixel layout");
        }
        return new FrameReadback(width, height, 0, 0, pixels.clone());
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
    /** Exact, untimed failure attribution; no pixels are masked or tolerated. */
    String difference(FrameReadback other) {
        if (width != other.width || height != other.height || format != other.format || type != other.type) {
            throw new IllegalArgumentException("incompatible pixel layouts");
        }
        int changed = 0, minX = width, minY = height, maxX = -1, maxY = -1, maxDelta = 0;
        StringBuilder examples = new StringBuilder();
        for (int index = 0; index < words.length; index++) {
            if (words[index] == other.words[index]) continue;
            int x = index % width, y = index / width;
            changed++;
            minX = Math.min(minX, x); minY = Math.min(minY, y);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
            for (int shift = 0; shift < 32; shift += 8) {
                maxDelta = Math.max(maxDelta, Math.abs(((words[index] >>> shift) & 255)
                    - ((other.words[index] >>> shift) & 255)));
            }
            if (changed <= 16) examples.append(x).append(',').append(y).append(':')
                .append(Integer.toHexString(words[index])).append("->")
                .append(Integer.toHexString(other.words[index])).append(';');
        }
        return "changedPixels=" + changed + "\nbounds=" + minX + "," + minY + ":" + maxX + "," + maxY
            + "\nmaximumChannelDelta=" + maxDelta + "\nexamples=" + examples + "\n";
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
