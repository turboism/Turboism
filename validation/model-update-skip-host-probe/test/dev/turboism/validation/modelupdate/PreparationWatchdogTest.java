package dev.turboism.validation.modelupdate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Offline only: no host, input events, or GL context. */
public final class PreparationWatchdogTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("preparation-watchdog-test-");
        try {
            try (PreparationWatchdog watchdog = new PreparationWatchdog(root, 20L)) {
                watchdog.stage("discover");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!Files.isRegularFile(root.resolve("preparation-threads.txt")) && System.nanoTime() < deadline) {
                    Thread.sleep(10L);
                }
                if (!Files.isRegularFile(root.resolve("preparation-threads.txt"))) {
                    throw new AssertionError("stalled preparation produced no thread diagnostic");
                }
                String dump = Files.readString(root.resolve("preparation-threads.txt"));
                if (!dump.contains("stage=discover") || !dump.contains("PreparationWatchdogTest")) {
                    throw new AssertionError("missing preparation phase or calling thread stack");
                }
            }
            Files.deleteIfExists(root.resolve("preparation-threads.txt"));
            try (PreparationWatchdog watchdog = new PreparationWatchdog(root, 200L)) {
                watchdog.stage("ready");
            }
            Thread.sleep(250L);
            if (Files.exists(root.resolve("preparation-threads.txt"))) {
                throw new AssertionError("closed preparation watcher wrote during measurement");
            }
            if (Thread.getAllStackTraces().keySet().stream().anyMatch(t -> t.isAlive()
                    && t.getName().equals("turboism-preparation-watchdog"))) {
                throw new AssertionError("watcher thread survived close");
            }
            System.out.println("PreparationWatchdogTest PASS (stage, thread dump, stop before timing)");
        } finally {
            try (var files = Files.walk(root)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }
}
