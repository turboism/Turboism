package dev.turboism.validation;

import java.lang.management.ManagementFactory;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shared, JDK-only load accounting; packaged independently of sibling premain agents. */
final class NativeLoadMetrics {
    private NativeLoadMetrics() {}

    static long processCpu() {
        return ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean bean ? bean.getProcessCpuTime() : -1;
    }

    static final class Allocations {
        final ConcurrentHashMap<Long, Long> observed = new ConcurrentHashMap<>();
        final AtomicBoolean running = new AtomicBoolean();
        final com.sun.management.ThreadMXBean bean;
        Thread thread;
        boolean stopped;
        long total = -1;
        Allocations() {
            bean = ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean candidate
                && candidate.isThreadAllocatedMemorySupported() ? candidate : null;
            if (bean == null) return;
            bean.setThreadAllocatedMemoryEnabled(true); running.set(true);
            thread = new Thread(() -> {
                while (running.get()) {
                    sample();
                    try { Thread.sleep(100); } catch (InterruptedException stopped) { break; }
                }
            }, "turboism-native-load-allocation-sampler");
            thread.setDaemon(true); thread.start();
        }
        void sample() {
            long[] ids = bean.getAllThreadIds(), bytes = bean.getThreadAllocatedBytes(ids);
            for (int i = 0; i < ids.length; i++) if (bytes[i] >= 0) observed.merge(ids[i], bytes[i], Math::max);
        }
        synchronized long stopAndTotal() {
            if (bean == null || stopped) return total;
            stopped = true; running.set(false);
            if (thread != null) {
                thread.interrupt();
                try {thread.join(1000);} catch (InterruptedException interrupted) {Thread.currentThread().interrupt();}
                if (thread.isAlive()) return -1;
            }
            sample(); total = observed.values().stream().mapToLong(Long::longValue).sum();
            return total;
        }
    }

}
