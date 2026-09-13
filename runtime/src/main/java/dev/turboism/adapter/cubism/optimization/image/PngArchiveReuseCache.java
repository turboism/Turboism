package dev.turboism.adapter.cubism.optimization.image;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded SHA-256 content proofs captured before decoded native pixels escape to callers.
 * No image, pixel array, encoded byte array or native owner is retained strongly.
 */
final class PngArchiveReuseCache {
    private static final long MAX_PIXELS = 4096L * 4096L;
    private static final int MAX_PNG_BYTES = 64 * 1024 * 1024;
    private static final byte[] PNG_HEADER = {(byte)137,80,78,71,13,10,26,10};
    private final int maximumEntries;
    private final ReferenceQueue<Object> collected = new ReferenceQueue<>();
    private final Map<IdentityReference, Proof> proofs = new LinkedHashMap<>();

    PngArchiveReuseCache(final int maximumEntries) {
        if (maximumEntries < 1) throw new IllegalArgumentException("maximumEntries must be positive");
        this.maximumEntries = maximumEntries;
    }

    void remember(final Object owner, final Object image, final byte[] png,
                  final int width, final int height, final int[] pixels) {
        synchronized (this) {
            prune();
            proofs.remove(new IdentityReference(owner, null));
        }
        if (!eligible(owner, image, png, width, height, pixels)) return;
        final Proof proof = new Proof(new WeakReference<>(image), new WeakReference<>(png),
            width, height, digest().digest(png), pixelDigest(width, height, pixels));
        synchronized (this) {
            prune();
            while (proofs.size() >= maximumEntries) proofs.remove(proofs.keySet().iterator().next());
            proofs.put(new IdentityReference(owner, collected), proof);
        }
        Reference.reachabilityFence(owner);
        Reference.reachabilityFence(image);
        Reference.reachabilityFence(png);
    }

    byte[] reusable(final Object owner, final Object image, final byte[] png,
                    final int width, final int height, final int[] pixels) {
        final IdentityReference key = new IdentityReference(owner, null);
        final Proof proof;
        synchronized (this) { prune(); proof = proofs.get(key); }
        if (proof == null) return null;
        final boolean valid = eligible(owner, image, png, width, height, pixels)
            && proof.image().get() == image && proof.png().get() == png
            && proof.width() == width && proof.height() == height
            && MessageDigest.isEqual(proof.encodedDigest(), digest().digest(png))
            && MessageDigest.isEqual(proof.pixelDigest(), pixelDigest(width, height, pixels));
        if (!valid) synchronized (this) { proofs.remove(key, proof); }
        Reference.reachabilityFence(owner);
        return valid ? png : null;
    }

    synchronized int size() { prune(); return proofs.size(); }
    synchronized void clear() { proofs.clear(); while (collected.poll() != null) { /* drain */ } }

    private void prune() {
        for (Reference<?> reference; (reference = collected.poll()) != null;) proofs.remove(reference);
    }

    private static boolean eligible(final Object owner, final Object image, final byte[] png,
                                    final int width, final int height, final int[] pixels) {
        if (owner == null || image == null || png == null || pixels == null
            || width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS
            || (long) width * height != pixels.length
            || png.length < PNG_HEADER.length || png.length > MAX_PNG_BYTES) return false;
        for (int i = 0; i < PNG_HEADER.length; i++) if (png[i] != PNG_HEADER[i]) return false;
        return true;
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA-256 unavailable", failure); }
    }

    private static byte[] pixelDigest(final int width, final int height, final int[] pixels) {
        final MessageDigest digest = digest();
        final byte[] scratch = new byte[Math.min(64 * 1024, pixels.length * 4)];
        digest.update((byte)(width >>> 24)); digest.update((byte)(width >>> 16));
        digest.update((byte)(width >>> 8)); digest.update((byte)width);
        digest.update((byte)(height >>> 24)); digest.update((byte)(height >>> 16));
        digest.update((byte)(height >>> 8)); digest.update((byte)height);
        int used = 0;
        for (int pixel : pixels) {
            scratch[used++] = (byte)(pixel >>> 24); scratch[used++] = (byte)(pixel >>> 16);
            scratch[used++] = (byte)(pixel >>> 8); scratch[used++] = (byte)pixel;
            if (used == scratch.length) { digest.update(scratch, 0, used); used = 0; }
        }
        if (used != 0) digest.update(scratch, 0, used);
        return digest.digest();
    }

    private record Proof(WeakReference<Object> image, WeakReference<byte[]> png, int width, int height,
                         byte[] encodedDigest, byte[] pixelDigest) { }

    private static final class IdentityReference extends WeakReference<Object> {
        private final int hash;
        IdentityReference(final Object referent, final ReferenceQueue<Object> queue) {
            super(referent, queue); hash = System.identityHashCode(referent);
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(final Object other) {
            if (this == other) return true;
            final Object value = get();
            return value != null && other instanceof IdentityReference reference && value == reference.get();
        }
    }
}
