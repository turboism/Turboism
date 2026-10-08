package dev.turboism.adapter.cubism.command;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeInterpolationSaveCacheTest {
    @Test void nativeSaveSeesNoDerivedFormsAndLiveReferencesReturnOnSuccess() {
        Object a = new Object(), b = new Object(), c = new Object();
        List<Object> one = new ArrayList<>(List.of(a, b)), two = new ArrayList<>(List.of(c));
        assertTrue(NativeInterpolationSaveCache.withoutCaches(List.of(one, two), () -> {
            assertTrue(one.isEmpty()); assertTrue(two.isEmpty()); return true;
        }));
        assertSame(a, one.get(0)); assertSame(b, one.get(1)); assertSame(c, two.get(0));
    }

    @Test void failedSerializationRestoresEveryLiveCache() {
        Object a = new Object(), b = new Object();
        List<Object> one = new ArrayList<>(List.of(a)), two = new ArrayList<>(List.of(b));
        var failure = new IllegalStateException("serialization failed");
        assertSame(failure, assertThrows(IllegalStateException.class, () ->
            NativeInterpolationSaveCache.withoutCaches(List.of(one, two), () -> { throw failure; })));
        assertSame(a, one.get(0)); assertSame(b, two.get(0));
    }

    @Test void partialClearFailureStillRestoresEarlierCaches() {
        Object a = new Object(), b = new Object();
        List<Object> one = new ArrayList<>(List.of(a));
        List<Object> two = new ArrayList<>(List.of(b)) {
            boolean first = true;
            @Override public void clear() {
                if (first) { first = false; throw new IllegalStateException("clear failed"); }
                super.clear();
            }
        };
        assertThrows(IllegalStateException.class, () -> NativeInterpolationSaveCache.withoutCaches(
            List.of(one, two), () -> { fail("save must not start"); return true; }));
        assertSame(a, one.get(0)); assertSame(b, two.get(0));
    }
}
