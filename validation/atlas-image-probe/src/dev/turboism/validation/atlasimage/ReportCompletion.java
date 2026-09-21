package dev.turboism.validation.atlasimage;

import java.nio.file.Path;
import java.util.Properties;

/** One immutable observation window, with serialized, retryable report persistence. */
final class ReportCompletion {
    @FunctionalInterface
    interface Writer {
        void write(Path path, Properties values) throws Exception;
    }

    private final AtlasImageLoadProbeAgent.Recorder recorder;
    private final Path run;
    private final Writer writer;
    private Properties frozen;
    private boolean persisted;
    private int attempts;

    ReportCompletion(final AtlasImageLoadProbeAgent.Recorder recorder, final Path run, final Writer writer) {
        this.recorder = recorder;
        this.run = run;
        this.writer = writer;
    }

    synchronized boolean frozen() { return frozen != null; }
    synchronized boolean persisted() { return persisted; }

    synchronized void finish(final String reason) throws Exception {
        if (persisted) return;
        if (frozen == null) {
            frozen = recorder.freeze();
            frozen.setProperty("completionReason", reason);
            frozen.setProperty("runId", run.getFileName().toString());
        }
        final Properties report = new Properties();
        report.putAll(frozen);
        report.setProperty("reportAttempts", Integer.toString(++attempts));
        // Hold only this completion monitor during IO, never the class-callback recorder monitor.
        // A non-daemon shutdown hook waits here if the daemon reporter owns an in-flight write.
        writer.write(run.resolve("result.properties"), report);
        persisted = true;
    }

    void attempt(final String reason) {
        try {
            finish(reason);
        } catch (Exception failure) {
            System.err.println("ATLAS_IMAGE_LOAD_PROBE_REPORT_FAILED " + failure.getClass().getSimpleName());
        }
    }
}
