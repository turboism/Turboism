package dev.turboism.validation;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** Test-only no-input observation; never requests GC, mutates models or changes product settings. */
final class NativeMemoryObservation {
    static void observe(Path home) throws Exception {
        int seconds = Integer.parseInt(System.getProperty("turboism.validation.textureUpload.memoryIdleSeconds", "0"));
        if (seconds == 0) return;
        if (seconds < 30 || seconds > 300) throw new IllegalArgumentException("memory idle window must be 30..300 seconds");
        Path directory = home.resolve("state/texture-upload/memory");
        Files.createDirectories(directory);
        if (!Files.isRegularFile(directory.resolve("attached.properties"))) {
            throw new IllegalStateException("process memory sampler was not attached before document readiness");
        }
        long start = System.nanoTime();
        write(directory.resolve("ready.properties"), "epochMillis=" + System.currentTimeMillis() + "\nseconds=" + seconds + "\n");
        try (var out = Files.newBufferedWriter(directory.resolve("jvm.csv"))) {
            out.write("elapsedSeconds,epochMillis,heapUsedBytes,heapCommittedBytes,heapMaxBytes,nonHeapUsedBytes,gcCount,gcCollectionTimeMs\n");
            for (int index = 0; index <= seconds; index++) {
                long remaining = start + index * 1_000_000_000L - System.nanoTime();
                if (remaining > 0) Thread.sleep(remaining / 1_000_000L, (int)(remaining % 1_000_000L));
                var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
                var nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
                long count = 0, millis = 0;
                for (var gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                    if (gc.getCollectionCount() < 0 || gc.getCollectionTime() < 0) {
                        throw new IllegalStateException("GC counters unavailable");
                    }
                    count += gc.getCollectionCount(); millis += gc.getCollectionTime();
                }
                out.write((System.nanoTime()-start)/1e9 + "," + System.currentTimeMillis() + ","
                    + heap.getUsed() + "," + heap.getCommitted() + "," + heap.getMax() + ","
                    + nonHeap.getUsed() + "," + count + "," + millis + "\n");
                out.flush();
            }
        }
        write(directory.resolve("end.properties"), "epochMillis=" + System.currentTimeMillis() + "\n");
        Path complete = directory.resolve("complete.properties");
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (!Files.isRegularFile(complete) && System.nanoTime() < deadline) Thread.sleep(100);
        Properties result = new Properties();
        try (var input = Files.newInputStream(complete)) { result.load(input); }
        if (!"PASS".equals(result.getProperty("status"))) throw new IllegalStateException("process memory sampling failed");
    }

    private static void write(Path path, String content) throws Exception {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temporary, content);
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
    }
}
