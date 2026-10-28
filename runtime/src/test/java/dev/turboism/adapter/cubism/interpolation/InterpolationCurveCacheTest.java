package dev.turboism.adapter.cubism.interpolation;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

final class InterpolationCurveCacheTest {
    @Test
    void oneOffInputsRetainOnlyBoundedSignaturesAndRepeatedInputAdmitsResult() {
        var cache = new InterpolationCurveCache();
        cache.enabled(true);
        var key = new InterpolationCurveCache.Key(3, new int[] {1, 2});
        assertFalse(cache.admit(key));
        assertNull(cache.get(key));
        assertTrue(cache.admit(new InterpolationCurveCache.Key(3, new int[] {1, 2})));
        long pendingBytes = cache.statistics().bytes();
        var result = new InterpolationCurveCache.Result(4);
        cache.put(key, result);
        assertEquals(pendingBytes + result.bytes(), cache.statistics().bytes());
        assertSame(result, cache.get(key));
        for (int i = 0; i < 100; i++) assertFalse(cache.admit(new InterpolationCurveCache.Key(3, new int[] {i, 4})));
        assertEquals(InterpolationCurveCache.MAX_ENTRIES, cache.statistics().entries());
        assertTrue(cache.statistics().bytes() < 8192);
        cache.enabled(false);
        assertFalse(cache.admit(key));
        assertEquals(0, cache.statistics().bytes());
    }

    @Test
    void keysOwnTheirSnapshotAndCompareFullInputsEvenOnHashCollision() {
        var cache = new InterpolationCurveCache();
        cache.enabled(true);
        int[] input = {0, 31};
        var key = new InterpolationCurveCache.Key(7, input);
        var result = new InterpolationCurveCache.Result(2);
        result.set(0, -0f, Float.intBitsToFloat(0x7fc00012));
        cache.put(key, result);
        input[0] = 99;
        assertSame(result, cache.get(new InterpolationCurveCache.Key(7, new int[] {0, 31})));
        // Arrays.hashCode collision: checking only hashes would return the wrong geometry.
        var collision = new InterpolationCurveCache.Key(7, new int[] {1, 0});
        assertEquals(key.hashCode(), collision.hashCode());
        assertNull(cache.get(collision));
        assertNull(cache.get(new InterpolationCurveCache.Key(8, new int[] {0, 31})));
        assertEquals(0x80000000, Float.floatToRawIntBits(result.get(0)));
        assertEquals(0x7fc00012, Float.floatToRawIntBits(result.get(1)));
        assertThrows(IllegalStateException.class, () -> result.set(0, 1, 2));
    }

    @Test
    void absentEvaluationsStayAbsentAndDisableReleasesAllNumericalStorage() {
        var cache = new InterpolationCurveCache();
        cache.enabled(true);
        var result = new InterpolationCurveCache.Result(4);
        result.set(2, 3, 4);
        var key = new InterpolationCurveCache.Key(1, new int[] {1});
        cache.put(key, result);
        assertFalse(cache.get(key).present(0));
        assertTrue(cache.get(key).present(2));
        assertTrue(cache.statistics().bytes() > 0);
        cache.enabled(false);
        // An in-flight computation completing after disable cannot repopulate the cache.
        cache.put(key, result);
        assertEquals(0, cache.statistics().entries());
        assertEquals(0, cache.statistics().bytes());
        cache.enabled(true);
        assertNull(cache.get(key));
    }

    @Test
    void entryAndByteBudgetsEvictLeastRecentlyUsedResultsAndRejectOversize() {
        var cache = new InterpolationCurveCache();
        cache.enabled(true);
        var first = new InterpolationCurveCache.Key(1, new int[] {0});
        var second = new InterpolationCurveCache.Key(1, new int[] {1});
        for (int i = 0; i < InterpolationCurveCache.MAX_ENTRIES; i++)
            cache.put(new InterpolationCurveCache.Key(1, new int[] {i}), new InterpolationCurveCache.Result(2));
        assertNotNull(cache.get(first));
        cache.put(new InterpolationCurveCache.Key(1, new int[] {100}), new InterpolationCurveCache.Result(2));
        assertNotNull(cache.get(first));
        assertNull(cache.get(second));
        cache.enabled(false);
        cache.enabled(true);
        for (int i = 0; i < 20; i++) {
            cache.put(new InterpolationCurveCache.Key(1, new int[] {i}), new InterpolationCurveCache.Result(200_000));
            assertTrue(cache.statistics().bytes() <= InterpolationCurveCache.MAX_BYTES);
        }
        assertTrue(cache.statistics().evictions() > 0);
        long before = cache.statistics().bytes();
        cache.put(first, new InterpolationCurveCache.Result(300_000));
        assertEquals(before, cache.statistics().bytes());
    }
}
