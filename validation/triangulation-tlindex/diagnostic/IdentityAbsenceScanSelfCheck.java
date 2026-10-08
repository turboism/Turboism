import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.LinkedHashSet;

/** Owned-only T054 feasibility: retain physical-absence proof and native removal. */
public final class IdentityAbsenceScanSelfCheck {
    private static final int CAPACITY = 4096;
    private static int checks;
    private static volatile int consumed;
    private IdentityAbsenceScanSelfCheck() {}

    private static final class Scratch {
        final Object[] values = new Object[CAPACITY];
        boolean busy;
    }

    private static final class Triangle {
        int equalityKey;
        int equalityCalls;
        Triangle(int key) { equalityKey = key; }
        @Override public int hashCode() { return 1; }
        @Override public boolean equals(Object other) {
            equalityCalls++;
            return other instanceof Triangle triangle && equalityKey == triangle.equalityKey;
        }
    }

    private static boolean iteratorProof(LinkedHashSet<?> set, Object[] pending) {
        if (pending.length == 1) {
            Object first = pending[0];
            for (Object survivor : set) if (survivor == first) return false;
        } else if (pending.length == 2) {
            Object first = pending[0], second = pending[1];
            for (Object survivor : set) if (survivor == first || survivor == second) return false;
        } else {
            for (Object survivor : set) {
                for (Object argument : pending) if (survivor == argument) return false;
            }
        }
        return true;
    }

    private static boolean arrayProof(LinkedHashSet<?> set, Object[] pending, Scratch scratch) {
        int size = set.size();
        if (set.getClass() != LinkedHashSet.class || size > CAPACITY || scratch.busy)
            return iteratorProof(set, pending);
        scratch.busy = true;
        int clearCount = CAPACITY;
        try {
            Object[] array = set.toArray(scratch.values);
            if (array != scratch.values || set.size() != size) return iteratorProof(set, pending);
            // All slots were null before copying. Include the terminating null slot.
            clearCount = Math.min(size + 1, CAPACITY);
            if (pending.length == 1) {
                Object first = pending[0];
                for (int i = 0; i < size; i++) if (array[i] == first) return false;
            } else if (pending.length == 2) {
                Object first = pending[0], second = pending[1];
                for (int i = 0; i < size; i++)
                    if (array[i] == first || array[i] == second) return false;
            } else {
                for (int i = 0; i < size; i++)
                    for (Object argument : pending) if (array[i] == argument) return false;
            }
            return true;
        } finally {
            Arrays.fill(scratch.values, 0, clearCount, null);
            scratch.busy = false;
        }
    }

    private static void require(boolean value) {
        checks++;
        if (!value) throw new AssertionError("check " + checks);
    }

    private static void control(LinkedHashSet<?> set, Object[] pending, Scratch scratch) {
        require(iteratorProof(set, pending) == arrayProof(set, pending, scratch));
        require(!scratch.busy);
        for (Object entry : scratch.values) require(entry == null);
    }

