import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/** Owned metadata prototype only; production edge buckets remain ArrayLists. */
public final class PrimitiveEdgeTableSelfCheck {
    private PrimitiveEdgeTableSelfCheck() { }
    private static long checks;

    static final class Table {
        private long[] keys = new long[16];
        private Object[] values = new Object[16];
        private int size;

        private static int hash(long key) {
            key ^= key >>> 33;
            key *= 0xff51afd7ed558ccdL;
            key ^= key >>> 33;
            key *= 0xc4ceb9fe1a85ec53L;
            key ^= key >>> 33;
            return (int) (key ^ (key >>> 32));
        }

        Object get(long key) {
            int mask = values.length - 1;
            int slot = hash(key) & mask;
            while (values[slot] != null) {
                if (keys[slot] == key) return values[slot];
                slot = (slot + 1) & mask;
            }
            return null;
        }

        Object put(long key, Object value) {
            Objects.requireNonNull(value);
            int mask = values.length - 1;
            int slot = hash(key) & mask;
            while (values[slot] != null) {
                if (keys[slot] == key) {
                    Object previous = values[slot];
                    values[slot] = value;
                    return previous;
                }
                slot = (slot + 1) & mask;
            }
            if (size + 1 > values.length * 2L / 3) {
                grow();
                return put(key, value);
            }
            keys[slot] = key;
            values[slot] = value;
            size++;
            return null;
        }

        Object remove(long key) {
            int mask = values.length - 1;
            int slot = hash(key) & mask;
            while (values[slot] != null && keys[slot] != key) slot = (slot + 1) & mask;
            Object previous = values[slot];
            if (previous == null) return null;
            int hole = slot;
            int scan = (hole + 1) & mask;
            while (values[scan] != null) {
                int home = hash(keys[scan]) & mask;
                // Move exactly those entries whose search path crosses the hole.
                if (((hole - home) & mask) < ((scan - home) & mask)) {
                    keys[hole] = keys[scan];
                    values[hole] = values[scan];
                    hole = scan;
                }
                scan = (scan + 1) & mask;
            }
            values[hole] = null;
            keys[hole] = 0;
            size--;
            return previous;
        }

        void clear() {
            Arrays.fill(values, null);
            Arrays.fill(keys, 0);
            size = 0;
        }

        private void grow() {
            if (values.length >= 1 << 29) throw new IllegalStateException("prototype capacity limit");
            long[] oldKeys = keys;
            Object[] oldValues = values;
            keys = new long[oldKeys.length * 2];
            values = new Object[oldValues.length * 2];
            size = 0;
            for (int i = 0; i < oldValues.length; i++) {
                if (oldValues[i] != null) put(oldKeys[i], oldValues[i]);
            }
        }
    }

    static final class Hostile {
        @Override public boolean equals(Object other) { throw new AssertionError("value equality called"); }
        @Override public int hashCode() { throw new AssertionError("value hashing called"); }
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    @SuppressWarnings("ReferenceEquality")
    private static void compare(Table actual, Map<Long, Object> expected) {
        require(actual.size == expected.size(), "cardinality");
        IdentityHashMap<Object, Integer> observedValues = new IdentityHashMap<>();
        IdentityHashMap<Object, Integer> expectedValues = new IdentityHashMap<>();
        for (Map.Entry<Long, Object> entry : expected.entrySet()) {
            require(actual.get(entry.getKey()) == entry.getValue(), "value identity");
            expectedValues.merge(entry.getValue(), 1, Integer::sum);
        }
        int occupied = 0;
        for (int i = 0; i < actual.values.length; i++) {
            if (actual.values[i] == null) {
                require(actual.keys[i] == 0, "released free key slot");
            } else {
                require(expected.get(actual.keys[i]) == actual.values[i], "no stale value or duplicate key");
                observedValues.merge(actual.values[i], 1, Integer::sum);
                occupied++;
            }
        }
        require(occupied == expected.size(), "occupied cardinality");
        require(observedValues.equals(expectedValues), "no retained removed values");
    }

    @SuppressWarnings("ReferenceEquality")
    public static void main(String[] args) {
        Table actual = new Table();
        Map<Long, Object> expected = new HashMap<>();
        long[] extremes = {0, -1, Long.MIN_VALUE, Long.MAX_VALUE, 0x80000000ffffffffL,
            0xffffffff80000000L, 1L << 32, -1L << 32};
        for (long key : extremes) {
            Object value = new Hostile();
            require(actual.put(key, value) == expected.put(key, value), "extreme put");
        }
        compare(actual, expected);
        actual.clear(); expected.clear(); compare(actual, expected);
        // Force a cluster that wraps the initial array; remove each possible position.
        long[] colliding = new long[10];
        int count = 0;
        for (long key = 0; count < colliding.length; key++) {
            if ((Table.hash(key) & 15) == 15) colliding[count++] = key;
        }
        for (int victim = 0; victim < colliding.length; victim++) {
            for (long key : colliding) {
                Object value = new Hostile();
                actual.put(key, value); expected.put(key, value);
            }
            require(actual.remove(colliding[victim]) == expected.remove(colliding[victim]), "wrapped deletion");
            compare(actual, expected);
            actual.clear(); expected.clear();
        }
        Random random = new Random(0x7072696d69746976L);
        long[] keys = new long[8192];
        for (int i = 0; i < keys.length; i++) keys[i] = random.nextLong();
        for (int i = 0; i < 300_000; i++) {
            long key = i % 17 == 0 ? extremes[i % extremes.length] : keys[random.nextInt(keys.length)];
            switch (random.nextInt(5)) {
                case 0, 1 -> {
                    Object value = new Hostile();
                    require(actual.put(key, value) == expected.put(key, value), "random put");
                }
                case 2 -> require(actual.remove(key) == expected.remove(key), "random remove");
                default -> require(actual.get(key) == expected.get(key), "random get");
            }
            if (i % 1024 == 0) compare(actual, expected);
            if (i % 40_000 == 0) { actual.clear(); expected.clear(); compare(actual, expected); }
        }
        compare(actual, expected);
        actual.clear(); expected.clear(); compare(actual, expected);
        try {
            actual.put(0, null);
            throw new AssertionError("null value accepted");
        } catch (NullPointerException expectedFailure) {
            compare(actual, expected);
        }
        System.out.println("PASS primitive edge metadata checks=" + checks
            + "; owned prototype only, no full-index or host performance claim");
    }
}
