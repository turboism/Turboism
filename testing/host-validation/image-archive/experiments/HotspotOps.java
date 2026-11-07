package dev.turboism.validation.experiments;

import com.live2d.cubism.doc.model.ACForm;
import com.live2d.cubism.doc.model.deformer.warp.CWarpDeformerForm;
import com.live2d.doc.selection.IPointRef;
import com.live2d.graphics3d.type.GVector2;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import kotlin.io.ByteStreamsKt;

/** Throwaway, host-attested candidates. No global ImageIO setting, payload cache or native writes. */
public final class HotspotOps {
    static String mode = "none";
    static boolean shadow;
    private static final Map<String, LongAdder> counts = new java.util.concurrent.ConcurrentHashMap<>();
    private static final MessageDigest archiveDigest = sha();
    private HotspotOps() {}
    static void add(String name, long count) { counts.computeIfAbsent(name, key -> new LongAdder()).add(count); }
    static Map<String, Long> snapshot() {
        var out = new HashMap<String, Long>();
        counts.forEach((key, value) -> out.put(key, value.sum()));
        return out;
    }
    private static MessageDigest sha() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException error) { throw new AssertionError(error); }
    }
    static synchronized String archiveHash() { return HexFormat.of().formatHex(archiveDigest.digest()); }

    public static float parseFloat(String token) {
        add("floatCalls", 1);
        if (!mode.equals("float") || token == null) return Float.parseFloat(token);
        boolean hit = false;
        float value = 0;
        int n = token.length();
        if (n == 1 && digit(token.charAt(0))) { value = token.charAt(0) - '0'; hit = true; }
        else if (n == 2 && token.charAt(0) == '-' && digit(token.charAt(1))) {
            value = -((float) (token.charAt(1) - '0')); hit = true;
        } else if (n == 3 && digit(token.charAt(0)) && token.charAt(1) == '.' && token.charAt(2) == '0') {
            value = token.charAt(0) - '0'; hit = true;
        } else if (n == 4 && token.charAt(0) == '-' && digit(token.charAt(1))
                && token.charAt(2) == '.' && token.charAt(3) == '0') {
            value = -((float) (token.charAt(1) - '0')); hit = true;
        }
        if (!hit) return Float.parseFloat(token);
        add("floatFastHits", 1);
        if (shadow) {
            if (Float.floatToRawIntBits(value) != Float.floatToRawIntBits(Float.parseFloat(token)))
                throw new AssertionError("float raw bits mismatch");
            add("floatCompared", 1);
        }
        return value;
    }
    private static boolean digit(char c) { return c >= '0' && c <= '9'; }

    public static byte[] readArchive(InputStream stream) throws IOException {
        add("archiveCalls", 1);
        long start = System.nanoTime();
        try {
            boolean eligible = stream.getClass() == ByteArrayInputStream.class || stream.getClass() == ZipInputStream.class;
            byte[] result = mode.equals("archive") && eligible ? stream.readAllBytes() : ByteStreamsKt.readBytes(stream);
            if (mode.equals("archive") && eligible) add("archivePrepared", 1);
            add("archiveBytes", result.length);
            // Length framing covers multiple XML reads; identical digest work runs in every arm.
            synchronized (HotspotOps.class) {
                archiveDigest.update((byte) (result.length >>> 24)); archiveDigest.update((byte) (result.length >>> 16));
                archiveDigest.update((byte) (result.length >>> 8)); archiveDigest.update((byte) result.length);
                archiveDigest.update(result);
            }
            return result;
        } finally { add("archiveNs", System.nanoTime() - start); }
    }

    public static BufferedImage readImage(InputStream stream) throws IOException {
        add("imageCalls", 1);
        long start = System.nanoTime();
        try {
            if (!mode.equals("png") || !pngEligible(stream)) return ImageIO.read(stream);
            byte[] replay = null;
            if (shadow) {
                stream.mark(Integer.MAX_VALUE); replay = stream.readAllBytes(); stream.reset();
            }
            BufferedImage result = memoryDecode(stream);
            add("imagePrepared", 1);
            if (shadow) {
                BufferedImage original = ImageIO.read(new ByteArrayInputStream(replay));
                samePixels(original, result);
                if (original != null) original.flush();
                add("imageCompared", 1);
            }
            return result;
        } finally { add("imageNs", System.nanoTime() - start); }
    }

    private static boolean pngEligible(InputStream stream) throws IOException {
        if (stream == null || stream.getClass() != ByteArrayInputStream.class || stream.available() > 268_435_456) return false;
        stream.mark(8); byte[] header = stream.readNBytes(8); stream.reset();
        return Arrays.equals(header, new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10});
    }
    static BufferedImage memoryDecode(InputStream stream) throws IOException {
        // ImageIO.read(ImageInputStream) closes its input on a recognized reader, so close is idempotent.
        try (var input = new MemoryCacheImageInputStream(stream) {
            private boolean closed;
            @Override public void close() throws IOException { if (!closed) { closed = true; super.close(); } }
        }) { return ImageIO.read(input); }
    }
    static void samePixels(BufferedImage a, BufferedImage b) {
        if (a == null || b == null) { if (a != b) throw new AssertionError("PNG null behavior mismatch"); return; }
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight() || a.getType() != b.getType()
                || a.isAlphaPremultiplied() != b.isAlphaPremultiplied() || !a.getColorModel().equals(b.getColorModel()))
            throw new AssertionError("PNG metadata mismatch");
        int w = a.getWidth(); int[] x = new int[w], y = new int[w];
        for (int row = 0; row < a.getHeight(); row++) {
            a.getRGB(0, row, w, 1, x, 0, w); b.getRGB(0, row, w, 1, y, 0, w);
            if (!Arrays.equals(x, y)) throw new AssertionError("PNG pixel mismatch");
        }
    }

    public static void setupCalled(boolean force, boolean refresh) {
        add("setupCalls", 1); if (force) add("setupForce", 1); if (refresh) add("setupRefresh", 1);
    }
    public static List<GVector2> project(ACForm form) {
        add("projectionCalls", 1);
        if (!mode.equals("warp") || form == null || form.getClass() != CWarpDeformerForm.class) return null;
        long start = System.nanoTime();
        CWarpDeformerForm warp = (CWarpDeformerForm) form;
        float[] positions = warp.getPositions();
        if (positions == null || positions.length / 2 > 131_072) return null;
        int n = positions.length / 2;
        Object owner = n == 0 ? null : warp.getSource();
        if (n != 0 && owner == null) return null;
        var result = vectors(positions);
        if (shadow) {
            List<IPointRef> original = form.getAllPointRef();
            if (original.size() != n) throw new AssertionError("point count mismatch");
            for (int i = 0; i < n; i++) {
                GVector2 a = original.get(i).getPos(), b = result.get(i);
                if (Float.floatToRawIntBits(a.getX()) != Float.floatToRawIntBits(b.getX())
                        || Float.floatToRawIntBits(a.getY()) != Float.floatToRawIntBits(b.getY()))
                    throw new AssertionError("position raw bits mismatch");
            }
            add("projectionCompared", 1);
        }
        if (positions != warp.getPositions() || (n != 0 && owner != warp.getSource())) return null;
        add("projectionPrepared", 1); add("projectedPoints", n); add("projectionNs", System.nanoTime() - start);
        return result;
    }
    static ArrayList<GVector2> vectors(float[] positions) {
        int n = positions.length / 2;
        var out = new ArrayList<GVector2>(n);
        for (int i = 0; i < n; i++) out.add(new GVector2(positions[i * 2], positions[i * 2 + 1]));
        return out;
    }
}
