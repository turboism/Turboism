import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Owned storage prototype only. Does not replace native removal or the index settle proof. */
public final class CompactEdgeBucketSelfCheck {
    private static long checks;
    private static volatile Object[] allocationSink;

    private CompactEdgeBucketSelfCheck() { }

    static final class Bucket {
        private Object first;
        private Object second;
        private Object[] overflow;
        private int size;

        int size() { return size; }

        void add(final Object value) {
            if (size == 0) first = value;
            else if (size == 1) second = value;
            else {
                if (overflow == null) overflow = new Object[1];
                else if (size - 2 == overflow.length) {
                    // Match ArrayList's logical capacity growth, discounting the two
                    // inline entries. Do not allocate a separate overflow list object.
                    final long oldCapacity = (long) overflow.length + 2;
                    final long wanted = Math.max((long) size - 1, oldCapacity + oldCapacity / 2 - 2);
                    if (wanted > Integer.MAX_VALUE) throw new OutOfMemoryError("bucket capacity");
                    overflow = java.util.Arrays.copyOf(overflow, (int) wanted);
                }
                overflow[size - 2] = value;
            }
            size++;
        }

        boolean removeIdentity(final Object victim) {
            int index = -1;
            if (size > 0 && first == victim) index = 0;
            else if (size > 1 && second == victim) index = 1;
            else if (overflow != null) {
                for (int i = 0; i < size - 2; i++) {
                    if (overflow[i] == victim) {
                        index = i + 2;
                        break;
                    }
                }
            }
            if (index < 0) return false;
            if (index == 0) {
                first = size > 1 ? second : null;
                second = size > 2 ? removeOverflow(0) : null;
            } else if (index == 1) {
                second = size > 2 ? removeOverflow(0) : null;
            } else {
                removeOverflow(index - 2);
            }
            size--;
            if (size <= 2) overflow = null;
            return true;
        }

        private Object removeOverflow(final int index) {
            final Object removed = overflow[index];
            final int moved = size - 3 - index;
            if (moved > 0) System.arraycopy(overflow, index + 1, overflow, index, moved);
            overflow[size - 3] = null;
            return removed;
        }

        ArrayList<Object> snapshot() {
            final ArrayList<Object> result = new ArrayList<>(size);
            if (size > 0) result.add(first);
            if (size > 1) result.add(second);
            // Avoid Collection.addAll's temporary array; snapshots must remain detached.
            if (overflow != null) {
                for (int i = 0; i < size - 2; i++) result.add(overflow[i]);
            }
            return result;
        }
    }

    private static final class Hostile {
        @Override public boolean equals(final Object ignored) { throw new AssertionError("equals invoked"); }
        @Override public int hashCode() { throw new AssertionError("hashCode invoked"); }
    }

    private static final class EqualValue {
        private final int value;
        EqualValue(final int value) { this.value = value; }
        @Override public boolean equals(final Object other) {
            return other instanceof EqualValue equal && equal.value == value;
        }
        @Override public int hashCode() { return value; }
    }

    private static void require(final boolean condition, final String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }

