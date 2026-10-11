package dev.turboism.validation.modelimport3d;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Deterministic domain digest: SHA-256 over a canonical little-endian encoding
 * of (domain label, float payload, uv payload, index payload, draw order).
 * Element order and encoding are frozen before any probe run; float tolerance
 * never applies to these digests.
 */
final class Digest {

    private Digest() {}

    static String of(String domain, float[] positions, float[] uvs,
                     int[] indices, Integer drawOrder) {
        byte[] label = domain.getBytes(StandardCharsets.UTF_8);
        int n = 4 + label.length
                + 4 + (positions == null ? 0 : positions.length * 4)
                + 4 + (uvs == null ? 0 : uvs.length * 4)
                + 4 + (indices == null ? 0 : indices.length * 4)
                + 1 + (drawOrder == null ? 0 : 4);
        ByteBuffer b = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(label.length).put(label);
        putFloats(b, positions);
        putFloats(b, uvs);
        putInts(b, indices);
        // Presence is explicit: absent drawOrder can never collide with a
        // real value (including Integer.MIN_VALUE).
        b.put((byte) (drawOrder == null ? 0 : 1));
        if (drawOrder != null) b.putInt(drawOrder);
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(b.array()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void putFloats(ByteBuffer b, float[] a) {
        b.putInt(a == null ? -1 : a.length);
        if (a != null) for (float v : a) b.putFloat(v);
    }

    private static void putInts(ByteBuffer b, int[] a) {
        b.putInt(a == null ? -1 : a.length);
        if (a != null) for (int v : a) b.putInt(v);
    }
}
