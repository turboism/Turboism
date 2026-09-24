package dev.turboism.validation.atlasimage;

import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Deterministic offline regressions for reporting and partial arming failures. */
public final class AtlasImageLifecycleSelfCheck {
    private static int checks;
    private AtlasImageLifecycleSelfCheck() {}

    public static void main(final String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("shutdown-race")) {
            realShutdown(Path.of(args[1]));
            return;
        }
        final Path root = Files.createTempDirectory("atlas-lifecycle-selfcheck-");
        try {
            retryAfterFilesystemFailure(root.resolve("retry"));
            concurrentFinish(root.resolve("concurrent"));
            for (String stage : new String[]{"IDENTITY", "TRANSFORMER", "LOADED_SCAN", "SHUTDOWN_HOOK",
                    "REPORTER", "START_THEN_FAIL"}) armingFailure(root.resolve(stage), stage);
            successfulArming(root.resolve("success"));
            System.out.println("ATLAS_IMAGE_LIFECYCLE_SELFCHECK PASS checks=" + checks + " hostExecuted=false");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path file : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }

    private static AtlasImageLoadProbeAgent.Recorder recorder() {
        return new AtlasImageLoadProbeAgent.Recorder(Map.of(), "file:/synthetic-only", "not-loaded");
    }

    private static void retryAfterFilesystemFailure(final Path run) throws Exception {
        Files.createDirectory(run);
        final var recorder = recorder();
        final var completion = new ReportCompletion(recorder, run, AtlasImageLoadProbeAgent::write);
        Files.createDirectory(run.resolve("result.properties"));
        Files.writeString(run.resolve("result.properties/block"), "block atomic replace");
        try { completion.finish("EXPLICIT_FINISH"); throw new AssertionError("write unexpectedly succeeded"); }
        catch (IOException expected) { checks++; }
        check(recorder.finished() && completion.frozen() && !completion.persisted(), "frozen is not persisted");
        recorder.reject("AFTER_FREEZE_MUST_NOT_REPLACE_OBSERVATIONS");
        Files.delete(run.resolve("result.properties/block"));
        Files.delete(run.resolve("result.properties"));
        completion.finish("JVM_SHUTDOWN");
        final Properties result = load(run.resolve("result.properties"));
        check(completion.persisted(), "shutdown retries failed persistence");
        check("2".equals(result.getProperty("reportAttempts")), "retry count");
        check("EXPLICIT_FINISH".equals(result.getProperty("completionReason")), "same frozen window");
        check("0".equals(result.getProperty("eventCount")), "frozen observation not resampled");
    }

    private static void concurrentFinish(final Path run) throws Exception {
        Files.createDirectory(run);
        final CountDownLatch writing = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch shutdownStarted = new CountDownLatch(1);
        final CountDownLatch shutdownReturned = new CountDownLatch(1);
        final AtomicInteger writes = new AtomicInteger();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final var completion = new ReportCompletion(recorder(), run, (path, properties) -> {
            writes.incrementAndGet(); writing.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("writer release timed out");
            AtlasImageLoadProbeAgent.write(path, properties);
        });
        final Thread reporter = new Thread(() -> {
            try { completion.finish("EXPLICIT_FINISH"); } catch (Throwable error) { failure.set(error); }
        });
        final Thread shutdown = new Thread(() -> {
            shutdownStarted.countDown();
            try { completion.finish("JVM_SHUTDOWN"); } catch (Throwable error) { failure.set(error); }
            finally { shutdownReturned.countDown(); }
        });
        reporter.start();
        try {
            check(writing.await(5, TimeUnit.SECONDS), "writer entered");
            shutdown.start();
            check(shutdownStarted.await(5, TimeUnit.SECONDS), "shutdown contender entered");
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (shutdown.getState() != Thread.State.BLOCKED && shutdown.isAlive() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            check(shutdown.getState() == Thread.State.BLOCKED && shutdownReturned.getCount() == 1,
                "shutdown cannot mistake frozen for persisted");
        } finally {
            release.countDown(); reporter.join(5000); shutdown.join(5000);
        }
        check(!reporter.isAlive() && !shutdown.isAlive() && failure.get() == null, "both completion requests exit");
        check(writes.get() == 1 && completion.persisted(), "one successful write");
    }

    private static void realShutdown(final Path run) throws Exception {
        Files.createDirectory(run);
        final CountDownLatch writing = new CountDownLatch(1);
        final CountDownLatch shutdown = new CountDownLatch(1);
        final var completion = new ReportCompletion(recorder(), run, (path, properties) -> {
            writing.countDown();
            if (!shutdown.await(5, TimeUnit.SECONDS)) throw new IOException("shutdown hook not entered");
            Thread.sleep(100L); // Make a frozen-only hook deterministically return before daemon IO.
            AtlasImageLoadProbeAgent.write(path, properties);
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            shutdown.countDown();
            completion.attempt("JVM_SHUTDOWN");
        }));
        final Thread reporter = new Thread(() -> completion.attempt("EXPLICIT_FINISH"));
        reporter.setDaemon(true);
        reporter.start();
        if (!writing.await(5, TimeUnit.SECONDS)) throw new AssertionError("daemon writer not entered");
        // Main returns normally; the real JVM runs its non-daemon shutdown hook.
    }

