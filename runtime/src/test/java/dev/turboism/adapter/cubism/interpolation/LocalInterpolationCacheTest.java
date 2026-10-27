package dev.turboism.adapter.cubism.interpolation;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

final class LocalInterpolationCacheTest {
    @Test
    void storesDetachedNotificationMaskAndChecksFullInputsOnCollision() {
        var cache = new LocalInterpolationCache();
        cache.enabled(true);
        var a = new InterpolationCurveCache.Key(1, new int[] {0, 31});
        var b = new InterpolationCurveCache.Key(1, new int[] {1, 0});
        assertEquals(a.hashCode(), b.hashCode());
        boolean[] writes = {false, true, false, true};
        cache.put(a, writes);
        writes[1] = false;
        assertArrayEquals(new boolean[] {false, true, false, true}, cache.get(a));
        assertNull(cache.get(b));
        assertNull(cache.get(new InterpolationCurveCache.Key(2, new int[] {0, 31})));
    }

    @Test
    void boundsEntriesBytesAndClearsOnDisableIncludingLatePublication() {
        var cache = new LocalInterpolationCache();
        cache.enabled(true);
        for (int i = 0; i < 100; i++) {
            cache.put(new InterpolationCurveCache.Key(1, new int[] {i}), new boolean[200_000]);
            assertTrue(cache.statistics().bytes() <= LocalInterpolationCache.MAX_BYTES);
        }
        assertTrue(cache.statistics().entries() <= LocalInterpolationCache.MAX_ENTRIES);
        assertTrue(cache.statistics().evictions() > 0);
        cache.enabled(false);
        cache.put(new InterpolationCurveCache.Key(1, new int[] {1}), new boolean[1]);
        assertEquals(0, cache.statistics().entries());
        assertEquals(0, cache.statistics().bytes());
    }

    @Test
    void rejectsOversizeWithoutReplacingPreviousValue() {
        var cache = new LocalInterpolationCache();
        cache.enabled(true);
        var key = new InterpolationCurveCache.Key(1, new int[] {0});
        cache.put(key, new boolean[] {true});
        long bytes = cache.statistics().bytes();
        cache.put(key, new boolean[(int) LocalInterpolationCache.MAX_ENTRY_BYTES]);
        assertEquals(bytes, cache.statistics().bytes());
        assertArrayEquals(new boolean[] {true}, cache.get(key));
    }

    @Test
    void scratchLoansAreExclusiveAndPublicationDetachesBeforeReturn() {
        var cache = new LocalInterpolationCache();
        cache.enabled(true);
        int[] input = cache.borrowInput(2);
        input[0] = 7;
        input[1] = 11;
        assertNotSame(input, cache.borrowInput(2));
        boolean[] mask = cache.borrowWrites(2);
        mask[1] = true;
        cache.put(InterpolationCurveCache.Key.owned(1, input), mask);
        cache.release(input);
        cache.release(mask);
        int[] reused = cache.borrowInput(2);
        assertSame(input, reused);
        reused[0] = 99;
        boolean[] reusedMask = cache.borrowWrites(2);
        assertSame(mask, reusedMask);
        assertArrayEquals(new boolean[2], reusedMask);
        assertArrayEquals(
                new boolean[] {false, true}, cache.get(new InterpolationCurveCache.Key(1, new int[] {7, 11})));
        cache.release(reused);
        cache.enabled(false);
        assertNotSame(reused, cache.borrowInput(2));
        cache.release(reused); // Late return after disable cannot retain the buffer.
        assertNotSame(reused, cache.borrowInput(2));
    }

    @Test
    void sourceAdmissionRequiresRepetitionAndClearsOnDisable() {
        var cache = new LocalInterpolationCache();
        cache.enabled(true);
        var key = new InterpolationCurveCache.Key(1, new int[] {3, 5});
        for (int i = 0; i < 4; i++) assertFalse(cache.admit(key));
        assertTrue(cache.admit(key));
        assertTrue(cache.admit(key));
        for (int i = 0; i < 128; i++) cache.admit(new InterpolationCurveCache.Key(1, new int[] {i}));
        assertFalse(cache.admit(key));
        cache.enabled(false);
        assertFalse(cache.admit(key));
        cache.enabled(true);
        assertFalse(cache.admit(key));
    }
}
