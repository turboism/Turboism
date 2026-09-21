package dev.turboism.adapter.cubism.optimization.serialization;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Bounded exact numeric-token reuse; never retains caller lists or mutable arrays. */
public final class FloatArrayParseCache {
    private static final int MAX_ARRAY_LENGTH = 1_048_576;
    private static final int MAX_TOKEN_LENGTH = 128;
    private final int entryLimit;
    private final int characterLimit;
    private final String[] keys;
    private final int[] valueBits;
    private int entries;
    private int characters;
    private int evictionCursor;
    private long arrays;
    private long tokens;
    private long hits;
    private long parses;

    /** Limits entry overhead and retained UTF-16 text; slots do not allocate nodes or Float boxes on misses. */
    public FloatArrayParseCache(int entryLimit, int characterLimit) {
        if (entryLimit < 1 || entryLimit > 65536 || characterLimit < 1) throw new IllegalArgumentException("invalid cache limits");
        this.entryLimit = entryLimit;
        this.characterLimit = characterLimit;
        int capacity = 1;
        while (capacity < entryLimit) capacity <<= 1;
        keys = new String[capacity];
        valueBits = new int[capacity];
    }

    /**
     * Returns a fresh float array, or null to request the unchanged native path.
     * Only known JDK random-access lists are admitted; foreign List methods are never called.
     * Misses use Float.parseFloat itself, including its signed-zero/NaN/hex behavior.
     * Hash collisions are misses unless the complete immutable strings are equal.
     */
    public synchronized float[] parse(int length, Object input) {
        if (length < 0 || length > MAX_ARRAY_LENGTH || !(input instanceof List<?> list)) return null;
        String type = input.getClass().getName();
        if (input.getClass().getClassLoader() != null || !(type.equals("java.util.ArrayList")
            || type.equals("java.util.Arrays$ArrayList") || type.equals("java.util.Collections$SingletonList")
            || type.equals("java.util.ImmutableCollections$List12") || type.equals("java.util.ImmutableCollections$ListN")
            || type.equals("java.util.Collections$EmptyList"))) return null;
        if (length > list.size()) return null;
        float[] result = new float[length];
        long localHits = 0, localParses = 0;
        for (int i = 0; i < length; i++) {
            Object item = list.get(i);
            if (!(item instanceof String token) || token.length() > MAX_TOKEN_LENGTH) return null;
            int hash = token.hashCode();
            int slot = (hash ^ (hash >>> 16)) & (keys.length - 1);
            String cached = keys[slot];
            if (token.equals(cached)) {
                result[i] = Float.intBitsToFloat(valueBits[slot]);
                localHits++;
                continue;
            }
            final float parsed;
            try {
                parsed = Float.parseFloat(token);
            } catch (NumberFormatException invalid) {
                return null; // The native method, not this callback, owns the original exception.
            }
            result[i] = parsed;
            localParses++;
            if (token.length() <= characterLimit) {
                remove(slot);
                while (entries >= entryLimit || characters + token.length() > characterLimit) {
                    remove(evictionCursor);
                    evictionCursor = (evictionCursor + 1) & (keys.length - 1);
                }
                keys[slot] = token;
                valueBits[slot] = Float.floatToRawIntBits(parsed);
                characters += token.length();
                entries++;
            }
        }
        arrays++;
        tokens += length;
        hits += localHits;
        parses += localParses;
        return result;
    }

    private void remove(int slot) {
        if (keys[slot] == null) return;
        characters -= keys[slot].length();
        entries--;
        keys[slot] = null;
    }

    /** Releases numeric text and bits; counters remain cumulative for disable verification. */
    public synchronized void clear() {
        Arrays.fill(keys, null);
        Arrays.fill(valueBits, 0);
        characters = 0;
        entries = 0;
        evictionCursor = 0;
    }

    /** Payload-free counters; allocation savings are not inferred from cache-hit counts. */
    public synchronized Map<String, Long> snapshot() {
        return Map.of("arrays", arrays, "tokens", tokens, "hits", hits, "parses", parses,
            "entries", (long) entries, "characters", (long) characters,
            "entryLimit", (long) entryLimit, "characterLimit", (long) characterLimit);
    }
}
