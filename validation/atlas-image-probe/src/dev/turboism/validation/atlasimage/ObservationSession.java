package dev.turboism.validation.atlasimage;

import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;

/** Owns only the passive observer, reporting threads and its own shutdown hook. */
final class ObservationSession {
    interface Platform extends ReportCompletion.Writer {
        void addHook(Thread hook);
        boolean removeHook(Thread hook);
        void startReporter(Thread reporter);
    }

    static final Platform JVM = new Platform() {
        @Override public void addHook(final Thread hook) { Runtime.getRuntime().addShutdownHook(hook); }
        @Override public boolean removeHook(final Thread hook) { return Runtime.getRuntime().removeShutdownHook(hook); }
        @Override public void startReporter(final Thread reporter) { reporter.start(); }
        @Override public void write(final Path file, final Properties values) throws Exception {
            AtlasImageLoadProbeAgent.write(file, values);
        }
    };

    private final Instrumentation instrumentation;
    private final AtlasImageLoadProbeAgent.Recorder recorder;
    private final Path run;
    private final Platform platform;
    private final ReportCompletion completion;
    private final Thread shutdown;
    private final Thread reporter;
    private final CountDownLatch armedSignal = new CountDownLatch(1);
    private volatile boolean armed;
    private boolean transformerAttempted;
    private boolean hookAdded;
    private String stage = "NEW";

    ObservationSession(final Instrumentation instrumentation, final AtlasImageLoadProbeAgent.Recorder recorder,
                       final Path run, final Platform platform) {
        this.instrumentation = instrumentation;
        this.recorder = recorder;
        this.run = run;
        this.platform = platform;
        completion = new ReportCompletion(recorder, run, platform);
        shutdown = new Thread(() -> {
            if (!armed) recorder.reject("SHUTDOWN_DURING_ARMING");
            completion.attempt("JVM_SHUTDOWN");
        }, "atlas-load-probe-shutdown");
        reporter = new Thread(this::report, "atlas-load-probe-reporter");
        reporter.setDaemon(true);
    }

    boolean start(final Properties identity) {
        try {
            stage = "IDENTITY";
            platform.write(run.resolve("identity.properties"), identity);
            stage = "TRANSFORMER";
            transformerAttempted = true;
            instrumentation.addTransformer(recorder, false);
            stage = "LOADED_SCAN";
            for (Class<?> type : instrumentation.getAllLoadedClasses()) recorder.alreadyLoaded(type);
            stage = "SHUTDOWN_HOOK";
            platform.addHook(shutdown);
            hookAdded = true;
            stage = "REPORTER";
            platform.startReporter(reporter);
            if (recorder.finished()) throw new IllegalStateException("observation ended during arming");
            armed = true;
            stage = "ARMED";
            armedSignal.countDown();
            return true;
        } catch (Throwable failure) {
            final String failedStage = stage;
            final boolean cleaned = abort();
            System.err.println("ATLAS_IMAGE_LOAD_PROBE_BLOCKED stage=" + failedStage
                + " failure=" + failure.getClass().getSimpleName() + " cleanupComplete=" + cleaned);
            return false;
        }
    }

    private void report() {
        try {
            armedSignal.await();
            while (!recorder.finished()) {
                if (Files.exists(run.resolve("finish.request"))) {
                    completion.attempt("EXPLICIT_FINISH");
                    return;
                }
                Thread.sleep(250L);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException failure) {
            recorder.reject("REPORTER_FAILED");
            completion.attempt("REPORTER_FAILED");
        }
    }

    private boolean abort() {
        recorder.reject("ARMING_FAILED_" + stage);
        recorder.end(); // Late callbacks after removeTransformer become no-ops.
        boolean cleaned = true;
        reporter.interrupt();
        if (reporter.isAlive()) {
            try { reporter.join(5000L); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); cleaned = false; }
            if (reporter.isAlive()) cleaned = false;
        }
        if (hookAdded) {
            try { if (!platform.removeHook(shutdown)) cleaned = false; }
            catch (Throwable failure) { cleaned = false; }
        }
        if (transformerAttempted) {
            try { if (!instrumentation.removeTransformer(recorder)) cleaned = false; }
            catch (Throwable failure) { cleaned = false; }
        }
        if (!cleaned) recorder.reject("CLEANUP_INCOMPLETE");
        completion.attempt("ARMING_FAILED");
        return cleaned;
    }
}
