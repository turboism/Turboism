package dev.turboism.validation.modelupdate;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Preparation-only diagnostic, joined and stopped before any performance measurement. */
final class PreparationWatchdog implements AutoCloseable {
    private final Path directory;
    private final Thread worker;
    private volatile String stage = "starting";
    private volatile boolean closed;
    private volatile Exception failure;

    PreparationWatchdog(Path directory, long delayMillis) {
        if (directory == null || delayMillis < 1L) throw new IllegalArgumentException("invalid preparation watchdog");
        this.directory = directory;
        worker = new Thread(() -> {
            try {
                Thread.sleep(delayMillis);
                if (closed) return;
                StringBuilder text = new StringBuilder("stage=").append(stage)
                    .append("\nperformanceAccepted=false\nphase=preparation-only\n");
                var threads = ManagementFactory.getThreadMXBean().dumpAllThreads(true, true);
                int count = 0;
                for (var thread : threads) {
                    if (thread == null || count++ >= 256) continue;
                    text.append('\n').append(thread.getThreadId()).append(' ').append(thread.getThreadName())
                        .append(" state=").append(thread.getThreadState())
                        .append(" lock=").append(thread.getLockName())
                        .append(" owner=").append(thread.getLockOwnerName()).append('\n');
                    var stack = thread.getStackTrace();
                    for (int i = 0; i < Math.min(stack.length, 64); i++) text.append("  at ").append(stack[i]).append('\n');
                }
                if (closed) return;
                Path temporary = Files.createTempFile(directory, "preparation-threads-", ".tmp");
                try {
                    Files.writeString(temporary, text);
                    if (!closed) Files.move(temporary, directory.resolve("preparation-threads.txt"), StandardCopyOption.REPLACE_EXISTING);
                } finally { Files.deleteIfExists(temporary); }
            } catch (InterruptedException expectedStop) {
                Thread.currentThread().interrupt();
            } catch (Exception diagnosticFailure) { failure = diagnosticFailure; }
        }, "turboism-preparation-watchdog");
        worker.setDaemon(true);
        worker.start();
    }

    void stage(String name) throws java.io.IOException {
        if (closed || name == null || !name.matches("[a-z-]{1,64}")) throw new IllegalArgumentException("invalid preparation stage");
        stage = name;
        Files.writeString(directory.resolve("preparation-progress.txt"), "stage=" + name + "\n");
    }

    @Override public void close() throws Exception {
        closed = true;
        worker.interrupt();
        worker.join(5000L);
        if (worker.isAlive()) throw new IllegalStateException("preparation watcher did not stop before measurement");
        if (failure != null) throw new IllegalStateException("preparation diagnostic failed", failure);
    }
}
