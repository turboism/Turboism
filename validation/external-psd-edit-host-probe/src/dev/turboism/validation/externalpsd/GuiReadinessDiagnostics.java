package dev.turboism.validation.externalpsd;

import static dev.turboism.validation.externalpsd.HostCloseCoordinator.guiReadyTriggerPath;
import static dev.turboism.validation.externalpsd.TargetResolution.remainingMillis;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import javax.swing.SwingUtilities;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Observational diagnostics for a GUI readiness wait that never received its trigger.
 *
 * <p>The probe owns one daemon that samples host thread state on a fixed schedule and writes
 * bounded thread dumps into the task state directory. Nothing here runs on the readiness worker,
 * the EDT, or a host callback thread.</p>
 */
final class GuiReadinessDiagnostics {
    static final String GUI_READY_TRIGGER_NAME = "gui-ready.flag";
    static final String GUI_TRIGGER_ARMED_MARKER =
        "EXTERNAL_PSD_EDIT_GUI_TRIGGER_ARMED";
    static final long GUI_READY_TRIGGER_TIMEOUT_MILLIS = 300_000L;
    static final long GUI_READY_TRIGGER_POLL_MILLIS = 250L;
    static final long GUI_SESSION_FILE_POLL_MILLIS = 250L;
    static final long GUI_CURRENT_RAW_EXPORT_POLL_MILLIS = 1000L;
    static final int GUI_SESSION_FILE_STABLE_READS = 3;

    static final long[] GUI_WAIT_DIAGNOSTIC_DELAYS_MILLIS = {120_000L, 180_000L};
    static final int GUI_WAIT_DIAGNOSTIC_MAX_SAMPLES = 2;
    static final int GUI_WAIT_DIAGNOSTIC_MAX_THREADS = 256;
    static final int GUI_WAIT_DIAGNOSTIC_MAX_STACK_FRAMES = 128;
    static final int GUI_WAIT_DIAGNOSTIC_MAX_BYTES = 256 * 1024;

    static final String GUI_WAIT_DIAGNOSTIC_STAGE = "armed-waiting-for-trigger";
    static final String GUI_WAIT_DIAGNOSTIC_FILE_PREFIX =
        "external-psd-gui-thread-dump-";

    static final GuiDiagnosticClock SYSTEM_GUI_DIAGNOSTIC_CLOCK =
        new GuiDiagnosticClock() {
            @Override public long nanoTime() { return System.nanoTime(); }
            @Override public Instant utcNow() { return Instant.now(); }
        };

    static String expectedHostVersion(final String raw) {
        if (raw == null || raw.isBlank()) return "5.3.02";
        final String trimmed = raw.trim();
        if (trimmed.length() == 4 && trimmed.startsWith("5")) {
            return trimmed.charAt(0) + "." + trimmed.charAt(1) + "." + trimmed.substring(2);
        }
        return trimmed;
    }

    /** Clock seam used only to exercise the long-running GUI wait without sleeping in tests. */
    interface GuiDiagnosticClock {
        long nanoTime();
        Instant utcNow();
    }

    @FunctionalInterface
    interface GuiDiagnosticSleeper {
        void sleep(long millis) throws InterruptedException;
    }

    @FunctionalInterface
    interface GuiDiagnosticSampler {
        String sample() throws Exception;
    }

    /**
     * Observational, task-local diagnostics for a GUI readiness wait that has not received its
     * Runner trigger.  This object owns one daemon only; it never runs on the readiness worker,
     * the EDT, or a host callback thread.
     */
    static final class GuiWaitDiagnostics implements AutoCloseable {
        final Path stateDir;
        final String runId;
        final long armedAtNanos;
        final BooleanSupplier stopped;
        final BooleanSupplier triggerPresent;
        final AtomicReference<String> waitingStage;
        final GuiDiagnosticClock clock;
        final GuiDiagnosticSleeper sleeper;
        final GuiDiagnosticSampler sampler;
        final Consumer<String> reporter;
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicBoolean triggerMarked = new AtomicBoolean();
        final AtomicBoolean triggerObservationUnavailable = new AtomicBoolean();
        final AtomicInteger sampleAttempts = new AtomicInteger();
        final AtomicInteger writtenSamples = new AtomicInteger();
        final AtomicInteger unavailableSamples = new AtomicInteger();
        final Thread thread;