    private static boolean referenceRemove(final ArrayList<Object> values, final Object victim) {
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i) == victim) {
                values.remove(i);
                return true;
            }
        }
        return false;
    }

    private static void compare(final Bucket bucket, final List<Object> expected) {
        final ArrayList<Object> actual = bucket.snapshot();
        require(actual.getClass() == ArrayList.class, "snapshot is native-compatible mutable ArrayList");
        require(bucket.size == expected.size() && actual.size() == expected.size(), "size");
        for (int i = 0; i < actual.size(); i++) require(actual.get(i) == expected.get(i), "identity order");
        require(bucket.size > 0 || bucket.first == null, "first reference released");
        require(bucket.size > 1 || bucket.second == null, "second reference released");
        require(bucket.size > 2 || bucket.overflow == null, "overflow references released");
        require(bucket.size <= 2 || bucket.overflow.length >= bucket.size - 2, "overflow capacity");
        if (bucket.overflow != null) {
            for (int i = bucket.size - 2; i < bucket.overflow.length; i++)
                require(bucket.overflow[i] == null, "unused overflow reference released");
        }
        actual.clear();
        actual.add(new Hostile());
        final ArrayList<Object> again = bucket.snapshot();
        require(again.size() == expected.size(), "mutating snapshot leaves bucket unchanged");
        for (int i = 0; i < again.size(); i++) require(again.get(i) == expected.get(i), "snapshot alias isolation");
    }

    private static void fixtures() {
        final Object[] pool = {new Hostile(), new Hostile(), null, new EqualValue(1), new EqualValue(1),
                               new EqualValue(2), new Object()};
        for (int count = 0; count <= 32; count++) {
            for (int victim = -1; victim < count; victim++) {
                final Bucket bucket = new Bucket();
                final ArrayList<Object> expected = new ArrayList<>(2);
                for (int i = 0; i < count; i++) {
                    final Object value = pool[i % pool.length];
                    bucket.add(value);
                    expected.add(value);
                }
                final ArrayList<Object> held = bucket.snapshot();
                final Object target = victim < 0 ? new Hostile() : pool[victim % pool.length];
                require(bucket.removeIdentity(target) == referenceRemove(expected, target), "fixture remove result");
                compare(bucket, expected);
                require(held.size() == count, "bucket removal leaves held snapshot unchanged");
                while (!expected.isEmpty()) {
                    final Object next = expected.get(expected.size() / 2);
                    require(bucket.removeIdentity(next) == referenceRemove(expected, next), "drain result");
                    compare(bucket, expected);
                }
                for (final Object value : pool) {
                    bucket.add(value);
                    expected.add(value);
                    compare(bucket, expected);
                }
            }
        }
        final Bucket identity = new Bucket();
        identity.add(pool[3]);
        require(!identity.removeIdentity(pool[4]), "equal but distinct victim must survive");
    }

    private static void randomized() {
        final Object[] pool = new Object[64];
        for (int i = 0; i < pool.length; i++) pool[i] = i == 0 ? null : new Hostile();
        for (int seed = 0; seed < 16; seed++) {
            final Random random = new Random(0x650000L + seed);
            final Bucket bucket = new Bucket();
            final ArrayList<Object> expected = new ArrayList<>(2);
            for (int step = 0; step < 10000; step++) {
                final Object value = pool[random.nextInt(pool.length)];
                if (expected.isEmpty() || expected.size() < 64 && random.nextBoolean()) {
                    bucket.add(value);
                    expected.add(value);
                } else {
                    require(bucket.removeIdentity(value) == referenceRemove(expected, value), "random identity remove");
                }
                compare(bucket, expected);
            }
        }
    }

    private static long allocate(final ThreadMXBean bean, final int size, final boolean compact,
                                 final int repetitions) {
        final Object[] retained = new Object[repetitions];
        final Object value = new Object();
        final long thread = Thread.currentThread().getId();
        final long before = bean.getThreadAllocatedBytes(thread);
        for (int n = 0; n < repetitions; n++) {
            if (compact) {
                final Bucket bucket = new Bucket();
                for (int i = 0; i < size; i++) bucket.add(value);
                retained[n] = bucket;
            } else {
                final ArrayList<Object> bucket = new ArrayList<>(2);
                for (int i = 0; i < size; i++) bucket.add(value);
                retained[n] = bucket;
            }
        }
        final long bytes = bean.getThreadAllocatedBytes(thread) - before;
        allocationSink = retained; // Objects escape; do not measure a scalar-replaced empty loop.
        require(bytes >= 0 && retained.length == repetitions, "allocation counter valid");
        return bytes;
    }

    private static void allocation() {
        final java.lang.management.ThreadMXBean generic = ManagementFactory.getThreadMXBean();
        if (!(generic instanceof ThreadMXBean bean) || !bean.isThreadAllocatedMemorySupported()) {
            throw new IllegalStateException("thread allocation counter unavailable");
        }
        bean.setThreadAllocatedMemoryEnabled(true);
        final int repetitions = 20000;
        for (final int size : new int[] {0, 1, 2, 3, 4, 8, 32}) {
            allocate(bean, size, false, repetitions);
            allocate(bean, size, true, repetitions);
            final long baseline = allocate(bean, size, false, repetitions);
            final long compact = allocate(bean, size, true, repetitions);
            System.out.println("ALLOCATION size=" + size + " repetitions=" + repetitions
                    + " baselineBytes=" + baseline + " compactBytes=" + compact);
        }
        allocationSink = null;
    }

    public static void main(final String[] args) {
        fixtures();
        randomized();
        allocation();
        System.out.println("COMPACT_EDGE_BUCKET_FINISHED checks=" + checks);
    }
}
