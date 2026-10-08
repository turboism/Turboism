import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Task-local diagnostic only. Does not enable monitoring, collect stacks or request GC. */
final class NativeJvmContextObservation {
    private NativeJvmContextObservation() {}

    record Row(String kind, String name, long id, long first, long second, long third, String state) {}
    record Snapshot(long epochStartMillis, long epochEndMillis,
                    long nanoStart, long nanoEnd, List<Row> rows) {}
    record Pair(Snapshot before, Snapshot after) {}
    private static final AtomicReference<Pair> PENDING = new AtomicReference<>();

    static void publish(Snapshot before, Snapshot after) {
        if (!PENDING.compareAndSet(null, new Pair(before, after))) {
            throw new IllegalStateException("unconsumed JVM context pair");
        }
    }

    static void persistPending(Path run, int operation) throws Exception {
        Pair pair = PENDING.getAndSet(null);
        if (pair == null) throw new IllegalStateException("missing JVM context pair");
        persist(run, "native-command-before", operation, pair.before());
        persist(run, "native-command-after", operation, pair.after());
    }

    static Snapshot capture() {
        long startEpoch = System.currentTimeMillis();
        long startNano = System.nanoTime();
        List<Row> rows = new ArrayList<>();
        var memory = ManagementFactory.getMemoryMXBean();
        usage(rows, "heap", "heap", memory.getHeapMemoryUsage());
        usage(rows, "nonheap", "nonheap", memory.getNonHeapMemoryUsage());
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            MemoryUsage value = pool.getUsage();
            if (value == null) rows.add(new Row("pool", pool.getName(), -1, -1, -1, -1, "UNAVAILABLE"));
            else usage(rows, "pool", pool.getName(), value);
        }
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            rows.add(new Row("gc", gc.getName(), -1, gc.getCollectionCount(),
                             gc.getCollectionTime(), -1, gc.isValid() ? "VALID" : "INVALID"));
        }
        for (BufferPoolMXBean buffer : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            rows.add(new Row("buffer", buffer.getName(), -1, buffer.getCount(),
                             buffer.getMemoryUsed(), buffer.getTotalCapacity(), "OBSERVED"));
        }
        var compiler = ManagementFactory.getCompilationMXBean();
        if (compiler != null) {
            boolean supported = compiler.isCompilationTimeMonitoringSupported();
            rows.add(new Row("compiler", compiler.getName(), -1,
                             supported ? compiler.getTotalCompilationTime() : -1,
                             -1, -1, supported ? "SUPPORTED" : "UNSUPPORTED"));
        }
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        boolean supported = threads.isThreadCpuTimeSupported();
        boolean enabled = supported && threads.isThreadCpuTimeEnabled();
        rows.add(new Row("thread-count", "threads", -1, threads.getThreadCount(),
                         threads.getPeakThreadCount(), threads.getTotalStartedThreadCount(),
                         enabled ? "CPU_ENABLED" : "CPU_UNAVAILABLE"));
        long[] ids = threads.getAllThreadIds();
        ThreadInfo[] infos = threads.getThreadInfo(ids, 0);
        for (int i = 0; i < ids.length; i++) {
            ThreadInfo info = infos[i];
            // A thread may exit between enumeration, info and CPU reads. Preserve unavailable values.
            rows.add(new Row("thread", info == null ? "<exited>" : info.getThreadName(), ids[i],
                             enabled ? threads.getThreadCpuTime(ids[i]) : -1,
                             enabled ? threads.getThreadUserTime(ids[i]) : -1, -1,
                             info == null ? "EXITED" : info.getThreadState().name()));
        }
        return new Snapshot(startEpoch, System.currentTimeMillis(), startNano, System.nanoTime(), List.copyOf(rows));
    }

    private static void usage(List<Row> rows, String kind, String name, MemoryUsage value) {
        rows.add(new Row(kind, name, -1, value.getUsed(), value.getCommitted(), value.getMax(), "OBSERVED"));
    }

    static String safe(String value) {
        return value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    static void persist(Path run, String phase, int operation, Snapshot snapshot) throws Exception {
        Path target = run.resolve("native-jvm-context.tsv");
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            Files.writeString(target,
                    "phase\toperation\tepochStartMillis\tepochEndMillis\tnanoStart\tnanoEnd\tkind\tname\tid\tfirst\tsecond\tthird\tstate\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        }
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("JVM context evidence must be a regular file");
        }
        StringBuilder text = new StringBuilder();
        for (Row row : snapshot.rows()) {
            text.append(safe(phase)).append('\t').append(operation).append('\t')
                .append(snapshot.epochStartMillis()).append('\t').append(snapshot.epochEndMillis()).append('\t')
                .append(snapshot.nanoStart()).append('\t').append(snapshot.nanoEnd()).append('\t')
                .append(row.kind()).append('\t').append(safe(row.name())).append('\t').append(row.id()).append('\t')
                .append(row.first()).append('\t').append(row.second()).append('\t').append(row.third()).append('\t')
                .append(row.state()).append('\n');
        }
        Files.writeString(target, text, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }
}
