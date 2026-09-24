package dev.turboism.adapter.cubism.optimization.serialization;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FloatArrayParseCacheTest {
    @Test void preservesReferenceBitsForSpecialDecimalAndHexTokens() {
        var cache = new FloatArrayParseCache(128, 4096);
        List<String> text = List.of("0", "-0", "-0.0", "+0.0", "NaN", "-NaN", "Infinity", "-Infinity",
            "1e-45", "-1e-50", "3.4028235e38", "1e100", "0x1.fffffep127", "0x0.000002p-126",
            " 1.25 ", "1.0f", "-2.0D", "16777217", "1.234567890123456789");
        for (int iteration = 0; iteration < 3; iteration++) assertReference(text, cache.parse(text.size(), text));
        assertTrue(cache.snapshot().get("hits") > 0); // Direct slots may evict on collisions; never return a wrong value.
    }

    @Test void randomFloatsRemainBitExactOnMissAndHit() {
        var cache = new FloatArrayParseCache(4096, 262144);
        var random = new Random(5302);
        for (int batch = 0; batch < 30; batch++) {
            var text = new ArrayList<String>();
            for (int i = 0; i < 1000; i++) text.add(Float.toString(Float.intBitsToFloat(random.nextInt())));
            assertReference(text, cache.parse(text.size(), text));
            assertReference(text, cache.parse(text.size(), text));
        }
        assertTrue(cache.snapshot().get("hits") >= 1000);
    }

    @Test void eachResultIsOwnedAndLaterListMutationIsObserved() {
        var cache = new FloatArrayParseCache(16, 128);
        var input = new ArrayList<>(List.of("1.25", "-0"));
        float[] first = cache.parse(2, input);
        first[0] = 99;
        float[] second = cache.parse(2, input);
        assertNotSame(first, second);
        assertEquals(1.25f, second[0]);
        input.set(0, "2.5");
        assertEquals(2.5f, cache.parse(2, input)[0]);
        assertEquals(1.25f, second[0]);
        assertNotSame(cache.parse(0, List.of()), cache.parse(0, List.of()));
    }

    @Test void nativeErrorCasesAndUnknownListImplementationsFallThrough() {
        var cache = new FloatArrayParseCache(8, 128);
        assertNull(cache.parse(-1, List.of()));
        assertNull(cache.parse(1_048_577, List.of()));
        assertNull(cache.parse(1, null));
        assertNull(cache.parse(2, List.of("1")));
        assertNull(cache.parse(1, List.of("not-a-number")));
        assertNull(cache.parse(1, Arrays.asList((String) null)));
        assertNull(cache.parse(1, List.of(1)));
        assertNull(cache.parse(1, List.of("1".repeat(129))));
        assertNull(cache.parse(1, new ArrayList<String>() {
            @Override public int size() { throw new AssertionError("foreign list must not be called"); }
            @Override public String get(int index) { throw new AssertionError("foreign list must not be called"); }
        }));
        assertArrayEquals(new float[]{2}, cache.parse(1, List.of("2", "invalid trailing value is ignored natively")));
    }

    @Test void boundsEntriesAndCharactersAndClearsAllRetainedText() {
        var cache = new FloatArrayParseCache(4, 8);
        for (int i = 0; i < 1000; i++) {
            assertNotNull(cache.parse(1, List.of(Integer.toString(i))));
            assertTrue(cache.snapshot().get("entries") <= 4);
            assertTrue(cache.snapshot().get("characters") <= 8);
        }
        cache.clear();
        assertEquals(0L, cache.snapshot().get("entries").longValue());
        assertEquals(0L, cache.snapshot().get("characters").longValue());
        assertEquals(1000L, cache.snapshot().get("arrays").longValue());
    }

    @Test void concurrentParsesAndClearDoNotCrossContaminateResults() throws Exception {
        var cache = new FloatArrayParseCache(64, 2048);
        var pool = Executors.newFixedThreadPool(4);
        try {
            var jobs = new ArrayList<Callable<Void>>();
            for (int worker = 0; worker < 4; worker++) jobs.add(() -> {
                for (int i = 0; i < 500; i++) {
                    var input = List.of("1.25", Integer.toString(i), "-0.0");
                    assertReference(input, cache.parse(3, input));
                    if (i % 97 == 0) cache.clear();
                }
                return null;
            });
            for (var future : pool.invokeAll(jobs)) future.get();
            assertEquals(2000L, cache.snapshot().get("arrays").longValue());
            assertEquals(6000L, cache.snapshot().get("tokens").longValue());
        } finally { pool.shutdownNow(); }
    }

    @Test void slotCollisionsAndNonPowerOfTwoBudgetsRemainExact() {
        var collision = new FloatArrayParseCache(1, 128);
        for (int i = 0; i < 100; i++) {
            var input = List.of("1.25", "-0.0", "99.5", "1.25");
            assertReference(input, collision.parse(input.size(), input));
            assertEquals(1L, collision.snapshot().get("entries").longValue());
        }
        var bounded = new FloatArrayParseCache(3, 7);
        for (int i = 0; i < 100; i++) {
            var input = List.of(Integer.toString(i), "0.25", "-1");
            assertReference(input, bounded.parse(input.size(), input));
            assertTrue(bounded.snapshot().get("entries") <= 3);
            assertTrue(bounded.snapshot().get("characters") <= 7);
        }
    }

    private static void assertReference(List<String> input, float[] actual) {
        assertNotNull(actual);
        assertEquals(input.size(), actual.length);
        for (int i = 0; i < actual.length; i++) {
            assertEquals(Float.floatToRawIntBits(Float.parseFloat(input.get(i))), Float.floatToRawIntBits(actual[i]), input.get(i));
        }
    }
}