        GuiWaitDiagnostics(final Path stateDir, final String runId,
            final long armedAtNanos, final BooleanSupplier stopped,
            final BooleanSupplier triggerPresent, final AtomicReference<String> waitingStage,
            final GuiDiagnosticClock clock, final GuiDiagnosticSleeper sleeper,
            final GuiDiagnosticSampler sampler, final Consumer<String> reporter) {
            this.stateDir = Objects.requireNonNull(stateDir, "GUI diagnostic state directory");
            this.runId = runId == null ? "" : runId;
            this.armedAtNanos = armedAtNanos;
            this.stopped = Objects.requireNonNull(stopped, "GUI diagnostic stop supplier");
            this.triggerPresent = Objects.requireNonNull(
                triggerPresent, "GUI diagnostic trigger supplier");
            this.waitingStage = Objects.requireNonNull(
                waitingStage, "GUI diagnostic waiting stage");
            this.clock = Objects.requireNonNull(clock, "GUI diagnostic clock");
            this.sleeper = Objects.requireNonNull(sleeper, "GUI diagnostic sleeper");
            this.sampler = Objects.requireNonNull(sampler, "GUI diagnostic sampler");
            this.reporter = reporter == null ? ignored -> { } : reporter;
            this.thread = new Thread(this::run, "external-psd-gui-thread-diagnostics");
            this.thread.setDaemon(true);
        }

        void start() { thread.start(); }

        @Override public void close() { close("closed"); }

        void close(final String reason) {
            if (!triggerMarked.get() && reason != null && !reason.isBlank()) {
                waitingStage.set(reason);
            }
            closed.set(true);
            thread.interrupt();
        }

        void markTriggerReceived() {
            triggerMarked.set(true);
            waitingStage.set("trigger-received");
            closed.set(true);
            thread.interrupt();
        }

        int writtenSamples() { return writtenSamples.get(); }
        int unavailableSamples() { return unavailableSamples.get(); }

        /** Test-only bounded join; production close never waits for this daemon. */
        boolean awaitForTest(final long timeoutMillis) throws InterruptedException {
            if (timeoutMillis < 0) throw new IllegalArgumentException(
                "GUI diagnostic test timeout must not be negative");
            thread.join(timeoutMillis);
            return !thread.isAlive();
        }

        void run() {
            try {
                for (int index = 0; index < GUI_WAIT_DIAGNOSTIC_DELAYS_MILLIS.length; index++) {
                    if (!waitUntilDue(GUI_WAIT_DIAGNOSTIC_DELAYS_MILLIS[index])) return;
                    final int ordinal = sampleAttempts.incrementAndGet();
                    if (ordinal > GUI_WAIT_DIAGNOSTIC_MAX_SAMPLES || !maySample()) return;
                    sample(ordinal);
                }
            } catch (InterruptedException interrupted) {
                // close()/disable() is the normal interruption path.  No readiness state is
                // changed and no diagnostic is claimed when scheduling is stopped.
                Thread.currentThread().interrupt();
            } catch (Throwable failure) {
                if (!maySample()) return;
                final int ordinal = sampleAttempts.incrementAndGet();
                if (ordinal <= GUI_WAIT_DIAGNOSTIC_MAX_SAMPLES) {
                    unavailableSamples.incrementAndGet();
                    writeSample(ordinal, unavailableBody("scheduler", failure));
                }
                report("EXTERNAL_PSD_EDIT_GUI_THREAD_DIAGNOSTIC unavailable runId="
                    + SessionFileReadiness.safeDiagnostic(runId) + " reason=" + failure);
            }
        }

        boolean waitUntilDue(final long delayMillis) throws InterruptedException {
            final long delayNanos = TimeUnit.MILLISECONDS.toNanos(delayMillis);
            while (elapsedNanos() < delayNanos) {
                if (!maySample()) return false;
                final long remainingNanos = delayNanos - elapsedNanos();
                sleeper.sleep(Math.min(1000L, nanosToMillisCeiling(remainingNanos)));
            }
            return maySample();
        }

