package dev.turboism.validation.nativecharacter;

import java.nio.file.*;
import java.util.*;

/** Opt-in, bounded test-only wall-clock sampling; never changes the host model. */
final class ImportStackSampler implements AutoCloseable {
    private final Path output;
    private final Map<String, Long> counts = new HashMap<>();
    private volatile boolean running = true;
    private Thread thread;
    private long polls;

    ImportStackSampler(Path state) { output = state.resolve("import-stacks.txt"); }

    void start() {
        thread = new Thread(this::sample, "native-import-stack-sampler");
        thread.setDaemon(true);
        thread.start();
    }

    private void sample() {
        try {
            while (running && polls < 36_000) {
                for (var entry : Thread.getAllStackTraces().entrySet()) {
                    var target = entry.getKey();
                    var stack = entry.getValue();
                    boolean importer = Arrays.stream(stack).anyMatch(f ->
                            f.getClassName().startsWith("dev.turboism.validation.modelimport3d.host."));
                    if (!target.getName().startsWith("AWT-EventQueue") && !importer) continue;
                    var key = new StringBuilder(target.getName()).append(" [").append(target.getState()).append("]\n");
                    for (var frame : stack) key.append("  ").append(frame).append('\n');
                    String value = key.toString();
                    if (counts.size() < 10_000 || counts.containsKey(value)) counts.merge(value, 1L, Long::sum);
                }
                if (++polls % 50 == 0) flush();
                Thread.sleep(100);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            counts.merge("SAMPLER_ERROR " + error, 1L, Long::sum);
        } finally { flush(); }
    }

    private void flush() {
        try {
            var text = new StringBuilder("schemaVersion=1\nintervalMillis=100\npolls=").append(polls)
                    .append("\nupdatedEpochMillis=").append(System.currentTimeMillis())
                    .append("\nInterpretation=wall-clock stack counts, not CPU percentages\n");
            counts.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .forEach(entry -> text.append("\nSAMPLES ").append(entry.getValue()).append('\n').append(entry.getKey()));
            Path temporary = output.resolveSibling("import-stacks.tmp");
            Files.writeString(temporary, text);
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) { /* Diagnostic output must not change import acceptance. */ }
    }

    @Override public void close() {
        running = false;
        if (thread != null) thread.interrupt();
    }
}
