package dev.turboism.adapter.cubism.mesh;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Threshold;

/** Independent memory diagnostic overlay only; never include in a performance candidate. */
@Name("dev.turboism.validation.NativeMeshMemoryScope")
@Enabled(true)
@StackTrace(false)
@Threshold("0 ns")
final class NativeMeshMemoryScopeEvent extends Event {
    private static final com.sun.management.ThreadMXBean ALLOCATIONS = allocations();
    private static final Field BYTES = field("bytes");
    private static final Field KEYS = field("keys");
    private static final Field LIMIT = field("entryLimit");
    private long allocationStart;
    long tableCalls;
    long hits;
    long absent;
    long unknown;
    long appended;
    int initialEntries;
    int entryLimit;
    int bufferCapacity;
    int declaredBufferBytes;
    long helperAndSuffixAllocatedBytes;
    boolean discarded;
    boolean released;

    private static com.sun.management.ThreadMXBean allocations() {
        var bean = ManagementFactory.getThreadMXBean();
        if (!(bean instanceof com.sun.management.ThreadMXBean extended)
                || !extended.isThreadAllocatedMemorySupported()
                || !extended.isThreadAllocatedMemoryEnabled())
            throw new IllegalStateException("diagnostic thread allocation counter unavailable");
        return extended;
    }

    private static Field field(String name) {
        try {
            Field result = NativeMeshEdgeTable.class.getDeclaredField(name);
            result.setAccessible(true);
            return result;
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    static long allocatedBytes() {
        long value = ALLOCATIONS.getThreadAllocatedBytes(Thread.currentThread().getId());
        if (value < 0) throw new IllegalStateException("diagnostic allocation read failed");
        return value;
    }

    void capture(NativeMeshEdgeTable table, int entries, long start) {
        try {
            initialEntries = entries;
            declaredBufferBytes = BYTES.getInt(table);
            bufferCapacity = ((long[]) KEYS.get(table)).length;
            entryLimit = LIMIT.getInt(table);
            allocationStart = start;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("diagnostic table reservation unavailable", failure);
        }
    }

    void finishAllocation() {
        helperAndSuffixAllocatedBytes = allocatedBytes() - allocationStart;
        if (helperAndSuffixAllocatedBytes < 0)
            throw new IllegalStateException("diagnostic allocation counter decreased");
    }
}