        void sample(final int ordinal) {
            if (!maySample()) return;
            final String body;
            try {
                final String sampled = sampler.sample();
                if (sampled == null) throw new IllegalStateException(
                    "thread diagnostic sampler returned null");
                body = sampled;
            } catch (Throwable failure) {
                if (!maySample()) return;
                unavailableSamples.incrementAndGet();
                writeSample(ordinal, unavailableBody("ThreadMXBean", failure));
                return;
            }
            // If the Runner trigger appeared while dumpAllThreads was running, discard the
            // observation rather than writing a post-ready sample as waiting evidence.
            if (!maySample()) return;
            writeSample(ordinal, body);
        }

        boolean maySample() {
            if (closed.get()) return false;
            try {
                if (stopped.getAsBoolean()) return false;
            } catch (Throwable failure) {
                reportOnce("stop-state", failure);
                return false;
            }
            try {
                if (triggerPresent.getAsBoolean()) return false;
            } catch (Throwable failure) {
                // Unknown trigger state is fail-closed for diagnostics.  The ordinary readiness
                // worker remains responsible for deciding whether the trigger is valid.
                reportOnce("trigger-state", failure);
                return false;
            }
            return true;
        }

        long elapsedNanos() {
            return Math.max(0L, clock.nanoTime() - armedAtNanos);
        }

        static long nanosToMillisCeiling(final long nanos) {
            if (nanos <= 0) return 1L;
            final long millis = TimeUnit.NANOSECONDS.toMillis(nanos);
            return millis == 0 || nanos % 1_000_000L != 0 ? millis + 1L : millis;
        }

        void writeSample(final int ordinal, final String body) {
            try {
                final Path directory = guiDiagnosticStateDirectory(stateDir);
                final Path output = directory.resolve(
                    GUI_WAIT_DIAGNOSTIC_FILE_PREFIX + ordinal + ".txt");
                if (Files.isSymbolicLink(output)
                    || Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalStateException(
                        "diagnostic output already exists or is a symlink: " + output);
                }
                final StringBuilder content = new StringBuilder();
                content.append("runId=").append(GuiRowDispatch.quoted(runId)).append('\n');
                content.append("sampleOrdinal=").append(ordinal).append('\n');
                content.append("sampleUtc=").append(clock.utcNow()).append('\n');
                content.append("elapsedMillis=").append(TimeUnit.NANOSECONDS.toMillis(
                    elapsedNanos())).append('\n');
                content.append("waitingStage=").append(SessionFileReadiness.safeDiagnostic(
                    waitingStage.get())).append('\n');
                content.append("samplingThreadName=").append(GuiRowDispatch.quoted(
                    Thread.currentThread().getName())).append('\n');
                content.append(body == null ? unavailableBody(
                    "diagnostic", new IllegalStateException("null output")) : body);
                Files.write(output, boundedUtf8(content.toString()),
                    java.nio.file.StandardOpenOption.CREATE_NEW,
                    java.nio.file.StandardOpenOption.WRITE);
                writtenSamples.incrementAndGet();
                report("EXTERNAL_PSD_EDIT_GUI_THREAD_DIAGNOSTIC written runId="
                    + SessionFileReadiness.safeDiagnostic(runId) + " sample=" + ordinal + " path=" + output);
            } catch (Throwable failure) {
                report("EXTERNAL_PSD_EDIT_GUI_THREAD_DIAGNOSTIC unavailable runId="
                    + SessionFileReadiness.safeDiagnostic(runId) + " sample=" + ordinal + " reason=" + failure);
            }
        }

        String unavailableBody(final String operation, final Throwable failure) {
            return "diagnostic unavailable operation=" + operation + " error=" + failure
                + "\n" + GuiRowDispatch.stackTrace(failure);
        }

        void reportOnce(final String operation, final Throwable failure) {
            if (triggerObservationUnavailable.compareAndSet(false, true)) {
                report("EXTERNAL_PSD_EDIT_GUI_THREAD_DIAGNOSTIC unavailable runId="
                    + SessionFileReadiness.safeDiagnostic(runId) + " operation=" + operation + " reason=" + failure);
            }
        }

        void report(final String message) {
            try {
                reporter.accept(message);
            } catch (Throwable ignored) {
                // Diagnostic reporting cannot be allowed to affect the readiness wait.
            }
        }
    }