    private static void selfCheck() {
        Scratch scratch = new Scratch();
        for (int size : new int[] {0, 1, 2, 17, 128, 2048, 4096, 4097}) {
            LinkedHashSet<Object> set = new LinkedHashSet<>();
            Object[] objects = new Object[size];
            for (int i = 0; i < size; i++) { objects[i] = new Object(); set.add(objects[i]); }
            for (int count : new int[] {1, 2, 8}) {
                Object[] pending = new Object[count];
                for (int i = 0; i < count; i++) pending[i] = new Object();
                control(set, pending, scratch);
                if (size > 0) {
                    pending[count - 1] = objects[size - 1]; control(set, pending, scratch);
                    pending[0] = objects[0]; control(set, pending, scratch);
                }
            }
            set.add(null); control(set, new Object[] {null}, scratch);
            set.remove(null); control(set, new Object[] {null}, scratch);
        }
        LinkedHashSet<Object> subclass = new LinkedHashSet<>() {
            private static final long serialVersionUID = 1L;
            @Override public <T> T[] toArray(T[] array) { throw new AssertionError("subclass array path"); }
        };
        subclass.add(new Object()); control(subclass, new Object[] {new Object()}, scratch);
        scratch.busy = true;
        require(arrayProof(subclass, new Object[] {new Object()}, scratch));
        require(scratch.busy); scratch.busy = false;
        for (int victim = 0; victim < 32; victim++) {
            LinkedHashSet<Triangle> set = new LinkedHashSet<>();
            Triangle[] triangles = new Triangle[32];
            for (int i = 0; i < 32; i++) { triangles[i] = new Triangle(i); set.add(triangles[i]); }
            for (Triangle triangle : triangles) triangle.equalityKey = 0;
            require(set.remove(triangles[victim])); // Keep the actual collision-tree victim.
            for (Triangle triangle : triangles) triangle.equalityCalls = 0;
            control(set, new Object[] {triangles[victim]}, scratch);
            for (Triangle triangle : triangles) require(triangle.equalityCalls == 0);
        }
        LinkedHashSet<Object> first = new LinkedHashSet<>(), second = new LinkedHashSet<>();
        Object shared = new Object(); first.add(shared);
        control(first, new Object[] {shared}, scratch);
        control(second, new Object[] {shared}, scratch);
    }

    private static long run(LinkedHashSet<?> set, Object[] pending, Scratch scratch,
            boolean array, int iterations) {
        long start = System.nanoTime();
        int hits = 0;
        for (int i = 0; i < iterations; i++)
            if (array ? arrayProof(set, pending, scratch) : iteratorProof(set, pending)) hits++;
        consumed = hits;
        require(hits == iterations);
        return System.nanoTime() - start;
    }

    public static void main(String[] args) {
        selfCheck();
        System.out.println("IDENTITY_ABSENCE_SELF_CHECK_PASS checks=" + checks);
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().getId();
        for (int size : new int[] {128, 512, 2048, 4096}) {
            LinkedHashSet<Triangle> set = new LinkedHashSet<>();
            for (int i = 0; i < size; i++) set.add(new Triangle(i));
            for (int count : new int[] {1, 2, 8}) {
                Object[] pending = new Object[count];
                for (int i = 0; i < count; i++) pending[i] = new Triangle(-i - 1);
                Scratch scratch = new Scratch();
                int iterations = Math.max(1000, 4000000 / size);
                for (int warm = 0; warm < 6; warm++) {
                    run(set, pending, scratch, false, iterations);
                    run(set, pending, scratch, true, iterations);
                }
                long[] baseline = new long[5], candidate = new long[5];
                for (int round = 0; round < 5; round++) {
                    boolean candidateFirst = (round & 1) != 0;
                    long before = bean.getThreadAllocatedBytes(thread);
                    long a = run(set, pending, scratch, candidateFirst, iterations);
                    long allocatedA = bean.getThreadAllocatedBytes(thread) - before;
                    before = bean.getThreadAllocatedBytes(thread);
                    long b = run(set, pending, scratch, !candidateFirst, iterations);
                    long allocatedB = bean.getThreadAllocatedBytes(thread) - before;
                    baseline[round] = candidateFirst ? b : a;
                    candidate[round] = candidateFirst ? a : b;
                    System.out.printf("ROUND size=%d pending=%d round=%d iterations=%d iteratorNs=%d arrayNs=%d iteratorBytes=%d arrayBytes=%d%n",
                            size, count, round, iterations, baseline[round], candidate[round],
                            candidateFirst ? allocatedB : allocatedA, candidateFirst ? allocatedA : allocatedB);
                }
                Arrays.sort(baseline); Arrays.sort(candidate);
                System.out.printf("MEDIAN size=%d pending=%d iteratorNs=%d arrayNs=%d changePercent=%.3f%n",
                        size, count, baseline[2], candidate[2], (100.0 * candidate[2] / baseline[2]) - 100.0);
                for (Object entry : scratch.values) require(entry == null);
            }
        }
        System.out.println("OWNED_FEASIBILITY_FINISHED checks=" + checks + " consumed=" + consumed);
    }
}
