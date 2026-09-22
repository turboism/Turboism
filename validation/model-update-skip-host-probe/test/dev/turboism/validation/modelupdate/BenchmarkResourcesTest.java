package dev.turboism.validation.modelupdate;

/** Real MXBean smoke plus deterministic arithmetic/layout checks; no Cubism/GL. */
public final class BenchmarkResourcesTest {
    public static void main(String[] args) throws Exception {
        check(ProcessMemorySample.structureSize(4) == 44, "x86 structure");
        check(ProcessMemorySample.structureSize(8) == 80, "x64 structure");
        check(BenchmarkResources.delta(-1, 3) == -1, "missing counter");
        check(BenchmarkResources.delta(4, 3) == -1, "reset counter");
        check(BenchmarkResources.delta(3, 8) == 5, "counter delta");
        check(BenchmarkResources.cpuPercent(2_000_000, 1_000_000, 1) == 200.0, "CPU can exceed one core");
        check(BenchmarkResources.cpuPercent(2_000_000, 1_000_000, 4) == 50.0, "machine denominator");
        check(BenchmarkResources.cpuPercent(-1, 1, 1) == -1.0, "unsupported CPU stays unknown");
        // An idle fresh JVM can report zero used heap before its first accounting
        // update. Establish an observable live fixture for this OFFLINE smoke test.
        // This collection happens before sampling; real host workloads are unchanged.
        byte[] heapFixture = new byte[8 * 1024 * 1024];
        heapFixture[0] = 1;
        System.gc();
        try (var sample = new BenchmarkResources(BenchmarkResourcesTest.class.getClassLoader(),
                Thread.currentThread().getId())) {
            sample.start();
            Thread.sleep(300L);
            sample.stop();
            var result = sample.snapshot();
            check(((Number) result.get("samples")).longValue() >= 2, "actual samples");
            check(((Number) result.get("heapUsedSamplePeakBytes")).longValue() >= heapFixture.length,
                "heap observed: " + result + "; directMXBean="
                    + java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage());
            check(((Number) result.get("wallNanos")).longValue() > 0, "monotonic window");
            if (!System.getProperty("os.name").startsWith("Windows")) {
                check(((Number) result.get("workingSetMeanBytes")).longValue() == -1, "no fake RSS from heap");
            }
            long count = ((Number) result.get("samples")).longValue();
            Thread.sleep(20L);
            check(((Number) sample.snapshot().get("samples")).longValue() == count, "sampler stopped");
        } finally {
            java.lang.ref.Reference.reachabilityFence(heapFixture);
        }
        System.out.println("BenchmarkResourcesTest PASS (denominators, unknowns, memory, stop, PSAPI layout)");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