    static GuiWaitDiagnostics startGuiWaitDiagnostics(final Path stateDir,
        final String runId, final long armedAtNanos, final BooleanSupplier stopped,
        final BooleanSupplier triggerPresent, final AtomicReference<String> waitingStage,
        final GuiDiagnosticClock clock, final GuiDiagnosticSleeper sleeper,
        final GuiDiagnosticSampler sampler, final Consumer<String> reporter) {
        final GuiWaitDiagnostics diagnostics = new GuiWaitDiagnostics(stateDir, runId,
            armedAtNanos, stopped, triggerPresent, waitingStage, clock, sleeper, sampler,
            reporter);
        diagnostics.start();
        return diagnostics;
    }

    static GuiWaitDiagnostics startGuiWaitDiagnosticsForTest(final Path stateDir,
        final String runId, final long armedAtNanos, final BooleanSupplier stopped,
        final BooleanSupplier triggerPresent, final AtomicReference<String> waitingStage,
        final GuiDiagnosticClock clock, final GuiDiagnosticSleeper sleeper,
        final GuiDiagnosticSampler sampler) {
        return startGuiWaitDiagnostics(stateDir, runId, armedAtNanos, stopped, triggerPresent,
            waitingStage, clock, sleeper, sampler, ignored -> { });
    }

    static String captureGuiThreadDumpForTest() {
        return captureGuiThreadDump();
    }

    static Path guiDiagnosticStateDirectory(final Path stateDir) {
        return guiReadyTriggerPath(stateDir).getParent();
    }

