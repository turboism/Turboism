package dev.turboism.validation.modelupdate;

import java.lang.management.ManagementFactory;

/** Allocation regression for the real cache hit path; no Editor or GL is loaded. */
public final class UniformValueAllocationTest {
    public static void main(String[] args) {
        if (!(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean)
            || !bean.isThreadAllocatedMemorySupported()) {
            System.out.println("UniformValueAllocationTest SKIP (allocation counter unavailable)");
            return;
        }
        if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        UniformValueCache cache = new UniformValueCache(16);
        Object context = new Object();
        float[] matrix = new float[16];
        for (int i = 0; i < matrix.length; i++) matrix[i] = i;
        Object[] value = {4, 1, false, matrix, 0};
        cache.begin(context);
        cache.before(context, "glUseProgram", new Object[]{7});
        cache.checkedError(0);
        cache.before(context, "glUniformMatrix4fv", value);
        cache.checkedError(0);
        for (int i = 0; i < 100_000; i++) requireHit(cache, context, value);
        long id = Thread.currentThread().getId();
        long before = bean.getThreadAllocatedBytes(id);
        int repeats = 100_000;
        for (int i = 0; i < repeats; i++) requireHit(cache, context, value);
        long allocated = bean.getThreadAllocatedBytes(id) - before;
        double perHit = (double) allocated / repeats;
        System.out.println("uniformValueCache.allocatedBytesPerMatrixHit=" + perHit);
        // Allow a map key object and counter overhead, but not a new payload array/value.
        if (perHit > 64.0) throw new AssertionError("payload allocated on unchanged hit: " + perHit);
        matrix[15] += 1;
        if (cache.before(context, "glUniformMatrix4fv", value)) throw new AssertionError("tail change lost");
        cache.end();
        System.out.println("UniformValueAllocationTest PASS (hit allocation bounded, complete comparison)");
    }
    private static void requireHit(UniformValueCache cache, Object context, Object[] value) {
        if (!cache.before(context, "glUniformMatrix4fv", value)) throw new AssertionError("expected hit");
    }
}
