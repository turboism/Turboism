package dev.turboism.adapter.cubism.mesh;

import java.lang.management.ManagementFactory;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Threshold;

/** Independent memory diagnostic overlay only; never include in a performance candidate. */
@Name("dev.turboism.validation.NativeMeshGrowthMemoryScope")
@Enabled(true)
@StackTrace(false)
@Threshold("0 ns")
final class NativeMeshGrowthMemoryScopeEvent extends Event {
    private static final com.sun.management.ThreadMXBean ALLOCATIONS = allocations();
    private long allocationStart;
    long tableCalls;
    long hits;
    long absent;
    long unknown;
    long appended;
    int initialEntries;
    int entryLimit;
    int initialBufferCapacity;
    int finalBufferCapacity;
    int growthCount;
    long initialDeclaredBufferBytes;
    long finalDeclaredBufferBytes;
    long peakLiveDeclaredBufferBytes;
    long cumulativeDeclaredBufferBytes;
    long rawPrimitiveArrayAllocatedBytes;
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

    static long allocatedBytes() {
        long value = ALLOCATIONS.getThreadAllocatedBytes(Thread.currentThread().getId());
        if (value < 0) throw new IllegalStateException("diagnostic allocation read failed");
        return value;
    }

    void capture(NativeMeshEdgeTable table, int entries, long start) {
        initialEntries = entries;
        entryLimit = table.diagnosticEntryLimit;
        initialBufferCapacity = table.diagnosticInitialCapacity;
        initialDeclaredBufferBytes = table.diagnosticInitialBytes;
        allocationStart = start;
        observe(table);
    }

    void observe(NativeMeshEdgeTable table) {
        finalBufferCapacity = table.diagnosticFinalCapacity;
        finalDeclaredBufferBytes = table.diagnosticFinalBytes;
        growthCount = table.diagnosticGrowthCount;
        peakLiveDeclaredBufferBytes = table.diagnosticPeakBytes;
        cumulativeDeclaredBufferBytes = table.diagnosticCumulativeBytes;
        rawPrimitiveArrayAllocatedBytes = table.diagnosticPayloadBytes;
    }

    void finishAllocation() {
        helperAndSuffixAllocatedBytes = allocatedBytes() - allocationStart;
        if (helperAndSuffixAllocatedBytes < 0)
            throw new IllegalStateException("diagnostic allocation counter decreased");
    }
}