    static byte[] boundedUtf8(final String value) {
        final byte[] full = value.getBytes(StandardCharsets.UTF_8);
        if (full.length <= GUI_WAIT_DIAGNOSTIC_MAX_BYTES) return full;
        final String marker = "\n...[diagnostic output truncated]...\n";
        final int budget = GUI_WAIT_DIAGNOSTIC_MAX_BYTES
            - marker.getBytes(StandardCharsets.UTF_8).length;
        int low = 0;
        int high = value.length();
        while (low < high) {
            final int middle = (low + high + 1) >>> 1;
            if (value.substring(0, middle).getBytes(StandardCharsets.UTF_8).length <= budget) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return (value.substring(0, low) + marker).getBytes(StandardCharsets.UTF_8);
    }

    static String captureGuiThreadDump() {
        final ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (bean == null) throw new IllegalStateException("ThreadMXBean is unavailable");
        final Thread current = Thread.currentThread();
        final ThreadInfo[] infos = bean.dumpAllThreads(true, true);
        return renderGuiThreadDump(infos, current.getName(), current.getId());
    }

    static String renderGuiThreadDump(final ThreadInfo[] infos,
        final String samplingThreadName, final long samplingThreadId) {
        final List<ThreadInfo> ordered = new ArrayList<>();
        if (infos != null) {
            for (final ThreadInfo info : infos) if (info != null) ordered.add(info);
        }
        ordered.sort(Comparator.comparingInt((ThreadInfo info) -> guiThreadPriority(
            info, samplingThreadName, samplingThreadId))
            .thenComparingLong(info -> info.getThreadId()));
        final int outputCount = Math.min(GUI_WAIT_DIAGNOSTIC_MAX_THREADS, ordered.size());
        boolean samplingPresent = false;
        boolean bootstrapPresent = false;
        boolean awtPresent = false;
        final StringBuilder text = new StringBuilder();
        text.append("threadDumpSource=java.lang.management.ThreadMXBean.dumpAllThreads")
            .append(" lockedMonitors=true lockedSynchronizers=true\n");
        text.append("threadCount=").append(infos == null ? 0 : infos.length)
            .append(" outputThreadCount=").append(outputCount)
            .append(" maxStackFrames=").append(GUI_WAIT_DIAGNOSTIC_MAX_STACK_FRAMES)
            .append(" samplingThreadId=").append(samplingThreadId)
            .append(" samplingThreadName=").append(GuiRowDispatch.quoted(samplingThreadName)).append('\n');
        for (int index = 0; index < outputCount; index++) {
            final ThreadInfo info = ordered.get(index);
            final String name = info.getThreadName();
            samplingPresent |= info.getThreadId() == samplingThreadId;
            bootstrapPresent |= name != null && name.contains("turboism-bootstrap");
            awtPresent |= name != null && name.startsWith("AWT-EventQueue");
            appendThreadInfo(text, index, info);
        }
        text.append("samplingThreadPresent=").append(samplingPresent)
            .append(" bootstrapThreadPresent=").append(bootstrapPresent)
            .append(" awtEventQueuePresent=").append(awtPresent)
            .append(" threadsTruncated=").append(ordered.size() > outputCount).append('\n');
        if (!samplingPresent || !bootstrapPresent || !awtPresent) {
            text.append("importantThreadObservation=missing-or-not-running-at-sample-time\n");
        }
        return text.toString();
    }

    static int guiThreadPriority(final ThreadInfo info,
        final String samplingThreadName, final long samplingThreadId) {
        if (info.getThreadId() == samplingThreadId
            || Objects.equals(info.getThreadName(), samplingThreadName)) return 0;
        final String name = info.getThreadName();
        if (name != null && name.contains("turboism-bootstrap")) return 1;
        if (name != null && name.startsWith("AWT-EventQueue")) return 2;
        return 3;
    }

    static void appendThreadInfo(final StringBuilder text, final int index,
        final ThreadInfo info) {
        text.append("thread[").append(index).append("] id=").append(info.getThreadId())
            .append(" name=").append(GuiRowDispatch.quoted(info.getThreadName()))
            .append(" state=").append(info.getThreadState())
            .append(" suspended=").append(info.isSuspended())
            .append(" inNative=").append(info.isInNative())
            .append(" blockedCount=").append(info.getBlockedCount())
            .append(" blockedTimeMillis=").append(info.getBlockedTime())
            .append(" waitedCount=").append(info.getWaitedCount())
            .append(" waitedTimeMillis=").append(info.getWaitedTime())
            .append(" lockName=").append(GuiRowDispatch.quoted(info.getLockName()))
            .append(" lockOwnerId=").append(info.getLockOwnerId())
            .append(" lockOwnerName=").append(GuiRowDispatch.quoted(info.getLockOwnerName()))
            .append(" lockInfo=").append(info.getLockInfo()).append('\n');
        for (final java.lang.management.MonitorInfo monitor : info.getLockedMonitors()) {
            text.append("  lockedMonitor=").append(monitor)
                .append(" stackDepth=").append(monitor.getLockedStackDepth()).append('\n');
        }
        for (final java.lang.management.LockInfo synchronizer : info.getLockedSynchronizers()) {
            text.append("  lockedSynchronizer=").append(synchronizer).append('\n');
        }
        final StackTraceElement[] stack = info.getStackTrace();
        text.append("  stackFrames=").append(stack.length).append('\n');
        final int frameCount = Math.min(GUI_WAIT_DIAGNOSTIC_MAX_STACK_FRAMES, stack.length);
        for (int frame = 0; frame < frameCount; frame++) {
            text.append("    at ").append(stack[frame]).append('\n');
        }
        if (stack.length > frameCount) {
            text.append("    ... stack truncated after ").append(frameCount)
                .append(" frames\n");
        }
    }

    /**
     * Test seam for the task-local trigger protocol.  It deliberately contains no Swing access;
     * production calls it from the daemon worker and tests can inject a short sleeper without
     * changing the production timeout or polling policy.
     */
    static GuiTriggerWait waitForGuiReadyTriggerForTest(final Path stateDir,
        final long timeoutMillis, final BooleanSupplier stopped,
        final SessionFileReadiness.TriggerSleeper sleeper) {
        final GuiTriggerArm arm = prepareGuiTrigger(stateDir);
        return arm.armed()
            ? waitForArmedGuiTrigger(arm, timeoutMillis, stopped, sleeper)
            : GuiTriggerWait.rejected(arm.diagnostic());
    }

    static GuiTriggerArm prepareGuiTriggerForTest(final Path stateDir) {
        return prepareGuiTrigger(stateDir);
    }

    static GuiTriggerWait waitForArmedGuiTriggerForTest(final GuiTriggerArm arm,
        final long timeoutMillis, final BooleanSupplier stopped,
        final SessionFileReadiness.TriggerSleeper sleeper) {
        return waitForArmedGuiTrigger(arm, timeoutMillis, stopped, sleeper);
    }

    static GuiTriggerArm prepareGuiTrigger(final Path stateDir) {
        final Path flag;
        try {
            flag = HostCloseCoordinator.guiReadyTriggerPath(stateDir);
        } catch (RuntimeException invalidStateDirectory) {
            return GuiTriggerArm.rejected(invalidStateDirectory.getMessage());
        }
        final String path = flag.toString();
        if (Files.isSymbolicLink(flag)) {
            return GuiTriggerArm.rejected("GUI readiness trigger is a symlink: " + path);
        }
        if (Files.exists(flag, LinkOption.NOFOLLOW_LINKS)) {
            return GuiTriggerArm.rejected(
                "GUI readiness trigger pre-existed this probe: " + path);
        }
        return GuiTriggerArm.armed(flag);
    }

    static GuiTriggerWait waitForArmedGuiTrigger(final GuiTriggerArm arm,
        final long timeoutMillis, final BooleanSupplier stopped,
        final SessionFileReadiness.TriggerSleeper sleeper) {
        Objects.requireNonNull(stopped, "stopped");
        Objects.requireNonNull(sleeper, "sleeper");
        if (SwingUtilities.isEventDispatchThread()) {
            return GuiTriggerWait.rejected(
                "GUI readiness trigger wait must run off the EDT");
        }
        if (timeoutMillis <= 0) {
            return GuiTriggerWait.rejected("GUI readiness trigger timeout must be positive");
        }
        if (arm == null || !arm.armed() || arm.path() == null) {
            return GuiTriggerWait.rejected(arm == null
                ? "GUI readiness trigger was not armed"
                : arm.diagnostic());
        }
        final Path flag = arm.path();
        final String path = flag.toString();

        final long deadline = System.nanoTime()
            + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (!stopped.getAsBoolean() && System.nanoTime() < deadline) {
            try {
                // Revalidate the fixed directory and trigger parent on every poll.  The
                // absence check belongs to prepareGuiTrigger; after the armed marker the
                // runner is allowed to create the file before this wait's first poll.
                final Path current = HostCloseCoordinator.guiReadyTriggerPath(flag.getParent());
                if (!current.equals(flag)) {
                    return GuiTriggerWait.rejected(
                        "GUI readiness trigger path changed after arming: " + path);
                }
            } catch (RuntimeException invalidStateDirectory) {
                return GuiTriggerWait.rejected(invalidStateDirectory.getMessage());
            }
            if (Files.isSymbolicLink(flag)) {
                return GuiTriggerWait.rejected("GUI readiness trigger is a symlink: " + path);
            }
            if (Files.exists(flag, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isRegularFile(flag, LinkOption.NOFOLLOW_LINKS)) {
                    return GuiTriggerWait.ready(flag);
                }
                return GuiTriggerWait.rejected(
                    "GUI readiness trigger is not a regular file: " + path);
            }
            try {
                final long remainingMillis = Math.max(1L,
                    TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                sleeper.sleep(Math.min(GUI_READY_TRIGGER_POLL_MILLIS, remainingMillis));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return GuiTriggerWait.rejected("GUI readiness trigger wait interrupted");
            }
        }
        return stopped.getAsBoolean()
            ? GuiTriggerWait.rejected("probe stopped while waiting for GUI readiness trigger")
            : GuiTriggerWait.rejected("GUI readiness trigger timeout after "
                + timeoutMillis + "ms: " + path);
    }

    record GuiTriggerArm(boolean armed, Path path, String diagnostic) {
        GuiTriggerArm {
            diagnostic = diagnostic == null ? "" : diagnostic;
        }

        static GuiTriggerArm armed(final Path path) {
            return new GuiTriggerArm(true, path, "");
        }

        static GuiTriggerArm rejected(final String diagnostic) {
            return new GuiTriggerArm(false, null, diagnostic);
        }
    }

    record GuiTriggerWait(boolean ready, Path path, String diagnostic) {
        GuiTriggerWait {
            diagnostic = diagnostic == null ? "" : diagnostic;
        }

        static GuiTriggerWait ready(final Path path) {
            return new GuiTriggerWait(true, path, "");
        }

        static GuiTriggerWait rejected(final String diagnostic) {
            return new GuiTriggerWait(false, null, diagnostic);
        }
    }
}