    private static void armingFailure(final Path run, final String failingStage) throws Exception {
        Files.createDirectory(run);
        final FakeHost host = new FakeHost(failingStage);
        final var recorder = recorder();
        final var session = new ObservationSession(host.instrumentation(), recorder, run, host);
        check(!session.start(new Properties()), "arming rejects " + failingStage);
        check(recorder.finished() && host.transformer == null && host.hook == null,
            "partial resources removed " + failingStage);
        check(host.reporter == null || !host.reporter.isAlive(), "reporter stopped " + failingStage);
        final Properties result = load(run.resolve("result.properties"));
        check("BLOCKED".equals(result.getProperty("status")), "arming failure never passes " + failingStage);
    }

    private static void successfulArming(final Path run) throws Exception {
        Files.createDirectory(run);
        final FakeHost host = new FakeHost("");
        final var recorder = recorder();
        check(new ObservationSession(host.instrumentation(), recorder, run, host).start(new Properties()),
            "session arms");
        Files.createFile(run.resolve("finish.request"));
        check(host.resultWritten.await(5, TimeUnit.SECONDS), "explicit finish persists");
        host.hook.run();
        host.reporter.join(5000);
        check(recorder.finished() && !host.reporter.isAlive(), "reporter ended normally");
        check(host.resultWrites.get() == 1, "shutdown does not rewrite successful result");
    }

    private static Properties load(final Path file) throws Exception {
        final Properties result = new Properties();
        try (var in = Files.newInputStream(file)) { result.load(in); }
        return result;
    }

    private static final class FakeHost implements ObservationSession.Platform {
        private final String failingStage;
        private ClassFileTransformer transformer;
        private Thread hook;
        private Thread reporter;
        private final CountDownLatch resultWritten = new CountDownLatch(1);
        private final AtomicInteger resultWrites = new AtomicInteger();
        FakeHost(final String failingStage) { this.failingStage = failingStage; }
        Instrumentation instrumentation() {
            return (Instrumentation) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Instrumentation.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "addTransformer" -> {
                        transformer = (ClassFileTransformer) args[0];
                        if (failingStage.equals("TRANSFORMER")) throw new IllegalStateException("injected registration");
                        yield null;
                    }
                    case "getAllLoadedClasses" -> {
                        if (failingStage.equals("LOADED_SCAN")) throw new IllegalStateException("injected scan");
                        yield new Class<?>[0];
                    }
                    case "removeTransformer" -> {
                        boolean matches = transformer == args[0];
                        transformer = null; yield matches;
                    }
                    default -> throw new AssertionError("unexpected instrumentation call: " + method.getName());
                });
        }
        @Override public void addHook(final Thread value) {
            if (failingStage.equals("SHUTDOWN_HOOK")) throw new IllegalStateException("injected hook");
            hook = value;
        }
        @Override public boolean removeHook(final Thread value) {
            boolean matches = hook == value; hook = null; return matches;
        }
        @Override public void startReporter(final Thread value) {
            reporter = value;
            if (failingStage.equals("REPORTER")) throw new IllegalStateException("injected reporter");
            reporter.start();
            if (failingStage.equals("START_THEN_FAIL")) throw new IllegalStateException("injected after start");
        }
        @Override public void write(final Path path, final Properties values) throws Exception {
            if (path.getFileName().toString().equals("identity.properties") && failingStage.equals("IDENTITY")) {
                throw new IOException("injected identity write");
            }
            AtlasImageLoadProbeAgent.write(path, values);
            if (path.getFileName().toString().equals("result.properties")) {
                resultWrites.incrementAndGet(); resultWritten.countDown();
            }
        }
    }

    private static void check(final boolean condition, final String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }
}
