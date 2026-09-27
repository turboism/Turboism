package dev.turboism.adapter.cubism.optimization.uniform;

import java.lang.reflect.Array;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class FrameUniformLocationCacheStorageTest {
    @Test
    void collidingNamesAndProgramsRemainDistinctThroughGrowth() {
        Object context = new Object();
        try (var cache = new FrameUniformLocationCache(128)) {
            long token = cache.begin(context, true);
            String[] names = new String[64];
            for (int index = 0; index < names.length; index++) {
                StringBuilder name = new StringBuilder();
                for (int bit = 0; bit < 6; bit++) name.append((index & (1 << bit)) == 0 ? "Aa" : "BB");
                names[index] = name.toString();
                assertEquals(names[0].hashCode(), names[index].hashCode());
                cache.record(context, 1000, names[index], 2000 + index);
                assertEquals(FrameUniformLocationCache.MISS, cache.lookup(context, 1000, names[index]));
            }
            cache.record(context, 1001, names[0], -1);
            cache.checkedError(context, 0);
            for (int index = 0; index < names.length; index++) {
                assertEquals(2000 + index, cache.lookup(context, 1000, new String(names[index])));
            }
            assertEquals(-1, cache.lookup(context, 1001, names[0]));
            assertEquals(FrameUniformLocationCache.MISS, cache.lookup(context, 1002, names[0]));
            assertEquals(65, cache.retained());
            cache.end(token);
            assertEquals(0, cache.retained());
        }
    }

    @Test
    void fullCacheAllowsReplacementButNeverReusesAnUnconfirmedResult() {
        Object context = new Object();
        try (var cache = new FrameUniformLocationCache(2)) {
            long token = cache.begin(context, true);
            cache.record(context, 10, "a", 1);
            cache.record(context, 10, "a", 2);
            cache.record(context, 10, "b", 3);
            cache.record(context, 10, "c", 4);
            assertEquals(2, cache.retained());
            cache.checkedError(context, 0);
            assertEquals(2, cache.lookup(context, 10, "a"));
            assertEquals(3, cache.lookup(context, 10, "b"));
            assertEquals(FrameUniformLocationCache.MISS, cache.lookup(context, 10, "c"));
            cache.record(context, 10, "a", 5);
            cache.record(context, 10, "a", 6);
            assertEquals(FrameUniformLocationCache.MISS, cache.lookup(context, 10, "a"));
            assertEquals(3, cache.lookup(context, 10, "b"));
            cache.checkedError(context, 0);
            assertEquals(6, cache.lookup(context, 10, "a"));
            assertEquals(2, cache.retained());
            cache.end(token);
        }
    }

    @Test
    void endInvalidationAndCloseReleaseNamesAndContextEvenAfterGrowth() throws Exception {
        for (int action = 0; action < 3; action++) {
            Object context = new Object();
            String name = new String("unique-owned-uniform-name");
            var cache = new FrameUniformLocationCache(128);
            long token = cache.begin(context, true);
            cache.record(context, 100, name, 1000);
            for (int index = 0; index < 64; index++) cache.record(context, 100, "other-" + index, index);
            cache.checkedError(context, 0);
            assertTrue(references(cache, name));
            assertTrue(references(cache, context));
            if (action == 0) cache.end(token);
            else if (action == 1) cache.invalidate();
            else cache.close();
            assertEquals(0, cache.retained());
            assertFalse(references(cache, name));
            assertFalse(references(cache, context));
            cache.close();
        }
    }

    @Test
    void mixedFramesMatchThePreviousMapConfirmationAndCapacitySemantics() {
        record Key(int program, String name) { }
        var random = new java.util.Random(0x341L);
        var ready = new java.util.HashMap<Key, Integer>();
        var pending = new java.util.HashMap<Key, Integer>();
        Object context = new Object();
        try (var cache = new FrameUniformLocationCache(97)) {
            for (int frame = 0; frame < 40; frame++) {
                long token = cache.begin(context, true);
                ready.clear();
                pending.clear();
                for (int step = 0; step < 2000; step++) {
                    Key key = new Key(1000 + random.nextInt(5), "uniform_" + random.nextInt(80));
                    if (random.nextInt(5) == 0) {
                        cache.checkedError(context, 0);
                        ready.putAll(pending);
                        pending.clear();
                    } else if (random.nextBoolean()) {
                        int location = random.nextInt(1200) - 1;
                        cache.record(context, key.program(), key.name(), location);
                        ready.remove(key);
                        if (ready.size() + pending.size() < 97 || pending.containsKey(key)) {
                            pending.put(key, location);
                        }
                    }
                    assertEquals(ready.getOrDefault(key, FrameUniformLocationCache.MISS).intValue(),
                        cache.lookup(context, key.program(), key.name()));
                    assertEquals(ready.size() + pending.size(), cache.retained());
                }
                cache.end(token);
                assertEquals(0, cache.retained());
            }
        }
    }

    private static boolean references(Object root, Object target) throws Exception {
        return references(root, target, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    /** Inspect only owned storage, never traverse private JDK state or force GC. */
    private static boolean references(Object root, Object target, Set<Object> seen) throws Exception {
        if (root == target) return true;
        if (root == null || !seen.add(root)) return false;
        if (root instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                if (references(entry.getKey(), target, seen) || references(entry.getValue(), target, seen)) return true;
            }
        } else if (root.getClass().isArray() && !root.getClass().getComponentType().isPrimitive()) {
            for (int index = 0; index < Array.getLength(root); index++) {
                if (references(Array.get(root, index), target, seen)) return true;
            }
        } else if (root.getClass().getPackageName().equals(FrameUniformLocationCache.class.getPackageName())) {
            for (var field : root.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                field.setAccessible(true);
                if (references(field.get(root), target, seen)) return true;
            }
        }
        return false;
    }
}
