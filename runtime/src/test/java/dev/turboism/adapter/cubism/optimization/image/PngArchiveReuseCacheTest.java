package dev.turboism.adapter.cubism.optimization.image;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class PngArchiveReuseCacheTest {
    private static byte[] png() { return new byte[]{(byte)137,80,78,71,13,10,26,10,1,2,3,4}; }

    @Test
    void reusesOnlyTheSameUnchangedDecodedPng() {
        var cache = new PngArchiveReuseCache(16);
        var resource = new Object(); var image = new Object();
        byte[] bytes = png(); int[] pixels = {0xff102030, 0xff405060, 0xff708090, 0xffaabbcc};
        cache.remember(resource, image, bytes, 2, 2, pixels);
        assertSame(bytes, cache.reusable(resource, image, bytes, 2, 2, pixels));
        assertNull(cache.reusable(new Object(), image, bytes, 2, 2, pixels));
        assertNull(cache.reusable(resource, new Object(), bytes, 2, 2, pixels));
        assertNull(cache.reusable(resource, image, bytes.clone(), 2, 2, pixels));
    }

    @Test
    void detectsRawPixelMutationWithoutAnyNativeDirtyNotification() {
        var cache = new PngArchiveReuseCache(16);
        var resource = new Object(); var image = new Object(); byte[] bytes = png();
        int[] pixels = {1, 2, 3, 4};
        cache.remember(resource, image, bytes, 2, 2, pixels);
        pixels[2] = 123;
        assertNull(cache.reusable(resource, image, bytes, 2, 2, pixels));
        pixels[2] = 3;
        assertNull(cache.reusable(resource, image, bytes, 2, 2, pixels), "changed proof is invalidated");
    }

    @Test
    void detectsEncodedByteMutationAndNormalResourceInvalidation() {
        var cache = new PngArchiveReuseCache(16);
        var resource = new Object(); var image = new Object(); byte[] bytes = png(); int[] pixels = {1};
        cache.remember(resource, image, bytes, 1, 1, pixels);
        bytes[bytes.length - 1]++;
        assertNull(cache.reusable(resource, image, bytes, 1, 1, pixels));
        cache.remember(resource, image, bytes, 1, 1, pixels);
        assertNull(cache.reusable(resource, image, null, 1, 1, pixels));
    }

    @Test
    void rejectsWrongDimensionsUnsupportedLayoutsAndNonPng() {
        var cache = new PngArchiveReuseCache(16);
        var resource = new Object(); var image = new Object(); byte[] bytes = png(); int[] pixels = {1,2,3,4};
        cache.remember(resource, image, bytes, 2, 2, pixels);
        assertNull(cache.reusable(resource, image, bytes, 1, 4, pixels));
        cache.remember(resource, image, new byte[12], 2, 2, pixels);
        assertNull(cache.reusable(resource, image, new byte[12], 2, 2, pixels));
        cache.remember(resource, image, bytes, Integer.MAX_VALUE, Integer.MAX_VALUE, pixels);
        assertNull(cache.reusable(resource, image, bytes, 2, 2, pixels));
        cache.remember(resource, image, bytes, 2, 2, new int[5]);
        assertNull(cache.reusable(resource, image, bytes, 2, 2, pixels));
    }

    @Test
    void boundsMetadataAndClearDropsAllProofs() {
        var cache = new PngArchiveReuseCache(2);
        Object[] owners = {new Object(),new Object(),new Object()}; Object image = new Object();
        byte[] bytes = png(); int[] pixels = {1};
        for (Object owner : owners) cache.remember(owner, image, bytes, 1, 1, pixels);
        assertEquals(2, cache.size());
        assertNull(cache.reusable(owners[0], image, bytes, 1, 1, pixels));
        assertSame(bytes, cache.reusable(owners[2], image, bytes, 1, 1, pixels));
        cache.clear(); assertEquals(0, cache.size());
        assertNull(cache.reusable(owners[2], image, bytes, 1, 1, pixels));
    }

    @Test
    void keysUseReferenceIdentityEvenWhenNativeObjectsCompareEqual() {
        class EqualOwner {
            @Override public boolean equals(Object other) { return other instanceof EqualOwner; }
            @Override public int hashCode() { return 1; }
        }
        var cache = new PngArchiveReuseCache(4);
        Object first = new EqualOwner(), second = new EqualOwner(), image = new Object();
        byte[] a = png(), b = png();
        cache.remember(first, image, a, 1, 1, new int[]{1});
        cache.remember(second, image, b, 1, 1, new int[]{2});
        assertEquals(2, cache.size());
        assertSame(a, cache.reusable(first, image, a, 1, 1, new int[]{1}));
        assertSame(b, cache.reusable(second, image, b, 1, 1, new int[]{2}));
    }

    @Test
    void concurrentIndependentResourcesDoNotMixProofs() throws Exception {
        var cache = new PngArchiveReuseCache(128);
        Object[] owners = new Object[80];
        for (int i=0;i<owners.length;i++) owners[i]=new Object();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i=0;i<owners.length;i++) {
                final int index=i;
                tasks.add(executor.submit(() -> {
                    Object image=new Object();byte[] bytes=png();int[] pixels={index};
                    cache.remember(owners[index],image,bytes,1,1,pixels);
                    assertSame(bytes,cache.reusable(owners[index],image,bytes,1,1,pixels));
                }));
            }
            for (var task : tasks) task.get(5,java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS));
        }
        java.lang.ref.Reference.reachabilityFence(owners);
    }

    @Test
    void hashesEveryPixelIncludingBeyondTheScratchBufferBoundary() {
        var cache = new PngArchiveReuseCache(2);
        Object owner = new Object(), image = new Object(); byte[] bytes = png();
        int[] pixels = new int[257 * 257]; Arrays.fill(pixels, 0x80abcdef);
        cache.remember(owner, image, bytes, 257, 257, pixels);
        assertSame(bytes, cache.reusable(owner, image, bytes, 257, 257, pixels));
        pixels[pixels.length - 1] ^= 1;
        assertNull(cache.reusable(owner, image, bytes, 257, 257, pixels));
    }
}
