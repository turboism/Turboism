package dev.turboism.validation.experiments;

import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import kotlin.io.ByteStreamsKt;

/** Offline boundaries: byte equality, current-entry EOF/CRC, PNG pixels and fresh float outputs. */
public final class HotspotOpsTest {
    private HotspotOpsTest() {}
    public static void main(String[] args) throws Exception {
        var random = new Random(47);
        for (int size : new int[] {0, 1, 8191, 8192, 8193, 1_048_577}) {
            byte[] data = new byte[size]; random.nextBytes(data);
            for (boolean zipped : new boolean[] {false, true}) {
                byte[] encoded = zipped ? zip(data) : data;
                HotspotOps.mode = "none"; byte[] a = HotspotOps.readArchive(stream(encoded, zipped));
                HotspotOps.mode = "archive"; InputStream input = stream(encoded, zipped);
                byte[] b = HotspotOps.readArchive(input);
                if (!Arrays.equals(a, b) || !Arrays.equals(data, b)) throw new AssertionError("archive bytes");
                if (input.read() != -1) throw new AssertionError("EOF lost");
                if (zipped && !((ZipInputStream) input).getNextEntry().getName().equals("second"))
                    throw new AssertionError("current entry boundary lost");
                input.close();
            }
        }
        byte[] damaged = zip(new byte[65536]); damaged = Arrays.copyOf(damaged, 45);
        String legacy = failure(damaged, false), candidate = failure(damaged, true);
        if (!legacy.equals(candidate) || legacy.equals("NONE")) throw new AssertionError("truncated ZIP errors: " + legacy + "/" + candidate);
        for (int type : new int[] {BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_4BYTE_ABGR,
                                   BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_BYTE_GRAY}) {
            var source = new BufferedImage(257, 263, type);
            for (int y = 0; y < source.getHeight(); y++) for (int x = 0; x < source.getWidth(); x++)
                source.setRGB(x, y, random.nextInt());
            var encoded = new ByteArrayOutputStream(); ImageIO.write(source, "PNG", encoded);
            HotspotOps.mode = "png"; HotspotOps.shadow = true;
            HotspotOps.samePixels(ImageIO.read(new ByteArrayInputStream(encoded.toByteArray())),
                    HotspotOps.readImage(new ByteArrayInputStream(encoded.toByteArray())));
        }
        if (HotspotOps.memoryDecode(new ByteArrayInputStream(new byte[] {1, 2, 3})) != null)
            throw new AssertionError("unknown reader fallback");
        float[] values = {0, -0.0f, Float.intBitsToFloat(0x7fc12345), Float.POSITIVE_INFINITY,
                          Float.NEGATIVE_INFINITY, Float.MIN_VALUE, 7};
        var vectors = HotspotOps.vectors(values);
        if (vectors.size() != 3 || vectors == HotspotOps.vectors(values)) throw new AssertionError("fresh list");
        for (int i = 0; i < 3; i++) {
            if (Float.floatToRawIntBits(values[i * 2]) != Float.floatToRawIntBits(vectors.get(i).getX())
                    || Float.floatToRawIntBits(values[i * 2 + 1]) != Float.floatToRawIntBits(vectors.get(i).getY()))
                throw new AssertionError("float bits changed");
        }
        vectors.get(0).setX(88);
        if (Float.floatToRawIntBits(values[0]) != 0) throw new AssertionError("source modified");
        if (!HotspotOps.vectors(new float[0]).isEmpty()) throw new AssertionError("empty array");
        HotspotOps.mode = "float"; HotspotOps.shadow = true;
        var tokens = new ArrayList<String>(List.of("0", "-0", "-0.0", "1.0", "9.0", "-9.0", "+0", " 0 ",
                "NaN", "Infinity", "-Infinity", "0x1.0p-149", "1e100", "9e-100", "invalid", "", "--1"));
        tokens.add(null);
        for (int i = 0; i < 50000; i++) tokens.add(Float.toString(Float.intBitsToFloat(random.nextInt())));
        for (String token : tokens) {
            Float expected = null; Class<?> error = null;
            try { expected = Float.parseFloat(token); } catch (RuntimeException failure) { error = failure.getClass(); }
            try {
                float actual = HotspotOps.parseFloat(token);
                if (error != null || Float.floatToRawIntBits(actual) != Float.floatToRawIntBits(expected))
                    throw new AssertionError("float case mismatch");
            } catch (RuntimeException failure) {
                if (error != failure.getClass()) throw new AssertionError("float exception mismatch", failure);
            }
        }
        System.out.println("HotspotOpsTest PASS");
    }
    private static byte[] zip(byte[] data) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry("first")); z.write(data); z.closeEntry();
            z.putNextEntry(new ZipEntry("second")); z.write(17); z.closeEntry();
        }
        return out.toByteArray();
    }
    private static InputStream stream(byte[] bytes, boolean zipped) throws IOException {
        if (!zipped) return new ByteArrayInputStream(bytes);
        var zip = new ZipInputStream(new ByteArrayInputStream(bytes)); zip.getNextEntry(); return zip;
    }
    private static String failure(byte[] bytes, boolean candidate) {
        try (var stream = stream(bytes, true)) {
            if (candidate) stream.readAllBytes(); else ByteStreamsKt.readBytes(stream);
            return "NONE";
        } catch (IOException error) { return error.getClass().getName(); }
    }
}
