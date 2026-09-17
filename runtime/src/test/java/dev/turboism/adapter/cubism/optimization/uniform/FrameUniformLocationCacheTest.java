package dev.turboism.adapter.cubism.optimization.uniform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FrameUniformLocationCacheTest {
    private final Object context = new Object();
    private final FrameUniformLocationCache cache = new FrameUniformLocationCache(4);

    @Test void requiresSameContextNativeErrorConfirmation() {
        long scope = cache.begin(context, true);
        cache.record(context, 7, "color", 12);
        assertEquals(FrameUniformLocationCache.MISS, cache.lookup(context, 7, "color"));
        cache.checkedError(context, 0);
        assertEquals(12, cache.lookup(context, 7, "color"));
        cache.end(scope);
        assertEquals(0, cache.retained());
    }
    @Test void errorFromDifferentContextCannotConfirmPendingResult() {
        cache.begin(context, true);
        cache.record(context, 7, "color", 12);
        cache.checkedError(new Object(), 0);
        cache.checkedError(context, 0);
        assertEquals(FrameUniformLocationCache.MISS, cache.lookup(context, 7, "color"));
    }
    @Test void negativeLocationIsReusableOnlyAfterConfirmation() {
        cache.begin(context, true);
        cache.record(context, 7, "absent", -1);
        cache.checkedError(context, 0);
        assertEquals(-1, cache.lookup(context, 7, "absent"));
        assertEquals(FrameUniformLocationCache.MISS, cache.lookup(context, 8, "absent"));
    }
    @Test void unsupportedScopeAndInvalidInputsCannotBeCached() {
        long scope = cache.begin(context, false);
        cache.record(context, 7, "color", 12); cache.checkedError(context, 0);
        assertEquals(FrameUniformLocationCache.MISS, cache.lookup(context, 7, "color"));
        cache.end(scope);
        cache.begin(context, true);
        cache.record(context, 0, "color", 12);
        cache.record(context, 7, null, 12);
        cache.record(context, 7, "color", -2);
        cache.record(context, 7, "x".repeat(513), 12);
        cache.checkedError(context, 0);
        assertEquals(0, cache.retained());
    }
    @Test void errorAndMutationRetireTheFrame() {
        long scope = confirmed();
        cache.checkedError(context, 1282);
        cache.record(context, 7, "color", 99); cache.checkedError(context, 0);
        assertEquals(FrameUniformLocationCache.MISS, cache.lookup(context, 7, "color"));
        cache.end(scope);
        confirmed(); cache.invalidate();
        assertEquals(FrameUniformLocationCache.MISS, cache.lookup(context, 7, "color"));
    }
    @Test void nestedBeginCannotReactivateOrCloseOuterScope() {
        long outer = confirmed();
        assertEquals(0, cache.begin(context, true));
        assertEquals(0, cache.begin(context, true));
        cache.end(0);
        assertEquals(FrameUniformLocationCache.MISS, cache.lookup(context, 7, "color"));
        cache.end(outer);
        long next = confirmed();
        assertNotEquals(outer, next);
        cache.end(outer);
        assertEquals(12, cache.lookup(context, 7, "color"));
        cache.end(next);
    }
    @Test void staleValuesCannotCrossFrames() {
        long first = confirmed(); cache.end(first);
        cache.begin(context, true);
        assertEquals(FrameUniformLocationCache.MISS, cache.lookup(context, 7, "color"));
    }
    @Test void otherThreadCannotConfirmOrUseResults() throws Exception {
        long scope = cache.begin(context, true);
        cache.record(context, 7, "color", 12);
        AtomicInteger result = new AtomicInteger(123);
        Thread other = new Thread(() -> {
            cache.checkedError(context, 0);
            result.set(cache.lookup(context, 7, "color"));
            cache.end(scope);
        });
        other.start(); other.join();
        assertEquals(FrameUniformLocationCache.MISS, result.get());
        assertEquals(FrameUniformLocationCache.MISS, cache.lookup(context, 7, "color"));
        cache.checkedError(context, 0);
        assertEquals(12, cache.lookup(context, 7, "color"));
        cache.end(scope);
    }
    @Test void boundAndCloseDoNotPreventNativeFallback() {
        assertThrows(IllegalArgumentException.class, () -> new FrameUniformLocationCache(0));
        cache.begin(context, true);
        for (int i = 0; i < 12; i++) { cache.record(context, 7, "u" + i, i); cache.checkedError(context, 0); }
        assertEquals(4, cache.retained());
        assertEquals(FrameUniformLocationCache.MISS, cache.lookup(context, 7, "u11"));
        cache.close();
        assertEquals(0, cache.retained());
        assertEquals(0, cache.begin(context, true));
    }
    private long confirmed() {
        long scope = cache.begin(context, true);
        cache.record(context, 7, "color", 12); cache.checkedError(context, 0);
        return scope;
    }
}
