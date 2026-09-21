package dev.turboism.validation.modelupdate;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Optional off-EDT observation of a measured leg, without forced GC or file I/O. */
final class BenchmarkResources implements AutoCloseable {
    private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    private final List<BufferPoolMXBean> buffers = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);
    private final List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
    private final com.sun.management.OperatingSystemMXBean os =
        ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean bean ? bean : null;
    private final com.sun.management.ThreadMXBean threads =
        ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean ? bean : null;
    private final long edtId;
    private final ProcessMemorySample nativeMemory;
    private String nativeStatus;
    private Thread sampler;
    private volatile boolean running;
    private long started, ended, cpuBefore, cpuAfter, allocationBefore, allocationAfter;
    private long gcBefore, gcAfter, gcMillisBefore, gcMillisAfter;
    private long samples, nativeSamples, failures, sampleNanos;
    private long heapSum, heapPeak, committedPeak, nonHeapPeak, directPeak;
    private long workingSum, workingPeak, privatePeak;

    BenchmarkResources(ClassLoader hostLoader, long edtId) {
        this.edtId = edtId;
        ProcessMemorySample created = null;
        try { created = new ProcessMemorySample(hostLoader); nativeStatus = "PSAPI-working-set-and-private-commit"; }
        catch (ReflectiveOperationException | RuntimeException | LinkageError absent) {
            nativeStatus = "unavailable:" + absent.getClass().getSimpleName();
        }
        nativeMemory = created;
    }
    void start() {
        if (sampler != null) throw new IllegalStateException("resource window cannot restart");
        sample();
        allocationBefore = allocated(); cpuBefore = cpu();
        gcBefore = gc(false); gcMillisBefore = gc(true);
        started = System.nanoTime();
        running = true;
        sampler = new Thread(() -> {
            while (running) {
                try { Thread.sleep(250L); }
                catch (InterruptedException end) { return; }
                if (running) sample();
            }
        }, "turboism-benchmark-resources");
        sampler.setDaemon(true);
        sampler.start();
    }
    void stop() throws InterruptedException {
        if (!running) return;
        ended = System.nanoTime(); cpuAfter = cpu(); allocationAfter = allocated();
        gcAfter = gc(false); gcMillisAfter = gc(true);
        running = false;
        sampler.interrupt(); sampler.join(2000L);
        if (sampler.isAlive()) throw new IllegalStateException("resource sampler did not stop");
        sample();
    }
    private synchronized void sample() {
        long start = System.nanoTime();
        try {
            var heap = memory.getHeapMemoryUsage();
            samples++; heapSum += heap.getUsed(); heapPeak = Math.max(heapPeak, heap.getUsed());
            committedPeak = Math.max(committedPeak, heap.getCommitted());
            nonHeapPeak = Math.max(nonHeapPeak, memory.getNonHeapMemoryUsage().getUsed());
            long direct = 0L;
            for (var buffer : buffers) if (buffer.getName().equals("direct")) direct += buffer.getMemoryUsed();
            directPeak = Math.max(directPeak, direct);
            if (nativeMemory != null) {
                try {
                    var reading = nativeMemory.read();
                    if (reading.workingSet() <= 0 || reading.privateCommit() < 0) {
                        throw new IllegalStateException("invalid PSAPI counters");
                    }
                    nativeSamples++; workingSum += reading.workingSet();
                    workingPeak = Math.max(workingPeak, reading.workingSet());
                    privatePeak = Math.max(privatePeak, reading.privateCommit());
                } catch (ReflectiveOperationException | RuntimeException nativeFailure) {
                    nativeStatus = "partial-or-unavailable:" + nativeFailure.getClass().getSimpleName(); failures++;
                }
            }
        } catch (RuntimeException failure) { failures++; }
        finally { sampleNanos += System.nanoTime() - start; }
    }
    private long cpu() { return os == null ? -1L : os.getProcessCpuTime(); }
    private long allocated() {
        if (threads == null || !threads.isThreadAllocatedMemorySupported()
            || !threads.isThreadAllocatedMemoryEnabled()) return -1L;
        return threads.getThreadAllocatedBytes(edtId);
    }
    private long gc(boolean millis) {
        long total = 0L;
        for (var collector : collectors) {
            long value = millis ? collector.getCollectionTime() : collector.getCollectionCount();
            if (value < 0L) return -1L;
            total += value;
        }
        return total;
    }
    static long delta(long before, long after) { return before < 0L || after < before ? -1L : after - before; }
    static double cpuPercent(long cpuNanos, long wallNanos, int processors) {
        return cpuNanos < 0L || wallNanos <= 0L || processors < 1 ? -1.0
            : 100.0 * cpuNanos / wallNanos / processors;
    }
    synchronized Map<String, Object> snapshot() {
        if (running || ended == 0L) throw new IllegalStateException("resource window not complete");
        long wall = ended - started, cpu = delta(cpuBefore, cpuAfter);
        int processors = Runtime.getRuntime().availableProcessors();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", "MXBeans+optional-own-process-PSAPI");
        out.put("nativeMemoryStatus", nativeStatus);
        out.put("sampleIntervalMillis", 250); out.put("samples", samples);
        out.put("nativeSamples", nativeSamples); out.put("sampleFailures", failures);
        out.put("samplerNanos", sampleNanos); out.put("wallNanos", wall);
        out.put("availableProcessors", processors); out.put("processCpuNanos", cpu);
        out.put("processCpuPercentOneCore", cpuPercent(cpu, wall, 1));
        out.put("processCpuPercentMachine", cpuPercent(cpu, wall, processors));
        out.put("edtAllocatedBytes", delta(allocationBefore, allocationAfter));
        out.put("heapUsedMeanBytes", samples == 0 ? -1L : heapSum / samples);
        out.put("heapUsedSamplePeakBytes", samples == 0 ? -1L : heapPeak);
        out.put("heapCommittedSamplePeakBytes", samples == 0 ? -1L : committedPeak);
        out.put("heapMaxBytes", memory.getHeapMemoryUsage().getMax());
        out.put("nonHeapUsedSamplePeakBytes", samples == 0 ? -1L : nonHeapPeak);
        out.put("directBufferSamplePeakBytes", samples == 0 ? -1L : directPeak);
        out.put("workingSetMeanBytes", nativeSamples == 0 ? -1L : workingSum / nativeSamples);
        out.put("workingSetSamplePeakBytes", nativeSamples == 0 ? -1L : workingPeak);
        out.put("privateCommitSamplePeakBytes", nativeSamples == 0 ? -1L : privatePeak);
        out.put("gcCollections", delta(gcBefore, gcAfter));
        out.put("gcCollectionMillis", delta(gcMillisBefore, gcMillisAfter));
        return out;
    }
    @Override public void close() throws InterruptedException { stop(); }
}
