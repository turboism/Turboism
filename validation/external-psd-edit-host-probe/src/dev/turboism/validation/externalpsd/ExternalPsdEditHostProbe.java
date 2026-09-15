package dev.turboism.validation.externalpsd;

import dev.turboism.sdk.cubism.clipmask.PsdClipMaskDocumentSnapshot;
import dev.turboism.sdk.cubism.clipmask.PsdClipMaskDocumentSnapshot.PsdLayerSnapshot;
import dev.turboism.sdk.cubism.ProjectFileOperationType;
import dev.turboism.sdk.cubism.command.EditorCommandResult;
import dev.turboism.sdk.cubism.command.EditorFileCommand;
import dev.turboism.sdk.cubism.command.EditorFileCommandRequest;
import dev.turboism.sdk.cubism.command.EditorOverwritePolicy;
import dev.turboism.sdk.cubism.model.ArtMeshTextureInputs;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.ModelImageRelation;
import dev.turboism.sdk.cubism.model.RawLayerBinding;
import dev.turboism.sdk.cubism.model.TextureInputBinding;
import dev.turboism.sdk.cubism.model.TextureRelationsSnapshot;
import dev.turboism.sdk.cubism.psd.PsdEditFile;
import dev.turboism.sdk.cubism.psd.PsdExportResult;
import dev.turboism.sdk.cubism.psd.PsdFileOperationResult;
import dev.turboism.sdk.cubism.psd.PsdFileRevision;
import dev.turboism.sdk.cubism.psd.PsdReplaceResult;
import dev.turboism.sdk.event.cubism.ProjectFileLifecycleEvent;
import dev.turboism.sdk.cubism.id.RawImageId;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;
import dev.turboism.sdk.ui.UserFileHandle;
import dev.turboism.sdk.ui.UserFileLifetime;
import dev.turboism.sdk.ui.UserFileMode;
import dev.turboism.sdk.ui.UserFileRequest;
import dev.turboism.sdk.ui.UserFileRequestResult;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import javax.swing.table.TableCellRenderer;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.KeyboardFocusManager;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongConsumer;
import java.util.stream.Stream;

/**
 * Test-only 025 external PSD edit pipeline probe.
 *
 * <p>Drives the SDK seam end to end: relation resolution → native export → runtime-issued
 * handle → observed stable saves → explicit-target native replacement → native Undo/Redo
 * → handle stop → same-binding recovery. The probe never inspects pixels and never guesses
 * file paths from plugin input; it locates the runtime allocation only for simulated
 * external writes, exactly as an external editor would see it. In the explicit persistence
 * phase only, the validation-only fixture helper decodes the target RGB fingerprint; the
 * ordinary GUI phase does not parse PSD pixels.</p>
 *
 * <p>Phases ({@code -Dturboism.validation.externalpsd.phase}): {@code pipeline} (default)
 * runs the full save/replace/undo/stop pipeline and, with {@code .persist=1}, appends a
 * mediated SAVE_AS plus lifecycle-event confirmation; {@code reopen} re-exports the validated
 * target layer from a previously saved fixture copy; {@code gui} right-clicks a real object row,
 * clicks the contributed menu item, and verifies the plugin's own session auto-imports a
 * written save.</p>
 *
 * <p>NOT covered (recorded honestly): multi-document isolation, F3/F4 fixture entities.</p>
 */
public final class ExternalPsdEditHostProbe implements TurboismPlugin {
    private static final String GUI_READY_TRIGGER_NAME = "gui-ready.flag";
    private static final String GUI_TRIGGER_ARMED_MARKER =
        "EXTERNAL_PSD_EDIT_GUI_TRIGGER_ARMED";
    private static final long GUI_READY_TRIGGER_TIMEOUT_MILLIS = 300_000L;
    private static final long GUI_READY_TRIGGER_POLL_MILLIS = 250L;
    private static final long GUI_SESSION_FILE_POLL_MILLIS = 250L;
    private static final int GUI_SESSION_FILE_STABLE_READS = 3;
    private static final long HOST_CLOSE_TIMEOUT_MILLIS = 30_000L;
    private static final long HOST_CLOSE_POLL_MILLIS = 100L;
    private static final long HOST_CLOSE_EDT_CALL_TIMEOUT_MILLIS = 500L;
    private static final long TASK_WINDOW_BIND_TIMEOUT_MILLIS = 30_000L;
    private static final long TASK_WINDOW_BIND_POLL_MILLIS = 250L;
    private static final int EXIT_DIAGNOSTIC_MAX_THREADS = 256;
    private static final int EXIT_DIAGNOSTIC_MAX_STACK_FRAMES = 128;
    static final int EXIT_DIAGNOSTIC_MAX_CHARS = 256 * 1024;
    private static final int SESSION_DIAGNOSTIC_LIMIT = 512;
    private static final long[] GUI_WAIT_DIAGNOSTIC_DELAYS_MILLIS = {120_000L, 180_000L};
    private static final int GUI_WAIT_DIAGNOSTIC_MAX_SAMPLES = 2;
    private static final int GUI_WAIT_DIAGNOSTIC_MAX_THREADS = 256;
    private static final int GUI_WAIT_DIAGNOSTIC_MAX_STACK_FRAMES = 128;
    static final int GUI_WAIT_DIAGNOSTIC_MAX_BYTES = 256 * 1024;
    private static final AtomicLong CLOSE_SESSION_SEQUENCE = new AtomicLong();
    private static final int CLOSE_DIALOG_MAX_COUNT = 32;
    static final int CLOSE_DIALOG_FIELD_MAX_BYTES = 128;
    static final int CLOSE_DIAGNOSTIC_MAX_BYTES = 8 * 1024;
    static final int CLOSE_TRACE_MAX_BYTES = 7168;
    static final int CLOSE_TRACE_BEFORE_MAX_BYTES = 1536;
    static final int CLOSE_TRACE_AFTER_MAX_BYTES = 1536;
    static final int CLOSE_TRACE_DISPATCH_MAX_BYTES = 1024;
    static final int CLOSE_TRACE_REASON_MAX_BYTES = 2048;
    private static final String CLOSE_TRACE_SEPARATOR = "; ";
    static final int CLOSE_RESULT_REASON_MAX_BYTES = CLOSE_DIAGNOSTIC_MAX_BYTES
        - CLOSE_TRACE_MAX_BYTES - CLOSE_TRACE_SEPARATOR.getBytes(StandardCharsets.UTF_8).length;
    private static final String GUI_WAIT_DIAGNOSTIC_STAGE = "armed-waiting-for-trigger";
    private static final String GUI_WAIT_DIAGNOSTIC_FILE_PREFIX =
        "external-psd-gui-thread-dump-";
    private static final GuiDiagnosticClock SYSTEM_GUI_DIAGNOSTIC_CLOCK =
        new GuiDiagnosticClock() {
            @Override public long nanoTime() { return System.nanoTime(); }
            @Override public Instant utcNow() { return Instant.now(); }
        };
    private static final int BOUNDED_SETTLE_MAX_ATTEMPTS = 3;
    private static final long BOUNDED_SETTLE_MAX_DURATION_MILLIS = 15_000L;
    private static final long BOUNDED_SETTLE_INITIAL_BACKOFF_MILLIS = 100L;
    private static final long BOUNDED_SETTLE_MAX_BACKOFF_MILLIS = 500L;

    private PluginContext context;
    private volatile boolean stopped;
    private Thread worker;
    private volatile GuiWaitDiagnostics guiWaitDiagnostics;
    /** GUI-only cache: an off-EDT verified context is reused by all tables from one loader. */
    private final Map<ClassLoader, ExactHostRowTarget.HostAccessPreparation> hostAccessByLoader =
        new IdentityHashMap<>();
    /** The first exact host window containing the verified fixture target; never re-bound. */
    private volatile Window guiBoundWindow;

    @Override public void init(final PluginContext context) { this.context = context; }

    @Override public void enable() {
        final String runId = System.getProperty("turboism.validation.externalpsd.runId", "");
        if (runId.isBlank()) throw new IllegalStateException("Task-scoped runId is required");
        worker = new Thread(() -> run(runId), "external-psd-edit-host-probe");
        worker.setDaemon(true);
        worker.start();
    }

    @Override public void disable() {
        stopped = true;
        final GuiWaitDiagnostics diagnostics = guiWaitDiagnostics;
        if (diagnostics != null) diagnostics.close("disabled");
        if (worker != null) worker.interrupt();
    }
    @Override public void shutdown() { disable(); }

    private void run(final String runId) {
        final Properties result = new Properties();
        final int cycles = Integer.getInteger("turboism.validation.externalpsd.cycles", 3);
        result.setProperty("schemaVersion", "1");
        result.setProperty("runId", runId);
        result.setProperty("profile", "025-external-edit-pipeline-v1");
        result.setProperty("expectedHostVersion", "5.3.02");
        result.setProperty("hostIdentityEvidence", "runner exact JAR/BAT identity and lifecycle evidence");
        result.setProperty("cycles.requested", Integer.toString(cycles));
        final String phase = System.getProperty(
            "turboism.validation.externalpsd.phase", "pipeline");
        result.setProperty("phase", phase);
        try {
            if ("gui".equals(phase)) awaitGuiReadyTrigger(result);
            awaitReady();
            switch (phase) {
                case "reopen" -> runReopen(result);
                case "gui" -> runGui(result);
                case "pipeline" -> runPipeline(result, cycles);
                default -> throw new IllegalStateException("unknown probe phase " + phase);
            }
            result.setProperty("status", "PASS");
        } catch (Blocked blocked) {
            result.setProperty("status", "BLOCKED");
            result.setProperty("expected", blocked.expected);
            result.setProperty("actual", blocked.getMessage());
            context.logger().warn("EXTERNAL_PSD_EDIT_RESULT status=BLOCKED " + blocked.getMessage());
        } catch (Throwable error) {
            if (stopped) return;
            result.setProperty("status", "FAIL");
            result.setProperty("expected", "external edit save→replace→undo→stop→recover pipeline");
            result.setProperty("actual", error.toString());
            if (error.getCause() != null) result.setProperty("cause", error.getCause().toString());
            final StringWriter trace = new StringWriter();
            error.printStackTrace(new java.io.PrintWriter(trace));
            result.setProperty("failureTrace", trace.toString());
        }
        if (stopped) return;
        Path stateDir = null;
        try {
            stateDir = context.paths().stateDir();
            Files.createDirectories(stateDir);
            final var output = new StringWriter();
            result.store(output, "025 external PSD edit pipeline probe; not full feature acceptance");
            Files.writeString(stateDir.resolve("external-psd-edit-result.pending"),
                output.toString());
            Files.move(stateDir.resolve("external-psd-edit-result.pending"),
                stateDir.resolve("external-psd-edit-result.properties"),
                StandardCopyOption.REPLACE_EXISTING);
            context.logger().info("EXTERNAL_PSD_EDIT_RESULT status=" + result.getProperty("status"));
        } catch (Throwable error) {
            context.logger().error("EXTERNAL_PSD_EDIT_RESULT_WRITE_FAILED", error);
        }
        if (stopped) return;
        if (stateDir == null) {
            context.logger().error(
                "EXTERNAL_PSD_EDIT_EXIT_FAILED no task state directory was available");
            return;
        }
        try {
            armExitWatchdog(stateDir);
            // The exact GUI target is closed asynchronously.  A missing target is left to the
            // shared Supervisor rather than guessed from an unrelated visible window.
            closeHostGracefully();
        } catch (Throwable error) {
            context.logger().error("EXTERNAL_PSD_EDIT_EXIT_FAILED", error);
        }
    }

    /**
     * Drives Cubism's own window-close path for the exact window that contained the verified GUI
     * target.  The close event is posted asynchronously because a dirty document can enter a
     * modal option dialog on the EDT; the worker then observes that dialog without waiting for
     * the close event through {@code invokeAndWait}.
     */
    private void closeHostGracefully() {
        final Window target = guiBoundWindow;
        if (target == null) {
            context.logger().warn(
                "EXTERNAL_PSD_EDIT_EXIT_SKIPPED no exact task target window was bound; "
                    + "Supervisor owns cleanup");
            return;
        }
        try {
            final HostCloseResult result = closeBoundHostWindow(target);
            if (result.success()) {
                context.logger().info("EXTERNAL_PSD_EDIT_EXIT " + result.diagnostic());
            } else {
                context.logger().error("EXTERNAL_PSD_EDIT_EXIT_FAILED " + result.diagnostic());
            }
        } catch (Throwable error) {
            context.logger().error("EXTERNAL_PSD_EDIT_EXIT_FAILED", error);
        }
    }

    /**
     * Daemon watchdog armed after the result is recorded: if native shutdown stalls, it writes one
     * bounded thread-stack diagnostic.  Cleanup and process termination remain the Supervisor's
     * responsibility; this probe never terminates its own JVM.
     */
    private static void armExitWatchdog(final Path stateDir) {
        try {
            Files.writeString(
                stateDir.resolve("external-psd-exit-armed.txt"),
                "watchdog armed " + java.time.Instant.now());
        } catch (Throwable ignored) {
        }
        final Thread dumper = new Thread(() -> {
            try {
                Thread.sleep(15000);
                // Per-thread stack traces use handshakes, not a global safepoint, so a thread
                // wedged in native code cannot stall the dump itself.
                final String text = boundedExitThreadDiagnostic();
                Files.writeString(stateDir.resolve("external-psd-exit-threads.txt"), text);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Throwable ignored) {
            }
        }, "external-psd-exit-dump");
        dumper.setDaemon(true);
        dumper.start();
    }

    private static String boundedExitThreadDiagnostic() {
        final StringBuilder text = new StringBuilder("JVM exit watchdog: shutdown stalled\n");
        int includedThreads = 0;
        for (final Thread thread : allThreads()) {
            if (includedThreads >= EXIT_DIAGNOSTIC_MAX_THREADS) {
                appendBounded(text, "\n[thread list truncated]");
                break;
            }
            if (!appendBounded(text, "\n\"" + thread.getName() + "\" "
                + thread.getState())) break;
            includedThreads++;
            int includedFrames = 0;
            for (final StackTraceElement frame : thread.getStackTrace()) {
                if (includedFrames >= EXIT_DIAGNOSTIC_MAX_STACK_FRAMES) {
                    appendBounded(text, "\n    [... stack truncated]");
                    break;
                }
                if (!appendBounded(text, "\n    at " + frame)) return text.toString();
                includedFrames++;
            }
        }
        return text.toString();
    }

    static String exitThreadDiagnosticForTest() {
        return boundedExitThreadDiagnostic();
    }

    private static boolean appendBounded(final StringBuilder text, final String value) {
        if (text.length() >= EXIT_DIAGNOSTIC_MAX_CHARS) return false;
        final int remaining = EXIT_DIAGNOSTIC_MAX_CHARS - text.length();
        if (value.length() <= remaining) {
            text.append(value);
            return true;
        }
        text.append(value, 0, remaining);
        return false;
    }

    private static List<Thread> allThreads() {
        ThreadGroup group = Thread.currentThread().getThreadGroup();
        while (group.getParent() != null) group = group.getParent();
        Thread[] threads = new Thread[group.activeCount() + 16];
        int count;
        while ((count = group.enumerate(threads, true)) == threads.length) {
            threads = new Thread[threads.length * 2];
        }
        final List<Thread> alive = new ArrayList<>(count);
        for (int index = 0; index < count; index++) alive.add(threads[index]);
        return alive;
    }

    /**
     * Sends the host close event only to the exact task window retained by target resolution.
     * Keeping the value checks in this small helper makes the no-cross-window rule executable
     * offline without constructing a real AWT window in the headless focused test.
     */
    private static CloseDispatchResult dispatchBoundWindowClose(final Window target) {
        if (!SwingUtilities.isEventDispatchThread()) {
            return CloseDispatchResult.rejected("bound host close must run on EDT");
        }
        if (target == null) return CloseDispatchResult.rejected("bound host window is unavailable");
        final String identity = componentIdentity(target);
        return dispatchBoundWindowClose(identity, identity, target.isShowing(),
            target.isDisplayable(),
            () -> target.dispatchEvent(new WindowEvent(target, WindowEvent.WINDOW_CLOSING)));
    }

    /** Test seam for the exact-window checks used by {@link #dispatchBoundWindowClose(Window)}. */
    static CloseDispatchResult dispatchBoundWindowCloseForTest(final String expectedIdentity,
        final String actualIdentity, final boolean showing, final boolean displayable,
        final Runnable dispatch) {
        return dispatchBoundWindowClose(expectedIdentity, actualIdentity, showing, displayable,
            dispatch);
    }

    private static CloseDispatchResult dispatchBoundWindowClose(final String expectedIdentity,
        final String actualIdentity, final boolean showing, final boolean displayable,
        final Runnable dispatch) {
        if (expectedIdentity == null || expectedIdentity.isBlank()) {
            return CloseDispatchResult.rejected("bound host window identity is unavailable");
        }
        if (!expectedIdentity.equals(actualIdentity)) {
            return CloseDispatchResult.rejected("close target identity changed from "
                + expectedIdentity + " to " + actualIdentity);
        }
        if (!showing || !displayable) {
            return CloseDispatchResult.rejected("bound host window is not showing/displayable: "
                + actualIdentity);
        }
        if (dispatch == null) return CloseDispatchResult.rejected("close dispatch is unavailable");
        try {
            dispatch.run();
            return CloseDispatchResult.dispatched(
                "event=WINDOW_CLOSING target=" + actualIdentity);
        } catch (Throwable failure) {
            return CloseDispatchResult.rejected("event=WINDOW_CLOSING target=" + actualIdentity
                + " dispatch failed: " + stackTrace(failure));
        }
    }

    /**
     * Posts WINDOW_CLOSING and observes the modal save path without synchronously waiting for the
     * event handler.  Every Swing read/action in this routine is scheduled on the EDT; the worker
     * waits only on bounded latches, so a host modal loop or native stall remains diagnosable.
     */
    private static HostCloseResult closeBoundHostWindow(final Window target) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return HostCloseResult.rejected("host close coordinator must run off EDT");
        }
        final CloseSessionTrace trace = new CloseSessionTrace();
        final AtomicBoolean coordinatorActive = new AtomicBoolean(true);
        final AtomicBoolean noActionClaimed = new AtomicBoolean();
        final AtomicBoolean noActionSucceeded = new AtomicBoolean();
        final AtomicBoolean noActionFailed = new AtomicBoolean();
        try {
            final EdtCall<List<CloseDialogSnapshot>> beforeCall = invokeEdtBounded(
                () -> visibleDialogsOnEdt(target), HOST_CLOSE_EDT_CALL_TIMEOUT_MILLIS);
            if (!beforeCall.completed()) {
                return withCloseTrace(trace, HostCloseResult.timeout(
                    "could not snapshot pre-close dialogs on EDT"));
            }
            if (beforeCall.failure() != null) {
                return withCloseTrace(trace, HostCloseResult.rejected(
                    "pre-close dialog snapshot failed: " + stackTrace(beforeCall.failure())));
            }
            final List<CloseDialogSnapshot> beforeDialogs = beforeCall.value();
            if (beforeDialogs == null) {
                return withCloseTrace(trace, HostCloseResult.rejected(
                    "pre-close dialog snapshot was unavailable"));
            }
            trace.recordBefore(beforeDialogs);

            final AtomicReference<CloseDispatchResult> dispatch = new AtomicReference<>();
            final AtomicBoolean closeStarted = new AtomicBoolean();
            try {
                SwingUtilities.invokeLater(() -> {
                    if (!coordinatorActive.get()) return;
                    closeStarted.set(true);
                    trace.recordDispatchAttempt();
                    final CloseDispatchResult dispatched = dispatchBoundWindowClose(target);
                    trace.recordDispatch(dispatched);
                    dispatch.set(dispatched);
                });
            } catch (RuntimeException failure) {
                return withCloseTrace(trace, HostCloseResult.rejected(
                    "WINDOW_CLOSING could not be posted: " + stackTrace(failure)));
            }

            final long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(HOST_CLOSE_TIMEOUT_MILLIS);
            String lastDiagnostic = "WINDOW_CLOSING has not reached the EDT";
            while (System.nanoTime() < deadline) {
                final CloseDispatchResult dispatched = dispatch.get();
                if (dispatched != null && !dispatched.dispatched()) {
                    return withCloseTrace(trace,
                        HostCloseResult.rejected(dispatched.diagnostic()));
                }
                if (!closeStarted.get()) {
                    lastDiagnostic = "WINDOW_CLOSING is queued on the EDT";
                } else {
                    final EdtCall<CloseDialogInspection> inspection = invokeEdtBounded(
                        () -> inspectAndMaybeDismissCloseDialog(target, beforeDialogs,
                            coordinatorActive, noActionClaimed, noActionSucceeded,
                            noActionFailed, trace),
                        HOST_CLOSE_EDT_CALL_TIMEOUT_MILLIS);
                    if (!inspection.completed()) {
                        lastDiagnostic = "EDT close observation timed out";
                    } else if (inspection.failure() != null) {
                        return withCloseTrace(trace, HostCloseResult.rejected(
                            "close observation failed: " + stackTrace(inspection.failure())));
                    } else if (inspection.value() != null) {
                        final CloseDialogInspection observed = inspection.value();
                        lastDiagnostic = observed.diagnostic();
                        if (observed.rejected()) {
                            return withCloseTrace(trace,
                                HostCloseResult.rejected(lastDiagnostic));
                        }
                        if (observed.targetGone()) {
                            if (dispatched == null || !dispatched.dispatched()) {
                                lastDiagnostic = "bound host window disappeared before "
                                    + "WINDOW_CLOSING dispatch completed";
                                continue;
                            }
                            return withCloseTrace(trace, HostCloseResult.success(
                                noActionSucceeded.get()
                                    ? "native close completed after exact dirty-save NO selection"
                                    : "native close completed with no dirty-save dialog"));
                        }
                    }
                }
                final long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                Thread.sleep(Math.min(HOST_CLOSE_POLL_MILLIS,
                    Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining))));
            }
            return withCloseTrace(trace, HostCloseResult.timeout(
                "native close did not complete before timeout; last=" + lastDiagnostic));
        } finally {
            // Timed-out invokeLater inspections must not act after Supervisor cleanup has taken
            // ownership of the host.
            coordinatorActive.set(false);
        }
    }

    private static <T> EdtCall<T> invokeEdtBounded(final Callable<T> operation,
        final long timeoutMillis) throws InterruptedException {
        Objects.requireNonNull(operation, "EDT operation");
        if (timeoutMillis <= 0) return EdtCall.timeout();
        if (SwingUtilities.isEventDispatchThread()) {
            try {
                return EdtCall.completed(operation.call());
            } catch (Throwable failure) {
                return EdtCall.failed(failure);
            }
        }
        final AtomicReference<T> value = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        try {
            SwingUtilities.invokeLater(() -> {
                try {
                    value.set(operation.call());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    done.countDown();
                }
            });
        } catch (RuntimeException error) {
            return EdtCall.failed(error);
        }
        if (!done.await(timeoutMillis, TimeUnit.MILLISECONDS)) return EdtCall.timeout();
        return failure.get() == null
            ? EdtCall.completed(value.get()) : EdtCall.failed(failure.get());
    }

    private static List<CloseDialogSnapshot> visibleDialogsOnEdt(final Window target) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("dialog discovery must run on EDT");
        }
        return visibleCloseDialogs(target);
    }

    private static CloseDialogInspection inspectAndMaybeDismissCloseDialog(final Window target,
        final List<CloseDialogSnapshot> beforeDialogs, final AtomicBoolean coordinatorActive,
        final AtomicBoolean noActionClaimed, final AtomicBoolean noActionSucceeded,
        final AtomicBoolean noActionFailed, final CloseSessionTrace trace) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("dialog inspection must run on EDT");
        }
        if (!coordinatorActive.get()) {
            return CloseDialogInspection.waiting("close observation was cancelled");
        }
        if (noActionFailed != null && noActionFailed.get()) {
            return CloseDialogInspection.rejected(
                "dirty-save NO action failed; close observation is fail-closed");
        }
        if (target == null || !target.isShowing() || !target.isDisplayable()) {
            return CloseDialogInspection.targetGone("bound host window is no longer showing");
        }
        if (beforeDialogs == null) {
            return CloseDialogInspection.rejected("pre-close dialog snapshots are unavailable");
        }
        final List<CloseDialogSnapshot> currentDialogs = visibleCloseDialogs(target);
        if (trace != null) trace.recordInspection(currentDialogs);
        final CloseDialogDecision decision = classifyCloseDialog(
            componentIdentity(target), beforeDialogs, currentDialogs);
        if (trace != null) {
            trace.recordReason(decision == null ? "close dialog decision unavailable"
                : decision.diagnostic());
        }
        if (decision.outcome() == CloseDialogOutcome.DISMISS_NO) {
            return applyNoDialogDecision(decision, noActionClaimed, noActionSucceeded,
                noActionFailed);
        }
        if (decision.outcome() == CloseDialogOutcome.REJECTED) {
            return CloseDialogInspection.rejected(decision.diagnostic());
        }
        return CloseDialogInspection.waiting(decision.diagnostic());
    }

    /**
     * Applies the one permitted dirty-save answer on the EDT. The CAS is shared by every
     * inspection queued for this close session, including inspections that outlive an
     * invokeEdtBounded timeout; a failed action remains claimed and is never retried as another
     * real button action.
     */
    private static CloseDialogInspection applyNoDialogDecision(
        final CloseDialogDecision decision, final AtomicBoolean noActionClaimed,
        final AtomicBoolean noActionSucceeded, final AtomicBoolean noActionFailed) {
        if (!SwingUtilities.isEventDispatchThread()) {
            return CloseDialogInspection.rejected("dirty-save option action must run on EDT");
        }
        if (decision == null || decision.outcome() != CloseDialogOutcome.DISMISS_NO
            || decision.dismissNo() == null) {
            return CloseDialogInspection.rejected("No dirty-save action is unavailable");
        }
        if (noActionClaimed == null || !noActionClaimed.compareAndSet(false, true)) {
            return CloseDialogInspection.waiting(
                "dirty-save NO action was already claimed by an earlier inspection");
        }
        try {
            decision.dismissNo().run();
            if (noActionSucceeded != null) noActionSucceeded.set(true);
            return CloseDialogInspection.dismissed(
                decision.diagnostic() + " selected=NO(index=1)");
        } catch (Throwable failure) {
            if (noActionFailed != null) noActionFailed.set(true);
            return CloseDialogInspection.rejected("NO option dispatch failed: "
                + stackTrace(failure));
        }
    }

    /** Test seam for the shared late-inspection No-action gate. Must be invoked on the EDT. */
    static CloseDialogInspection dismissNoForTest(final CloseDialogDecision decision,
        final AtomicBoolean coordinatorActive, final AtomicBoolean noActionClaimed) {
        if (!SwingUtilities.isEventDispatchThread()) {
            return CloseDialogInspection.rejected("dirty-save test action must run on EDT");
        }
        if (coordinatorActive == null || !coordinatorActive.get()) {
            return CloseDialogInspection.waiting("close observation was cancelled");
        }
        return applyNoDialogDecision(decision, noActionClaimed, new AtomicBoolean(),
            new AtomicBoolean());
    }

    private static List<CloseDialogSnapshot> visibleCloseDialogs(final Window target) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("dialog discovery must run on EDT");
        }
        final List<CloseDialogSnapshot> snapshots = new ArrayList<>();
        for (final Window window : Window.getWindows()) {
            if (!(window instanceof Dialog dialog)
                || !dialog.isShowing() || !dialog.isDisplayable()) continue;
            final Dialog.ModalityType modality = dialog.getModalityType();
            final boolean modal = modality != null && modality != Dialog.ModalityType.MODELESS;
            final String modalityType = modality == null ? "UNKNOWN" : modality.name();
            final List<JOptionPane> panes = new ArrayList<>();
            collectOptionPanes(dialog, panes);
            if (panes.size() != 1) {
                snapshots.add(new CloseDialogSnapshot(componentIdentity(dialog),
                    objectIdentity(dialog.getOwner()), dialog.getOwner() == target,
                    dialog.isShowing(), dialog.isDisplayable(), modal, modalityType,
                    panes.size(), List.of(), List.of(), -1, () -> { }, () -> false));
                continue;
            }
            final JOptionPane pane = panes.get(0);
            final Object[] options = pane.getOptions();
            final List<String> classes = new ArrayList<>();
            final List<String> labels = new ArrayList<>();
            int initialIndex = -1;
            final Object initial = pane.getInitialValue();
            if (options != null) {
                for (int index = 0; index < options.length; index++) {
                    final Object option = options[index];
                    classes.add(option == null ? "null" : option.getClass().getName());
                    labels.add(option instanceof JButton button && button.getText() != null
                        ? button.getText() : "");
                    if (option == initial) initialIndex = index;
                }
            }
            final JButton noButton = options != null && options.length > 1
                && options[1] instanceof JButton button ? button : null;
            final Runnable dismissNo = noButton == null ? null : () -> {
                if (!isOperable(noButton)) {
                    throw new IllegalStateException(
                        "No option is not enabled/showing/displayable");
                }
                noButton.doClick();
            };
            final BooleanSupplier dismissNoReady = noButton == null
                ? () -> false : () -> isOperable(noButton);
            snapshots.add(new CloseDialogSnapshot(componentIdentity(dialog),
                objectIdentity(dialog.getOwner()), dialog.getOwner() == target,
                dialog.isShowing(), dialog.isDisplayable(), modal, modalityType,
                1, List.copyOf(classes), List.copyOf(labels), initialIndex,
                dismissNo, dismissNoReady));
        }
        return snapshots;
    }

    private static boolean isOperable(final JButton button) {
        return button != null && button.isEnabled() && button.isShowing()
            && button.isDisplayable();
    }

    private static void collectOptionPanes(final Component component,
        final List<JOptionPane> panes) {
        if (component instanceof JOptionPane pane) panes.add(pane);
        if (component instanceof Container container) {
            for (final Component child : container.getComponents()) {
                collectOptionPanes(child, panes);
            }
        }
    }

    static CloseDialogDecision classifyCloseDialogForTest(final String targetWindowIdentity,
        final List<CloseDialogSnapshot> beforeDialogs,
        final List<CloseDialogSnapshot> visibleDialogs) {
        return classifyCloseDialog(targetWindowIdentity, beforeDialogs, visibleDialogs);
    }

    private static CloseDialogDecision classifyCloseDialog(final String targetWindowIdentity,
        final List<CloseDialogSnapshot> beforeDialogs,
        final List<CloseDialogSnapshot> visibleDialogs) {
        if (targetWindowIdentity == null || targetWindowIdentity.isBlank()) {
            return CloseDialogDecision.rejected("bound host window identity is unavailable");
        }
        if (beforeDialogs == null) {
            return CloseDialogDecision.rejected("pre-close dialog snapshots are unavailable");
        }
        final List<CloseDialogSnapshot> current = visibleDialogs == null ? List.of()
            : visibleDialogs;
        final Map<String, CloseDialogSnapshot> beforeByIdentity = new LinkedHashMap<>();
        for (final CloseDialogSnapshot dialog : beforeDialogs) {
            final String invalid = preExistingDialogFailure(targetWindowIdentity, dialog);
            if (invalid != null) {
                return CloseDialogDecision.rejected(
                    "pre-close dialog cannot be safely excluded: " + invalid);
            }
            if (beforeByIdentity.put(dialog.dialogIdentity(), dialog) != null) {
                return CloseDialogDecision.rejected(
                    "duplicate pre-close dialog identity: " + dialog.diagnostic());
            }
        }

        final Map<String, CloseDialogSnapshot> currentByIdentity = new LinkedHashMap<>();
        for (final CloseDialogSnapshot dialog : current) {
            final String invalid = currentDialogFailure(targetWindowIdentity, dialog);
            if (invalid != null) {
                return CloseDialogDecision.rejected(
                    "close dialog snapshot is unavailable or not exact: " + invalid);
            }
            if (currentByIdentity.put(dialog.dialogIdentity(), dialog) != null) {
                return CloseDialogDecision.rejected(
                    "duplicate close dialog identity: " + dialog.diagnostic());
            }
        }

        final List<CloseDialogSnapshot> excluded = new ArrayList<>();
        for (final CloseDialogSnapshot before : beforeDialogs) {
            final CloseDialogSnapshot after = currentByIdentity.get(before.dialogIdentity());
            if (after == null) {
                return CloseDialogDecision.rejected(
                    "pre-existing dialog disappeared or changed identity before close "
                        + "could be classified: " + before.diagnostic());
            }
            final String changed = preExistingDialogFailure(targetWindowIdentity, after);
            if (changed != null) {
                return CloseDialogDecision.rejected(
                    "pre-existing dialog changed before/after and cannot be excluded: "
                        + changed);
            }
            excluded.add(after);
        }

        final List<CloseDialogSnapshot> added = current.stream()
            .filter(Objects::nonNull)
            .filter(dialog -> !beforeByIdentity.containsKey(dialog.dialogIdentity()))
            .toList();
        final String excludedEvidence = excluded.isEmpty() ? ""
            : " excludedPreExisting=" + dialogInventoryDiagnostic(excluded,
                CLOSE_TRACE_REASON_MAX_BYTES);
        if (added.isEmpty()) {
            return CloseDialogDecision.noDialog(
                "no new dialog is associated with WINDOW_CLOSING target=" + targetWindowIdentity
                    + excludedEvidence);
        }
        if (added.size() > 1) {
            return CloseDialogDecision.rejected(
                "multiple new dialogs are associated with WINDOW_CLOSING: " + added.size()
                    + " evidence=" + dialogInventoryDiagnostic(added) + excludedEvidence);
        }
        final CloseDialogSnapshot dialog = added.get(0);
        if (dialog.optionPaneCount() != 1 || dialog.optionClassNames().size() != 3
            || dialog.optionLabels().size() != 3 || dialog.initialOptionIndex() != 0) {
            return CloseDialogDecision.rejected(
                "close-associated JOptionPane options are unknown: " + dialog.diagnostic()
                    + excludedEvidence);
        }
        final List<String> expectedClasses = List.of(
            JButton.class.getName(), JButton.class.getName(), JButton.class.getName());
        if (!expectedClasses.equals(dialog.optionClassNames())
            || !hostOptionLabel(dialog.optionLabels().get(0), 'Y')
            || !hostOptionLabel(dialog.optionLabels().get(1), 'N')
            || !hostOptionLabel(dialog.optionLabels().get(2), 'C')) {
            return CloseDialogDecision.rejected(
                "close-associated JOptionPane does not have the verified Yes/No/Cancel options"
                    + excludedEvidence);
        }
        if (dialog.dismissNo() == null) {
            return CloseDialogDecision.rejected(
                "close-associated JOptionPane has no operable No action" + excludedEvidence);
        }
        final boolean noReady;
        try {
            noReady = dialog.dismissNoReady() != null && dialog.dismissNoReady().getAsBoolean();
        } catch (Throwable failure) {
            return CloseDialogDecision.rejected(
                "close-associated JOptionPane No-action readiness failed: "
                    + stackTrace(failure) + excludedEvidence);
        }
        if (!noReady) {
            return CloseDialogDecision.rejected(
                "close-associated JOptionPane No option is not enabled/showing/displayable"
                    + excludedEvidence);
        }
        return CloseDialogDecision.dismissNo(
            "new dirty-save JOptionPane=" + dialog.diagnostic() + excludedEvidence,
            dialog.dismissNo());
    }

    /**
     * Validates a complete before/after snapshot for a dialog that may be excluded as a
     * pre-existing modeless host tool window.  A partial or changed snapshot is never ignored.
     */
    private static String preExistingDialogFailure(final String targetWindowIdentity,
        final CloseDialogSnapshot dialog) {
        final String currentFailure = currentDialogFailure(targetWindowIdentity, dialog);
        if (currentFailure != null) return currentFailure;
        if (dialog.modal() || !"MODELESS".equals(dialog.modalityType())
            || dialog.optionPaneCount() != 0
            || !dialog.optionClassNames().isEmpty() || !dialog.optionLabels().isEmpty()
            || dialog.initialOptionIndex() != -1) {
            return "pre-existing dialog is not the verified modeless panes=0 shape: "
                + dialog.diagnostic();
        }
        return null;
    }

    /** Validates identity/owner/liveness and rejects unknown modality evidence for every after. */
    private static String currentDialogFailure(final String targetWindowIdentity,
        final CloseDialogSnapshot dialog) {
        if (dialog == null) return "null dialog snapshot";
        if (dialog.dialogIdentity() == null || dialog.dialogIdentity().isBlank()) {
            return "dialog identity is unavailable: " + dialog.diagnostic();
        }
        if (!dialog.ownerMatchesTarget()
            || !targetWindowIdentity.equals(dialog.ownerIdentity())) {
            return "dialog owner identity does not exactly equal bound window: owner="
                + dialog.ownerIdentity() + " bound=" + targetWindowIdentity
                + " evidence=" + dialog.diagnostic();
        }
        if (!dialog.showing() || !dialog.displayable()) {
            return "dialog is not showing/displayable: " + dialog.diagnostic();
        }
        if (dialog.modalityType() == null || dialog.modalityType().isBlank()
            || "UNKNOWN".equals(dialog.modalityType())) {
            return "dialog modality is UNKNOWN: " + dialog.diagnostic();
        }
        final boolean modalByType = !"MODELESS".equals(dialog.modalityType());
        if (modalByType != dialog.modal()) {
            return "dialog modal/modality evidence is inconsistent: " + dialog.diagnostic();
        }
        return null;
    }

    private static boolean hostOptionLabel(final String label, final char suffix) {
        return label != null && label.trim().endsWith("(" + suffix + ")");
    }

    /** Full save→replace→undo→stop→recover pipeline plus optional persist tail. */
    private void runPipeline(final Properties result, final int cycles) throws Exception {
        if (cycles < 1) throw new IllegalArgumentException("cycles must be at least 1");
        result.setProperty("realEditorApplication",
            "default-application launch recorded; OPENED requires the task .psd association");
        final Target target = resolveTarget(result);
        final boolean persistValidation = "1".equals(
            System.getProperty("turboism.validation.externalpsd.persist"));
        final TempTracker tracker = new TempTracker();
        Throwable pipelineFailure = null;
        try {
            if (persistValidation) {
                requireTargetBinding(result, target, "export.baseline.targetBinding.before", 0L);
            }
            final TrackedExport primary = exportTracked(result, target.raw(), tracker, "baseline");
            final Path tempFile = primary.path();
            if (persistValidation) {
                requireTargetBinding(result, target, "export.baseline.targetBinding.after", 0L);
            }
            final byte[] baselineBytes = Files.readAllBytes(tempFile);
            final PsdValidationContent.Fingerprint baselineFingerprint = persistValidation
                ? targetFingerprint(baselineBytes, "pipeline baseline") : null;
            result.setProperty("tempFile.discovered", Boolean.toString(tempFile.getFileName()
                .toString().equals("external-edit.psd")));
            result.setProperty("baseline.bytes", Integer.toString(baselineBytes.length));
            result.setProperty("baseline.sha256", sha256(baselineBytes));
            result.setProperty("baseline.revisionIssued", "true");
            if (persistValidation) {
                recordTargetFingerprint(result, "persist.baselineTargetRgb", baselineFingerprint);
                result.setProperty("persist.baselineTargetRgbSha256", baselineFingerprint.sha256());
                captureDiagnosticObservation(
                    result,
                    "persist.observation.baseline",
                    target,
                    baselineFingerprint,
                    "native-export-result",
                    "",
                    primary.exportStartedAtEpochMillis(),
                    primary.exportCompletedAtEpochMillis(),
                    0L,
                    true
                );
            }

            if (persistValidation) {
                // A second independent native export is required before any external edit. It is
                // not a copy of the first export and is stopped before the save subscription starts.
                requireTargetBinding(result, target,
                    "export.baselineSecond.targetBinding.before", 0L);
                final TrackedExport second = exportTracked(
                    result, target.raw(), tracker, "baselineSecond");
                Throwable secondaryFailure = null;
                try {
                    requireTargetBinding(result, target,
                        "export.baselineSecond.targetBinding.after", 0L);
                    final PsdValidationContent.Fingerprint secondFingerprint = targetFingerprint(
                        Files.readAllBytes(second.path()), "pipeline second baseline");
                    recordTargetFingerprint(
                        result, "persist.baselineSecondTargetRgb", secondFingerprint);
                    result.setProperty("persist.baselineSecondTargetRgbSha256",
                        secondFingerprint.sha256());
                    captureDiagnosticObservation(
                        result,
                        "persist.observation.baselineSecond",
                        target,
                        secondFingerprint,
                        "native-export-result",
                        "",
                        second.exportStartedAtEpochMillis(),
                        second.exportCompletedAtEpochMillis(),
                        0L,
                        true
                    );
                    requireStableBaseline(baselineFingerprint, secondFingerprint);
                } catch (Exception failure) {
                    secondaryFailure = failure;
                    throw failure;
                } catch (Error failure) {
                    secondaryFailure = failure;
                    throw failure;
                } finally {
                    stopPreservingPrimary(tracker, second, result, secondaryFailure);
                }
            }

            final PsdEditFile file = primary.file();
            final Deque<PsdFileRevision> revisions = new ArrayDeque<>();
            final Registration subscription = file.observeSaves(revision -> {
                synchronized (revisions) { revisions.add(revision); revisions.notifyAll(); }
            });
            try {
                assertNoRevision(revisions, 1500,
                    "baseline revision must not be replayed to subscribers");
                result.setProperty("baseline.replayed", "false");

                final PsdFileOperationResult opened = file.openInDefaultApplication()
                    .toCompletableFuture().get(60, TimeUnit.SECONDS);
                result.setProperty("defaultApplication.status", opened.status().name());
                result.setProperty("defaultApplication.diagnostic", opened.diagnostic());

                final Mutation marker = runSaveCycles(
                    result, file, target, tempFile, revisions, tracker, cycles, persistValidation);
                final PsdValidationContent.Fingerprint postBeforeUndo = persistValidation
                    ? exportTargetFingerprint(result, target, tracker, "postBeforeUndo") : null;
                if (persistValidation) {
                    result.setProperty("persist.postBeforeUndoTargetRgbSha256",
                        postBeforeUndo.sha256());
                    requireChangedPost(baselineFingerprint, postBeforeUndo);
                }

                runCorruptedSave(result, file, target, tempFile, revisions);
                runUndoRedo(result, target, tracker, baselineFingerprint, postBeforeUndo);
                assertNoReplayAfterIdle(result, revisions);
                runStopAndRecovery(result, primary, target, tempFile, revisions, tracker);
                recordEnvironment(result);
                if (marker != null) {
                    // Retained as diagnostic coordinates only; the persistence gate uses the
                    // decoded target RGB fingerprint below, never this legacy marker.
                    result.setProperty("persist.markerLayer", Integer.toString(marker.layer()));
                    result.setProperty("persist.markerOffset", Integer.toString(marker.nameOffset()));
                    result.setProperty("persist.markerChar", Integer.toString(marker.letter()));
                }
                if (persistValidation) {
                    runPersistTail(result, target, tracker, baselineFingerprint, postBeforeUndo);
                } else {
                    result.setProperty("documentPersistence",
                        "NOT_TESTED: persist tail not requested");
                }
            } finally {
                subscription.close();
            }
        } catch (Exception failure) {
            pipelineFailure = failure;
            throw failure;
        } catch (Error failure) {
            pipelineFailure = failure;
            throw failure;
        } finally {
            // This is also the leak guard for an export whose path discovery or later assertion
            // failed. A failed stop is recorded and prevents any quarantine PASS claim.
            stopAllPreservingPrimary(tracker, result, pipelineFailure);
        }
        result.setProperty("expected", "full pipeline assertions hold");
        result.setProperty("actual", "full pipeline assertions hold");
    }

    /**
     * Reopens a fixture copy saved by a persist run and proves the externally applied edit
     * survived a real native save → file → reopen roundtrip. The gate is the fresh native
     * export's decoded target-layer RGB fingerprint; legacy full-file/composite hashes remain
     * diagnostics only.
     */
    private void runReopen(final Properties result) throws Exception {
        final Target target = resolveTarget(result);
        final String expectedTarget = System.getProperty(
            "turboism.validation.externalpsd.postEditTargetRgbSha256", "");
        if (!isCanonicalSha256(expectedTarget)) {
            throw new IllegalStateException(
                "reopen requires a canonical lowercase postEditTargetRgbSha256");
        }
        final TempTracker tracker = new TempTracker();
        Throwable reopenFailure = null;
        try {
            requireTargetBinding(result, target, "export.reopen.targetBinding.before", 0L);
            final TrackedExport exported = exportTracked(result, target.raw(), tracker, "reopen");
            requireTargetBinding(result, target, "export.reopen.targetBinding.after", 0L);
            final byte[] bytes = Files.readAllBytes(exported.path());
            final PsdValidationContent.Fingerprint actual = targetFingerprint(
                bytes, "reopen target layer");
            result.setProperty("reopen.layerCount",
                Integer.toString(layerNameRanges(bytes).size()));
            result.setProperty("reopen.bytes", Integer.toString(bytes.length));
            result.setProperty("reopen.sha256", sha256(bytes));
            result.setProperty("reopen.imageSha256", imageDataSha256(bytes));
            result.setProperty("reopen.expectedTargetRgbSha256", expectedTarget);
            result.setProperty("reopen.targetRgbSha256", actual.sha256());
            recordTargetFingerprint(result, "reopen.targetRgb", actual);
            final String expectedFile = System.getProperty(
                "turboism.validation.externalpsd.postEditSha256", "");
            result.setProperty("reopen.fileShaMatched",
                Boolean.toString(!expectedFile.isBlank()
                    && expectedFile.equals(result.getProperty("reopen.sha256"))));
            requireReopenTarget(expectedTarget, actual);
        } catch (Exception failure) {
            reopenFailure = failure;
            throw failure;
        } catch (Error failure) {
            reopenFailure = failure;
            throw failure;
        } finally {
            stopAllPreservingPrimary(tracker, result, reopenFailure);
        }
        result.setProperty("expected", "reopened fixture retains the external-edit content");
        result.setProperty("actual", "decoded target RGB verified in a fresh native export");
    }

    /**
     * Real GUI path: right-click an object row until a popup exposes the contributed item,
     * click it, then verify the product plugin's own session opens the runtime-issued temp
     * file and auto-imports a written save.
     */
    private void runGui(final Properties result) throws Exception {
        final Target target = resolveTarget(result);
        result.setProperty("gui.menuLabel", System.getProperty(
            "turboism.validation.externalpsd.menuLabel", "Edit PSD Externally"));
        final GuiTargetState before = observeGuiTarget(target);
        recordGuiTargetState(result, "before", before);
        result.setProperty("gui.generationBefore", Long.toString(before.generation()));
        recordGuiTargetState(result, "after",
            GuiTargetState.unobserved("auto-import not attempted"));
        if (!before.observed()) {
            throw new Blocked("target-specific GUI replacement observation is available",
                "gui.before unavailable: " + before.diagnostic());
        }
        if (before.binding().isBlank() || before.generation() < 0 || before.raw().isBlank()
            || !target.raw().value().equals(before.raw())) {
            throw new Blocked("GUI target remains bound to the resolved document and raw image",
                "gui.before target identity is unavailable or stale: " + before);
        }
        if (before.rawReplaced()) {
            throw new Blocked("fixture target starts with isReplaced=false",
                "gui.before.rawReplaced=true; no available new replacement observation");
        }
        final TempCandidateSnapshot sessionCandidatesBefore = snapshotTempCandidates();

        final Set<String> labels = new LinkedHashSet<>(List.of(result.getProperty("gui.menuLabel"),
            "Edit PSD Externally",
            "外部编辑 PSD", "外部編輯 PSD",
            "外部でPSDを編集", "외부에서 PSD 편집"));
        final GuiClick click = clickContributedItem(labels, 64, result, target);
        if (!click.clicked()) {
            throw new Blocked("context menu with the contributed item was reachable",
                click.diagnostic());
        }
        result.setProperty("gui.menuPresent", "true");
        result.setProperty("gui.itemClicked", "true");

        final StablePsdSnapshot sessionSnapshot;
        try {
            sessionSnapshot = awaitSessionTempFile(sessionCandidatesBefore, 90, result);
        } catch (SessionFileReadinessException failure) {
            throw new Blocked("a unique, complete and stable new session PSD",
                failure.getMessage());
        }
        result.setProperty("gui.sessionFile",
            sessionSnapshot.path().getParent().getFileName().toString());
        final StablePsdSnapshot writeSnapshot;
        try {
            writeSnapshot = confirmSessionFileForWrite(sessionSnapshot, result);
        } catch (SessionFileReadinessException failure) {
            throw new Blocked("the validated session PSD remains unchanged before mutation",
                failure.getMessage());
        }
        final byte[] mutated = mutateLayerName(writeSnapshot.bytes(), 900)
            .orElseThrow(() -> new IllegalStateException("session PSD has no mutable layer name"));
        writeSessionFile(writeSnapshot, mutated);

        final AutoImportObservation observation = awaitAutoImport(target, before, 90);
        result.setProperty("gui.autoImportApplied", Boolean.toString(observation.applied()));
        recordGuiTargetState(result, "after", observation.after());
        result.setProperty("gui.generationAfter", Long.toString(observation.after().generation()));
        if (!observation.applied()) {
            if (observation.stale()) {
                throw new Blocked("GUI target binding, generation and raw image remain stable",
                    observation.diagnostic());
            }
            throw new IllegalStateException(
                "plugin session did not auto-import the written save: "
                    + observation.diagnostic());
        }
        result.setProperty("expected", "context-menu item starts a session and auto-imports saves");
        result.setProperty("actual", "menu click → session file → written save auto-applied");
    }

    /** Blocked signal: the evidence condition could not be reached, distinct from a failure. */
    private static final class Blocked extends IllegalStateException {
        final String expected;
        Blocked(final String expected, final String actual) {
            super(actual);
            this.expected = expected;
        }
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
        private final Path stateDir;
        private final String runId;
        private final long armedAtNanos;
        private final BooleanSupplier stopped;
        private final BooleanSupplier triggerPresent;
        private final AtomicReference<String> waitingStage;
        private final GuiDiagnosticClock clock;
        private final GuiDiagnosticSleeper sleeper;
        private final GuiDiagnosticSampler sampler;
        private final Consumer<String> reporter;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean triggerMarked = new AtomicBoolean();
        private final AtomicBoolean triggerObservationUnavailable = new AtomicBoolean();
        private final AtomicInteger sampleAttempts = new AtomicInteger();
        private final AtomicInteger writtenSamples = new AtomicInteger();
        private final AtomicInteger unavailableSamples = new AtomicInteger();
        private final Thread thread;

        private GuiWaitDiagnostics(final Path stateDir, final String runId,
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

        private void start() { thread.start(); }

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

        private void run() {
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
                    + safeDiagnostic(runId) + " reason=" + failure);
            }
        }

        private boolean waitUntilDue(final long delayMillis) throws InterruptedException {
            final long delayNanos = TimeUnit.MILLISECONDS.toNanos(delayMillis);
            while (elapsedNanos() < delayNanos) {
                if (!maySample()) return false;
                final long remainingNanos = delayNanos - elapsedNanos();
                sleeper.sleep(Math.min(1000L, nanosToMillisCeiling(remainingNanos)));
            }
            return maySample();
        }

        private void sample(final int ordinal) {
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

        private boolean maySample() {
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

        private long elapsedNanos() {
            return Math.max(0L, clock.nanoTime() - armedAtNanos);
        }

        private static long nanosToMillisCeiling(final long nanos) {
            if (nanos <= 0) return 1L;
            final long millis = TimeUnit.NANOSECONDS.toMillis(nanos);
            return millis == 0 || nanos % 1_000_000L != 0 ? millis + 1L : millis;
        }

        private void writeSample(final int ordinal, final String body) {
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
                content.append("runId=").append(quoted(runId)).append('\n');
                content.append("sampleOrdinal=").append(ordinal).append('\n');
                content.append("sampleUtc=").append(clock.utcNow()).append('\n');
                content.append("elapsedMillis=").append(TimeUnit.NANOSECONDS.toMillis(
                    elapsedNanos())).append('\n');
                content.append("waitingStage=").append(safeDiagnostic(
                    waitingStage.get())).append('\n');
                content.append("samplingThreadName=").append(quoted(
                    Thread.currentThread().getName())).append('\n');
                content.append(body == null ? unavailableBody(
                    "diagnostic", new IllegalStateException("null output")) : body);
                Files.write(output, boundedUtf8(content.toString()),
                    java.nio.file.StandardOpenOption.CREATE_NEW,
                    java.nio.file.StandardOpenOption.WRITE);
                writtenSamples.incrementAndGet();
                report("EXTERNAL_PSD_EDIT_GUI_THREAD_DIAGNOSTIC written runId="
                    + safeDiagnostic(runId) + " sample=" + ordinal + " path=" + output);
            } catch (Throwable failure) {
                report("EXTERNAL_PSD_EDIT_GUI_THREAD_DIAGNOSTIC unavailable runId="
                    + safeDiagnostic(runId) + " sample=" + ordinal + " reason=" + failure);
            }
        }

        private String unavailableBody(final String operation, final Throwable failure) {
            return "diagnostic unavailable operation=" + operation + " error=" + failure
                + "\n" + stackTrace(failure);
        }

        private void reportOnce(final String operation, final Throwable failure) {
            if (triggerObservationUnavailable.compareAndSet(false, true)) {
                report("EXTERNAL_PSD_EDIT_GUI_THREAD_DIAGNOSTIC unavailable runId="
                    + safeDiagnostic(runId) + " operation=" + operation + " reason=" + failure);
            }
        }

        private void report(final String message) {
            try {
                reporter.accept(message);
            } catch (Throwable ignored) {
                // Diagnostic reporting cannot be allowed to affect the readiness wait.
            }
        }
    }

    private static GuiWaitDiagnostics startGuiWaitDiagnostics(final Path stateDir,
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

    private static Path guiDiagnosticStateDirectory(final Path stateDir) {
        return guiReadyTriggerPath(stateDir).getParent();
    }

    private static byte[] boundedUtf8(final String value) {
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

    private static String captureGuiThreadDump() {
        final ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (bean == null) throw new IllegalStateException("ThreadMXBean is unavailable");
        final Thread current = Thread.currentThread();
        final ThreadInfo[] infos = bean.dumpAllThreads(true, true);
        return renderGuiThreadDump(infos, current.getName(), current.getId());
    }

    private static String renderGuiThreadDump(final ThreadInfo[] infos,
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
            .append(" samplingThreadName=").append(quoted(samplingThreadName)).append('\n');
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

    private static int guiThreadPriority(final ThreadInfo info,
        final String samplingThreadName, final long samplingThreadId) {
        if (info.getThreadId() == samplingThreadId
            || Objects.equals(info.getThreadName(), samplingThreadName)) return 0;
        final String name = info.getThreadName();
        if (name != null && name.contains("turboism-bootstrap")) return 1;
        if (name != null && name.startsWith("AWT-EventQueue")) return 2;
        return 3;
    }

    private static void appendThreadInfo(final StringBuilder text, final int index,
        final ThreadInfo info) {
        text.append("thread[").append(index).append("] id=").append(info.getThreadId())
            .append(" name=").append(quoted(info.getThreadName()))
            .append(" state=").append(info.getThreadState())
            .append(" suspended=").append(info.isSuspended())
            .append(" inNative=").append(info.isInNative())
            .append(" blockedCount=").append(info.getBlockedCount())
            .append(" blockedTimeMillis=").append(info.getBlockedTime())
            .append(" waitedCount=").append(info.getWaitedCount())
            .append(" waitedTimeMillis=").append(info.getWaitedTime())
            .append(" lockName=").append(quoted(info.getLockName()))
            .append(" lockOwnerId=").append(info.getLockOwnerId())
            .append(" lockOwnerName=").append(quoted(info.getLockOwnerName()))
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
     * Waits for the exact-host runner's GUI readiness handshake before any GUI target state is
     * sampled.  The state directory comes only from the authenticated plugin context; no system
     * property can redirect this wait to another task or another window.
     */
    private void awaitGuiReadyTrigger(final Properties result) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new Blocked("task-local GUI readiness trigger is awaited off the EDT",
                "GUI readiness trigger wait was requested on the EDT");
        }
        final String runId = result.getProperty("runId", "");
        final GuiTriggerArm arm = prepareGuiTrigger(context.paths().stateDir());
        if (!arm.armed()) {
            throw new Blocked("runner-created task-local GUI readiness trigger",
                arm.diagnostic());
        }
        result.setProperty("gui.readinessTrigger", "armed");
        result.setProperty("gui.readinessTriggerPath", arm.path().toString());
        // The wrapper's readiness set includes this marker.  It must be emitted only after the
        // state directory and trigger have been checked, so a late worker cannot mistake the
        // runner's newly-created trigger for stale evidence.
        context.logger().info(GUI_TRIGGER_ARMED_MARKER + " path=" + arm.path());
        final long armedAtNanos = System.nanoTime();
        final AtomicReference<String> waitingStage = new AtomicReference<>(
            GUI_WAIT_DIAGNOSTIC_STAGE);
        final GuiWaitDiagnostics diagnostics;
        try {
            diagnostics = startGuiWaitDiagnostics(
                arm.path().getParent(), runId, armedAtNanos, () -> stopped,
                () -> Files.exists(arm.path(), LinkOption.NOFOLLOW_LINKS), waitingStage,
                SYSTEM_GUI_DIAGNOSTIC_CLOCK, Thread::sleep,
                () -> captureGuiThreadDump(), message -> context.logger().warn(message));
            guiWaitDiagnostics = diagnostics;
            if (stopped) diagnostics.close("disabled");
        } catch (Throwable failure) {
            // Diagnostics are strictly observational.  A restricted management interface or a
            // daemon-creation failure must not change the readiness wait or manufacture a pass.
            context.logger().warn("EXTERNAL_PSD_EDIT_GUI_THREAD_DIAGNOSTIC unavailable: "
                + failure);
        }
        final GuiWaitDiagnostics activeDiagnostics = guiWaitDiagnostics;
        try {
            final GuiTriggerWait wait = waitForArmedGuiTrigger(
                arm, GUI_READY_TRIGGER_TIMEOUT_MILLIS, () -> stopped, Thread::sleep);
            if (!wait.ready()) {
                waitingStage.set("armed-wait-ended:" + wait.diagnostic());
                throw new Blocked("runner-created task-local GUI readiness trigger",
                    wait.diagnostic());
            }
            waitingStage.set("trigger-received");
            if (activeDiagnostics != null) activeDiagnostics.markTriggerReceived();
            result.setProperty("gui.readinessTrigger", "received");
            result.setProperty("gui.readinessTriggerPath", wait.path().toString());
            context.logger().info("EXTERNAL_PSD_EDIT_GUI_READY_TRIGGER_RECEIVED path="
                + wait.path());
        } finally {
            if (activeDiagnostics != null) {
                activeDiagnostics.close("wait-ended");
                if (guiWaitDiagnostics == activeDiagnostics) guiWaitDiagnostics = null;
                result.setProperty("gui.waitDiagnostics.samples",
                    Integer.toString(activeDiagnostics.writtenSamples()));
                result.setProperty("gui.waitDiagnostics.unavailable",
                    Integer.toString(activeDiagnostics.unavailableSamples()));
            }
        }
    }

    /**
     * Test seam for the task-local trigger protocol.  It deliberately contains no Swing access;
     * production calls it from the daemon worker and tests can inject a short sleeper without
     * changing the production timeout or polling policy.
     */
    static GuiTriggerWait waitForGuiReadyTriggerForTest(final Path stateDir,
        final long timeoutMillis, final BooleanSupplier stopped,
        final TriggerSleeper sleeper) {
        final GuiTriggerArm arm = prepareGuiTrigger(stateDir);
        return arm.armed()
            ? waitForArmedGuiTrigger(arm, timeoutMillis, stopped, sleeper)
            : GuiTriggerWait.rejected(arm.diagnostic());
    }

    static Path guiReadyTriggerPathForTest(final Path stateDir) {
        return guiReadyTriggerPath(stateDir);
    }

    static GuiTriggerArm prepareGuiTriggerForTest(final Path stateDir) {
        return prepareGuiTrigger(stateDir);
    }

    static GuiTriggerWait waitForArmedGuiTriggerForTest(final GuiTriggerArm arm,
        final long timeoutMillis, final BooleanSupplier stopped,
        final TriggerSleeper sleeper) {
        return waitForArmedGuiTrigger(arm, timeoutMillis, stopped, sleeper);
    }

    private static GuiTriggerArm prepareGuiTrigger(final Path stateDir) {
        final Path flag;
        try {
            flag = guiReadyTriggerPath(stateDir);
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

    private static GuiTriggerWait waitForArmedGuiTrigger(final GuiTriggerArm arm,
        final long timeoutMillis, final BooleanSupplier stopped,
        final TriggerSleeper sleeper) {
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
                final Path current = guiReadyTriggerPath(flag.getParent());
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

    private static Path guiReadyTriggerPath(final Path stateDir) {
        if (stateDir == null) throw new IllegalArgumentException(
            "GUI readiness state directory is null");
        final Path normalized = stateDir.toAbsolutePath().normalize();
        if (normalized.equals(Path.of("/"))) throw new IllegalArgumentException(
            "GUI readiness state directory must not be filesystem root");
        if (Files.isSymbolicLink(normalized)
            || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(
                "GUI readiness state directory is not a real directory: " + normalized);
        }
        requireNoSymlinkPath(normalized, "GUI readiness state directory");
        return normalized.resolve(GUI_READY_TRIGGER_NAME);
    }

    private void awaitReady() throws Exception {
        final long deadline = System.nanoTime() + java.time.Duration.ofSeconds(180).toNanos();
        boolean ready = false;
        while (!stopped && System.nanoTime() < deadline) {
            final AtomicReference<Boolean> active = new AtomicReference<>(false);
            SwingUtilities.invokeAndWait(() -> {
                if (stopped) return;
                try {
                    active.set(context.cubism().isHostPresent()
                        && context.cubism().activeDocument().isPresent()
                        && context.cubism().activeModel().isPresent());
                } catch (IllegalStateException unavailable) {
                    // Startup is bounded; projection errors after readiness are not retried.
                }
            });
            if (active.get()) { ready = true; break; }
            Thread.sleep(1000);
        }
        if (stopped) throw new IllegalStateException("Probe stopped before readiness");
        if (!ready) throw new IllegalStateException("Active fixture/model readiness timed out");
    }

    private Target resolveTarget(final Properties result) throws Exception {
        final AtomicReference<Target> found = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            if (stopped) return;
            try {
                final var document = context.cubism().activeDocument().orElseThrow();
                final String expectedFixture = System.getProperty("turboism.validation.fixtureName", "");
                final String relativePath = document.relativePath();
                final String actualFixture = relativePath.substring(relativePath.lastIndexOf('/') + 1);
                result.setProperty("fixture.expected", expectedFixture);
                result.setProperty("fixture.actual", actualFixture);
                if (expectedFixture.isBlank() || !expectedFixture.equals(actualFixture)) {
                    throw new IllegalStateException("Active document is not the task fixture copy");
                }
                final var model = context.cubism().model().active();
                result.setProperty("documentId", document.documentId());
                result.setProperty("modelId", model.id().value());
                final TextureRelationsSnapshot relations = model.textures().relations();
                if (!relations.isAvailable()) throw new IllegalStateException("Relations unavailable");
                result.setProperty("binding", relations.binding());
                result.setProperty("generation", Long.toString(relations.generation()));
                result.setProperty("rawCount", Integer.toString(relations.rawImages().size()));
                result.setProperty("modelImageCount", Integer.toString(relations.modelImages().size()));
                result.setProperty("artMeshCount", Integer.toString(relations.artMeshInputs().size()));
                final Target picked = pickTarget(relations).orElseThrow(() ->
                    new IllegalStateException("No ArtMesh resolves to a current raw image"));
                found.set(new Target(
                    picked.artMesh(),
                    picked.modelImage(),
                    picked.raw(),
                    picked.rawReplaced(),
                    new TargetIdentity(
                        document.documentId(),
                        model.id().value(),
                        relations.binding(),
                        picked.modelImage().value(),
                        picked.raw().value())
                ));
            } catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new IllegalStateException("Target resolution failed", failure.get());
        final Target target = found.get();
        result.setProperty("relation.artMesh", target.artMesh().id().value());
        result.setProperty("relation.modelImage", target.modelImage().value());
        result.setProperty("relation.raw", target.raw().value());
        result.setProperty("relation.rawReplaced",
            Boolean.toString(target.rawReplaced));
        final long shared = relationsOf(target).map(relations -> relations.artMeshInputs().stream()
            .filter(mesh -> mesh.currentInputIndex().isPresent())
            .map(mesh -> mesh.inputs().get(mesh.currentInputIndex().getAsInt()))
            .filter(input -> input.kind() == TextureInputBinding.Kind.MODEL_IMAGE
                && input.isResolved() && input.modelImageId().isPresent())
            .flatMap(input -> relations.modelImage(input.modelImageId().orElseThrow()).stream())
            .flatMap(image -> image.currentRawImageId().stream())
            .filter(raw -> raw.value().equals(target.raw().value()))
            .count()).orElse(0L);
        result.setProperty("relation.sharedRawArtMeshes", Long.toString(shared));
        bindExactTaskWindow(result, target);
        return target;
    }

    /**
     * Binds shutdown to the task document's exact host window for every phase. The SDK document
     * and fixture checks above establish which document is active; this additional reviewed-table
     * check proves that the same active window contains the exact ArtMesh domain ID. The Window
     * object is retained for the whole run, including SAVE_AS, so teardown never re-resolves by a
     * stale filename, title, or arbitrary visible-window fallback.
     */
    private void bindExactTaskWindow(final Properties result, final Target target)
        throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("task window binding must run off the EDT");
        }
        if (target == null || target.artMesh() == null || target.artMesh().id() == null) {
            throw new Blocked("the task fixture ArtMesh can be bound to one host window",
                "task target identity is unavailable for window binding");
        }
        final String expectedDomainId = target.artMesh().id().value();
        result.setProperty("exit.targetWindow.bindingStatus", "WAITING");
        result.setProperty("exit.targetWindow.documentId", result.getProperty("documentId", ""));
        result.setProperty("exit.targetWindow.modelId", result.getProperty("modelId", ""));
        result.setProperty("exit.targetWindow.artMesh", expectedDomainId);
        final long deadline = System.nanoTime()
            + TimeUnit.MILLISECONDS.toNanos(TASK_WINDOW_BIND_TIMEOUT_MILLIS);
        int attempts = 0;
        String lastDiagnostic = "no active reviewed host window has been observed";
        while (!stopped && System.nanoTime() < deadline) {
            attempts++;
            final ReviewedTables reviewed = visibleReviewedTables();
            result.setProperty("exit.targetWindow.bindingAttempt", Integer.toString(attempts));
            result.setProperty("exit.targetWindow.candidate", reviewed.windowIdentity());
            result.setProperty("exit.targetWindow.candidateDiagnostic", reviewed.diagnostic());
            if (!reviewed.proven() || reviewed.window() == null) {
                lastDiagnostic = reviewed.diagnostic();
            } else if (reviewed.tables().isEmpty()) {
                lastDiagnostic = "active window has no reviewed tree-table yet: "
                    + reviewed.windowIdentity();
            } else {
                final ExactCapture capture = captureExactRows(reviewed.tables(), target,
                    reviewed.windowIdentity());
                result.setProperty("exit.targetWindow.targetStatus",
                    capture.targetStatus().name());
                result.setProperty("exit.targetWindow.tableDiagnostics",
                    String.join("\n---\n", capture.tableDiagnostics()));
                if (!capture.hostAvailable()) {
                    result.setProperty("exit.targetWindow.bindingStatus", "BLOCKED");
                    throw new Blocked("the task fixture ArtMesh can be bound to one host window",
                        "exact host access unavailable for task window binding: "
                            + capture.diagnostic());
                }
                if (capture.targetStatus() == ExactTargetSelectionStatus.AMBIGUOUS
                    || capture.targetStatus() == ExactTargetSelectionStatus.REJECTED) {
                    result.setProperty("exit.targetWindow.bindingStatus", "BLOCKED");
                    throw new Blocked("the task fixture ArtMesh can be bound to one host window",
                        "exact task window target rejected: " + capture.diagnostic());
                }
                if (!capture.rows().isEmpty()) {
                    final TaskWindowBindingDecision binding = validateTaskWindowBinding(
                        result.getProperty("fixture.expected", ""),
                        result.getProperty("fixture.actual", ""),
                        result.getProperty("documentId", ""), result.getProperty("modelId", ""),
                        reviewed.windowIdentity(), expectedDomainId,
                        capture.rows().stream().map(ExactDispatchCapture::captured).toList());
                    if (!binding.bound()) {
                        result.setProperty("exit.targetWindow.bindingStatus", "BLOCKED");
                        throw new Blocked(
                            "the task fixture ArtMesh can be bound to one host window",
                            binding.diagnostic());
                    }
                    guiBoundWindow = reviewed.window();
                    result.setProperty("exit.targetWindow.bindingStatus", "BOUND");
                    result.setProperty("exit.targetWindow.window",
                        reviewed.windowIdentity());
                    result.setProperty("exit.targetWindow.windowObject",
                        objectIdentity(guiBoundWindow));
                    result.setProperty("exit.targetWindow.entrances",
                        Integer.toString(capture.rows().size()));
                    result.setProperty("exit.targetWindow.bindingDiagnostic",
                        "active task fixture window contains exact ArtMesh entrances: "
                            + capture.diagnostic());
                    return;
                }
                lastDiagnostic = capture.diagnostic();
            }
            final long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            Thread.sleep(Math.min(TASK_WINDOW_BIND_POLL_MILLIS,
                Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining))));
        }
        result.setProperty("exit.targetWindow.bindingStatus", stopped ? "STOPPED" : "BLOCKED");
        final String terminal = stopped ? "task window binding stopped" :
            "task window binding timed out after " + TASK_WINDOW_BIND_TIMEOUT_MILLIS + "ms";
        throw new Blocked("the task fixture ArtMesh can be bound to one host window",
            terminal + "; attempts=" + attempts + " last=" + lastDiagnostic);
    }

    /**
     * Validates the evidence that licenses a task-window close. The production caller supplies
     * rows captured from the exact reviewed table in the active window; this seam also keeps the
     * fixture/document/domain constraints executable without manufacturing an AWT host window.
     */
    private static TaskWindowBindingDecision validateTaskWindowBinding(
        final String expectedFixture, final String actualFixture, final String documentId,
        final String modelId, final String windowIdentity, final String expectedDomainId,
        final List<ExactCapturedRow> rows) {
        if (expectedFixture == null || expectedFixture.isBlank()
            || actualFixture == null || actualFixture.isBlank()
            || !expectedFixture.equals(actualFixture)) {
            return TaskWindowBindingDecision.rejected(
                "task fixture identity is not proven for the host window");
        }
        if (documentId == null || documentId.isBlank()) {
            return TaskWindowBindingDecision.rejected(
                "task document identity is unavailable for the host window");
        }
        if (modelId == null || modelId.isBlank()) {
            return TaskWindowBindingDecision.rejected(
                "task model identity is unavailable for the host window");
        }
        if (windowIdentity == null || windowIdentity.isBlank()) {
            return TaskWindowBindingDecision.rejected(
                "host window identity is unavailable for the task document");
        }
        if (expectedDomainId == null || expectedDomainId.isBlank()) {
            return TaskWindowBindingDecision.rejected(
                "task ArtMesh domain identity is unavailable for the host window");
        }
        final ExactTargetSelection selection = selectExactTargetRows(rows, windowIdentity,
            expectedDomainId);
        if (!selection.available() || selection.rows().isEmpty()) {
            return TaskWindowBindingDecision.rejected(
                "exact task window ArtMesh evidence is unavailable: " + selection.reason());
        }
        return TaskWindowBindingDecision.bound(
            "fixture=" + actualFixture + " document=" + documentId + " model=" + modelId
                + " window=" + windowIdentity + " domain=" + expectedDomainId
                + " entrances=" + selection.rows().size());
    }

    static TaskWindowBindingDecision validateTaskWindowBindingForTest(
        final String expectedFixture, final String actualFixture, final String documentId,
        final String modelId, final String windowIdentity, final String expectedDomainId,
        final List<ExactCapturedRow> rows) {
        return validateTaskWindowBinding(expectedFixture, actualFixture, documentId, modelId,
            windowIdentity, expectedDomainId, rows);
    }

    private Optional<TextureRelationsSnapshot> relationsOf(final Target target) {
        final AtomicReference<TextureRelationsSnapshot> snapshot = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> snapshot.set(
                context.cubism().model().active().textures().relations()));
        } catch (Exception unavailable) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshot.get());
    }

    private Optional<Target> pickTarget(final TextureRelationsSnapshot relations) {
        for (final ArtMeshTextureInputs mesh : relations.artMeshInputs()) {
            if (mesh.currentInputIndex().isEmpty()) continue;
            final TextureInputBinding input = mesh.inputs().get(mesh.currentInputIndex().getAsInt());
            if (input.kind() != TextureInputBinding.Kind.MODEL_IMAGE
                || !input.isResolved() || input.modelImageId().isEmpty()) continue;
            final var relation = relations.modelImage(input.modelImageId().orElseThrow());
            if (relation.isEmpty() || relation.orElseThrow().currentRawImageId().isEmpty()) continue;
            final RawImageId raw = relation.orElseThrow().currentRawImageId().orElseThrow();
            final boolean replaced = relations.rawImage(raw)
                .map(dev.turboism.sdk.cubism.model.RawImageDetails::isReplaced).orElse(false);
            return Optional.of(new Target(mesh, input.modelImageId().orElseThrow(), raw, replaced,
                TargetIdentity.unavailable()));
        }
        return Optional.empty();
    }

    private static final String TEMP_DIRECTORY_PREFIX = "turboism-psd-";
    private static final String TEMP_FILE_NAME = "external-edit.psd";

    /**
     * Narrow test seam for the platform file-object token.  The production implementation is
     * {@link BasicFileAttributes#fileKey()}; tests may return {@code null} or a controlled token
     * to exercise the portable path/content protocol without introducing a native file-ID API.
     */
    @FunctionalInterface
    interface FileKeySupplier {
        Object fileKey(BasicFileAttributes attributes);
    }

    private static final FileKeySupplier PLATFORM_FILE_KEY_SUPPLIER =
        BasicFileAttributes::fileKey;

    private Path tempRoot() throws Exception {
        final String configured = System.getProperty("java.io.tmpdir", "");
        if (configured.isBlank()) throw new IllegalStateException("java.io.tmpdir is blank");
        final Path configuredPath = Path.of(configured);
        if (!configuredPath.isAbsolute()) {
            throw new IllegalStateException("java.io.tmpdir must be absolute");
        }
        requireNoSymlinkPath(configuredPath, "java.io.tmpdir");
        if (Files.isSymbolicLink(configuredPath)
            || !Files.isDirectory(configuredPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("java.io.tmpdir is not a real directory");
        }
        final Path root = configuredPath.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (root.equals(Path.of("/"))) throw new IllegalStateException("java.io.tmpdir is root");
        return root;
    }

    private Path tempMarker() throws Exception {
        final Path marker = tempRoot().resolve(
            ".external-psd-probe-" + ProcessHandle.current().pid());
        return Files.writeString(marker, Long.toString(System.nanoTime()));
    }

    /** Captures the direct, task-scoped runtime allocation set before an export. */
    private TempCandidateSnapshot snapshotTempCandidates() throws Exception {
        return snapshotTempCandidates(tempRoot());
    }

    /** Captures direct candidate directories without following any path component. */
    private static TempCandidateSnapshot snapshotTempCandidates(final Path temporaryRoot)
        throws Exception {
        final Path tmp = requireOwnedDirectory(temporaryRoot, "temporary root");
        final Set<Path> directories = new LinkedHashSet<>();
        try (Stream<Path> stream = Files.list(tmp)) {
            for (final Path candidate : stream.toList()) {
                final String name = candidate.getFileName().toString();
                if (!name.startsWith(TEMP_DIRECTORY_PREFIX)) continue;
                if (Files.isSymbolicLink(candidate)) {
                    throw new IllegalStateException(
                        "temporary PSD candidate is a symlink: " + candidate);
                }
                if (!Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalStateException(
                        "temporary PSD candidate is not a real directory: " + candidate);
                }
                requireNoSymlinkPath(candidate, "temporary PSD candidate");
                directories.add(candidate.toAbsolutePath().normalize());
            }
        }
        return new TempCandidateSnapshot(tmp, directories);
    }

    private Path locateNewTempFile(final TempCandidateSnapshot before, final Path marker,
        final Properties result, final String label) throws Exception {
        final TempCandidateSnapshot after = snapshotTempCandidates();
        result.setProperty("export." + label + ".candidateBefore",
            Integer.toString(before.directories().size()));
        result.setProperty("export." + label + ".candidateAfter",
            Integer.toString(after.directories().size()));
        final Path directory = uniqueNewTempCandidate(before.directories(), after.directories());
        if (!directory.getParent().equals(after.root())) {
            throw new IllegalStateException("temporary PSD candidate escaped its temp root");
        }
        final boolean markerAuxiliary = modified(directory) >= modified(marker);
        result.setProperty("export." + label + ".markerAfterCandidate",
            Boolean.toString(markerAuxiliary));
        result.setProperty("export." + label + ".directory", directory.toString());
        return requireTrackedTempFile(directory, after.root());
    }

    /** Resolves an export only when exactly one allocation directory was added. */
    static Path uniqueNewTempCandidate(final Set<Path> before, final Set<Path> after) {
        if (before == null || after == null) {
            throw new IllegalArgumentException("temporary candidate sets are required");
        }
        final Set<Path> added = new LinkedHashSet<>(after);
        added.removeAll(before);
        if (added.size() != 1) {
            throw new IllegalStateException(
                "expected exactly one new temporary PSD candidate, found " + added.size());
        }
        return added.iterator().next();
    }

    /** Validates the one runtime PSD file inside a candidate directory without following links. */
    static Path requireTrackedTempFile(final Path directory, final Path expectedTempRoot)
        throws Exception {
        if (directory == null || expectedTempRoot == null) {
            throw new IllegalArgumentException("temporary candidate paths are required");
        }
        final Path root = requireOwnedDirectory(expectedTempRoot, "temporary root");
        final Path candidate = directory.toAbsolutePath().normalize();
        if (!candidate.getParent().equals(root)
            || !candidate.getFileName().toString().startsWith(TEMP_DIRECTORY_PREFIX)) {
            throw new IllegalStateException("temporary PSD candidate escaped its temp root: "
                + candidate);
        }
        if (Files.isSymbolicLink(candidate)) {
            throw new IllegalStateException("temporary PSD candidate is a symlink: " + candidate);
        }
        if (!Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("temporary PSD candidate is not a real directory: "
                + candidate);
        }
        requireNoSymlinkPath(candidate, "temporary PSD candidate");
        final Path file = candidate.resolve(TEMP_FILE_NAME);
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("temporary PSD candidate has no regular PSD file: " + file);
        }
        requireNoSymlinkPath(file, "temporary PSD file");
        return file.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }

    private static void requireNoSymlinkPath(final Path path, final String label) {
        Path current = path.toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isSymbolicLink(current)) {
                throw new IllegalStateException(label + " contains a symlink: " + current);
            }
            current = current.getParent();
        }
    }

    private static long modified(final Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); }
        catch (Exception error) { return 0; }
    }

    private PsdExportResult export(final Properties result, final RawImageId raw) throws Exception {
        return export(result, raw, 0L);
    }

    private PsdExportResult export(final Properties result, final RawImageId raw,
        final long budgetMillis) throws Exception {
        final long started = System.nanoTime();
        requireBudgetAvailable(started, budgetMillis, "native export invocation");
        final AtomicReference<CompletionStage<PsdExportResult>> stage = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                if (stopped) throw new IllegalStateException("Probe stopped");
                stage.set(context.cubism().model().active().textures().exportRawImagePsd(raw));
            } catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new IllegalStateException("Export invocation failed", failure.get());
        final PsdExportResult exported = stage.get().toCompletableFuture().get(120, TimeUnit.SECONDS);
        result.setProperty("export.status", exported.status().name());
        result.setProperty("export.diagnostic", exported.diagnostic());
        if (exported.status() != PsdExportResult.Status.EXPORTED
            || exported.file().isEmpty() || exported.initialRevision().isEmpty()) {
            throw new IllegalStateException("Native export did not issue an edit handle: "
                + exported.status() + " " + exported.diagnostic());
        }
        return exported;
    }

    private TrackedExport exportTracked(final Properties result, final RawImageId raw,
        final TempTracker tracker, final String label) throws Exception {
        return exportTracked(result, raw, tracker, label, 0L);
    }

    private TrackedExport exportTracked(final Properties result, final RawImageId raw,
        final TempTracker tracker, final String label, final long budgetMillis) throws Exception {
        final TempCandidateSnapshot before = snapshotTempCandidates();
        final Path marker = tempMarker();
        final long exportStartedAtEpochMillis = System.currentTimeMillis();
        final PsdExportResult exported = export(result, raw, budgetMillis);
        final TrackedExport tracked = tracker.register(
            label, exported.file().orElseThrow(), before.root());
        tracked.setExportTimes(exportStartedAtEpochMillis, System.currentTimeMillis());
        final Path path = locateNewTempFile(before, marker, result, label);
        tracked.setPath(path);
        result.setProperty("export." + label + ".path", path.toString());
        return tracked;
    }

    private PsdValidationContent.Fingerprint exportTargetFingerprint(final Properties result,
        final Target target, final TempTracker tracker, final String label) throws Exception {
        requireTargetBinding(result, target, "export." + label + ".targetBinding.before", 0L);
        final TrackedExport exported = exportTracked(result, target.raw(), tracker, label);
        Throwable primary = null;
        try {
            requireTargetBinding(result, target, "export." + label + ".targetBinding.after", 0L);
            return targetFingerprint(Files.readAllBytes(exported.path()), label);
        } catch (Exception failure) {
            primary = failure;
            throw failure;
        } catch (Error failure) {
            primary = failure;
            throw failure;
        } finally {
            stopPreservingPrimary(tracker, exported, result, primary);
        }
    }

    private static PsdValidationContent.Fingerprint targetFingerprint(final byte[] bytes,
        final String label) {
        try {
            return PsdValidationContent.targetLayerRgbFingerprint(bytes);
        } catch (PsdValidationContent.ValidationException invalid) {
            throw new IllegalStateException(label + " is not a supported validation PSD: "
                + invalid.getMessage(), invalid);
        }
    }

    private static void recordTargetFingerprint(final Properties result, final String prefix,
        final PsdValidationContent.Fingerprint fingerprint) {
        final PsdValidationContent.Bounds bounds = fingerprint.bounds();
        result.setProperty(prefix + ".sha256", fingerprint.sha256());
        result.setProperty(prefix + ".bounds",
            bounds.top() + "," + bounds.left() + "," + bounds.bottom() + "," + bounds.right());
        result.setProperty(prefix + ".width", Integer.toString(fingerprint.width()));
        result.setProperty(prefix + ".height", Integer.toString(fingerprint.height()));
        result.setProperty(prefix + ".channelIds", fingerprint.channelIds().toString());
    }

    /**
     * Captures public relation and PSD-document projections around a fresh native export. This is
     * deliberately diagnostic-only: the supplied fingerprint is the only content evidence and is
     * produced by re-exporting the raw image through the public SDK. The target identity is checked
     * on the same EDT observation; an active-document/model switch is never combined with the
     * fingerprint as an AVAILABLE observation.
     */
    private DiagnosticObservation captureDiagnosticObservation(final Properties result,
        final String prefix, final Target target,
        final PsdValidationContent.Fingerprint fingerprint, final String boundary,
        final String diagnostic, final long freshExportStartedAtEpochMillis,
        final long freshExportCompletedAtEpochMillis, final long budgetMillis,
        final boolean record) throws ObservationBudgetExceededException {
        final String normalizedPrefix = normalizePrefix(prefix);
        final long metadataStartedAtEpochMillis = System.currentTimeMillis();
        final DiagnosticObservation observation = observeDiagnosticObservation(
            target,
            fingerprint,
            freshExportStartedAtEpochMillis,
            freshExportCompletedAtEpochMillis,
            metadataStartedAtEpochMillis,
            budgetMillis);
        if (record) {
            recordDiagnosticCapture(
                result,
                normalizedPrefix,
                target,
                observation,
                boundary,
                diagnostic
            );
        }
        return observation;
    }

    private DiagnosticObservation captureFreshDiagnosticObservation(final Properties result,
        final String prefix, final Target target, final TempTracker tracker,
        final String boundary, final long budgetMillis, final boolean record) throws Exception {
        final String normalizedPrefix = normalizePrefix(prefix);
        final long started = System.nanoTime();
        requireBudgetAvailable(started, budgetMillis, "fresh diagnostic observation");
        requireTargetBinding(result, target,
            normalizedPrefix + ".targetBinding.beforeExport",
            remainingDiagnosticBudgetMillis(started, budgetMillis));
        requireBudgetAvailable(started, budgetMillis, "fresh diagnostic export");
        final TrackedExport exported = exportTracked(
            result, target.raw(), tracker, diagnosticExportLabel(normalizedPrefix),
            remainingDiagnosticBudgetMillis(started, budgetMillis));
        Throwable primary = null;
        try {
            requireBudgetAvailable(started, budgetMillis, "fresh diagnostic export");
            final PsdValidationContent.Fingerprint fingerprint = targetFingerprint(
                Files.readAllBytes(exported.path()), normalizedPrefix);
            requireBudgetAvailable(started, budgetMillis, "fresh diagnostic metadata");
            final DiagnosticObservation observation = captureDiagnosticObservation(
                result,
                normalizedPrefix,
                target,
                fingerprint,
                boundary,
                "",
                exported.exportStartedAtEpochMillis(),
                exported.exportCompletedAtEpochMillis(),
                remainingDiagnosticBudgetMillis(started, budgetMillis),
                false
            );
            requireBudgetAvailable(started, budgetMillis, "fresh diagnostic metadata");
            if (record) {
                recordDiagnosticCapture(result, normalizedPrefix, target, observation, boundary, "");
            }
            return observation;
        } catch (Exception failure) {
            primary = failure;
            throw failure;
        } catch (Error failure) {
            primary = failure;
            throw failure;
        } finally {
            stopPreservingPrimary(tracker, exported, result, primary);
        }
    }

    private static void recordDiagnosticCapture(final Properties result, final String prefix,
        final Target target, final DiagnosticObservation observation, final String boundary,
        final String diagnostic) {
        final String normalizedPrefix = normalizePrefix(prefix);
        result.setProperty(normalizedPrefix + ".boundary", valueOrUnavailable(boundary));
        result.setProperty(normalizedPrefix + ".requestedRawId", target.raw().value());
        recordExpectedTargetIdentity(result, normalizedPrefix, target.identity());
        if (diagnostic != null && !diagnostic.isBlank()) {
            result.setProperty(normalizedPrefix + ".boundaryDiagnostic", diagnostic);
        }
        recordDiagnosticObservation(result, normalizedPrefix, observation);
        if (normalizedPrefix.endsWith(".importCompletion")) {
            recordNativeReturnUnavailable(result, normalizedPrefix);
        }
    }

    /** Performs the public read projections on the EDT and returns explicit unavailable fields. */
    private DiagnosticObservation observeDiagnosticObservation(final Target target,
        final PsdValidationContent.Fingerprint fingerprint,
        final long freshExportStartedAtEpochMillis,
        final long freshExportCompletedAtEpochMillis,
        final long metadataStartedAtEpochMillis, final long budgetMillis)
        throws ObservationBudgetExceededException {
        final AtomicReference<DiagnosticObservation> captured = new AtomicReference<>();
        final Runnable observe = () -> captured.set(observeDiagnosticObservationOnEdt(
            target,
            fingerprint,
            freshExportStartedAtEpochMillis,
            freshExportCompletedAtEpochMillis,
            metadataStartedAtEpochMillis
        ));
        final long started = System.nanoTime();
        requireBudgetAvailable(started, budgetMillis, "public diagnostic observation dispatch");
        try {
            if (SwingUtilities.isEventDispatchThread()) observe.run();
            else SwingUtilities.invokeAndWait(observe);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return diagnosticUnavailable(
                "public observation dispatch interrupted",
                fingerprint,
                TargetIdentity.unavailable(),
                freshExportStartedAtEpochMillis,
                freshExportCompletedAtEpochMillis,
                metadataStartedAtEpochMillis,
                System.currentTimeMillis());
        } catch (InvocationTargetException failure) {
            final Throwable cause = failure.getCause();
            if (cause instanceof Error error) throw error;
            return diagnosticUnavailable(
                "public observation dispatch failed: " + cause,
                fingerprint,
                TargetIdentity.unavailable(),
                freshExportStartedAtEpochMillis,
                freshExportCompletedAtEpochMillis,
                metadataStartedAtEpochMillis,
                System.currentTimeMillis());
        }
        requireBudgetAvailable(started, budgetMillis, "public diagnostic observation");
        final DiagnosticObservation observation = captured.get();
        return observation == null
            ? diagnosticUnavailable(
                "public observation returned no value",
                fingerprint,
                TargetIdentity.unavailable(),
                freshExportStartedAtEpochMillis,
                freshExportCompletedAtEpochMillis,
                metadataStartedAtEpochMillis,
                System.currentTimeMillis())
            : observation;
    }

    private DiagnosticObservation observeDiagnosticObservationOnEdt(final Target target,
        final PsdValidationContent.Fingerprint fingerprint,
        final long freshExportStartedAtEpochMillis,
        final long freshExportCompletedAtEpochMillis,
        final long metadataStartedAtEpochMillis) {
        String rawIds = UNAVAILABLE_VALUE;
        String modelImageIds = UNAVAILABLE_VALUE;
        String selectorProjection = UNAVAILABLE_VALUE;
        String livePsd = UNAVAILABLE_VALUE;
        boolean relationsAvailable = false;
        boolean livePsdAvailable = false;
        boolean targetRawSnapshotAvailable = false;
        final List<String> diagnostics = new ArrayList<>();
        final TargetIdentity expected = target == null
            ? TargetIdentity.unavailable() : target.identity();
        final CubismModel model;
        String documentId = UNAVAILABLE_VALUE;
        try {
            final var document = context.cubism().activeDocument().orElse(null);
            if (document == null) diagnostics.add("active document unavailable");
            else documentId = valueOrUnavailable(document.documentId());
        } catch (RuntimeException failure) {
            diagnostics.add("active document unavailable: " + failure);
        }
        try {
            model = context.cubism().model().active();
        } catch (RuntimeException failure) {
            return diagnosticUnavailable(
                "active model unavailable: " + failure,
                fingerprint,
                TargetIdentity.unavailable(),
                freshExportStartedAtEpochMillis,
                freshExportCompletedAtEpochMillis,
                metadataStartedAtEpochMillis,
                System.currentTimeMillis());
        }

        String modelId = valueOrUnavailable(model.id().value());
        String binding = UNAVAILABLE_VALUE;
        String targetModelImageId = UNAVAILABLE_VALUE;
        String targetRawId = UNAVAILABLE_VALUE;
        TextureRelationsSnapshot relations = null;
        try {
            relations = model.textures().relations();
            if (relations == null || !relations.isAvailable()) {
                diagnostics.add("relations unavailable");
            } else {
                relationsAvailable = true;
                binding = valueOrUnavailable(relations.binding());
                rawIds = rawImageIds(relations);
                modelImageIds = modelImageIds(relations);
                selectorProjection = selectorProjection(relations);
                final Optional<ModelImageRelation> targetRelation = relations.modelImages().stream()
                    .filter(relation -> relation.id().value().equals(expected.modelImageId()))
                    .findFirst();
                if (targetRelation.isPresent()) {
                    targetModelImageId = targetRelation.orElseThrow().id().value();
                    targetRawId = targetRelation.orElseThrow().currentRawImageId()
                        .map(RawImageId::value).orElse(UNAVAILABLE_VALUE);
                } else {
                    diagnostics.add("target model-image relation unavailable");
                }
            }
        } catch (RuntimeException failure) {
            diagnostics.add("relations unavailable: " + failure);
        }

        try {
            final List<PsdClipMaskDocumentSnapshot> documents = model.psdDocuments();
            if (documents == null) {
                diagnostics.add("live PSD documents unavailable: null result");
            } else {
                livePsdAvailable = true;
                livePsd = livePsdDocuments(documents);
                targetRawSnapshotAvailable = hasUniquePsdSnapshotForRawImage(
                    documents, targetRawId);
                if (!targetRawSnapshotAvailable) {
                    diagnostics.add("target raw image snapshot is absent or ambiguous for rawId="
                        + targetRawId);
                }
            }
        } catch (RuntimeException failure) {
            diagnostics.add("live PSD documents unavailable: " + failure);
        }

        final TargetIdentity observed = new TargetIdentity(
            documentId, modelId, binding, targetModelImageId, targetRawId);
        final boolean identityVerified = targetRawSnapshotAvailable
            && targetIdentityMatches(expected, observed);
        if (!identityVerified) {
            diagnostics.add("target identity mismatch expected=" + expected
                + " actual=" + observed);
        }
        final DiagnosticStatus status = diagnosticStatusForTarget(
            expected, observed, relationsAvailable, livePsdAvailable, fingerprint,
            targetRawSnapshotAvailable);
        return new DiagnosticObservation(
            status,
            diagnostics.isEmpty() ? "" : String.join("; ", diagnostics),
            rawIds,
            modelImageIds,
            selectorProjection,
            livePsd,
            relationsAvailable,
            livePsdAvailable,
            fingerprint,
            observed,
            identityVerified,
            freshExportStartedAtEpochMillis,
            freshExportCompletedAtEpochMillis,
            metadataStartedAtEpochMillis,
            System.currentTimeMillis()
        );
    }

    private static DiagnosticObservation diagnosticUnavailable(final String diagnostic,
        final PsdValidationContent.Fingerprint fingerprint, final TargetIdentity observed,
        final long freshExportStartedAtEpochMillis, final long freshExportCompletedAtEpochMillis,
        final long metadataStartedAtEpochMillis, final long metadataCompletedAtEpochMillis) {
        return new DiagnosticObservation(
            DiagnosticStatus.UNAVAILABLE,
            diagnostic,
            UNAVAILABLE_VALUE,
            UNAVAILABLE_VALUE,
            UNAVAILABLE_VALUE,
            UNAVAILABLE_VALUE,
            false,
            false,
            fingerprint,
            observed,
            false,
            freshExportStartedAtEpochMillis,
            freshExportCompletedAtEpochMillis,
            metadataStartedAtEpochMillis,
            metadataCompletedAtEpochMillis
        );
    }

    /**
     * Records one observation without upgrading an unavailable public projection to content
     * evidence. The nested status values are intentionally lower-case for machine-readable field
     * presence; the top-level status and native-return status remain explicit enums.
     */
    static void recordDiagnosticObservation(final Properties result, final String prefix,
        final DiagnosticObservation observation) {
        Objects.requireNonNull(result, "result");
        final String normalizedPrefix = normalizePrefix(prefix);
        final DiagnosticObservation value = Objects.requireNonNull(observation, "observation");
        result.setProperty(normalizedPrefix + ".status", value.status().name());
        result.setProperty(normalizedPrefix + ".diagnostic", value.diagnostic());
        result.setProperty(normalizedPrefix + ".relations.status",
            value.relationsAvailable() ? "AVAILABLE" : "UNAVAILABLE");
        result.setProperty(normalizedPrefix + ".relations.rawIds", value.rawIds());
        result.setProperty(normalizedPrefix + ".relations.modelImageIds", value.modelImageIds());
        result.setProperty(normalizedPrefix + ".selectorProjection", value.selectorProjection());
        result.setProperty(normalizedPrefix + ".livePsd.status",
            value.livePsdAvailable() ? "AVAILABLE" : "UNAVAILABLE");
        result.setProperty(normalizedPrefix + ".livePsd", value.livePsd());
        result.setProperty(normalizedPrefix + ".target.identityVerified",
            Boolean.toString(value.targetIdentityVerified()));
        recordTargetIdentity(result, normalizedPrefix + ".target.observed",
            value.observedTarget());
        result.setProperty(normalizedPrefix + ".observation.freshExportStartedAtEpochMs",
            Long.toString(value.freshExportStartedAtEpochMillis()));
        result.setProperty(normalizedPrefix + ".observation.freshExportCompletedAtEpochMs",
            Long.toString(value.freshExportCompletedAtEpochMillis()));
        result.setProperty(normalizedPrefix + ".observation.metadataStartedAtEpochMs",
            Long.toString(value.metadataStartedAtEpochMillis()));
        result.setProperty(normalizedPrefix + ".observation.metadataCompletedAtEpochMs",
            Long.toString(value.metadataCompletedAtEpochMillis()));
        if (value.status() != DiagnosticStatus.AVAILABLE || value.freshNativeRgb() == null) {
            result.setProperty(normalizedPrefix + ".freshNativeRgb.status", "unavailable");
        } else {
            result.setProperty(normalizedPrefix + ".freshNativeRgb.status", "available");
            result.setProperty(normalizedPrefix + ".freshNativeRgb.source",
                "fresh native export decoded by validation-only fixture helper");
            recordTargetFingerprint(result, normalizedPrefix + ".freshNativeRgb",
                value.freshNativeRgb());
        }
    }

    /** Records only the public completion result; PsdReplaceResult exposes no native return value. */
    static void recordImportCompletion(final Properties result, final String prefix,
        final PsdReplaceResult replaced) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(replaced, "replaced");
        final String completionPrefix = joinPrefix(prefix, "importCompletion");
        result.setProperty(completionPrefix + ".observed", "true");
        result.setProperty(completionPrefix + ".publicCompletion.observed", "true");
        result.setProperty(completionPrefix + ".publicCompletion.status",
            replaced.status().name());
        result.setProperty(completionPrefix + ".status", replaced.status().name());
        result.setProperty(completionPrefix + ".diagnostic", replaced.diagnostic());
        result.setProperty(completionPrefix + ".beforeRawId", replaced.before().value());
        result.setProperty(completionPrefix + ".afterRawId",
            replaced.after().map(RawImageId::value).orElse(UNAVAILABLE_VALUE));
        result.setProperty(completionPrefix + ".consumedRevision",
            Boolean.toString(replaced.consumedRevision().isPresent()));
        if (replaced.relations().isPresent()) {
            recordRelationProjection(result, completionPrefix + ".relations",
                replaced.relations().orElseThrow());
        } else {
            recordUnavailableRelationProjection(result, completionPrefix + ".relations");
        }
        result.setProperty(completionPrefix + ".livePsd.status", "UNAVAILABLE");
        result.setProperty(completionPrefix + ".livePsd", UNAVAILABLE_VALUE);
        result.setProperty(completionPrefix + ".freshNativeRgb.status", "unavailable");
        recordNativeReturnUnavailable(result, completionPrefix);
    }

    private static void recordNativeReturnUnavailable(final Properties result,
        final String prefix) {
        result.setProperty(prefix + ".nativeReturn.observation", "UNAVAILABLE");
        result.setProperty(prefix + ".nativeReturn.diagnostic",
            "PsdReplaceResult exposes public completion only; native return is not observable");
    }

    /**
     * Waits for two consecutive complete public observations to compare equal. The caller is the
     * probe worker; EDT use is rejected so a fresh export or public read can never block UI work.
     * The pacing callback only throttles bounded polls and is not a completion signal. Public SDK
     * calls remain synchronous: elapsed time is checked before and after each call, but an SDK call
     * that cannot be cancelled can make this worker return after the advisory window.
     */
    static BoundedSettleResult awaitBoundedSettle(final DiagnosticObservation initial,
        final ObservationSupplier supplier, final int maxAttempts, final long maxDurationMillis,
        final LongConsumer pacing) {
        Objects.requireNonNull(supplier, "supplier");
        return awaitBoundedSettleBounded(
            initial,
            remainingMillis -> supplier.get(),
            maxAttempts,
            maxDurationMillis,
            pacing,
            observation -> { }
        );
    }

    static BoundedSettleResult awaitBoundedSettle(final DiagnosticObservation initial,
        final BoundedObservationSupplier supplier, final int maxAttempts,
        final long maxDurationMillis, final LongConsumer pacing) {
        return awaitBoundedSettleBounded(
            initial, supplier, maxAttempts, maxDurationMillis, pacing, observation -> { });
    }

    static BoundedSettleResult awaitBoundedSettle(final DiagnosticObservation initial,
        final BoundedObservationSupplier supplier, final int maxAttempts,
        final long maxDurationMillis, final LongConsumer pacing,
        final ObservationRecorder recorder) {
        return awaitBoundedSettleBounded(
            initial, supplier, maxAttempts, maxDurationMillis, pacing, recorder);
    }

    private static BoundedSettleResult awaitBoundedSettleBounded(
        final DiagnosticObservation initial, final BoundedObservationSupplier supplier,
        final int maxAttempts, final long maxDurationMillis, final LongConsumer pacing,
        final ObservationRecorder recorder) {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("bounded settle must run on the probe worker");
        }
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive");
        if (maxDurationMillis < 1) {
            throw new IllegalArgumentException("maxDurationMillis must be positive");
        }
        Objects.requireNonNull(supplier, "supplier");
        Objects.requireNonNull(pacing, "pacing");
        Objects.requireNonNull(recorder, "recorder");
        final String criterion = "two consecutive complete observations equal (observation-stable "
            + "only; does not prove native completion); maxAttempts="
            + maxAttempts + ", maxDurationMs=" + maxDurationMillis;
        final long started = System.nanoTime();
        if (initial == null || !initial.complete()) {
            return settleResult(SettleStatus.UNAVAILABLE, 0, started, criterion,
                "initial public observation is unavailable", initial, false);
        }
        final long durationNanos = TimeUnit.MILLISECONDS.toNanos(maxDurationMillis);
        final long deadline = started + durationNanos;
        DiagnosticObservation previous = initial;
        DiagnosticObservation last = initial;
        int attempts = 0;
        long backoffMillis = BOUNDED_SETTLE_INITIAL_BACKOFF_MILLIS;
        while (attempts < maxAttempts && System.nanoTime() < deadline) {
            final long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0L) break;
            attempts++;
            final DiagnosticObservation current;
            try {
                current = supplier.get(Math.max(1L,
                    (remainingNanos + 999_999L) / 1_000_000L));
            } catch (ObservationBudgetExceededException late) {
                return settleResult(SettleStatus.TIMEOUT, attempts, started, criterion,
                    late.getMessage(), last, false);
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                return settleResult(SettleStatus.FAILED, attempts, started, criterion,
                    failure.toString(), last, false);
            }
            if (System.nanoTime() >= deadline) {
                return settleResult(SettleStatus.TIMEOUT, attempts, started, criterion,
                    "observation returned after the settle deadline; late value was not recorded "
                        + "or considered stable", last, false);
            }
            last = current;
            if (current != null) {
                try {
                    recorder.record(current);
                } catch (RuntimeException failure) {
                    return settleResult(SettleStatus.FAILED, attempts, started, criterion,
                        "observation recording failed: " + failure, last, false);
                }
            }
            if (current == null || !current.complete()) {
                return settleResult(SettleStatus.UNAVAILABLE, attempts, started, criterion,
                    "public observation became unavailable", current, false);
            }
            if (sameDiagnosticObservation(previous, current)) {
                return settleResult(SettleStatus.STABLE, attempts, started, criterion,
                    "consecutive complete observations matched; observation-stable only", current,
                    true);
            }
            previous = current;
            if (attempts < maxAttempts && System.nanoTime() < deadline) {
                final long remainingForPacing = Math.max(0L,
                    remainingMillis(started, maxDurationMillis));
                final long waitMillis = Math.min(backoffMillis,
                    Math.max(0L, remainingForPacing));
                if (waitMillis > 0) {
                    try {
                        pacing.accept(TimeUnit.MILLISECONDS.toNanos(waitMillis));
                    } catch (RuntimeException failure) {
                        return settleResult(SettleStatus.FAILED, attempts, started, criterion,
                            "settle pacing failed: " + failure, last, false);
                    }
                }
                backoffMillis = Math.min(BOUNDED_SETTLE_MAX_BACKOFF_MILLIS,
                    Math.max(BOUNDED_SETTLE_INITIAL_BACKOFF_MILLIS, backoffMillis * 2));
            }
        }
        return settleResult(SettleStatus.TIMEOUT, attempts, started, criterion,
            "observations did not become stable within the bounded window", last, false);
    }

    private static boolean sameDiagnosticObservation(final DiagnosticObservation first,
        final DiagnosticObservation second) {
        return first != null && second != null
            && first.status() == second.status()
            && first.diagnostic().equals(second.diagnostic())
            && first.rawIds().equals(second.rawIds())
            && first.modelImageIds().equals(second.modelImageIds())
            && first.selectorProjection().equals(second.selectorProjection())
            && first.livePsd().equals(second.livePsd())
            && first.relationsAvailable() == second.relationsAvailable()
            && first.livePsdAvailable() == second.livePsdAvailable()
            && Objects.equals(first.freshNativeRgb(), second.freshNativeRgb())
            && Objects.equals(first.observedTarget(), second.observedTarget())
            && first.targetIdentityVerified() == second.targetIdentityVerified();
    }

    private static BoundedSettleResult settleResult(final SettleStatus status, final int attempts,
        final long started, final String criterion, final String diagnostic,
        final DiagnosticObservation last, final boolean stable) {
        return new BoundedSettleResult(status, attempts, elapsedMillis(started), criterion,
            diagnostic, stable, last);
    }

    private static long elapsedMillis(final long started) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - started));
    }

    static void recordBoundedSettle(final Properties result, final String prefix,
        final BoundedSettleResult settled) {
        final String normalizedPrefix = normalizePrefix(prefix);
        result.setProperty(normalizedPrefix + ".status", settled.status().name());
        result.setProperty(normalizedPrefix + ".stable", Boolean.toString(settled.stable()));
        result.setProperty(normalizedPrefix + ".attempts", Integer.toString(settled.attempts()));
        result.setProperty(normalizedPrefix + ".durationMs", Long.toString(settled.durationMillis()));
        result.setProperty(normalizedPrefix + ".criterion", settled.criterion());
        result.setProperty(normalizedPrefix + ".diagnostic", settled.diagnostic());
        result.setProperty(normalizedPrefix + ".nativeCompletion", "UNAVAILABLE");
        result.setProperty(normalizedPrefix + ".deadline.mode",
            "ADVISORY_SYNCHRONOUS_SDK_CALLS");
        result.setProperty(normalizedPrefix + ".deadline.hard", "false");
        result.setProperty(normalizedPrefix + ".deadline.sdkCallsCancellable", "false");
        result.setProperty(normalizedPrefix + ".deadline.diagnostic",
            "worker elapsed time is checked before and after each observation; public SDK "
                + "invokeAndWait/export calls are synchronous and cannot be cancelled safely");
        if (settled.lastObservation() != null) {
            recordDiagnosticObservation(result, normalizedPrefix + ".last",
                settled.lastObservation());
        }
    }

    private static void recordSettleNotAttempted(final Properties result, final String prefix,
        final String diagnostic) {
        final String normalizedPrefix = normalizePrefix(prefix);
        result.setProperty(normalizedPrefix + ".status", "NOT_ATTEMPTED");
        result.setProperty(normalizedPrefix + ".stable", "false");
        result.setProperty(normalizedPrefix + ".attempts", "0");
        result.setProperty(normalizedPrefix + ".durationMs", "0");
        result.setProperty(normalizedPrefix + ".criterion",
            "requires public APPLIED completion and a fresh native observation");
        result.setProperty(normalizedPrefix + ".diagnostic", diagnostic);
        result.setProperty(normalizedPrefix + ".nativeCompletion", "UNAVAILABLE");
        result.setProperty(normalizedPrefix + ".deadline.mode",
            "ADVISORY_SYNCHRONOUS_SDK_CALLS");
        result.setProperty(normalizedPrefix + ".deadline.hard", "false");
        result.setProperty(normalizedPrefix + ".deadline.sdkCallsCancellable", "false");
        result.setProperty(normalizedPrefix + ".deadline.diagnostic",
            "settle was not attempted; public SDK completion is not a native return signal");
    }

    static void recordFreshDiagnosticFailure(final Properties result,
        final String completionPrefix, final String settlePrefix, final Throwable failure) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(failure, "failure");
        final String completion = normalizePrefix(completionPrefix);
        result.setProperty(completion + ".freshNativeRgb.status", "failed");
        result.setProperty(completion + ".freshNativeRgb.observed", "false");
        result.setProperty(completion + ".freshNativeRgb.failure", failure.toString());
        result.setProperty(completion + ".diagnosticFailure.status", "FAILED");
        result.setProperty(completion + ".diagnosticFailure.exception", failure.toString());
        result.setProperty(completion + ".diagnosticFailure.phase", "fresh-native-export");
        recordNativeReturnUnavailable(result, completion);
        recordSettleNotAttempted(
            result,
            settlePrefix,
            "fresh native diagnostic observation failed before settle: " + failure);
    }

    private static void recordFreshDiagnosticFailurePreservingPrimary(
        final Properties result, final String completionPrefix, final String settlePrefix,
        final Throwable primary) {
        try {
            recordFreshDiagnosticFailure(result, completionPrefix, settlePrefix, primary);
        } catch (Throwable evidenceFailure) {
            addSuppressed(primary, evidenceFailure);
        }
    }

    static void recordRelationProjection(final Properties result, final String prefix,
        final TextureRelationsSnapshot relations) {
        final String normalizedPrefix = normalizePrefix(prefix);
        if (relations == null || !relations.isAvailable()) {
            recordUnavailableRelationProjection(result, normalizedPrefix);
            return;
        }
        result.setProperty(normalizedPrefix + ".status", "AVAILABLE");
        result.setProperty(normalizedPrefix + ".rawIds", rawImageIds(relations));
        result.setProperty(normalizedPrefix + ".modelImageIds", modelImageIds(relations));
        result.setProperty(normalizedPrefix + ".selectorProjection", selectorProjection(relations));
    }

    private static void recordUnavailableRelationProjection(final Properties result,
        final String prefix) {
        final String normalizedPrefix = normalizePrefix(prefix);
        result.setProperty(normalizedPrefix + ".status", "UNAVAILABLE");
        result.setProperty(normalizedPrefix + ".rawIds", UNAVAILABLE_VALUE);
        result.setProperty(normalizedPrefix + ".modelImageIds", UNAVAILABLE_VALUE);
        result.setProperty(normalizedPrefix + ".selectorProjection", UNAVAILABLE_VALUE);
    }

    private static String rawImageIds(final TextureRelationsSnapshot relations) {
        return relations.rawImages().stream()
            .map(raw -> raw.id().value())
            .toList().toString();
    }

    private static String modelImageIds(final TextureRelationsSnapshot relations) {
        return relations.modelImages().stream()
            .map(ModelImageRelation::id)
            .map(id -> id.value())
            .toList().toString();
    }

    /** Explicitly labels layerInputsByRawImage as a selector projection, not live PSD evidence. */
    private static String selectorProjection(final TextureRelationsSnapshot relations) {
        final List<String> projections = new ArrayList<>();
        for (final ModelImageRelation relation : relations.modelImages()) {
            final List<String> byRaw = new ArrayList<>();
            for (final Map.Entry<RawImageId, List<RawLayerBinding>> entry
                : relation.layerInputsByRawImage().entrySet()) {
                final List<String> bindings = entry.getValue().stream()
                    .map(binding -> binding.rawLayerId().value() + "@" + binding.inputOrder()
                        + ":transform=" + binding.transformAvailability()
                        + ":clipping=" + binding.clippingAvailability())
                    .toList();
                byRaw.add(entry.getKey().value() + "=" + bindings);
            }
            projections.add("modelImage=" + relation.id().value()
                + "/currentRaw=" + relation.currentRawImageId().map(RawImageId::value)
                    .orElse(UNAVAILABLE_VALUE)
                + "/linkedRaw=" + relation.linkedRawImageIds().stream()
                    .map(RawImageId::value).toList()
                + "/rawLayerBindings=" + byRaw
                + "/artMeshIds=" + relation.usingArtMeshIds().stream()
                    .map(id -> id.value()).toList());
        }
        return projections.toString();
    }

    static String livePsdDocuments(final List<PsdClipMaskDocumentSnapshot> documents) {
        final List<String> layers = new ArrayList<>();
        for (final PsdClipMaskDocumentSnapshot document : documents) {
            for (int index = 0; index < document.layers().size(); index++) {
                appendLiveLayer(layers, document, document.layers().get(index), Integer.toString(index));
            }
        }
        return layers.toString();
    }

    private static void appendLiveLayer(final List<String> layers,
        final PsdClipMaskDocumentSnapshot document, final PsdLayerSnapshot layer,
        final String path) {
        layers.add("documentId=" + diagnosticToken(document.documentId())
            + "/relativePath=" + diagnosticToken(document.relativePath())
            + "/layerPath=" + path
            + "/layerId=" + diagnosticToken(layer.layerId())
            + "/name=" + diagnosticToken(layer.name())
            + "/artMeshIds=" + layer.artMeshIds().stream().map(id -> id.value()).toList());
        for (int index = 0; index < layer.children().size(); index++) {
            appendLiveLayer(layers, document, layer.children().get(index), path + "." + index);
        }
    }

    private static String diagnosticToken(final String value) {
        return value.replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("|", "\\|");
    }

    private static String joinPrefix(final String prefix, final String suffix) {
        return normalizePrefix(prefix) + "." + suffix;
    }

    private static String diagnosticExportLabel(final String prefix) {
        return "diagnostic-" + normalizePrefix(prefix).replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String normalizePrefix(final String prefix) {
        if (prefix == null || prefix.isBlank()) throw new IllegalArgumentException("prefix is required");
        String normalized = prefix;
        while (normalized.endsWith(".")) normalized = normalized.substring(0, normalized.length() - 1);
        if (normalized.isBlank()) throw new IllegalArgumentException("prefix is required");
        return normalized;
    }

    private static String valueOrUnavailable(final String value) {
        return value == null || value.isBlank() ? UNAVAILABLE_VALUE : value;
    }

    private static final String UNAVAILABLE_VALUE = "unavailable";

    private static boolean isCanonicalSha256(final String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    static boolean targetIdentityMatches(final TargetIdentity expected,
        final TargetIdentity actual) {
        return expected != null && actual != null
            && !UNAVAILABLE_VALUE.equals(expected.documentId())
            && !UNAVAILABLE_VALUE.equals(expected.modelId())
            && !UNAVAILABLE_VALUE.equals(expected.binding())
            && !UNAVAILABLE_VALUE.equals(expected.modelImageId())
            && !UNAVAILABLE_VALUE.equals(expected.rawId())
            && expected.equals(actual);
    }

    /**
     * Matches a PSD snapshot to the raw image it describes. The public snapshot calls this
     * field {@code documentId}, but the editor adapter fills it from the CLayeredImage GUID;
     * the raw-image adapter uses that same GUID value as {@code RawImageId}. It is therefore
     * deliberately compared with {@link TargetIdentity#rawId()}, never with the CMO project
     * document ID held by {@link TargetIdentity#documentId()}.
     */
    static boolean hasUniquePsdSnapshotForRawImage(
        final List<PsdClipMaskDocumentSnapshot> documents, final String rawId) {
        if (documents == null || rawId == null || rawId.isBlank()
            || UNAVAILABLE_VALUE.equals(rawId)) {
            return false;
        }
        int matches = 0;
        for (final PsdClipMaskDocumentSnapshot document : documents) {
            if (document == null) return false;
            if (rawId.equals(document.documentId())) matches++;
        }
        return matches == 1;
    }

    static DiagnosticStatus diagnosticStatusForTarget(final TargetIdentity expected,
        final TargetIdentity actual, final boolean relationsAvailable,
        final boolean livePsdAvailable,
        final PsdValidationContent.Fingerprint fingerprint) {
        return diagnosticStatusForTarget(
            expected, actual, relationsAvailable, livePsdAvailable, fingerprint, true);
    }

    static DiagnosticStatus diagnosticStatusForTarget(final TargetIdentity expected,
        final TargetIdentity actual, final boolean relationsAvailable,
        final boolean livePsdAvailable,
        final PsdValidationContent.Fingerprint fingerprint,
        final boolean targetRawSnapshotAvailable) {
        return relationsAvailable && livePsdAvailable && targetRawSnapshotAvailable
            && fingerprint != null && targetIdentityMatches(expected, actual)
            ? DiagnosticStatus.AVAILABLE : DiagnosticStatus.UNAVAILABLE;
    }

    private TargetIdentity currentTargetIdentityOnEdt(final TargetIdentity expected) {
        final var document = context.cubism().activeDocument().orElseThrow(
            () -> new IllegalStateException("active document unavailable"));
        final CubismModel model = context.cubism().model().active();
        final TextureRelationsSnapshot relations = model.textures().relations();
        if (relations == null || !relations.isAvailable()) {
            throw new IllegalStateException("relations unavailable");
        }
        final ModelImageRelation targetRelation = relations.modelImages().stream()
            .filter(relation -> relation.id().value().equals(expected.modelImageId()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "target model-image relation unavailable"));
        return new TargetIdentity(
            document.documentId(),
            model.id().value(),
            relations.binding(),
            targetRelation.id().value(),
            targetRelation.currentRawImageId().map(RawImageId::value)
                .orElse(UNAVAILABLE_VALUE));
    }

    private void requireTargetBinding(final Properties result, final Target target,
        final String prefix, final long budgetMillis) throws Exception {
        Objects.requireNonNull(target, "target");
        final String normalizedPrefix = normalizePrefix(prefix);
        final long started = System.nanoTime();
        requireBudgetAvailable(started, budgetMillis, "target binding observation");
        final AtomicReference<TargetIdentity> actual = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Runnable observe = () -> {
            try {
                actual.set(currentTargetIdentityOnEdt(target.identity()));
            } catch (Throwable error) {
                failure.set(error);
            }
        };
        try {
            if (SwingUtilities.isEventDispatchThread()) observe.run();
            else SwingUtilities.invokeAndWait(observe);
        } catch (InterruptedException interrupted) {
            recordTargetBindingCheck(
                result, normalizedPrefix, target.identity(), TargetIdentity.unavailable());
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (InvocationTargetException invocationFailure) {
            final Throwable cause = invocationFailure.getCause();
            if (cause instanceof Error error) throw error;
            recordTargetBindingCheck(
                result, normalizedPrefix, target.identity(), TargetIdentity.unavailable());
            throw new IllegalStateException("target binding observation dispatch failed", cause);
        }
        requireBudgetAvailable(started, budgetMillis, "target binding observation");
        if (failure.get() != null) {
            final Throwable error = failure.get();
            final TargetIdentity observed = actual.get() == null
                ? TargetIdentity.unavailable() : actual.get();
            recordTargetBindingCheck(result, normalizedPrefix, target.identity(), observed);
            if (error instanceof Error fatal) throw fatal;
            throw new IllegalStateException("target binding observation failed", error);
        }
        final TargetIdentity observed = actual.get();
        recordTargetBindingCheck(result, normalizedPrefix, target.identity(), observed);
        if (!targetIdentityMatches(target.identity(), observed)) {
            throw new IllegalStateException("target binding changed expected=" + target.identity()
                + " actual=" + observed);
        }
    }

    private static void recordTargetBindingCheck(final Properties result, final String prefix,
        final TargetIdentity expected, final TargetIdentity actual) {
        recordExpectedTargetIdentity(result, prefix, expected);
        result.setProperty(prefix + ".observed.identityVerified",
            Boolean.toString(targetIdentityMatches(expected, actual)));
        recordTargetIdentity(result, prefix + ".observed", actual);
    }

    private static void recordExpectedTargetIdentity(final Properties result, final String prefix,
        final TargetIdentity identity) {
        recordTargetIdentity(result, prefix + ".target.expected", identity);
    }

    private static void recordTargetIdentity(final Properties result, final String prefix,
        final TargetIdentity identity) {
        final TargetIdentity value = identity == null ? TargetIdentity.unavailable() : identity;
        result.setProperty(prefix + ".documentId", value.documentId());
        result.setProperty(prefix + ".modelId", value.modelId());
        result.setProperty(prefix + ".binding", value.binding());
        result.setProperty(prefix + ".modelImageId", value.modelImageId());
        result.setProperty(prefix + ".rawId", value.rawId());
    }

    private static long remainingMillis(final long started, final long maxDurationMillis) {
        final long elapsedNanos = Math.max(0L, System.nanoTime() - started);
        final long durationNanos = TimeUnit.MILLISECONDS.toNanos(maxDurationMillis);
        final long remainingNanos = durationNanos - elapsedNanos;
        if (remainingNanos <= 0L) return 0L;
        return Math.max(1L, (remainingNanos + 999_999L) / 1_000_000L);
    }

    /**
     * Returns the budget available to the next diagnostic SDK stage. A zero input retains the
     * existing unlimited-budget sentinel; a positive input is recomputed from the caller's
     * operation start so elapsed work is not granted again to the next stage. A positive budget
     * never returns zero: exhaustion throws before a caller can pass an unlimited sentinel onward.
     */
    static long remainingDiagnosticBudgetMillis(final long started, final long budgetMillis) {
        return remainingDiagnosticBudgetMillis(started, budgetMillis, "diagnostic stage");
    }

    private static long remainingDiagnosticBudgetMillis(final long started,
        final long budgetMillis, final String operation) {
        if (budgetMillis <= 0L) return budgetMillis;
        final long remaining = remainingMillis(started, budgetMillis);
        if (remaining == 0L) {
            throw new ObservationBudgetExceededException(
                operation + " exceeded the remaining settle budget; SDK calls are synchronous "
                    + "and non-cancellable");
        }
        return remaining;
    }

    private static void requireBudgetAvailable(final long started, final long budgetMillis,
        final String operation) throws ObservationBudgetExceededException {
        if (budgetMillis > 0L) {
            remainingDiagnosticBudgetMillis(started, budgetMillis, operation);
        }
    }

    /** Requires the native history move that the following fresh export is meant to observe. */
    static void requireHistoryMoved(
        final dev.turboism.sdk.cubism.history.HistoryMoveResult move, final String direction) {
        final String name = direction == null || direction.isBlank() ? "history" : direction;
        if (move == null || move.outcome()
            != dev.turboism.sdk.cubism.history.HistoryMoveResult.Outcome.MOVED) {
            throw new IllegalStateException(name + " did not move native history: "
                + (move == null ? "null result" : move.outcome()));
        }
    }

    /**
     * Final persistence evidence gate owned by the probe before it reports a successful phase.
     * The runner repeats the same contract while binding the result to its Supervisor job.
     */
    static void requirePersistEvidence(final Properties result) {
        if (result == null) throw new IllegalStateException("persistence evidence is missing");
        final String baseline = result.getProperty("persist.baselineTargetRgbSha256");
        final String second = result.getProperty("persist.baselineSecondTargetRgbSha256");
        final String post = result.getProperty("persist.postEditTargetRgbSha256");
        if (!isCanonicalSha256(baseline) || !isCanonicalSha256(second)
            || !isCanonicalSha256(post)) {
            throw new IllegalStateException(
                "persistence target RGB hashes are missing or not canonical lowercase SHA-256");
        }
        if (!baseline.equals(second)) {
            throw new IllegalStateException(
                "independent native baseline target RGB fingerprints are unstable");
        }
        if (baseline.equals(post)) {
            throw new IllegalStateException(
                "fresh native post-edit target RGB fingerprint did not change");
        }
        requireExactProperty(result, "persist.targetContentChanged", "true");
        requireExactProperty(result, "persist.saveSucceeded", "true");
        requireExactProperty(result, "persist.tempQuarantine.status", "MOVED");
        requireExactProperty(result, "persist.tempQuarantine.taskOwned", "true");
        requireExactProperty(result, "persist.tempQuarantine.sourceMissing", "true");
    }

    private static void requireExactProperty(final Properties result, final String key,
        final String expected) {
        if (!expected.equals(result.getProperty(key))) {
            throw new IllegalStateException(
                "persistence evidence " + key + " must be " + expected);
        }
    }

    static void requireStableBaseline(final PsdValidationContent.Fingerprint first,
        final PsdValidationContent.Fingerprint second) {
        if (first == null || second == null || !isCanonicalSha256(first.sha256())
            || !isCanonicalSha256(second.sha256())) {
            throw new IllegalStateException("native baseline target RGB evidence is missing or invalid");
        }
        if (!first.equals(second)) {
            throw new IllegalStateException(
                "independent native baseline target RGB fingerprints are unstable");
        }
    }

    static void requireChangedPost(final PsdValidationContent.Fingerprint baseline,
        final PsdValidationContent.Fingerprint post) {
        if (baseline == null || post == null || !isCanonicalSha256(baseline.sha256())
            || !isCanonicalSha256(post.sha256())) {
            throw new IllegalStateException("native post-edit target RGB evidence is missing or invalid");
        }
        if (baseline.sha256().equals(post.sha256())) {
            throw new IllegalStateException(
                "fresh native post-edit target RGB fingerprint did not change");
        }
    }

    static void requireReopenTarget(final String expected,
        final PsdValidationContent.Fingerprint actual) {
        if (!isCanonicalSha256(expected) || actual == null
            || !isCanonicalSha256(actual.sha256())) {
            throw new IllegalStateException(
                "reopen target RGB evidence is missing or not canonical lowercase SHA-256");
        }
        if (!expected.equals(actual.sha256())) {
            throw new IllegalStateException(
                "reopened document target RGB fingerprint does not match the saved post-edit fingerprint");
        }
    }

    private Mutation runSaveCycles(final Properties result, final PsdEditFile file, final Target target,
        final Path tempFile, final Deque<PsdFileRevision> revisions, final TempTracker tracker,
        final int cycles, final boolean validateTargetContent) throws Exception {
        Mutation lastMutation = null;
        for (int i = 1; i <= cycles; i++) {
            final byte[] current = Files.readAllBytes(tempFile);
            final CycleWritePlan plan = prepareSaveCycleBytes(
                current, i, cycles, validateTargetContent);
            final Mutation mutation = plan.mutation();
            final byte[] mutated = plan.firstWrite();
            result.setProperty("cycle." + i + ".targetRgbMutation",
                plan.targetRgbMutated() ? "INVERTED_ONCE" : "UNCHANGED");
            lastMutation = mutation;
            final long writeStart = System.nanoTime();
            if (i == 2) {
                // Atomic-rename save: write sibling then move over the issued file.
                final Path sibling = tempFile.resolveSibling("external-edit.psd.tmp");
                Files.write(sibling, mutated);
                Files.move(sibling, tempFile, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } else if (i == 3) {
                // Overlapping save: a second distinct write lands inside the debounce
                // window; latest-pending must win and publish a single revision.
                Files.write(tempFile, mutated);
                Files.write(tempFile, plan.overlapFinalWrite());
                result.setProperty("cycle." + i + ".overlapFinalContainsRgbMutation",
                    Boolean.toString(plan.targetRgbMutated()));
            } else {
                Files.write(tempFile, mutated);
            }
            final PsdFileRevision revision = awaitRevision(revisions, 20);
            final long detectedMs = (System.nanoTime() - writeStart) / 1_000_000;
            final long replaceStart = System.nanoTime();
            final PsdReplaceResult replaced = replace(file, target.raw(), revision);
            final long replaceMs = (System.nanoTime() - replaceStart) / 1_000_000;
            final String prefix = "cycle." + i + ".";
            result.setProperty(prefix + "revisionDeliveredMs", Long.toString(detectedMs));
            result.setProperty(prefix + "replaceMs", Long.toString(replaceMs));
            result.setProperty(prefix + "status", replaced.status().name());
            result.setProperty(prefix + "diagnostic", replaced.diagnostic());
            result.setProperty(prefix + "after", replaced.after().map(RawImageId::value).orElse(""));
            result.setProperty(prefix + "consumed", Boolean.toString(replaced.consumedRevision().isPresent()));
            if (validateTargetContent) {
                recordImportCompletion(result, prefix, replaced);
                if (replaced.status() == PsdReplaceResult.Status.APPLIED
                    && replaced.after().isPresent()) {
                    final String completionPrefix =
                        joinPrefix("persist.observation." + prefix, "importCompletion");
                    final String settlePrefix =
                        joinPrefix("persist.observation." + prefix, "boundedSettle");
                    final DiagnosticObservation completion;
                    try {
                        completion = captureFreshDiagnosticObservation(
                            result,
                            completionPrefix,
                            target,
                            tracker,
                            "fresh-native-export-after-public-import-completion",
                            0L,
                            true
                        );
                    } catch (Exception failure) {
                        recordFreshDiagnosticFailurePreservingPrimary(
                            result, completionPrefix, settlePrefix, failure);
                        throw failure;
                    } catch (Error failure) {
                        recordFreshDiagnosticFailurePreservingPrimary(
                            result, completionPrefix, settlePrefix, failure);
                        throw failure;
                    }
                    final AtomicInteger settleAttempt = new AtomicInteger();
                    final AtomicReference<String> settleAttemptPrefix = new AtomicReference<>();
                    final BoundedSettleResult settled = awaitBoundedSettle(
                        completion,
                        remainingMillis -> {
                            final String attemptPrefix = settlePrefix + ".attempt."
                                + settleAttempt.incrementAndGet();
                            settleAttemptPrefix.set(attemptPrefix);
                            return captureFreshDiagnosticObservation(
                                result,
                                attemptPrefix,
                                target,
                                tracker,
                                "fresh-native-export-after-bounded-settle-poll",
                                remainingMillis,
                                false
                            );
                        },
                        BOUNDED_SETTLE_MAX_ATTEMPTS,
                        BOUNDED_SETTLE_MAX_DURATION_MILLIS,
                        nanos -> LockSupport.parkNanos(nanos),
                        observation -> {
                            final String attemptPrefix = settleAttemptPrefix.get();
                            if (attemptPrefix != null) {
                                recordDiagnosticCapture(
                                    result,
                                    attemptPrefix,
                                    target,
                                    observation,
                                    "fresh-native-export-after-bounded-settle-poll",
                                    ""
                                );
                            }
                        }
                    );
                    recordBoundedSettle(result, settlePrefix, settled);
                } else {
                    recordSettleNotAttempted(
                        result,
                        joinPrefix("persist.observation." + prefix, "boundedSettle"),
                        "public import completion did not report APPLIED with an observed target"
                    );
                }
            }
            if (replaced.status() != PsdReplaceResult.Status.APPLIED
                || replaced.consumedRevision().isEmpty() || replaced.after().isEmpty()) {
                throw new IllegalStateException("Explicit-target replacement not applied in cycle " + i
                    + ": " + replaced.status() + " " + replaced.diagnostic());
            }
            final String observed = currentRawOnEdt(target.modelImage().value());
            result.setProperty(prefix + "currentRawAfter", observed);
            result.setProperty("applied.currentRaw", observed);
            // Drain: an overlapping save may publish a second revision after the lane
            // settles; leftovers must not leak into the corrupted-save assertion.
            Thread.sleep(1500);
            synchronized (revisions) {
                result.setProperty(prefix + "extraRevisions", Integer.toString(revisions.size()));
                revisions.clear();
            }
        }
        return lastMutation;
    }

    /**
     * Prepares the exact bytes written by one save cycle. The production loop uses this seam for
     * both its atomic sibling move and its overlap second write, while offline tests can verify
     * decoded fixture content rather than only the selected cycle number.
     */
    static CycleWritePlan prepareSaveCycleBytes(final byte[] current, final int cycle,
        final int cycles, final boolean validateTargetContent) {
        if (current == null) throw new IllegalArgumentException("current PSD bytes are required");
        isFinalRgbMutationCycle(cycle, cycles);
        final Mutation mutation = mutationFor(current, cycle)
            .orElseThrow(() -> new IllegalStateException("PSD layer-name mutation failed"));
        byte[] mutated = applyMutation(current, mutation);
        final boolean finalCycle = validateTargetContent && isFinalRgbMutationCycle(cycle, cycles);
        if (finalCycle) {
            // Apply the decoded RGB mutation once. The overlap write below changes only a layer
            // name on this same byte array, so it retains exactly one content inversion.
            mutated = PsdValidationContent.invertTargetLayerRgb(mutated);
        }
        byte[] overlapFinal = mutated;
        if (cycle == 3) {
            final Mutation secondMutation = mutationFor(mutated, cycle + 100)
                .orElseThrow(() -> new IllegalStateException("Second mutation failed"));
            overlapFinal = applyMutation(mutated, secondMutation);
        }
        return new CycleWritePlan(mutation, mutated, overlapFinal, finalCycle);
    }

    record CycleWritePlan(Mutation mutation, byte[] firstWrite, byte[] overlapFinalWrite,
        boolean targetRgbMutated) {
        CycleWritePlan {
            if (mutation == null || firstWrite == null || overlapFinalWrite == null) {
                throw new IllegalArgumentException("cycle write plan is incomplete");
            }
            firstWrite = firstWrite.clone();
            overlapFinalWrite = overlapFinalWrite.clone();
        }

        @Override public byte[] firstWrite() { return firstWrite.clone(); }
        @Override public byte[] overlapFinalWrite() { return overlapFinalWrite.clone(); }
    }

    static boolean isFinalRgbMutationCycle(final int cycle, final int cycles) {
        if (cycles < 1 || cycle < 1 || cycle > cycles) {
            throw new IllegalArgumentException("cycle must be within a positive cycle count");
        }
        return cycle == cycles;
    }

    private void runCorruptedSave(final Properties result, final PsdEditFile file, final Target target,
        final Path tempFile, final Deque<PsdFileRevision> revisions) throws Exception {
        Files.write(tempFile, "not-a-psd-corrupted-save".getBytes(StandardCharsets.UTF_8));
        final PsdFileRevision revision = awaitRevision(revisions, 20);
        final PsdReplaceResult corrupted = replace(file, target.raw(), revision);
        result.setProperty("corrupted.status", corrupted.status().name());
        result.setProperty("corrupted.diagnostic", corrupted.diagnostic());
        result.setProperty("corrupted.consumed", Boolean.toString(corrupted.consumedRevision().isPresent()));
        if (corrupted.status() == PsdReplaceResult.Status.APPLIED
            || corrupted.consumedRevision().isPresent()) {
            throw new IllegalStateException("Corrupted save was applied or consumed");
        }
        result.setProperty("corrupted.check", "PASS");
    }

    private void runUndoRedo(final Properties result, final Target target,
        final TempTracker tracker, final PsdValidationContent.Fingerprint baselineFingerprint,
        final PsdValidationContent.Fingerprint postFingerprint) throws Exception {
        final String appliedRaw = result.getProperty("applied.currentRaw", target.raw().value());
        final AtomicReference<dev.turboism.sdk.cubism.history.HistoryMoveResult> undo =
            new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> undo.set(context.cubism().history().undo(1)));
        result.setProperty("undo.outcome", undo.get().outcome().name());
        requireHistoryMoved(undo.get(), "undo");
        final String afterUndo = currentRawOnEdt(target.modelImage().value());
        result.setProperty("undo.currentRaw", afterUndo);
        if (baselineFingerprint != null) {
            final PsdValidationContent.Fingerprint undoFingerprint = exportTargetFingerprint(
                result, target, tracker, "undo");
            recordTargetFingerprint(result, "undo.targetRgb", undoFingerprint);
            result.setProperty("undo.targetRgbSha256", undoFingerprint.sha256());
            if (!baselineFingerprint.equals(undoFingerprint)) {
                throw new IllegalStateException(
                    "Undo did not restore the baseline target RGB fingerprint");
            }
        }
        final AtomicReference<dev.turboism.sdk.cubism.history.HistoryMoveResult> redo =
            new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> redo.set(context.cubism().history().redo(1)));
        result.setProperty("redo.outcome", redo.get().outcome().name());
        requireHistoryMoved(redo.get(), "redo");
        final String afterRedo = currentRawOnEdt(target.modelImage().value());
        result.setProperty("redo.currentRaw", afterRedo);
        if (!appliedRaw.equals(afterRedo)) {
            throw new IllegalStateException("Redo did not restore the applied raw identity");
        }
        if (postFingerprint != null) {
            final PsdValidationContent.Fingerprint redoFingerprint = exportTargetFingerprint(
                result, target, tracker, "redo");
            recordTargetFingerprint(result, "redo.targetRgb", redoFingerprint);
            result.setProperty("redo.targetRgbSha256", redoFingerprint.sha256());
            if (!postFingerprint.equals(redoFingerprint)) {
                throw new IllegalStateException(
                    "Redo did not restore the post-edit target RGB fingerprint");
            }
        }
    }

    private void assertNoReplayAfterIdle(final Properties result,
        final Deque<PsdFileRevision> revisions) throws Exception {
        final int before;
        synchronized (revisions) { before = revisions.size(); }
        Thread.sleep(3000);
        final int after;
        synchronized (revisions) { after = revisions.size(); }
        result.setProperty("idle.revisionsBefore", Integer.toString(before));
        result.setProperty("idle.revisionsAfter", Integer.toString(after));
        if (after != before) {
            throw new IllegalStateException("New revisions appeared without an external save");
        }
    }

    private void runStopAndRecovery(final Properties result, final TrackedExport primary,
        final Target target, final Path tempFile, final Deque<PsdFileRevision> revisions,
        final TempTracker tracker)
        throws Exception {
        final PsdFileOperationResult stopResult = tracker.stop(primary, result);
        result.setProperty("stop.status", stopResult.status().name());
        if (stopResult.status() != PsdFileOperationResult.Status.STOPPED) {
            throw new IllegalStateException("Edit handle did not stop cleanly");
        }
        synchronized (revisions) { revisions.clear(); }
        Files.write(tempFile, "post-stop-write".getBytes(StandardCharsets.UTF_8));
        Thread.sleep(4000);
        synchronized (revisions) {
            result.setProperty("stop.postWriteRevisions", Integer.toString(revisions.size()));
            if (!revisions.isEmpty()) {
                throw new IllegalStateException("Stopped handle still published revisions");
            }
        }
        final TrackedExport again = exportTracked(result, target.raw(), tracker, "recovery");
        result.setProperty("recovery.exportStatus", PsdExportResult.Status.EXPORTED.name());
        final Path secondFile = again.path();
        result.setProperty("recovery.newFile", Boolean.toString(!secondFile.equals(tempFile)));
        final PsdFileOperationResult secondStop = tracker.stop(again, result);
        result.setProperty("recovery.stopStatus", secondStop.status().name());
        if (secondStop.status() != PsdFileOperationResult.Status.STOPPED) {
            throw new IllegalStateException("Recovered handle did not stop cleanly");
        }
    }

    private PsdReplaceResult replace(final PsdEditFile file, final RawImageId raw,
        final PsdFileRevision revision) throws Exception {
        final AtomicReference<CompletionStage<PsdReplaceResult>> stage = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                if (stopped) throw new IllegalStateException("Probe stopped");
                stage.set(context.cubism().model().active().textures()
                    .replaceRawImagePsd(raw, file, revision));
            } catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new IllegalStateException("Replace invocation failed", failure.get());
        return stage.get().toCompletableFuture().get(120, TimeUnit.SECONDS);
    }

    private String currentRawOnEdt(final String modelImageId) throws Exception {
        final AtomicReference<String> current = new AtomicReference<>("");
        SwingUtilities.invokeAndWait(() -> {
            final TextureRelationsSnapshot relations =
                context.cubism().model().active().textures().relations();
            relations.modelImages().stream()
                .filter(image -> image.id().value().equals(modelImageId))
                .findFirst()
                .flatMap(image -> image.currentRawImageId())
                .ifPresent(raw -> current.set(raw.value()));
        });
        return current.get();
    }

    private void assertNoRevision(final Deque<PsdFileRevision> revisions, final long millis,
        final String message) throws Exception {
        synchronized (revisions) {
            final long deadline = System.currentTimeMillis() + millis;
            while (revisions.isEmpty() && System.currentTimeMillis() < deadline) {
                revisions.wait(200);
            }
            if (!revisions.isEmpty()) throw new IllegalStateException(message);
        }
    }

    private PsdFileRevision awaitRevision(final Deque<PsdFileRevision> revisions,
        final int timeoutSeconds) throws Exception {
        synchronized (revisions) {
            final long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
            while (revisions.isEmpty() && System.currentTimeMillis() < deadline && !stopped) {
                revisions.wait(250);
            }
            if (revisions.isEmpty()) {
                throw new IllegalStateException("Stable save revision not delivered within "
                    + timeoutSeconds + "s");
            }
            return revisions.poll();
        }
    }

    private static String sha256(final byte[] bytes) throws Exception {
        final byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        final StringBuilder hex = new StringBuilder();
        for (final byte b : digest) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    /**
     * Returns a copy of {@code psd} with one ASCII letter substituted into a layer's Pascal
     * name, deterministic per {@code cycle}. The file stays structurally valid: same length,
     * same layout, only name bytes change. Returns empty when no mutable name exists.
     */
    static Optional<byte[]> mutateLayerName(final byte[] psd, final int cycle) {
        return mutationFor(psd, cycle).map(mutation -> applyMutation(psd, mutation));
    }

    /** Deterministic per-cycle mutation coordinates over the parsed layer-name table. */
    static Optional<Mutation> mutationFor(final byte[] psd, final int cycle) {
        final List<int[]> names = layerNameRanges(psd);
        if (names.isEmpty()) return Optional.empty();
        final int layer = (cycle - 1) % names.size();
        final int[] range = names.get(layer);
        final int nameOffset = range[1] > 1 ? (cycle - 1) / names.size() % range[1] : 0;
        byte replacement = (byte) ('a' + (cycle - 1) % 26);
        if (replacement == psd[range[0] + nameOffset]) {
            replacement = (byte) (replacement == 'a' ? 'b' : 'a');
        }
        return Optional.of(new Mutation(layer, nameOffset, (char) replacement));
    }

    private static byte[] applyMutation(final byte[] psd, final Mutation mutation) {
        final int[] range = layerNameRanges(psd).get(mutation.layer());
        final byte[] copy = psd.clone();
        copy[range[0] + mutation.nameOffset()] = (byte) mutation.letter();
        return copy;
    }

    /** (layerIndex, byteOffsetWithinName, letter) of one name-byte substitution. */
    record Mutation(int layer, int nameOffset, char letter) {
    }

    /** (nameByteStart, nameByteLength) per layer record; empty on any structural anomaly. */
    static List<int[]> layerNameRanges(final byte[] psd) {
        final List<int[]> names = new ArrayList<>();
        try {
            if (psd.length < 30 || psd[0] != '8' || psd[1] != 'B'
                || psd[2] != 'P' || psd[3] != 'S') return names;
            final ByteBuffer buffer = ByteBuffer.wrap(psd).order(ByteOrder.BIG_ENDIAN);
            buffer.position(26);
            buffer.position(buffer.position() + 4 + buffer.getInt(buffer.position()));
            buffer.position(buffer.position() + 4 + buffer.getInt(buffer.position()));
            final int layerMaskStart = buffer.position();
            final int layerMaskLength = buffer.getInt();
            if (layerMaskLength < 4) return names;
            buffer.position(layerMaskStart + 4);
            final int layerInfoLength = buffer.getInt();
            if (layerInfoLength < 2) return names;
            int count = Math.abs(buffer.getShort());
            for (int layer = 0; layer < count && buffer.remaining() > 0; layer++) {
                buffer.position(buffer.position() + 16);
                final int channels = Short.toUnsignedInt(buffer.getShort());
                buffer.position(buffer.position() + channels * 6);
                buffer.position(buffer.position() + 12); // blend sig+key, opacity, clipping, flags, filler
                final int extraStart = buffer.position();
                final int extraLength = buffer.getInt();
                final int extraDataStart = extraStart + 4;
                buffer.position(extraDataStart);
                final int maskLength = buffer.getInt();
                buffer.position(buffer.position() + maskLength);
                final int rangesLength = buffer.getInt();
                buffer.position(buffer.position() + rangesLength);
                final int nameStart = buffer.position();
                final int nameLength = Byte.toUnsignedInt(buffer.get());
                if (nameLength > 0) names.add(new int[] {nameStart + 1, nameLength});
                // All layer records are contiguous; channel image data follows the last record.
                buffer.position(extraDataStart + extraLength);
            }
        } catch (RuntimeException malformed) {
            names.clear();
        }
        return names;
    }

    /**
     * Checks PSD section and channel boundaries without decoding or comparing any pixel sample.
     * This is the GUI session-file readiness gate; it deliberately supports only the observed
     * RGB PSD forms (8-bit raw or PackBits/RLE) and fails closed for other formats.
     */
    static PsdStructureValidation inspectPsdStructure(final byte[] psd) {
        if (psd == null) return PsdStructureValidation.invalid(
            "file", 0, "PSD input is null");
        try {
            final StructureCursor file = new StructureCursor(psd, 0, psd.length);
            file.require(26, "header");
            if (!file.ascii(4, "header signature").equals("8BPS")) {
                throw structureInvalid("header", 0, "unsupported PSD signature");
            }
            final int version = file.u16("header version");
            if (version != 1) {
                throw structureInvalid("header", 4,
                    "unsupported PSD version " + version);
            }
            file.skip(6, "header reserved bytes");
            final int channels = file.u16("header channel count");
            final int height = file.u32("header height");
            final int width = file.u32("header width");
            final int depth = file.u16("header depth");
            final int colorMode = file.u16("header color mode");
            if (channels <= 0 || channels > 64) {
                throw structureInvalid("header", 12,
                    "unsupported channel count " + channels);
            }
            if (width <= 0 || height <= 0) {
                throw structureInvalid("header", 14,
                    "non-positive canvas " + width + "x" + height);
            }
            if (depth != 8) {
                throw structureInvalid("header", 22,
                    "unsupported bit depth " + depth + "; expected 8");
            }
            if (colorMode != 3) {
                throw structureInvalid("header", 24,
                    "unsupported color mode " + colorMode + "; expected RGB");
            }

            readStructureSection(file, "color mode data");
            // The section boundary is authoritative; its payload is not used for target choice.
            final StructureSection resources = readStructureSection(file,
                "image resources");
            validateImageResourcesShape(new StructureCursor(psd, resources.start(),
                resources.end()), "image resources");

            final StructureSection layerMask = readStructureSection(file,
                "layer and mask");
            final StructureCursor layerMaskCursor = new StructureCursor(
                psd, layerMask.start(), layerMask.end());
            final StructureSection layerInfo = readStructureSection(layerMaskCursor,
                "layer info");
            final StructureCursor layerInfoCursor = new StructureCursor(
                psd, layerInfo.start(), layerInfo.end());
            final int encodedLayerCount = layerInfoCursor.s16("layer count");
            final int layerCount = Math.abs(encodedLayerCount);
            final List<LayerShape> layers = new ArrayList<>(layerCount);
            for (int layer = 0; layer < layerCount; layer++) {
                final int top = layerInfoCursor.s32("layer " + layer + " top");
                final int left = layerInfoCursor.s32("layer " + layer + " left");
                final int bottom = layerInfoCursor.s32("layer " + layer + " bottom");
                final int right = layerInfoCursor.s32("layer " + layer + " right");
                if (top < 0 || left < 0 || bottom <= top || right <= left
                    || bottom > height || right > width) {
                    throw structureInvalid("layer info", layerInfoCursor.position(),
                        "layer " + layer + " has invalid bounds");
                }
                final int channelCount = layerInfoCursor.u16(
                    "layer " + layer + " channel count");
                if (channelCount > 64) {
                    throw structureInvalid("layer info", layerInfoCursor.position(),
                        "layer " + layer + " has unsupported channel count " + channelCount);
                }
                final List<Integer> channelLengths = new ArrayList<>(channelCount);
                for (int channel = 0; channel < channelCount; channel++) {
                    layerInfoCursor.s16("layer " + layer + " channel " + channel + " id");
                    final int length = layerInfoCursor.u32(
                        "layer " + layer + " channel " + channel + " length");
                    if (length < 2) {
                        throw structureInvalid("layer info", layerInfoCursor.position(),
                            "layer " + layer + " channel " + channel
                                + " is shorter than compression field");
                    }
                    channelLengths.add(length);
                }
                final String signature = layerInfoCursor.ascii(4,
                    "layer " + layer + " blend signature");
                if (!signature.equals("8BIM") && !signature.equals("8B64")) {
                    throw structureInvalid("layer info", layerInfoCursor.position() - 4,
                        "layer " + layer + " has unsupported blend signature " + signature);
                }
                layerInfoCursor.skip(4, "layer " + layer + " blend key");
                layerInfoCursor.skip(4, "layer " + layer + " blend attributes");
                final int extraLength = layerInfoCursor.u32(
                    "layer " + layer + " extra data length");
                final int extraStart = layerInfoCursor.position();
                final int extraEnd = layerInfoCursor.endFor(extraLength,
                    "layer " + layer + " extra data");
                validateLayerExtraShape(new StructureCursor(psd, extraStart, extraEnd),
                    "layer " + layer + " extra data");
                layerInfoCursor.position(extraEnd);
                layers.add(new LayerShape(top, left, bottom, right, channelLengths));
            }
            for (int layer = 0; layer < layers.size(); layer++) {
                final LayerShape shape = layers.get(layer);
                final int layerWidth = shape.right() - shape.left();
                final int layerHeight = shape.bottom() - shape.top();
                for (int channel = 0; channel < shape.channelLengths().size(); channel++) {
                    final int length = shape.channelLengths().get(channel);
                    final int start = layerInfoCursor.position();
                    final int end = layerInfoCursor.endFor(length,
                        "layer " + layer + " channel " + channel + " data");
                    validateChannelShape(new StructureCursor(psd, start, end), layerWidth,
                        layerHeight, depth, "layer " + layer + " channel " + channel);
                    layerInfoCursor.position(end);
                }
            }
            if (layerInfoCursor.remaining() != 0) {
                throw structureInvalid("layer info", layerInfoCursor.position(),
                    "unread layer-info bytes " + layerInfoCursor.remaining());
            }
            layerMaskCursor.position(layerInfo.end());
            final int globalMaskLength = layerMaskCursor.u32("global layer mask length");
            layerMaskCursor.skip(globalMaskLength, "global layer mask data");
            validateAdditionalInfoShape(layerMaskCursor, "global layer additional info");
            if (layerMaskCursor.remaining() != 0) {
                throw structureInvalid("layer and mask", layerMaskCursor.position(),
                    "unread layer/mask bytes " + layerMaskCursor.remaining());
            }

            validateCompositeShape(file, width, height, depth, channels);
            if (file.remaining() != 0) {
                throw structureInvalid("composite", file.position(),
                    "trailing bytes after composite image " + file.remaining());
            }
            return PsdStructureValidation.valid(psd.length, layerCount, file.position());
        } catch (StructureFailure failure) {
            return PsdStructureValidation.invalid(failure.section(), failure.offset(),
                failure.reason());
        } catch (RuntimeException failure) {
            return PsdStructureValidation.invalid("file", -1, failure.toString());
        }
    }

    private static StructureSection readStructureSection(final StructureCursor cursor,
        final String section) {
        final int length = cursor.u32(section + " length");
        final int start = cursor.position();
        final int end = cursor.endFor(length, section);
        cursor.position(end);
        return new StructureSection(start, end);
    }

    private static void validateImageResourcesShape(final StructureCursor resources,
        final String section) {
        while (resources.remaining() > 0) {
            if (resources.remaining() < 12) {
                throw structureInvalid(section, resources.position(),
                    "truncated resource header");
            }
            final String signature = resources.ascii(4, section + " signature");
            if (!signature.equals("8BIM") && !signature.equals("MeSa")) {
                throw structureInvalid(section, resources.position() - 4,
                    "unsupported resource signature " + signature);
            }
            resources.skip(2, section + " resource id");
            final int nameLength = resources.u8(section + " resource name length");
            final int paddedNameLength = (nameLength + 1 + 1) & ~1;
            resources.skip(paddedNameLength - 1, section + " resource name");
            final int dataLength = resources.u32(section + " resource data length");
            resources.skip(dataLength, section + " resource data");
            if ((dataLength & 1) != 0) resources.skip(1, section + " resource padding");
        }
    }

    private static void validateLayerExtraShape(final StructureCursor extra,
        final String section) {
        final int maskLength = extra.u32(section + " mask length");
        extra.skip(maskLength, section + " mask");
        final int blendingRangesLength = extra.u32(section + " blending ranges length");
        extra.skip(blendingRangesLength, section + " blending ranges");
        final int nameLength = extra.u8(section + " name length");
        final int paddedNameLength = (nameLength + 1 + 3) & ~3;
        extra.skip(paddedNameLength - 1, section + " name");
        validateAdditionalInfoShape(extra, section + " additional info");
        if (extra.remaining() != 0) {
            throw structureInvalid(section, extra.position(),
                "unread layer-extra bytes " + extra.remaining());
        }
    }

    private static void validateAdditionalInfoShape(final StructureCursor section,
        final String label) {
        while (section.remaining() > 0) {
            if (section.remaining() < 12) {
                throw structureInvalid(label, section.position(),
                    "truncated additional-info header");
            }
            final String signature = section.ascii(4, label + " signature");
            if (!signature.equals("8BIM") && !signature.equals("8B64")) {
                throw structureInvalid(label, section.position() - 4,
                    "unsupported additional-info signature " + signature);
            }
            section.skip(4, label + " key");
            final int length = section.u32(label + " block length");
            section.skip(length, label + " block");
        }
    }

    private static void validateChannelShape(final StructureCursor channel, final int width,
        final int height, final int depth, final String section) {
        final int compression = channel.u16(section + " compression");
        if (compression == 0) {
            final long samples = (long) width * height * (depth / 8);
            if (samples > Integer.MAX_VALUE) {
                throw structureInvalid(section, channel.position(), "raw channel is too large");
            }
            channel.skip((int) samples, section + " raw samples");
            if (channel.remaining() != 0) {
                throw structureInvalid(section, channel.position(),
                    "raw channel has unread bytes " + channel.remaining());
            }
            return;
        }
        if (compression != 1) {
            throw structureInvalid(section, channel.position() - 2,
                "unsupported compression " + compression + "; expected raw or RLE1");
        }
        validateRleShape(channel, width, height, section);
    }

    private static void validateCompositeShape(final StructureCursor file, final int width,
        final int height, final int depth, final int channels) {
        final String section = "composite";
        final int compression = file.u16(section + " compression");
        if (compression == 0) {
            final long samples = (long) width * height * channels * (depth / 8);
            if (samples > Integer.MAX_VALUE) {
                throw structureInvalid(section, file.position(), "raw composite is too large");
            }
            file.skip((int) samples, section + " raw samples");
            if (file.remaining() != 0) {
                throw structureInvalid(section, file.position(),
                    "raw composite has unread bytes " + file.remaining());
            }
            return;
        }
        if (compression != 1) {
            throw structureInvalid(section, file.position() - 2,
                "unsupported compression " + compression + "; expected raw or RLE1");
        }
        validateRleShape(file, width, channels * height, section);
    }

    private static void validateRleShape(final StructureCursor channel, final int width,
        final int rows, final String section) {
        if (rows <= 0) throw structureInvalid(section, channel.position(), "no RLE rows");
        if (rows > Integer.MAX_VALUE / 2) {
            throw structureInvalid(section, channel.position(), "RLE row table is too large");
        }
        channel.require(rows * 2, section + " row-length table");
        final int[] lengths = new int[rows];
        for (int row = 0; row < rows; row++) lengths[row] = channel.u16(
            section + " row " + row + " length");
        for (int row = 0; row < rows; row++) {
            final int start = channel.position();
            final int end = channel.endFor(lengths[row], section + " row " + row + " data");
            validatePackBitsRow(channel.bytes(), start, end, width,
                section + " row " + row);
            channel.position(end);
        }
        if (channel.remaining() != 0) {
            throw structureInvalid(section, channel.position(),
                "unread RLE bytes " + channel.remaining());
        }
    }

    private static void validatePackBitsRow(final byte[] bytes, final int start,
        final int end, final int expectedSamples, final String section) {
        int position = start;
        int decoded = 0;
        while (position < end) {
            final int control = bytes[position++] & 0xff;
            if (control == 0x80) continue;
            final int count;
            if (control <= 0x7f) {
                count = control + 1;
                if (count > end - position) {
                    throw structureInvalid(section, position,
                        "literal packet overruns row");
                }
                position += count;
            } else {
                count = 257 - control;
                if (position >= end) {
                    throw structureInvalid(section, position,
                        "repeat packet has no sample");
                }
                position++;
            }
            if (count > expectedSamples - decoded) {
                throw structureInvalid(section, position,
                    "packet decodes beyond row width");
            }
            decoded += count;
        }
        if (decoded != expectedSamples) {
            throw structureInvalid(section, end,
                "decoded " + decoded + " samples; expected " + expectedSamples);
        }
    }

    private static StructureFailure structureInvalid(final String section, final int offset,
        final String reason) {
        return new StructureFailure(section, offset, reason);
    }

    /** SHA-256 over the PSD image-data section (everything after the layer-and-mask record). */
    static String imageDataSha256(final byte[] psd) throws Exception {
        final ByteBuffer buffer = ByteBuffer.wrap(psd).order(ByteOrder.BIG_ENDIAN);
        if (psd.length < 30 || psd[0] != '8' || psd[1] != 'B'
            || psd[2] != 'P' || psd[3] != 'S') {
            throw new IllegalStateException("not a PSD: cannot locate image data section");
        }
        buffer.position(26);
        buffer.position(buffer.position() + 4 + buffer.getInt(buffer.position()));
        buffer.position(buffer.position() + 4 + buffer.getInt(buffer.position()));
        buffer.position(buffer.position() + 4 + buffer.getInt(buffer.position()));
        return sha256(java.util.Arrays.copyOfRange(psd, buffer.position(), psd.length));
    }

    private void recordEnvironment(final Properties result) throws Exception {
        final Runtime runtime = Runtime.getRuntime();
        result.setProperty("env.processors", Integer.toString(runtime.availableProcessors()));
        result.setProperty("env.heapMaxBytes", Long.toString(runtime.maxMemory()));
        result.setProperty("env.heapUsedBytes",
            Long.toString(runtime.totalMemory() - runtime.freeMemory()));
        final long start = System.nanoTime();
        SwingUtilities.invokeAndWait(() -> { });
        result.setProperty("env.edtDispatchMs",
            Long.toString((System.nanoTime() - start) / 1_000_000));
    }

    /**
     * Mediated persistence: a fixed-grant write handle → typed SAVE_AS → the native
     * {@code saveDocument} hook must surface a {@link ProjectFileOperationType#SAVE} After
     * event. The granted target lives outside the task fixture copy, so the runner's
     * fixture-unchanged guarantee still holds.
     */
    private void runPersistTail(final Properties result, final Target target,
        final TempTracker tracker, final PsdValidationContent.Fingerprint baselineFingerprint,
        final PsdValidationContent.Fingerprint postBeforeUndo) throws Exception {
        result.setProperty("persist.saveSucceeded", "false");
        result.setProperty("persist.targetContentChanged", "false");
        result.setProperty("persist.tempQuarantine.status", "NOT_ATTEMPTED");
        result.setProperty("persist.tempQuarantine.taskOwned", "false");
        result.setProperty("persist.tempQuarantine.sourceMissing", "false");

        // Undo/Redo must leave the same native post state that will be saved. Re-export it once
        // immediately before SAVE_AS, so the persistence gate does not trust staged PSD bytes or
        // a history/raw identity alone.
        final PsdValidationContent.Fingerprint preSavePost = exportTargetFingerprint(
            result, target, tracker, "postBeforeSave");
        recordTargetFingerprint(result, "persist.preSavePostEditTargetRgb", preSavePost);
        result.setProperty("persist.preSavePostEditTargetRgbSha256", preSavePost.sha256());
        if (!postBeforeUndo.equals(preSavePost)) {
            throw new IllegalStateException(
                "fresh post-edit target RGB fingerprint changed before SAVE_AS");
        }
        requireChangedPost(baselineFingerprint, preSavePost);

        final List<ProjectFileLifecycleEvent.After> saves = new CopyOnWriteArrayList<>();
        final AtomicInteger beforeEvents = new AtomicInteger();
        final AtomicInteger onEvents = new AtomicInteger();
        final Registration subscription = context.eventBus().subscribe(
            ProjectFileLifecycleEvent.After.class,
            event -> {
                if (event.operation().operation() == ProjectFileOperationType.SAVE) {
                    saves.add(event);
                }
            });
        final Registration beforeSub = context.eventBus().subscribe(
            ProjectFileLifecycleEvent.Before.class,
            event -> beforeEvents.incrementAndGet());
        final Registration onSub = context.eventBus().subscribe(
            ProjectFileLifecycleEvent.On.class,
            event -> onEvents.incrementAndGet());
        try {
            final UserFileRequestResult granted = context.userFiles().request(
                new UserFileRequest(
                    "external-psd-persist",
                    "Persist edited document",
                    List.of("cmo3"),
                    UserFileMode.WRITE,
                    UserFileLifetime.ONE_OPERATION))
                .toCompletableFuture().get(60, TimeUnit.SECONDS);
            result.setProperty("persist.grant.status", granted.status().name());
            final UserFileHandle handle = granted.handle().orElseThrow(() ->
                new IllegalStateException("No write grant issued: " + granted.status()));
            final Set<java.awt.Window> baselineWindows = Set.of(java.awt.Window.getWindows());
            final DialogAnswerWatcher watcher = new DialogAnswerWatcher(baselineWindows);
            final EditorCommandResult saved;
            try {
                saved = context.editorCommands().execute(
                    new EditorFileCommandRequest(
                        EditorFileCommand.SAVE_AS, handle, EditorOverwritePolicy.REPLACE_EXISTING));
            } finally {
                watcher.close();
            }
            result.setProperty("persist.saveAs.status", saved.status().name());
            result.setProperty("persist.saveAs.executed", Boolean.toString(saved.executed()));
            if (!watcher.actions.isEmpty()) {
                result.setProperty("persist.dialogActions", watcher.actions.toString());
            }
            if (!saved.executed()) {
                // A blocked native save leaves its modal dialog open; record which windows
                // are up so the failure identifies the blocker instead of a bare FAILED.
                result.setProperty("persist.openWindows", describeWindows());
                throw new IllegalStateException("SAVE_AS did not execute: " + saved.status());
            }
            final long deadline = System.currentTimeMillis() + 15_000;
            while (saves.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(200);
            }
            result.setProperty("persist.saveEvents", Integer.toString(saves.size()));
            result.setProperty("persist.beforeEvents", Integer.toString(beforeEvents.get()));
            result.setProperty("persist.onEvents", Integer.toString(onEvents.get()));
            if (saves.isEmpty()) {
                throw new IllegalStateException("SAVE lifecycle event not observed");
            }
            final var saveResult = saves.get(0).result();
            result.setProperty("persist.saveSucceeded",
                Boolean.toString(saveResult.succeeded()));
            result.setProperty("persist.savedFile",
                saveResult.request().fileName().orElse(""));
            if (!saveResult.succeeded()) {
                throw new IllegalStateException("SAVE lifecycle completed without success");
            }
            result.setProperty("persist.saveSucceeded", "true");
            result.setProperty("documentPersistence", "SAVE_AS executed + SAVE event confirmed");

            // A second fresh native export after SAVE_AS is the durable post fingerprint. The
            // legacy full-file/composite values are retained only as optional diagnostics.
            requireTargetBinding(result, target,
                "export.postAfterSave.targetBinding.before", 0L);
            final TrackedExport postExport = exportTracked(
                result, target.raw(), tracker, "postAfterSave");
            Throwable postExportFailure = null;
            try {
                requireTargetBinding(result, target,
                    "export.postAfterSave.targetBinding.after", 0L);
                final byte[] postBytes = Files.readAllBytes(postExport.path());
                final PsdValidationContent.Fingerprint post = targetFingerprint(
                    postBytes, "persist post-save target");
                recordTargetFingerprint(result, "persist.postEditTargetRgb", post);
                result.setProperty("persist.postEditTargetRgbSha256", post.sha256());
                result.setProperty("persist.postEditBytes", Integer.toString(postBytes.length));
                result.setProperty("persist.postEditSha256", sha256(postBytes));
                result.setProperty("persist.postEditImageSha256", imageDataSha256(postBytes));
                if (!preSavePost.equals(post)) {
                    throw new IllegalStateException(
                        "fresh post-save target RGB fingerprint differs from pre-SAVE_AS state");
                }
                requireChangedPost(baselineFingerprint, post);
                result.setProperty("persist.targetContentChanged", "true");
            } catch (Exception failure) {
                postExportFailure = failure;
                throw failure;
            } catch (Error failure) {
                postExportFailure = failure;
                throw failure;
            } finally {
                stopPreservingPrimary(tracker, postExport, result, postExportFailure);
            }
            tracker.quarantine(result, context.paths().stateDir());
            requirePersistEvidence(result);
        } finally {
            subscription.close();
            beforeSub.close();
            onSub.close();
        }
    }

    /**
     * Stops every tracked export while preserving an already-failing phase as the primary cause.
     * Cleanup failures are suppressed on that cause; with no primary failure they fail the phase.
     */
    static void stopAllPreservingPrimary(final TempTracker tracker, final Properties result,
        final Throwable primary) throws Exception {
        try {
            tracker.stopAll(result);
        } catch (Throwable cleanup) {
            if (primary != null) {
                addSuppressed(primary, cleanup);
                return;
            }
            rethrowCleanup(cleanup);
        }
    }

    private static void stopPreservingPrimary(final TempTracker tracker,
        final TrackedExport export, final Properties result, final Throwable primary)
        throws Exception {
        try {
            tracker.stop(export, result);
        } catch (Throwable cleanup) {
            if (primary != null) {
                addSuppressed(primary, cleanup);
                return;
            }
            rethrowCleanup(cleanup);
        }
    }

    private static void addSuppressed(final Throwable primary, final Throwable cleanup) {
        if (primary != cleanup) primary.addSuppressed(cleanup);
    }

    private static void rethrowCleanup(final Throwable failure) throws Exception {
        if (failure instanceof Error error) throw error;
        if (failure instanceof Exception exception) throw exception;
        throw new IllegalStateException("cleanup failed", failure);
    }

    private record TempCandidateSnapshot(Path root, Set<Path> directories) {
        private TempCandidateSnapshot {
            root = root.toAbsolutePath().normalize();
            directories = Set.copyOf(directories);
        }
    }

    @FunctionalInterface
    private interface CandidateSnapshotSupplier {
        TempCandidateSnapshot snapshot() throws Exception;
    }

    static final class SessionFileReadinessException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        SessionFileReadinessException(final String message) { super(message); }
        SessionFileReadinessException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }

    static record StablePsdSnapshot(Path path, byte[] bytes, FileObservation observation) {
        StablePsdSnapshot {
            Objects.requireNonNull(path, "stable PSD path");
            Objects.requireNonNull(bytes, "stable PSD bytes");
            Objects.requireNonNull(observation, "stable PSD observation");
            bytes = bytes.clone();
        }

        @Override public byte[] bytes() { return bytes.clone(); }
    }

    static record FileObservation(FileIdentity identity, String sha256,
        PsdStructureValidation structure) {
        FileObservation {
            Objects.requireNonNull(identity, "file identity");
            sha256 = sha256 == null ? "" : sha256;
            Objects.requireNonNull(structure, "PSD structure observation");
        }

        long size() { return identity.size(); }

        long modifiedMillis() { return identity.modifiedMillis(); }

        String realPath() { return identity.realPath().toString(); }

        String fileKeyStatus() { return identity.fileKeyStatus(); }

        boolean identityVerified() { return identity.identityVerified(); }
    }

    private record FileIdentity(Path realPath, long size, long modifiedMillis, Object fileKey) {
        FileIdentity {
            Objects.requireNonNull(realPath, "real path");
        }

        String fileKeyStatus() { return fileKey == null ? "UNAVAILABLE" : "VERIFIED"; }

        boolean identityVerified() { return fileKey != null; }

        String diagnostic() {
            return "path=" + realPath + " size=" + size + " mtime=" + modifiedMillis
                + " fileKeyStatus=" + fileKeyStatus()
                + " identityVerified=" + identityVerified()
                + " fileKey=" + safeDiagnostic(fileKey == null ? "<unavailable>"
                    : String.valueOf(fileKey));
        }
    }

    private record SessionFileRead(String state, byte[] bytes, FileObservation observation,
        String diagnostic) {
        SessionFileRead {
            state = state == null ? "UNKNOWN" : state;
            bytes = bytes == null ? null : bytes.clone();
            diagnostic = diagnostic == null ? "" : diagnostic;
        }

        @Override public byte[] bytes() { return bytes == null ? null : bytes.clone(); }

        boolean complete() { return "COMPLETE".equals(state); }

        static SessionFileRead failed(final String state, final String diagnostic) {
            return new SessionFileRead(state, null, null, diagnostic);
        }

        static SessionFileRead replaced(final FileIdentity identity, final String diagnostic) {
            final PsdStructureValidation structure = PsdStructureValidation.invalid(
                "file", -1, "file identity changed");
            return new SessionFileRead("REPLACED", null,
                identity == null ? null : new FileObservation(identity, "", structure),
                diagnostic);
        }

        static SessionFileRead replaced(final FileObservation observation,
            final String diagnostic) {
            return new SessionFileRead("REPLACED", null, observation, diagnostic);
        }
    }

    static record PsdStructureValidation(boolean complete, int bytes, int layerCount,
        int compositeEnd, String section, int offset, String reason) {
        PsdStructureValidation {
            section = section == null ? "file" : section;
            reason = reason == null ? "" : reason;
        }

        static PsdStructureValidation valid(final int bytes, final int layerCount,
            final int compositeEnd) {
            return new PsdStructureValidation(true, bytes, layerCount, compositeEnd,
                "complete", -1, "complete PSD section/channel/composite boundaries");
        }

        static PsdStructureValidation invalid(final String section, final int offset,
            final String reason) {
            return new PsdStructureValidation(false, -1, -1, -1, section, offset, reason);
        }
    }

    private record StructureSection(int start, int end) {
    }

    private record LayerShape(int top, int left, int bottom, int right,
        List<Integer> channelLengths) {
        LayerShape {
            channelLengths = List.copyOf(channelLengths);
        }
    }

    private static final class StructureFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String section;
        private final int offset;
        private final String reason;

        StructureFailure(final String section, final int offset, final String reason) {
            super(section + " at offset " + offset + ": " + reason);
            this.section = section;
            this.offset = offset;
            this.reason = reason;
        }

        String section() { return section; }
        int offset() { return offset; }
        String reason() { return reason; }
    }

    private static final class StructureCursor {
        private final byte[] bytes;
        private final int limit;
        private int position;

        StructureCursor(final byte[] bytes, final int start, final int limit) {
            if (bytes == null || start < 0 || limit < start || limit > bytes.length) {
                throw new IllegalArgumentException("invalid PSD structure cursor");
            }
            this.bytes = bytes;
            this.position = start;
            this.limit = limit;
        }

        byte[] bytes() { return bytes; }
        int position() { return position; }
        int remaining() { return limit - position; }

        void position(final int next) {
            if (next < 0 || next > limit) {
                throw structureInvalid("file", position,
                    "cursor position escaped section: " + next);
            }
            position = next;
        }

        void require(final int count, final String section) {
            if (count < 0 || count > remaining()) {
                throw structureInvalid(section, position,
                    "truncated; need " + count + " bytes, remaining " + remaining());
            }
        }

        int endFor(final int length, final String section) {
            if (length < 0 || length > remaining()) {
                throw structureInvalid(section, position,
                    "declared length " + length + " exceeds remaining " + remaining());
            }
            return position + length;
        }

        void skip(final int count, final String section) {
            require(count, section);
            position += count;
        }

        int u8(final String section) {
            require(1, section);
            return bytes[position++] & 0xff;
        }

        int u16(final String section) {
            require(2, section);
            final int value = ((bytes[position] & 0xff) << 8)
                | (bytes[position + 1] & 0xff);
            position += 2;
            return value;
        }

        int s16(final String section) { return (short) u16(section); }

        int s32(final String section) {
            require(4, section);
            final int value = ((bytes[position] & 0xff) << 24)
                | ((bytes[position + 1] & 0xff) << 16)
                | ((bytes[position + 2] & 0xff) << 8)
                | (bytes[position + 3] & 0xff);
            position += 4;
            return value;
        }

        int u32(final String section) {
            final int value = s32(section);
            if (value < 0) {
                throw structureInvalid(section, position - 4,
                    "unsigned 32-bit length exceeds supported bounds");
            }
            return value;
        }

        String ascii(final int length, final String section) {
            require(length, section);
            final String value = new String(bytes, position, length,
                StandardCharsets.US_ASCII);
            position += length;
            return value;
        }
    }

    static final class TrackedExport {
        private final String label;
        private final PsdEditFile file;
        private Path path;
        private PsdFileOperationResult stopResult;
        private Throwable stopFailure;
        private boolean stopAttempted;
        private long exportStartedAtEpochMillis;
        private long exportCompletedAtEpochMillis;

        private TrackedExport(final String label, final PsdEditFile file) {
            this.label = label;
            this.file = file;
        }

        private PsdEditFile file() { return file; }
        private Path path() { return path; }
        private void setPath(final Path path) { this.path = path; }
        private void setExportTimes(final long startedAtEpochMillis,
            final long completedAtEpochMillis) {
            exportStartedAtEpochMillis = startedAtEpochMillis;
            exportCompletedAtEpochMillis = completedAtEpochMillis;
        }
        private long exportStartedAtEpochMillis() { return exportStartedAtEpochMillis; }
        private long exportCompletedAtEpochMillis() { return exportCompletedAtEpochMillis; }
        private Path directory() { return path == null ? null : path.getParent(); }
    }

    static final class TempTracker {
        private final List<TrackedExport> exports = new ArrayList<>();
        private Path tempRoot;

        TrackedExport register(final String label, final PsdEditFile file,
            final Path root) {
            if (label == null || label.isBlank() || file == null || root == null) {
                throw new IllegalArgumentException("tracked export identity is incomplete");
            }
            if (exports.stream().anyMatch(export -> export.label.equals(label))) {
                throw new IllegalArgumentException("duplicate tracked export label " + label);
            }
            final Path normalizedRoot = root.toAbsolutePath().normalize();
            if (tempRoot == null) tempRoot = normalizedRoot;
            if (!tempRoot.equals(normalizedRoot)) {
                throw new IllegalStateException("native exports changed task temp root");
            }
            final TrackedExport tracked = new TrackedExport(label, file);
            exports.add(tracked);
            return tracked;
        }

        private PsdFileOperationResult stop(final TrackedExport tracked,
            final Properties result) throws Exception {
            if (!exports.contains(tracked)) {
                throw new IllegalArgumentException("unregistered export handle");
            }
            if (tracked.stopAttempted) {
                if (tracked.stopFailure != null) {
                    throw asException(tracked.stopFailure);
                }
                if (tracked.stopResult == null) {
                    throw new IllegalStateException("tracked stop has no result");
                }
                return tracked.stopResult;
            }
            tracked.stopAttempted = true;
            try {
                final PsdFileOperationResult stopped = tracked.file.stop()
                    .toCompletableFuture().get(60, TimeUnit.SECONDS);
                tracked.stopResult = stopped;
                result.setProperty("export." + tracked.label + ".stopStatus",
                    stopped.status().name());
                result.setProperty("export." + tracked.label + ".stopDiagnostic",
                    stopped.diagnostic());
                if (stopped.status() != PsdFileOperationResult.Status.STOPPED) {
                    throw new IllegalStateException("export handle did not stop: "
                        + tracked.label + " status=" + stopped.status());
                }
                return stopped;
            } catch (Throwable failure) {
                tracked.stopFailure = failure;
                result.setProperty("export." + tracked.label + ".stopStatus", "FAILED");
                result.setProperty("export." + tracked.label + ".stopDiagnostic",
                    failure.toString());
                rethrowCleanup(failure);
                return null;
            }
        }

        void stopAll(final Properties result) throws Exception {
            final List<Throwable> failures = new ArrayList<>();
            for (final TrackedExport tracked : exports) {
                try {
                    stop(tracked, result);
                } catch (Throwable failure) {
                    result.setProperty("export." + tracked.label + ".stopStatus", "FAILED");
                    result.setProperty("export." + tracked.label + ".stopDiagnostic",
                        failure.toString());
                    failures.add(failure);
                }
            }
            if (!failures.isEmpty()) {
                Throwable selected = failures.stream()
                    .filter(failure -> failure instanceof Error)
                    .findFirst()
                    .orElse(failures.get(0));
                for (final Throwable failure : failures) {
                    if (failure != selected) addSuppressed(selected, failure);
                }
                rethrowCleanup(selected);
            }
        }

        boolean allStopped() {
            return !exports.isEmpty() && exports.stream().allMatch(export ->
                export.stopAttempted && export.stopFailure == null && export.stopResult != null
                    && export.stopResult.status() == PsdFileOperationResult.Status.STOPPED);
        }

        private void quarantine(final Properties result, final Path taskRoot) throws Exception {
            if (!allStopped()) {
                result.setProperty("persist.tempQuarantine.status", "FAILED");
                result.setProperty("persist.tempQuarantine.diagnostic",
                    "not all native export handles stopped");
                throw new IllegalStateException("cannot quarantine while an export handle is active");
            }
            final String runId = System.getProperty("turboism.validation.externalpsd.runId", "");
            if (!runId.matches("[A-Za-z0-9._-]+")) {
                result.setProperty("persist.tempQuarantine.status", "FAILED");
                result.setProperty("persist.tempQuarantine.diagnostic", "invalid task runId");
                throw new IllegalStateException("task runId is not safe for quarantine naming");
            }
            final Path owner = taskRoot.toAbsolutePath().normalize();
            final Path destination = owner.resolve("external-psd-quarantine-" + runId);
            result.setProperty("persist.tempQuarantine.root", destination.toString());
            for (int index = 0; index < exports.size(); index++) {
                final TrackedExport tracked = exports.get(index);
                final String prefix = "persist.tempQuarantine.item." + (index + 1);
                result.setProperty(prefix + ".source",
                    tracked.directory() == null ? "" : tracked.directory().toString());
                result.setProperty(prefix + ".target",
                    tracked.directory() == null ? ""
                        : destination.resolve(tracked.directory().getFileName()).toString());
                result.setProperty(prefix + ".stopStatus", tracked.stopResult.status().name());
                result.setProperty(prefix + ".moved", "false");
            }
            try {
                final QuarantineReport report = moveTrackedTempDirectories(
                    exports.stream().map(TrackedExport::directory).toList(),
                    tempRoot, owner, destination);
                for (int index = 0; index < report.entries().size(); index++) {
                    final QuarantineEntry entry = report.entries().get(index);
                    final String prefix = "persist.tempQuarantine.item." + (index + 1);
                    result.setProperty(prefix + ".target", entry.target().toString());
                    result.setProperty(prefix + ".moved", Boolean.toString(entry.moved()));
                }
                result.setProperty("persist.tempQuarantine.status", report.status());
                result.setProperty("persist.tempQuarantine.taskOwned",
                    Boolean.toString(report.taskOwned()));
                result.setProperty("persist.tempQuarantine.sourceMissing",
                    Boolean.toString(report.sourceMissing()));
                if (!report.diagnostic().isBlank()) {
                    result.setProperty("persist.tempQuarantine.diagnostic", report.diagnostic());
                }
                if (!"MOVED".equals(report.status())) {
                    throw new IllegalStateException(
                        "per-directory quarantine move failed: " + report.diagnostic());
                }
            } catch (Exception failure) {
                result.setProperty("persist.tempQuarantine.status", "FAILED");
                result.setProperty("persist.tempQuarantine.taskOwned", "false");
                result.setProperty("persist.tempQuarantine.sourceMissing", "false");
                result.setProperty("persist.tempQuarantine.diagnostic", failure.toString());
                throw failure;
            }
        }

        private static Exception asException(final Throwable failure) {
            if (failure instanceof Exception exception) return exception;
            if (failure instanceof Error error) throw error;
            return new IllegalStateException(failure);
        }
    }

    /** Atomic, no-overwrite move used by the persist tail and its offline path-boundary tests. */
    static QuarantineReport moveTrackedTempDirectories(final List<Path> sources,
        final Path expectedTempRoot, final Path taskRoot, final Path destinationRoot)
        throws Exception {
        return moveTrackedTempDirectories(sources, expectedTempRoot, taskRoot, destinationRoot,
            (source, target) -> Files.move(source, target, StandardCopyOption.ATOMIC_MOVE));
    }

    /**
     * Testable implementation of the per-directory atomic quarantine protocol. The injected
     * mover must perform one no-overwrite atomic move; production passes only Files.move with
     * ATOMIC_MOVE and never falls back to copy/delete.
     */
    static QuarantineReport moveTrackedTempDirectories(final List<Path> sources,
        final Path expectedTempRoot, final Path taskRoot, final Path destinationRoot,
        final AtomicDirectoryMove mover) throws Exception {
        if (sources == null || sources.isEmpty()) {
            throw new IllegalArgumentException("tracked temporary directories are required");
        }
        if (mover == null) throw new IllegalArgumentException("atomic directory mover is required");
        final Path tempRoot = requireOwnedDirectory(expectedTempRoot, "temporary root");
        final Path owner = requireOwnedDirectory(taskRoot, "task quarantine root");
        final Path destination = destinationRoot.toAbsolutePath().normalize();
        if (!destination.getParent().equals(owner)) {
            throw new IllegalStateException("quarantine destination escaped task root");
        }
        requireNoSymlinkPath(destination.getParent(), "quarantine destination");
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
            || Files.isSymbolicLink(destination)) {
            throw new IllegalStateException("quarantine destination already exists: " + destination);
        }

        final Set<Path> uniqueSources = new LinkedHashSet<>();
        final List<QuarantineEntry> entries = new ArrayList<>(sources.size());
        for (final Path sourceValue : sources) {
            if (sourceValue == null) throw new IllegalArgumentException("null tracked source");
            final Path source = sourceValue.toAbsolutePath().normalize();
            if (!source.getParent().equals(tempRoot)
                || !source.getFileName().toString().startsWith(TEMP_DIRECTORY_PREFIX)) {
                throw new IllegalStateException("tracked source escaped task temp root: " + source);
            }
            if (!uniqueSources.add(source)) {
                throw new IllegalStateException("duplicate tracked source: " + source);
            }
            if (Files.isSymbolicLink(source)
                || !Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("tracked source is not a real directory: " + source);
            }
            requireNoSymlinkPath(source, "tracked source");
            try (Stream<Path> descendants = Files.walk(source)) {
                descendants.forEach(path -> {
                    if (Files.isSymbolicLink(path)) {
                        throw new IllegalStateException("tracked source contains a symlink: " + path);
                    }
                    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IllegalStateException("tracked source contains an unsupported item: "
                            + path);
                    }
                });
            }
            final Path target = destination.resolve(source.getFileName());
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(target)) {
                throw new IllegalStateException("quarantine target already exists: " + target);
            }
            entries.add(new QuarantineEntry(source, target, false));
        }

        Files.createDirectory(destination);
        for (int index = 0; index < entries.size(); index++) {
            final QuarantineEntry entry = entries.get(index);
            try {
                mover.move(entry.source(), entry.target());
                entries.set(index, new QuarantineEntry(entry.source(), entry.target(), true));
            } catch (Exception failure) {
                for (int check = index; check < entries.size(); check++) {
                    final QuarantineEntry candidate = entries.get(check);
                    entries.set(check, new QuarantineEntry(candidate.source(), candidate.target(),
                        candidate.moved() || isCompletedDirectoryMove(candidate)));
                }
                return new QuarantineReport("FAILED", false, false, entries,
                    failure.toString());
            }
        }
        boolean sourceMissing = true;
        for (final QuarantineEntry entry : entries) {
            final boolean missing = !Files.exists(entry.source(), LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(entry.source());
            sourceMissing &= missing;
            if (!Files.isDirectory(entry.target(), LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(entry.target())) {
                return new QuarantineReport("FAILED", false, false, entries,
                    "quarantine target is not a real directory: " + entry.target());
            }
        }
        if (!sourceMissing) {
            return new QuarantineReport("FAILED", false, false, entries,
                "not all tracked sources are missing");
        }
        return new QuarantineReport("MOVED", true, true, entries, "");
    }

    private static boolean isCompletedDirectoryMove(final QuarantineEntry entry) {
        return !Files.exists(entry.source(), LinkOption.NOFOLLOW_LINKS)
            && !Files.isSymbolicLink(entry.source())
            && Files.isDirectory(entry.target(), LinkOption.NOFOLLOW_LINKS)
            && !Files.isSymbolicLink(entry.target());
    }

    private static Path requireOwnedDirectory(final Path value, final String label) {
        if (value == null) throw new IllegalArgumentException(label + " is null");
        final Path path = value.toAbsolutePath().normalize();
        if (path.equals(Path.of("/")) || Files.isSymbolicLink(path)
            || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException(label + " is not a real directory: " + path);
        }
        requireNoSymlinkPath(path, label);
        return path;
    }

    record QuarantineEntry(Path source, Path target, boolean moved) {
    }

    @FunctionalInterface
    interface AtomicDirectoryMove {
        void move(Path source, Path target) throws Exception;
    }

    record QuarantineReport(String status, boolean taskOwned, boolean sourceMissing,
        List<QuarantineEntry> entries, String diagnostic) {
        QuarantineReport {
            status = status == null ? "FAILED" : status;
            entries = List.copyOf(entries);
            diagnostic = diagnostic == null ? "" : diagnostic;
        }
    }

    /**
     * Snapshot of currently showing top-level windows taken from a non-EDT thread: class,
     * title, and for dialogs the visible button labels and text so a blocking modal can be
     * identified from evidence alone.
     */
    private static String describeWindows() {
        final StringBuilder text = new StringBuilder();
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (!window.isShowing()) {
                continue;
            }
            if (text.length() > 0) {
                text.append(" | ");
            }
            text.append(window.getClass().getSimpleName());
            final String title = window instanceof java.awt.Dialog dialog ? dialog.getTitle()
                : window instanceof java.awt.Frame frame ? frame.getTitle() : null;
            if (title != null && !title.isBlank()) {
                text.append('\'').append(title).append('\'');
            }
            if (window instanceof java.awt.Dialog) {
                final List<String> buttons = new ArrayList<>();
                final List<String> labels = new ArrayList<>();
                collectDialogText(window, buttons, labels, 0);
                if (!buttons.isEmpty()) {
                    text.append(" buttons=").append(buttons);
                }
                if (!labels.isEmpty()) {
                    text.append(" text=").append(labels);
                }
            }
        }
        return text.length() == 0 ? "none" : text.toString();
    }

    private static void collectDialogText(final java.awt.Component component,
        final List<String> buttons, final List<String> labels, final int depth) {
        if (depth > 6) {
            return;
        }
        if (component instanceof javax.swing.AbstractButton button
            && button.getText() != null && !button.getText().isBlank()) {
            buttons.add(button.getText().trim());
        }
        if (component instanceof javax.swing.JLabel label
            && label.getText() != null && !label.getText().isBlank()) {
            labels.add(label.getText().replaceAll("\\s+", " ").trim());
        }
        if (component instanceof javax.swing.text.JTextComponent textComponent
            && textComponent.getText() != null && !textComponent.getText().isBlank()) {
            labels.add(textComponent.getText().replaceAll("\\s+", " ").trim());
        }
        if (component instanceof java.awt.Container container) {
            for (final java.awt.Component child : container.getComponents()) {
                collectDialogText(child, buttons, labels, depth + 1);
            }
        }
    }

    /**
     * Polls for JDialogs that appear while a mediated save runs on the EDT. Native save flows
     * can open a modal confirmation (e.g. unused raw images); without an answer the EDT task
     * times out. Each new dialog's content is recorded; a button is clicked only when its
     * label unambiguously means "keep / do not remove" so the save proceeds untouched.
     */
    private static final class DialogAnswerWatcher implements AutoCloseable {
        private static final List<String> KEEP_LABELS = List.of(
            "いいえ", "不删除", "保留", "否", "No(N)", "No", "Keep", "Keep all");
        private final Set<java.awt.Window> baseline;
        final List<String> actions = new CopyOnWriteArrayList<>();
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final Thread thread;

        DialogAnswerWatcher(final Set<java.awt.Window> baseline) {
            this.baseline = baseline;
            thread = new Thread(this::poll, "external-psd-dialog-watcher");
            thread.setDaemon(true);
            thread.start();
        }

        private void poll() {
            final Set<java.awt.Window> answered = new HashSet<>();
            while (!stopped.get()) {
                for (final java.awt.Window window : java.awt.Window.getWindows()) {
                    if (!(window instanceof java.awt.Dialog dialog)
                        || baseline.contains(window) || !dialog.isShowing()
                        || answered.contains(window)) {
                        continue;
                    }
                    answered.add(window);
                    final List<String> buttons = new ArrayList<>();
                    final List<String> labels = new ArrayList<>();
                    collectDialogText(dialog, buttons, labels, 0);
                    actions.add("dialog title='" + dialog.getTitle()
                        + "' buttons=" + buttons + " text=" + labels);
                    final javax.swing.AbstractButton keep =
                        findButton(dialog, KEEP_LABELS, 0);
                    if (keep != null) {
                        // Modal dialogs run a nested event pump, so a queued click still runs.
                        SwingUtilities.invokeLater(() -> keep.doClick());
                        actions.add("clicked '" + keep.getText().trim() + "'");
                    }
                }
                try {
                    Thread.sleep(300);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        private static javax.swing.AbstractButton findButton(final java.awt.Component component,
            final List<String> wanted, final int depth) {
            if (depth > 6 || !(component instanceof java.awt.Container container)) {
                return null;
            }
            for (final java.awt.Component child : container.getComponents()) {
                if (child instanceof javax.swing.AbstractButton button
                    && button.getText() != null
                    && wanted.stream().anyMatch(w -> button.getText().trim().equals(w))) {
                    return button;
                }
                final javax.swing.AbstractButton nested = findButton(child, wanted, depth + 1);
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }

        @Override
        public void close() {
            stopped.set(true);
            try {
                thread.join(2000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private GuiTargetState observeGuiTarget(final Target target) throws Exception {
        final AtomicReference<GuiTargetState> observed = new AtomicReference<>(
            GuiTargetState.unobserved("target state was not observed"));
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    final var relations = context.cubism().model().active().textures().relations();
                    if (!relations.isAvailable()) throw new IllegalStateException(
                        "relations unavailable");
                    final RawImageId currentRaw = relations.modelImage(target.modelImage())
                        .flatMap(image -> image.currentRawImageId())
                        .orElseThrow(() -> new IllegalStateException(
                            "target model image has no current raw image"));
                    final boolean rawReplaced = relations.rawImage(currentRaw)
                        .map(dev.turboism.sdk.cubism.model.RawImageDetails::isReplaced)
                        .orElseThrow(() -> new IllegalStateException(
                            "target raw image details unavailable"));
                    observed.set(new GuiTargetState(true, relations.binding(),
                        relations.generation(), currentRaw.value(), rawReplaced, ""));
                } catch (RuntimeException unavailable) {
                    observed.set(GuiTargetState.unobserved(unavailable.toString()));
                }
            });
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return GuiTargetState.unobserved("observation interrupted");
        } catch (Exception unavailable) {
            return GuiTargetState.unobserved(unavailable.toString());
        }
        return observed.get();
    }

    static void recordGuiTargetState(final Properties result, final String phase,
        final GuiTargetState state) {
        final String prefix = "gui." + phase + ".";
        result.setProperty(prefix + "observed", Boolean.toString(state.observed()));
        result.setProperty(prefix + "binding", state.binding());
        result.setProperty(prefix + "generation", Long.toString(state.generation()));
        result.setProperty(prefix + "raw", state.raw());
        result.setProperty(prefix + "rawReplaced", Boolean.toString(state.rawReplaced()));
        result.remove(prefix + "diagnostic");
        if (!state.diagnostic().isBlank()) {
            result.setProperty(prefix + "diagnostic", state.diagnostic());
        }
    }

    static boolean acceptsAutoImport(final GuiTargetState before,
        final GuiTargetState after, final String expectedRaw) {
        return before.observed() && after.observed()
            && !before.rawReplaced() && after.rawReplaced()
            && before.binding().equals(after.binding())
            && before.generation() == after.generation()
            && expectedRaw.equals(before.raw())
            && expectedRaw.equals(after.raw());
    }

    private static boolean sameGuiTarget(final GuiTargetState before,
        final GuiTargetState after, final String expectedRaw) {
        return before.observed() && after.observed()
            && before.binding().equals(after.binding())
            && before.generation() == after.generation()
            && expectedRaw.equals(before.raw())
            && expectedRaw.equals(after.raw());
    }

    /**
     * Scans only the reviewed host tree-table model, resolves the selected ArtMesh by its exact
     * domain identity, and clicks the first popup item whose text equals one of {@code labels}.
     * Generic Swing row diagnostics below remain offline test seams and are not a GUI fallback.
     */
    private GuiClick clickContributedItem(final Set<String> labels, final int rowBudget,
        final Properties result, final Target expectedTarget) throws Exception {
        int attempts = 0;
        int popups = 0;
        final Set<String> menuTexts = new LinkedHashSet<>();
        final List<String> rowDiagnostics = new ArrayList<>();
        String diagnostic = "no visible reviewed host tree-table row found";
        String boundWindowIdentity = guiBoundWindow == null ? ""
            : componentIdentity(guiBoundWindow);
        hostAccessByLoader.clear();
        final long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline && !stopped) {
            final ReviewedTables reviewed = visibleReviewedTables();
            result.setProperty("gui.exactTarget.window", reviewed.windowIdentity());
            result.setProperty("gui.exactTarget.windowSelection", reviewed.diagnostic());
            final GuiWindowBindingDecision currentWindow = decideGuiWindowBinding(
                boundWindowIdentity, reviewed.windowIdentity(), reviewed.proven(), false);
            if (!boundWindowIdentity.isBlank()
                && currentWindow.waitForBoundWindow()) {
                diagnostic = currentWindow.diagnostic();
                result.setProperty("gui.exactTarget.windowBinding", boundWindowIdentity);
                result.setProperty("gui.exactTarget.windowBindingStatus", "WAITING");
                result.setProperty("gui.exactTarget.windowBindingDiagnostic", diagnostic);
                Thread.sleep(250);
                continue;
            }
            if (!reviewed.proven()) {
                result.setProperty("gui.exactTarget.status", "REJECTED");
                result.setProperty("gui.exactTarget.diagnostic", reviewed.diagnostic());
                return new GuiClick(false, reviewed.diagnostic());
            }
            final List<ExactTableRef> tables = reviewed.tables();
            if (tables.isEmpty()) {
                diagnostic = reviewed.diagnostic();
                Thread.sleep(1000);
                continue;
            }
            final ExactCapture capture = captureExactRows(tables, expectedTarget,
                reviewed.windowIdentity());
            result.setProperty("gui.exactTableDiagnostics",
                String.join("\n---\n", capture.tableDiagnostics()));
            if (!capture.hostAvailable()) {
                result.setProperty("gui.hostAccess.status", "BLOCKED");
                result.setProperty("gui.hostAccess.diagnostic", capture.diagnostic());
                return new GuiClick(false, "host access unavailable: " + capture.diagnostic());
            }
            result.setProperty("gui.hostAccess.status", "PREPARED_OFF_EDT");
            result.setProperty("gui.hostAccess.preflightCount",
                Integer.toString(hostAccessByLoader.size()));
            result.setProperty("gui.exactTarget.status", capture.targetStatus().name());
            if (capture.targetStatus() == ExactTargetSelectionStatus.AMBIGUOUS
                || capture.targetStatus() == ExactTargetSelectionStatus.REJECTED) {
                diagnostic = capture.diagnostic();
                result.setProperty("gui.exactTarget.status", capture.targetStatus().name());
                result.setProperty("gui.exactTarget.diagnostic", diagnostic);
                return new GuiClick(false, diagnostic);
            }
            if (capture.rows().isEmpty()) {
                diagnostic = capture.diagnostic();
                Thread.sleep(1000);
                continue;
            }
            final GuiWindowBindingDecision targetWindow = decideGuiWindowBinding(
                boundWindowIdentity, reviewed.windowIdentity(), reviewed.proven(), true);
            if (targetWindow.bindNow()) {
                boundWindowIdentity = reviewed.windowIdentity();
                guiBoundWindow = reviewed.window();
                result.setProperty("gui.exactTarget.windowBinding", boundWindowIdentity);
                result.setProperty("gui.exactTarget.windowBindingStatus", "BOUND");
                result.setProperty("gui.exactTarget.windowBindingDiagnostic",
                    targetWindow.diagnostic() + " object=" + objectIdentity(guiBoundWindow));
            }
            for (final ExactDispatchCapture captured : capture.rows()) {
                if (attempts >= rowBudget) break;
                if (stopped) return new GuiClick(false, "probe stopped");
                attempts++;
                final RowAttempt rowAttempt = exactRowAttempt(captured);
                popups += rowAttempt.popupCount();
                String menuDiagnostic = "no selected popup";
                final JPopupMenu popup = rowAttempt.popup();
                final AtomicReference<JMenuItem> foundItem = new AtomicReference<>();
                if (popup != null) {
                    final Set<String> rowMenuTexts = new LinkedHashSet<>();
                    final AtomicReference<String> popupMarker = new AtomicReference<>("");
                    SwingUtilities.invokeAndWait(() -> {
                        foundItem.set(findItem(popup, labels, rowMenuTexts));
                        popupMarker.set(popupMarker(popup));
                    });
                    menuTexts.addAll(rowMenuTexts);
                    menuDiagnostic = "selectedPopup=" + popupMarker.get()
                        + " item=" + (foundItem.get() == null
                            ? "none" : foundItem.get().getText())
                        + " menuTexts=" + rowMenuTexts;
                }
                final String rowDiagnostic = "attempt=" + attempts
                    + " family=" + captured.captured().identity().rowFamily()
                    + " widget=" + captured.captured().widgetIdentity() + " row="
                    + captured.captured().viewRow() + " "
                    + rowAttempt.diagnostic() + " " + menuDiagnostic;
                rowDiagnostics.add(rowDiagnostic);
                result.setProperty("gui.row." + attempts, rowDiagnostic);
                if (!rowAttempt.dispatchFailureTrace().isBlank()) {
                    result.setProperty("gui.row." + attempts
                        + ".dispatchFailureTrace", rowAttempt.dispatchFailureTrace());
                }
                context.logger().warn("EXTERNAL_PSD_EDIT_GUI_ATTEMPT " + rowDiagnostic);
                result.setProperty("gui.rowDiagnostics", String.join("\n---\n", rowDiagnostics));
                if (rowAttempt.terminalRejection()
                    || !rowAttempt.dispatchFailureTrace().isBlank()) {
                    result.setProperty("gui.exactTarget.status", "REJECTED");
                    return new GuiClick(false, rowAttempt.diagnostic());
                }
                final JMenuItem item = foundItem.get();
                if (popup == null) continue;
                if (item == null) {
                    dismissPopup();
                    continue;
                }
                result.setProperty("gui.popupRow", Integer.toString(rowAttempt.dispatchRow()));
                result.setProperty("gui.popupComponent", rowAttempt.dispatchComponent());
                clickItem(item);
                result.setProperty("gui.attempts", Integer.toString(attempts));
                result.setProperty("gui.popupsSeen", Integer.toString(popups));
                return new GuiClick(true,
                    "clicked exact ArtMesh " + captured.captured().identity().rowFamily()
                        + " row " + rowAttempt.dispatchRow());
            }
            if (attempts >= rowBudget) break;
            diagnostic = "exact ArtMesh entrances exhausted without the item; popups seen "
                + popups + " menuTexts=" + menuTexts + " rowDiagnostics="
                + String.join(" || ", rowDiagnostics);
            Thread.sleep(1000);
        }
        diagnostic = "exact ArtMesh row exhausted without the item; popups seen " + popups
            + " menuTexts=" + menuTexts
            + " rowDiagnostics=" + String.join(" || ", rowDiagnostics);
        result.setProperty("gui.attempts", Integer.toString(attempts));
        result.setProperty("gui.popupsSeen", Integer.toString(popups));
        result.setProperty("gui.menuTexts", menuTexts.toString());
        result.setProperty("gui.rowDiagnostics", String.join("\n---\n", rowDiagnostics));
        result.setProperty("gui.hierarchy", hierarchyDigest());
        return new GuiClick(false, diagnostic);
    }

    /**
     * Keeps a GUI attempt tied to the first window that actually contains the exact target.  A
     * later active window is never a replacement candidate; the caller must wait for the bound
     * window to become active again or time out to BLOCKED.
     */
    private static GuiWindowBindingDecision decideGuiWindowBinding(
        final String boundWindowIdentity, final String currentWindowIdentity,
        final boolean currentWindowProven, final boolean currentTargetMatched) {
        final String bound = boundWindowIdentity == null ? "" : boundWindowIdentity;
        final String current = currentWindowIdentity == null ? "" : currentWindowIdentity;
        if (!currentWindowProven || current.isBlank()) {
            return bound.isBlank()
                ? GuiWindowBindingDecision.unbound(
                    "active target window cannot be proven yet")
                : GuiWindowBindingDecision.waiting(
                    "bound target window is not currently active; waiting for " + bound);
        }
        if (!bound.isBlank() && !bound.equals(current)) {
            return GuiWindowBindingDecision.waiting(
                "active window changed from bound target " + bound + " to " + current
                    + "; waiting for the original window");
        }
        if (!bound.isBlank()) {
            return GuiWindowBindingDecision.stable(
                "active window remains bound target " + bound);
        }
        if (!currentTargetMatched) {
            return GuiWindowBindingDecision.unbound(
                "reviewed window has no exact target; window remains unbound");
        }
        return GuiWindowBindingDecision.bind(
            "bound first reviewed window containing the exact target " + current);
    }

    static GuiWindowBindingDecision decideGuiWindowBindingForTest(
        final String boundWindowIdentity, final String currentWindowIdentity,
        final boolean currentWindowProven, final boolean currentTargetMatched) {
        return decideGuiWindowBinding(boundWindowIdentity, currentWindowIdentity,
            currentWindowProven, currentTargetMatched);
    }

    private ReviewedTables visibleReviewedTables() throws Exception {
        final AtomicReference<ReviewedTables> found = new AtomicReference<>(
            ReviewedTables.unavailable("active target window was not observed"));
        SwingUtilities.invokeAndWait(() -> {
            try {
                final Window active = KeyboardFocusManager.getCurrentKeyboardFocusManager()
                    .getActiveWindow();
                if (active == null) {
                    found.set(ReviewedTables.unavailable(
                        "active target window cannot be proven: AWT active window is null"));
                    return;
                }
                if (!active.isShowing() || !active.isDisplayable()) {
                    found.set(ReviewedTables.unavailable(
                        "active target window cannot be proven showing/displayable: "
                            + componentIdentity(active)));
                    return;
                }
                final List<ExactTableRef> tables = new ArrayList<>();
                collectReviewedTables(active, active, tables);
                final String windowIdentity = componentIdentity(active);
                found.set(new ReviewedTables(List.copyOf(tables), windowIdentity, active, true,
                    "activeTargetWindow=" + windowIdentity + " tables=" + tables.size()));
            } catch (RuntimeException failure) {
                found.set(ReviewedTables.unavailable(
                    "active target window discovery failed: " + failure));
            }
        });
        return found.get();
    }

    private static void collectReviewedTables(final Container container, final Window window,
        final List<ExactTableRef> tables) {
        for (final Component component : container.getComponents()) {
            if (component instanceof JTable table && isLiveComponent(table)) {
                final javax.swing.table.TableModel model = table.getModel();
                if (model != null && ExactHostRowTarget.isReviewedTableModelClass(
                    model.getClass())) {
                    tables.add(new ExactTableRef(table, model.getClass(),
                        componentIdentity(table), componentIdentity(window),
                        objectIdentity(model)));
                }
            }
            if (component instanceof Container child) {
                collectReviewedTables(child, window, tables);
            }
        }
    }

    /** Runs exactly once per class loader for this GUI phase, always on the worker thread. */
    private ExactHostRowTarget.HostAccessPreparation prepareHostAccess(
        final ExactTableRef table) {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("host access preflight must run off EDT");
        }
        final ClassLoader loader = table.modelClass().getClassLoader();
        if (hostAccessByLoader.containsKey(loader)) return hostAccessByLoader.get(loader);
        final ExactHostRowTarget.HostAccessPreparation preparation =
            ExactHostRowTarget.prepareHostAccess(table.modelClass());
        hostAccessByLoader.put(loader, preparation);
        return preparation;
    }

    private ExactCapture captureExactRows(final List<ExactTableRef> tables,
        final Target expectedTarget, final String targetWindowIdentity) throws Exception {
        final List<String> tableDiagnostics = new ArrayList<>();
        if (targetWindowIdentity == null || targetWindowIdentity.isBlank()) {
            return new ExactCapture(List.of(), tableDiagnostics,
                "active target window identity is unavailable", false,
                ExactTargetSelectionStatus.REJECTED);
        }
        int availableContexts = 0;
        for (final ExactTableRef table : tables) {
            final ExactHostRowTarget.HostAccessPreparation preparation = prepareHostAccess(table);
            if (preparation.available()) {
                availableContexts++;
                tableDiagnostics.add(table.diagnostic() + " preflight=available artifact="
                    + preparation.context().artifact() + " sha256="
                    + preparation.context().artifactSha256());
            } else {
                tableDiagnostics.add(table.diagnostic() + " preflight=unavailable reason="
                    + preparation.reason());
            }
        }
        if (availableContexts == 0) {
            return new ExactCapture(List.of(), tableDiagnostics,
                String.join(" || ", tableDiagnostics), false,
                ExactTargetSelectionStatus.REJECTED);
        }

        final AtomicReference<ExactCapture> captured = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            final List<ExactDispatchCapture> rows = new ArrayList<>();
            final List<String> currentDiagnostics = new ArrayList<>(tableDiagnostics);
            final Window active = KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .getActiveWindow();
            if (active == null || !active.isShowing() || !active.isDisplayable()
                || !targetWindowIdentity.equals(componentIdentity(active))) {
                captured.set(new ExactCapture(List.of(), List.copyOf(currentDiagnostics),
                    "active target window changed before exact row capture; expected="
                        + targetWindowIdentity + " actual=" + componentIdentity(active), true,
                    ExactTargetSelectionStatus.NOT_FOUND));
                return;
            }
            for (final ExactTableRef table : tables) {
                final ExactHostRowTarget.HostAccessPreparation preparation =
                    hostAccessByLoader.get(table.modelClass().getClassLoader());
                if (preparation == null || !preparation.available()) continue;
                final JTable widget = table.table();
                final Object model = widget.getModel();
                if (model == null || model.getClass() != table.modelClass()) {
                    currentDiagnostics.add(table.diagnostic()
                        + " capture=table model was replaced before EDT capture");
                    continue;
                }
                if (!isLiveComponent(widget)) {
                    currentDiagnostics.add(table.diagnostic()
                        + " capture=table became detached before EDT capture");
                    continue;
                }
                final List<String> rowFacts = new ArrayList<>();
                for (int row = 0; row < widget.getRowCount(); row++) {
                    final ExactHostRowTarget.Resolution resolution =
                        ExactHostRowTarget.resolve(widget, row, preparation.context());
                    if (!resolution.available()) {
                        rowFacts.add("row=" + row + " unavailable=" + resolution.reason());
                        continue;
                    }
                    final ExactHostRowTarget.Target target = resolution.target();
                    rowFacts.add("row=" + row + " identity=" + target.identity()
                        + " state=" + target.state()
                        + " cell=" + target.nameCell().bounds()
                        + " click=" + target.nameClickPoint());
                    if (!target.domainId().equals(expectedTarget.artMesh().id().value())) {
                        continue;
                    }
                    final ExactCapturedRow descriptor = exactCapturedRow(widget, table, target);
                    rows.add(new ExactDispatchCapture(descriptor, preparation.context()));
                }
                currentDiagnostics.add(table.diagnostic() + " rows=" + rowFacts);
            }
            final List<ExactCapturedRow> descriptors = rows.stream()
                .map(ExactDispatchCapture::captured).toList();
            final ExactTargetSelection selection = selectExactTargetRows(descriptors,
                targetWindowIdentity, expectedTarget.artMesh().id().value());
            final IdentityHashMap<ExactCapturedRow, ExactDispatchCapture> byDescriptor =
                new IdentityHashMap<>();
            for (final ExactDispatchCapture row : rows) byDescriptor.put(row.captured(), row);
            final List<ExactDispatchCapture> ordered = new ArrayList<>();
            for (final ExactCapturedRow descriptor : selection.rows()) {
                final ExactDispatchCapture row = byDescriptor.get(descriptor);
                if (row == null) {
                    captured.set(new ExactCapture(List.of(), List.copyOf(currentDiagnostics),
                        "exact target capture mapping was lost", true,
                        ExactTargetSelectionStatus.REJECTED));
                    return;
                }
                ordered.add(row);
            }
            currentDiagnostics.add("targetSelection=" + selection.status()
                + " reason=" + selection.reason());
            final String diagnostic = selection.available()
                ? "exact target entrances=" + ordered.stream()
                    .map(ExactDispatchCapture::diagnostic).toList()
                : selection.reason();
            captured.set(new ExactCapture(List.copyOf(ordered), List.copyOf(currentDiagnostics),
                diagnostic, true, selection.status()));
        });
        return captured.get();
    }

    private static ExactCapturedRow exactCapturedRow(final JTable table,
        final ExactTableRef tableRef, final ExactHostRowTarget.Target target) {
        return new ExactCapturedRow(target.identity(), tableRef.widgetIdentity(),
            tableRef.windowIdentity(), objectIdentity(table.getModel()), target.viewRow(),
            target.modelRow(), target.nameCell(), target.state());
    }

    private static RowAttempt exactRowAttempt(final ExactDispatchCapture captured)
        throws Exception {
        final AtomicReference<RowDispatchResult> rowOutcome = new AtomicReference<>();
        final AtomicReference<RightClickDispatchException> dispatchFailure =
            new AtomicReference<>();
        final AtomicReference<PopupAttempt> popupAttempt = new AtomicReference<>(dismissPopup());
        final List<String> retryReasons = new ArrayList<>();
        final int attempts = runBoundedRowDispatches(attemptNumber -> {
            rowOutcome.set(null);
            try {
                final boolean triggerOnPress = popupTriggerOnPress(
                    System.getProperty("os.name", ""));
                SwingUtilities.invokeAndWait(() -> rowOutcome.set(
                    dispatchExactCapturedRow(captured, triggerOnPress)));
            } catch (InvocationTargetException wrapped) {
                if (wrapped.getCause() instanceof RightClickDispatchException failure) {
                    dispatchFailure.set(failure);
                    return false;
                }
                throw wrapped;
            }
            final RowDispatchResult result = rowOutcome.get();
            if (result == null || !result.retryable()) return false;
            retryReasons.add("attempt=" + attemptNumber + " phase=" + result.phase()
                + " reason=" + result.reason());
            if (attemptNumber < MAX_ROW_DISPATCH_ATTEMPTS) {
                popupAttempt.set(mergePopupAttempts(popupAttempt.get(), dismissPopup()));
            }
            return true;
        });
        final RowDispatchResult rowResult = rowOutcome.get();
        final PopupCapture popup = rowResult != null && rowResult.terminalRejection()
            ? new PopupCapture(null, 0, 0, "popupPolls=0 terminal exact target rejection")
            : awaitPopup(popupAttempt.get());
        final RightClickDispatchException failure = dispatchFailure.get();
        final String dispatchDiagnostic = rowResult != null
            ? rowResult.dispatch().diagnostic()
            : failure != null
                ? failure.dispatch().diagnostic() : "not-dispatched";
        final String failureTrace = failure == null ? "" : stackTrace(failure);
        final String diagnostic = "exactCapture=" + captured.diagnostic()
            + " attempts=" + attempts
            + " dispatchRow=" + (rowResult == null ? -1 : rowResult.row())
            + " dispatchComponent=" + (rowResult == null ? "" : rowResult.component())
            + " retryLimit=" + MAX_ROW_DISPATCH_ATTEMPTS
            + " retryReasons=" + retryReasons
            + " " + dispatchDiagnostic
            + (failureTrace.isBlank() ? "" : " dispatchExceptionTrace=" + failureTrace)
            + " popupCount=" + popup.popupCount()
            + " popupMaxCount=" + popup.maxPopupCount()
            + " " + popup.diagnostic();
        return new RowAttempt(popup.popup(), popup.popupCount(), diagnostic, failureTrace,
            rowResult == null ? -1 : rowResult.row(),
            rowResult == null ? "" : rowResult.component(),
            rowResult != null && rowResult.terminalRejection());
    }

    private static RowDispatchResult dispatchExactCapturedRow(
        final ExactDispatchCapture captured, final boolean triggerOnPress) {
        final ExactRowResolution selection = resolveExactActiveRow(captured.captured(),
            captured.context());
        if (!selection.available()) {
            return RowDispatchResult.retry(captured.diagnostic(), "before-left",
                selection.reason());
        }
        return dispatchExactResolvedSelection(captured.captured(), selection.row(),
            triggerOnPress, () -> resolveExactActiveRow(captured.captured(), captured.context()));
    }

    private static RowDispatchResult dispatchExactResolvedSelection(
        final ExactCapturedRow captured, final ExactCurrentRow selected,
        final boolean triggerOnPress, final java.util.function.Supplier<ExactRowResolution> refreshed) {
        if (!ExactHostRowTarget.ART_MESH_SOURCE_CLASS_NAME.equals(
            captured.identity().sourceClass())) {
            return RowDispatchResult.rejected(captured.diagnostic(), "before-left",
                "resolved row source class is not the supported ArtMesh class");
        }
        if (selected == null || !captured.identity().equals(selected.descriptor().identity())) {
            return RowDispatchResult.rejected(captured.diagnostic(), "before-left",
                "resolved row identity differs from captured exact target");
        }
        if (!isLiveComponent(selected.component())
            || !validBounds(selected.descriptor().nameCell().bounds())) {
            return RowDispatchResult.retry(captured.diagnostic(), "before-left",
                "resolved exact table is not live or name bounds are invalid");
        }
        if (!sameExactState(captured, selected)) {
            return RowDispatchResult.rejected(captured.diagnostic(), "before-left",
                exactStateChange(captured, selected));
        }

        final String prefix = exactRowDispatchPrefix(captured, selected);
        final AtomicReference<ExactRowResolution> afterSelection = new AtomicReference<>();
        final AtomicReference<String> stateChange = new AtomicReference<>("");
        final AtomicReference<String> identityChange = new AtomicReference<>("");
        try {
            final Point point = selected.descriptor().nameCell().clickPoint();
            final RightClickDispatch dispatch = dispatchRightClickAfterSelection(
                selected.component(), point.x, point.y, triggerOnPress, prefix, () -> {
                    final ExactRowResolution resolved = refreshed.get();
                    afterSelection.set(resolved);
                    if (!resolved.available()) return null;
                    final ExactCurrentRow current = resolved.row();
                    if (!captured.identity().equals(current.descriptor().identity())) {
                        identityChange.set("after-left exact row identity differs: captured="
                            + captured.identity() + " after=" + current.descriptor().identity());
                        return null;
                    }
                    if (!sameExactState(captured, current)) {
                        stateChange.set(exactStateChange(captured, current));
                        return null;
                    }
                    if (!isLiveComponent(current.component())
                        || !validBounds(current.descriptor().nameCell().bounds())) {
                        return null;
                    }
                    final Point freshPoint = current.descriptor().nameCell().clickPoint();
                    return new DispatchTarget(current.component(), freshPoint.x, freshPoint.y,
                        "afterLeft=" + current.diagnostic());
                });
            final ExactCurrentRow dispatched = afterSelection.get() == null
                ? null : afterSelection.get().row();
            if (dispatched == null) {
                throw new RowRelocationException(
                    "active exact row was not recorded after the selection click");
            }
            return RowDispatchResult.success(dispatch, dispatched);
        } catch (RowRelocationException retry) {
            final ExactRowResolution resolved = afterSelection.get();
            final String reason = !identityChange.get().isBlank() ? identityChange.get()
                : !stateChange.get().isBlank() ? stateChange.get()
                : resolved == null ? retry.getMessage() : resolved.reason();
            final String detail = captured.diagnostic() + " afterLeft="
                + (resolved == null ? "unavailable" : resolved.diagnostic());
            if (!identityChange.get().isBlank() || !stateChange.get().isBlank()) {
                return RowDispatchResult.rejected(detail, "after-left", reason);
            }
            return RowDispatchResult.retry(detail, "after-left", reason);
        }
    }

    private static ExactRowResolution resolveExactActiveRow(
        final ExactCapturedRow captured, final ExactHostRowTarget.HostAccessContext context) {
        if (!SwingUtilities.isEventDispatchThread()) {
            return ExactRowResolution.unavailable("exact row resolution must run on EDT");
        }
        if (captured == null) {
            return ExactRowResolution.unavailable("captured exact row is unavailable");
        }
        final Window active = KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .getActiveWindow();
        if (active == null || !active.isShowing() || !active.isDisplayable()
            || !componentIdentity(active).equals(captured.windowIdentity())) {
            return ExactRowResolution.unavailable(
                "captured target window is no longer the active showing window");
        }
        final List<ExactCurrentRow> rows = new ArrayList<>();
        for (final Window window : Window.getWindows()) {
            if (!window.isShowing() || !componentIdentity(window).equals(captured.windowIdentity())) {
                continue;
            }
            collectExactCurrentRows(window, captured, context, rows);
        }
        return resolveExactActiveRow(captured, rows);
    }

    private static void collectExactCurrentRows(final Container container,
        final ExactCapturedRow captured, final ExactHostRowTarget.HostAccessContext context,
        final List<ExactCurrentRow> rows) {
        for (final Component component : container.getComponents()) {
            if (component instanceof JTable table && isLiveComponent(table)) {
                final Object model = table.getModel();
                if (model != null && ExactHostRowTarget.isReviewedTableModelClass(
                    model.getClass())) {
                    for (int row = 0; row < table.getRowCount(); row++) {
                        final ExactHostRowTarget.Resolution resolution =
                            ExactHostRowTarget.resolve(table, row, context);
                        if (!resolution.available()
                            || !resolution.target().identity().equals(captured.identity())) {
                            continue;
                        }
                        final ExactHostRowTarget.Target target = resolution.target();
                        final ExactCapturedRow descriptor = new ExactCapturedRow(
                            target.identity(), componentIdentity(table),
                            componentIdentity(SwingUtilities.getWindowAncestor(table)),
                            objectIdentity(model), target.viewRow(), target.modelRow(),
                            target.nameCell(), target.state());
                        rows.add(new ExactCurrentRow(descriptor, table));
                    }
                }
            }
            if (component instanceof Container child) {
                collectExactCurrentRows(child, captured, context, rows);
            }
        }
    }

    /** Package-private seam for the exact GUI resolver; unlike the legacy resolver it has no key fallback. */
    static ExactRowResolution resolveExactActiveRowForTest(final ExactCapturedRow captured,
        final List<ExactCurrentRow> currentRows) {
        return resolveExactActiveRow(captured, currentRows);
    }

    private static ExactRowResolution resolveExactActiveRow(final ExactCapturedRow captured,
        final List<ExactCurrentRow> currentRows) {
        if (captured == null) return ExactRowResolution.unavailable("captured exact row is unavailable");
        if (captured.identity() == null) {
            return ExactRowResolution.unavailable("captured exact row identity is unavailable");
        }
        if (!ExactHostRowTarget.ART_MESH_SOURCE_CLASS_NAME.equals(
            captured.identity().sourceClass())) {
            return ExactRowResolution.unavailable(
                "captured exact row source class is not the supported ArtMesh class");
        }
        final List<ExactCurrentRow> all = currentRows == null ? List.of() : currentRows;
        final List<ExactCurrentRow> sameWindow = all.stream()
            .filter(row -> row != null && row.descriptor() != null)
            .filter(row -> captured.windowIdentity().equals(row.descriptor().windowIdentity()))
            .filter(row -> captured.identity().equals(row.descriptor().identity()))
            .filter(row -> isLiveComponent(row.component()))
            .filter(row -> validBounds(row.descriptor().nameCell().bounds()))
            .toList();
        if (sameWindow.isEmpty()) {
            final boolean otherWindow = all.stream()
                .filter(row -> row != null && row.descriptor() != null)
                .anyMatch(row -> captured.identity().equals(row.descriptor().identity())
                    && !captured.windowIdentity().equals(row.descriptor().windowIdentity()));
            return ExactRowResolution.unavailable(otherWindow
                ? "matching exact row exists only in another window"
                : "no live exact row matches family/source/domain identity");
        }
        if (sameWindow.size() > 1) {
            return ExactRowResolution.unavailable(
                "exact row identity is ambiguous in captured window: " + sameWindow.size());
        }
        return ExactRowResolution.available(sameWindow.get(0));
    }

    /**
     * Selects the exact entrances for one proven active target window. Parts and Deformer are
     * independent host entry points, so they may both be returned; duplicate identities within a
     * family remain ambiguous and are rejected.
     */
    static ExactTargetSelection selectExactTargetRowsForTest(
        final List<ExactCapturedRow> rows, final String targetWindowIdentity,
        final String expectedDomainId) {
        return selectExactTargetRows(rows, targetWindowIdentity, expectedDomainId);
    }

    private static ExactTargetSelection selectExactTargetRows(
        final List<ExactCapturedRow> rows, final String targetWindowIdentity,
        final String expectedDomainId) {
        if (targetWindowIdentity == null || targetWindowIdentity.isBlank()) {
            return ExactTargetSelection.rejected(
                "active target window cannot be proven: window identity is unavailable");
        }
        if (expectedDomainId == null || expectedDomainId.isBlank()) {
            return ExactTargetSelection.rejected(
                "expected ArtMesh domain ID is unavailable");
        }
        final List<ExactCapturedRow> all = rows == null ? List.of() : rows.stream()
            .filter(Objects::nonNull)
            .filter(row -> row.identity() != null)
            .toList();
        final List<ExactCapturedRow> matching = all.stream()
            .filter(row -> targetWindowIdentity.equals(row.windowIdentity()))
            .filter(row -> ExactHostRowTarget.ART_MESH_SOURCE_CLASS_NAME.equals(
                row.identity().sourceClass()))
            .filter(row -> expectedDomainId.equals(row.identity().domainId()))
            .toList();
        if (matching.isEmpty()) {
            final boolean otherWindow = all.stream()
                .filter(row -> ExactHostRowTarget.ART_MESH_SOURCE_CLASS_NAME.equals(
                    row.identity().sourceClass()))
                .anyMatch(row -> expectedDomainId.equals(row.identity().domainId())
                    && !targetWindowIdentity.equals(row.windowIdentity()));
            return otherWindow
                ? ExactTargetSelection.rejected(
                    "matching exact ArtMesh row exists only in another window")
                : ExactTargetSelection.notFound(
                    "no exact ArtMesh row matched domain ID " + expectedDomainId);
        }

        final Map<ExactTargetGroup, List<ExactCapturedRow>> groups = new LinkedHashMap<>();
        for (final ExactCapturedRow row : matching) {
            final ExactTargetGroup group = new ExactTargetGroup(row.windowIdentity(),
                row.identity());
            groups.computeIfAbsent(group, ignored -> new ArrayList<>()).add(row);
        }
        for (final Map.Entry<ExactTargetGroup, List<ExactCapturedRow>> entry
            : groups.entrySet()) {
            if (entry.getValue().size() > 1) {
                return ExactTargetSelection.ambiguous(
                    "same row family has multiple active exact candidates: "
                        + entry.getKey() + " count=" + entry.getValue().size());
            }
        }

        final List<ExactCapturedRow> ordered = new ArrayList<>(matching);
        ordered.sort(Comparator.comparingInt(row -> exactFamilyOrder(
            row.identity().rowFamily())));
        return ExactTargetSelection.available(ordered);
    }

    private static int exactFamilyOrder(final ExactHostRowTarget.RowFamily family) {
        return family == ExactHostRowTarget.RowFamily.DEFORMER ? 0 : 1;
    }

    private static boolean sameExactState(final ExactCapturedRow captured,
        final ExactCurrentRow current) {
        return current != null && captured.state().equals(current.descriptor().state());
    }

    static boolean sameExactStateForTest(final ExactCapturedRow captured,
        final ExactCurrentRow current) {
        return sameExactState(captured, current);
    }

    /** Test seam for the same identity/state/coordinate gate used by the live exact dispatcher. */
    static RightClickDispatch dispatchExactResolvedRowForTest(
        final ExactCapturedRow captured, final ExactCurrentRow selected,
        final ExactCurrentRow refreshed) {
        if (!SwingUtilities.isEventDispatchThread()) {
            return RightClickDispatch.notDispatched("exact test dispatch must run on EDT");
        }
        return dispatchExactResolvedSelection(captured, selected, true,
            () -> refreshed == null ? ExactRowResolution.unavailable("refreshed row unavailable")
                : ExactRowResolution.available(refreshed)).dispatch();
    }

    private static String exactStateChange(final ExactCapturedRow captured,
        final ExactCurrentRow current) {
        return "visible/locked changed: before=" + captured.state()
            + " after=" + (current == null ? "unavailable" : current.descriptor().state());
    }

    private static String exactRowDispatchPrefix(final ExactCapturedRow captured,
        final ExactCurrentRow selection) {
        return "capture=" + captured.diagnostic()
            + " selection=" + selection.diagnostic()
            + " selectionStateUnchanged=" + sameExactState(captured, selection) + " ";
    }

    private static String objectIdentity(final Object value) {
        if (value == null) return "null";
        return value.getClass().getName() + '@'
            + Integer.toHexString(System.identityHashCode(value));
    }

    private static String requireIdentityText(final String value, final String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    /**
     * Offline-only generic resolver seams retained for regression tests. {@link #runGui} never
     * calls these widgets or their label/value keys; the live GUI path is exact-table-only.
     */
    private sealed interface RowWidget {
        String name();
        List<CapturedRow> captureRows() throws Exception;
        RowAttempt rightClick(CapturedRow row) throws Exception;
    }

    private record TreeWidget(JTree tree) implements RowWidget {
        public String name() { return tree.getClass().getName(); }
        public List<CapturedRow> captureRows() throws Exception {
            final AtomicReference<List<CapturedRow>> rows = new AtomicReference<>(List.of());
            SwingUtilities.invokeAndWait(() -> rows.set(captureRowsOnEdt(
                tree, RowKind.TREE)));
            return rows.get();
        }
        public RowAttempt rightClick(final CapturedRow row) throws Exception {
            return rightClickRow(row);
        }
    }

    private record TableWidget(javax.swing.JTable table) implements RowWidget {
        public String name() { return table.getClass().getName(); }
        public List<CapturedRow> captureRows() throws Exception {
            final AtomicReference<List<CapturedRow>> rows = new AtomicReference<>(List.of());
            SwingUtilities.invokeAndWait(() -> rows.set(captureRowsOnEdt(
                table, RowKind.TABLE)));
            return rows.get();
        }
        public RowAttempt rightClick(final CapturedRow row) throws Exception {
            return rightClickRow(row);
        }
    }

    private record ListWidget(javax.swing.JList<?> list) implements RowWidget {
        public String name() { return list.getClass().getName(); }
        public List<CapturedRow> captureRows() throws Exception {
            final AtomicReference<List<CapturedRow>> rows = new AtomicReference<>(List.of());
            SwingUtilities.invokeAndWait(() -> rows.set(captureRowsOnEdt(
                list, RowKind.LIST)));
            return rows.get();
        }
        public RowAttempt rightClick(final CapturedRow row) throws Exception {
            return rightClickRow(row);
        }
    }

    private static final int MAX_ROW_DISPATCH_ATTEMPTS = 3;

    @FunctionalInterface
    interface RowDispatchAttempt {
        boolean retry(int attemptNumber) throws Exception;
    }

    /** Runs the same bounded retry policy used by the live row dispatcher. */
    static int runBoundedRowDispatches(final RowDispatchAttempt operation) throws Exception {
        if (operation == null) throw new IllegalArgumentException("row dispatch operation is required");
        int attempts = 0;
        while (attempts < MAX_ROW_DISPATCH_ATTEMPTS) {
            attempts++;
            if (!operation.retry(attempts)) break;
        }
        return attempts;
    }

    private static RowAttempt rightClickRow(final CapturedRow captured) throws Exception {
        return rowAttempt(dismissPopup(), captured);
    }

    /**
     * Runs one captured row through a bounded stale-widget recovery loop. Every invocation of
     * {@link #dispatchCapturedRow(CapturedRow, boolean)} is made on the EDT; returning to the
     * worker between retries gives a replacement table a chance to become visible.
     */
    private static RowAttempt rowAttempt(PopupAttempt attempt, final CapturedRow captured)
        throws Exception {
        final AtomicReference<RowDispatchResult> rowOutcome = new AtomicReference<>();
        final AtomicReference<RightClickDispatchException> dispatchFailure = new AtomicReference<>();
        final AtomicReference<PopupAttempt> popupAttempt = new AtomicReference<>(attempt);
        final List<String> retryReasons = new ArrayList<>();
        final int attempts = runBoundedRowDispatches(attemptNumber -> {
            rowOutcome.set(null);
            try {
                final boolean triggerOnPress = popupTriggerOnPress(
                    System.getProperty("os.name", ""));
                SwingUtilities.invokeAndWait(() -> rowOutcome.set(
                    dispatchCapturedRow(captured, triggerOnPress)));
            } catch (InvocationTargetException wrapped) {
                if (wrapped.getCause() instanceof RightClickDispatchException failure) {
                    dispatchFailure.set(failure);
                    return false;
                }
                throw wrapped;
            }

            final RowDispatchResult result = rowOutcome.get();
            if (result == null || !result.retryable()) return false;
            retryReasons.add("attempt=" + attemptNumber + " phase=" + result.phase()
                + " reason=" + result.reason());
            if (attemptNumber < MAX_ROW_DISPATCH_ATTEMPTS) {
                final PopupAttempt retryPopup = dismissPopup();
                popupAttempt.set(mergePopupAttempts(popupAttempt.get(), retryPopup));
            }
            return true;
        });
        final PopupCapture popup = awaitPopup(popupAttempt.get());
        final RowDispatchResult rowResult = rowOutcome.get();
        final RightClickDispatchException failure = dispatchFailure.get();
        final String dispatchDiagnostic = rowResult != null
            ? rowResult.dispatch().diagnostic()
            : failure != null
                ? failure.dispatch().diagnostic()
                : "not-dispatched";
        final String failureTrace = failure == null ? "" : stackTrace(failure);
        final String diagnostic = "capture=" + captured.diagnostic()
            + " attempts=" + attempts
            + " dispatchRow=" + (rowResult == null ? -1 : rowResult.row())
            + " dispatchComponent=" + (rowResult == null ? "" : rowResult.component())
            + " retryLimit=" + MAX_ROW_DISPATCH_ATTEMPTS
            + " retryReasons=" + retryReasons
            + " " + dispatchDiagnostic
            + (failureTrace.isBlank() ? "" : " dispatchExceptionTrace=" + failureTrace)
            + " popupCount=" + popup.popupCount()
            + " popupMaxCount=" + popup.maxPopupCount()
            + " " + popup.diagnostic();
        return new RowAttempt(popup.popup(), popup.popupCount(), diagnostic, failureTrace,
            rowResult == null ? -1 : rowResult.row(),
            rowResult == null ? "" : rowResult.component(), false);
    }

    private static RowDispatchResult dispatchCapturedRow(final CapturedRow captured,
        final boolean triggerOnPress) {
        RowResolution selection = resolveActiveRow(captured);
        if (!selection.available()) {
            return RowDispatchResult.retry(captured, "before-left", selection.reason());
        }

        // Preserve the old tree behavior, but re-resolve because expansion itself can rebuild the
        // host widget before the selection click.
        if (selection.row().component() instanceof JTree tree) {
            tree.expandRow(selection.row().row());
            selection = resolveActiveRow(captured);
            if (!selection.available()) {
                return RowDispatchResult.retry(captured, "before-left", selection.reason());
            }
        }

        final AtomicReference<RowResolution> afterSelection = new AtomicReference<>();
        final CurrentRow selected = selection.row();
        final String prefix = rowDispatchPrefix(captured, selected);
        try {
            final RightClickDispatch dispatch = dispatchRightClickAfterSelection(
                selected.component(), centerX(selected.bounds()), centerY(selected.bounds()),
                triggerOnPress, prefix, () -> {
                    final RowResolution resolved = resolveActiveRow(captured);
                    afterSelection.set(resolved);
                    if (!resolved.available()) return null;
                    final CurrentRow row = resolved.row();
                    return new DispatchTarget(row.component(), centerX(row.bounds()),
                        centerY(row.bounds()));
                });
            final CurrentRow dispatched = afterSelection.get().row();
            return RowDispatchResult.success(dispatch, dispatched);
        } catch (RowRelocationException retry) {
            final RowResolution resolved = afterSelection.get();
            final String reason = resolved == null
                ? retry.getMessage() : resolved.reason();
            return RowDispatchResult.retry(captured, "after-left", reason);
        }
    }

    private static String rowDispatchPrefix(final CapturedRow captured,
        final CurrentRow selection) {
        return "captureWidget=" + captured.widgetIdentity()
            + " captureRow=" + captured.row()
            + " captureWindow=" + captured.windowIdentity()
            + " captureRowKey=" + captured.rowKeyDiagnostic()
            + " captureBounds=" + captured.bounds()
            + " selectionWidget=" + componentIdentity(selection.component())
            + " selectionState=" + componentState(selection.component())
            + " selectionRow=" + selection.row()
            + " selectionBounds=" + boundsMarker(selection.bounds()) + " ";
    }

    private static int centerX(final Rectangle bounds) {
        return bounds.x + bounds.width / 2;
    }

    private static int centerY(final Rectangle bounds) {
        return bounds.y + bounds.height / 2;
    }

    private static void dispatchLeftClick(final Component target, final int x, final int y) {
        final long now = System.currentTimeMillis();
        target.dispatchEvent(new MouseEvent(target, MouseEvent.MOUSE_PRESSED, now,
            InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1));
        target.dispatchEvent(new MouseEvent(target, MouseEvent.MOUSE_RELEASED, now,
            InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1));
        target.dispatchEvent(new MouseEvent(target, MouseEvent.MOUSE_CLICKED, now,
            InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1));
    }

    static RightClickDispatch dispatchRightClick(final Component target, final int x, final int y) {
        return dispatchRightClick(target, x, y,
            popupTriggerOnPress(System.getProperty("os.name", "")));
    }

    static RightClickDispatch dispatchRightClick(final Component target, final int x, final int y,
        final boolean triggerOnPress) {
        dispatchLeftClick(target, x, y);
        return dispatchRightClickEvents(target, x, y, triggerOnPress, "");
    }

    /**
     * Dispatches the right-click after the selection click has been completed. The caller supplies
     * a fresh target, so a host table replacement between the two phases cannot receive the right
     * click merely because it was the object captured before selection.
     */
    static RightClickDispatch dispatchRightClickAfterSelection(final Component selectionTarget,
        final int selectionX, final int selectionY, final boolean triggerOnPress,
        final java.util.function.Supplier<DispatchTarget> refreshedTarget) {
        return dispatchRightClickAfterSelection(selectionTarget, selectionX, selectionY,
            triggerOnPress, "", refreshedTarget);
    }

    private static RightClickDispatch dispatchRightClickAfterSelection(
        final Component selectionTarget, final int selectionX, final int selectionY,
        final boolean triggerOnPress, final String diagnosticPrefix,
        final java.util.function.Supplier<DispatchTarget> refreshedTarget) {
        try {
            dispatchLeftClick(selectionTarget, selectionX, selectionY);
        } catch (RuntimeException failure) {
            final RightClickDispatch dispatch = new RightClickDispatch(diagnosticPrefix
                + "selectionTarget=" + componentState(selectionTarget)
                + " selectionCoordinates=(" + selectionX + ',' + selectionY + ')');
            throw new RightClickDispatchException(dispatch,
                List.of(new DispatchFailure("MOUSE_LEFT", failure)));
        }
        final DispatchTarget target = refreshedTarget.get();
        if (target == null || target.component() == null) {
            throw new RowRelocationException(
                "active row could not be revalidated after the selection click");
        }
        return dispatchRightClickEvents(target.component(), target.x(), target.y(),
            triggerOnPress, diagnosticPrefix
                + " dispatchWidget=" + componentIdentity(target.component())
                + " dispatchState=" + componentState(target.component())
                + " dispatchCoordinates=(" + target.x() + ',' + target.y() + ") "
                + (target.diagnostic().isBlank() ? "" : target.diagnostic() + " "));
    }

    private static RightClickDispatch dispatchRightClickEvents(final Component target,
        final int x, final int y, final boolean triggerOnPress, final String diagnosticPrefix) {
        final long now = System.currentTimeMillis();
        final MouseEvent pressed = new MouseEvent(target, MouseEvent.MOUSE_PRESSED, now,
            InputEvent.BUTTON3_DOWN_MASK, x, y, 1, triggerOnPress, MouseEvent.BUTTON3);
        final MouseEvent released = new MouseEvent(target, MouseEvent.MOUSE_RELEASED, now,
            InputEvent.BUTTON3_DOWN_MASK, x, y, 1, !triggerOnPress, MouseEvent.BUTTON3);
        final List<DispatchFailure> failures = new ArrayList<>();
        try {
            target.dispatchEvent(pressed);
        } catch (RuntimeException failure) {
            failures.add(new DispatchFailure("MOUSE_PRESSED", failure));
        }
        try {
            target.dispatchEvent(released);
        } catch (RuntimeException failure) {
            failures.add(new DispatchFailure("MOUSE_RELEASED", failure));
        }
        final RightClickDispatch dispatch = new RightClickDispatch(diagnosticPrefix
            + "synthetic=" + mouseEventMarker(pressed) + "," + mouseEventMarker(released)
                + " triggerPhase=" + (triggerOnPress ? "MOUSE_PRESSED" : "MOUSE_RELEASED")
                + " target=" + componentState(target)
                + " renderer=" + rendererState(target, x, y)
                + " pointer=" + pointerState());
        if (!failures.isEmpty()) throw new RightClickDispatchException(dispatch, failures);
        return dispatch;
    }

    static boolean popupTriggerOnPress(final String osName) {
        return !(osName == null ? "" : osName).toLowerCase(Locale.ROOT).contains("win");
    }

    private static PopupCapture awaitPopup(final PopupAttempt attempt) throws Exception {
        // The host may build and show the popup asynchronously after the event returns.
        final List<String> polls = new ArrayList<>();
        PopupSnapshot last = new PopupSnapshot(null, 0, "none", "[]");
        int maxPopupCount = 0;
        for (int poll = 0; poll < 20; poll++) {
            final AtomicReference<PopupSnapshot> snapshot = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                final List<JPopupMenu> visibleAfter = popupMenus(true);
                final JPopupMenu associated = popupForAttempt(
                    attempt.visibleBefore(), attempt.dismissed(), visibleAfter);
                snapshot.set(new PopupSnapshot(associated, visibleAfter.size(),
                    popupAssociation(attempt, associated), popupMarkers(visibleAfter)));
            });
            last = snapshot.get();
            maxPopupCount = Math.max(maxPopupCount, last.popupCount());
            polls.add("poll=" + poll + " popupCount=" + last.popupCount()
                + " association=" + last.association() + " menus=" + last.markers());
            if (last.popup() != null) {
                return new PopupCapture(last.popup(), last.popupCount(), maxPopupCount,
                    popupDiagnostic(polls));
            }
            Thread.sleep(150);
        }
        return new PopupCapture(null, last.popupCount(), maxPopupCount,
            popupDiagnostic(polls));
    }

    private static String popupAssociation(final PopupAttempt attempt,
        final JPopupMenu popup) {
        if (popup == null) return "none";
        if (containsIdentity(attempt.dismissed(), popup)) return "reused-dismissed";
        if (containsIdentity(attempt.visibleBefore(), popup)) return "stale-visible-before";
        return "new";
    }

    private static String popupDiagnostic(final List<String> polls) {
        if (polls.isEmpty()) return "popupPolls=0";
        return "popupPolls=" + polls.size() + " last=" + polls.get(polls.size() - 1)
            + " history=" + String.join(" || ", polls);
    }

    private static String popupMarkers(final List<JPopupMenu> popups) {
        final List<String> markers = new ArrayList<>(popups.size());
        for (JPopupMenu popup : popups) markers.add(popupMarker(popup));
        return markers.toString();
    }

    /** Returns an identity and Swing visibility marker without reading host-private state. */
    static String popupMarker(final JPopupMenu popup) {
        if (popup == null) return "null";
        final StringBuilder marker = new StringBuilder(componentState(popup));
        marker.append(" invoker=").append(componentState(popup.getInvoker()));
        marker.append(" components=");
        appendMenuComponents(popup, marker, 0);
        return marker.toString();
    }

    private static void appendMenuComponents(final Container container,
        final StringBuilder marker, final int depth) {
        marker.append('[');
        final Component[] components = container.getComponents();
        for (int index = 0; index < components.length; index++) {
            if (index > 0) marker.append(", ");
            final Component component = components[index];
            marker.append(index).append(':').append(menuComponentMarker(component));
            if (depth < 4 && component instanceof Container child) {
                marker.append(" children=");
                appendMenuComponents(child, marker, depth + 1);
            }
        }
        marker.append(']');
    }

    private static String menuComponentMarker(final Component component) {
        final StringBuilder marker = new StringBuilder(componentState(component));
        if (component instanceof JMenuItem item) {
            marker.append(" menuItem=true text=").append(quoted(item.getText()))
                .append(" actionCommand=").append(quoted(item.getActionCommand()));
        }
        return marker.toString();
    }

    private static String quoted(final String value) {
        if (value == null) return "<null>";
        return "'" + value.replace("\\", "\\\\")
            .replace("\r", "\\r").replace("\n", "\\n") + "'";
    }

    private static String componentIdentity(final Component component) {
        if (component == null) return "null";
        return component.getClass().getName() + '@'
            + Integer.toHexString(System.identityHashCode(component));
    }

    private static String componentState(final Component component) {
        if (component == null) return "null";
        final Container parent = component.getParent();
        return componentIdentity(component)
            + "{visible=" + component.isVisible()
            + ",showing=" + component.isShowing()
            + ",displayable=" + component.isDisplayable()
            + ",enabled=" + component.isEnabled()
            + ",parent=" + componentIdentity(parent)
            + ",locationOnScreen=" + locationOnScreen(component) + '}';
    }

    private static String locationOnScreen(final Component component) {
        try {
            final Point point = component.getLocationOnScreen();
            return '(' + Integer.toString(point.x) + ',' + Integer.toString(point.y) + ')';
        } catch (RuntimeException failure) {
            return "<" + failure + ">";
        }
    }

    private static String rendererState(final Component target, final int x, final int y) {
        if (!(target instanceof JTable table)) return "not-JTable";
        // Renderer preparation is host code and may mutate/rebuild a table. It is diagnostic
        // only, so never invoke it for a detached target; in the live path it is deliberately
        // evaluated after the right-click events have completed.
        if (!isLiveComponent(table)) {
            return "target-not-live prepared=<skipped renderer preparation>";
        }
        final Point point = new Point(x, y);
        final int row = table.rowAtPoint(point);
        final int column = table.columnAtPoint(point);
        if (row < 0 || column < 0) {
            return "cell=none row=" + row + " column=" + column;
        }
        try {
            final TableCellRenderer renderer = table.getCellRenderer(row, column);
            final Component prepared = table.prepareRenderer(renderer, row, column);
            return "cell=" + row + "," + column
                + " renderer=" + rendererIdentity(renderer)
                + " prepared=" + componentState(prepared);
        } catch (RuntimeException failure) {
            return "cell=" + row + "," + column
                + " rendererError=" + failure;
        }
    }

    private static String rendererIdentity(final TableCellRenderer renderer) {
        if (renderer == null) return "null";
        if (renderer instanceof Component component) return componentIdentity(component);
        return renderer.getClass().getName() + '@'
            + Integer.toHexString(System.identityHashCode(renderer));
    }

    private static String pointerState() {
        try {
            final PointerInfo pointer = MouseInfo.getPointerInfo();
            if (pointer == null) return "null";
            final Point location = pointer.getLocation();
            return '(' + Integer.toString(location.x) + ',' + Integer.toString(location.y) + ')';
        } catch (RuntimeException failure) {
            return "<" + failure + ">";
        }
    }

    private static String mouseEventMarker(final MouseEvent event) {
        return "{" + mouseEventName(event.getID())
            + ",local=(" + event.getX() + ',' + event.getY() + ')'
            + ",screen=(" + event.getXOnScreen() + ',' + event.getYOnScreen() + ')'
            + ",button=" + event.getButton()
            + ",popupTrigger=" + event.isPopupTrigger()
            + ",source=" + componentIdentity(event.getComponent()) + '}';
    }

    private static String mouseEventName(final int id) {
        return switch (id) {
            case MouseEvent.MOUSE_PRESSED -> "MOUSE_PRESSED";
            case MouseEvent.MOUSE_RELEASED -> "MOUSE_RELEASED";
            case MouseEvent.MOUSE_CLICKED -> "MOUSE_CLICKED";
            default -> Integer.toString(id);
        };
    }

    private static String stackTrace(final Throwable failure) {
        final StringWriter trace = new StringWriter();
        failure.printStackTrace(new java.io.PrintWriter(trace));
        return trace.toString();
    }

    private record GuiClick(boolean clicked, String diagnostic) {
    }

    /** Bounded evidence for one native close coordinator invocation. */
    static final class CloseSessionTrace {
        private final String sessionId;
        private final long startedAtNanos;
        private final AtomicInteger dispatchAttempts = new AtomicInteger();
        private final AtomicInteger inspectionCount = new AtomicInteger();
        private final AtomicLong beforeAtNanos = new AtomicLong(-1L);
        private final AtomicLong dispatchAtNanos = new AtomicLong(-1L);
        private final AtomicLong firstInspectionAtNanos = new AtomicLong(-1L);
        private final AtomicReference<String> before = new AtomicReference<>("unavailable");
        private final AtomicReference<String> dispatch = new AtomicReference<>("not-dispatched");
        private final AtomicReference<String> lastAfter = new AtomicReference<>("unavailable");
        private final AtomicReference<String> reason = new AtomicReference<>("unavailable");

        CloseSessionTrace() {
            this("close-" + CLOSE_SESSION_SEQUENCE.incrementAndGet());
        }

        CloseSessionTrace(final String sessionId) {
            this.sessionId = sessionId == null || sessionId.isBlank() ? "unknown" : sessionId;
            startedAtNanos = System.nanoTime();
        }

        void recordBefore(final List<CloseDialogSnapshot> dialogs) {
            beforeAtNanos.compareAndSet(-1L, System.nanoTime());
            before.set(dialogInventoryDiagnostic(dialogs, CLOSE_TRACE_BEFORE_MAX_BYTES));
        }

        void recordDispatchAttempt() {
            dispatchAttempts.incrementAndGet();
            dispatchAtNanos.compareAndSet(-1L, System.nanoTime());
            dispatch.set("started");
        }

        void recordDispatch(final CloseDispatchResult result) {
            dispatch.set(boundedCloseDiagnostic(
                result == null ? "unavailable" : result.diagnostic(),
                CLOSE_TRACE_DISPATCH_MAX_BYTES));
        }

        void recordInspection(final List<CloseDialogSnapshot> dialogs) {
            inspectionCount.incrementAndGet();
            firstInspectionAtNanos.compareAndSet(-1L, System.nanoTime());
            lastAfter.set(dialogInventoryDiagnostic(dialogs, CLOSE_TRACE_AFTER_MAX_BYTES));
        }

        void recordReason(final String value) {
            reason.set(boundedCloseDiagnostic(value, CLOSE_TRACE_REASON_MAX_BYTES));
        }

        String diagnostic() {
            final long dispatchNanos = dispatchAtNanos.get();
            final long firstInspectionNanos = firstInspectionAtNanos.get();
            return boundedCloseDiagnostic(
                "closeSession=" + boundedCloseField(sessionId)
                    + " elapsedMs=" + elapsedMillis(startedAtNanos)
                    + " dispatchAttempts=" + dispatchAttempts.get()
                    + " inspectionCount=" + inspectionCount.get()
                    + " beforeToDispatchMs=" + elapsedBetween(beforeAtNanos.get(), dispatchNanos)
                    + " dispatchToFirstInspectionMs="
                    + elapsedBetween(dispatchNanos, firstInspectionNanos)
                    + " before=" + before.get()
                    + " after=" + lastAfter.get()
                    + " dispatch=" + dispatch.get()
                    + " reason=" + reason.get(), CLOSE_TRACE_MAX_BYTES);
        }

        String sessionId() { return sessionId; }

        private static long elapsedMillis(final long startNanos) {
            return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos));
        }

        private static long elapsedBetween(final long startNanos, final long endNanos) {
            if (startNanos < 0L || endNanos < 0L || endNanos < startNanos) return -1L;
            return TimeUnit.NANOSECONDS.toMillis(endNanos - startNanos);
        }
    }

    private static HostCloseResult withCloseTrace(final CloseSessionTrace trace,
        final HostCloseResult result) {
        if (trace == null || result == null) return result;
        final String resultReason = boundedCloseDiagnostic(result.diagnostic(),
            CLOSE_RESULT_REASON_MAX_BYTES);
        final String traceDiagnostic = boundedCloseDiagnostic(trace.diagnostic(),
            CLOSE_TRACE_MAX_BYTES);
        return new HostCloseResult(result.success(), boundedCloseDiagnostic(
            resultReason + CLOSE_TRACE_SEPARATOR + traceDiagnostic,
            CLOSE_DIAGNOSTIC_MAX_BYTES));
    }

    /** Test seam for asserting the final close-result byte budget without exposing host results. */
    static String closeDiagnosticForTest(final CloseSessionTrace trace,
        final String resultDiagnostic) {
        return withCloseTrace(trace, HostCloseResult.rejected(resultDiagnostic)).diagnostic();
    }

    private static String dialogInventoryDiagnostic(final List<CloseDialogSnapshot> dialogs) {
        return dialogInventoryDiagnostic(dialogs, CLOSE_TRACE_REASON_MAX_BYTES);
    }

    private static String dialogInventoryDiagnostic(final List<CloseDialogSnapshot> dialogs,
        final int maxBytes) {
        if (dialogs == null) return "unavailable";
        final int includedLimit = Math.min(dialogs.size(), CLOSE_DIALOG_MAX_COUNT);
        final StringBuilder text = new StringBuilder("[count=")
            .append(dialogs.size());
        if (dialogs.size() > includedLimit) text.append(" truncated=true");
        text.append(" entries=");
        for (int index = 0; index < includedLimit; index++) {
            if (index > 0) text.append(", ");
            final CloseDialogSnapshot dialog = dialogs.get(index);
            text.append(dialog == null ? "null" : dialog.diagnostic());
        }
        if (dialogs.size() > includedLimit) text.append(", …more");
        return boundedCloseDiagnostic(text.append(']').toString(), maxBytes);
    }

    private static String boundedCloseDiagnostic(final String value) {
        return boundedCloseDiagnostic(value, CLOSE_DIAGNOSTIC_MAX_BYTES);
    }

    /** Returns a single-line UTF-8 string whose encoded byte length never exceeds maxBytes. */
    private static String boundedCloseDiagnostic(final String value, final int maxBytes) {
        if (value == null || maxBytes <= 0) return "";
        final String singleLine = value.replace('\r', ' ').replace('\n', ' ');
        if (utf8ByteLength(singleLine) <= maxBytes) return singleLine;
        final String marker = "…";
        final int markerBytes = utf8ByteLength(marker);
        if (maxBytes <= markerBytes) return utf8Prefix(singleLine, maxBytes);
        return utf8Prefix(singleLine, maxBytes - markerBytes) + marker;
    }

    private static String utf8Prefix(final String value, final int maxBytes) {
        if (value == null || maxBytes <= 0) return "";
        int offset = 0;
        int bytes = 0;
        while (offset < value.length()) {
            final int next = value.offsetByCodePoints(offset, 1);
            final int codePointBytes = utf8ByteLength(value.substring(offset, next));
            if (bytes + codePointBytes > maxBytes) break;
            bytes += codePointBytes;
            offset = next;
        }
        return value.substring(0, offset);
    }

    private static int utf8ByteLength(final String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String boundedCloseField(final String value) {
        return boundedCloseDiagnostic(value, CLOSE_DIALOG_FIELD_MAX_BYTES);
    }

    private static String boundedCloseList(final List<String> values) {
        if (values == null) return "null";
        final StringBuilder text = new StringBuilder("[");
        final int limit = Math.min(values.size(), 8);
        for (int index = 0; index < limit; index++) {
            if (index > 0) text.append(", ");
            text.append(boundedCloseField(values.get(index)));
        }
        if (values.size() > limit) text.append(", ...");
        return text.append(']').toString();
    }

    enum CloseDialogOutcome {
        NO_DIALOG,
        DISMISS_NO,
        REJECTED
    }

    static record CloseDialogSnapshot(String dialogIdentity, String ownerIdentity,
        boolean ownerMatchesTarget, boolean showing, boolean displayable, boolean modal,
        String modalityType, int optionPaneCount, List<String> optionClassNames,
        List<String> optionLabels, int initialOptionIndex, Runnable dismissNo,
        BooleanSupplier dismissNoReady) {
        CloseDialogSnapshot(final String dialogIdentity, final String ownerIdentity,
            final boolean ownerMatchesTarget, final boolean showing, final boolean displayable,
            final int optionPaneCount, final List<String> optionClassNames,
            final List<String> optionLabels, final int initialOptionIndex,
            final Runnable dismissNo) {
            this(dialogIdentity, ownerIdentity, ownerMatchesTarget, showing, displayable,
                false, "UNKNOWN", optionPaneCount, optionClassNames, optionLabels,
                initialOptionIndex, dismissNo, dismissNo == null ? () -> false : () -> true);
        }

        CloseDialogSnapshot(final String dialogIdentity, final String ownerIdentity,
            final boolean ownerMatchesTarget, final boolean showing, final boolean displayable,
            final int optionPaneCount, final List<String> optionClassNames,
            final List<String> optionLabels, final int initialOptionIndex,
            final Runnable dismissNo, final BooleanSupplier dismissNoReady) {
            this(dialogIdentity, ownerIdentity, ownerMatchesTarget, showing, displayable,
                false, "UNKNOWN", optionPaneCount, optionClassNames, optionLabels,
                initialOptionIndex, dismissNo, dismissNoReady);
        }

        CloseDialogSnapshot {
            dialogIdentity = dialogIdentity == null ? "" : dialogIdentity;
            ownerIdentity = ownerIdentity == null ? "" : ownerIdentity;
            modalityType = modalityType == null || modalityType.isBlank()
                ? "UNKNOWN" : modalityType;
            optionClassNames = optionClassNames == null ? List.of()
                : List.copyOf(optionClassNames);
            optionLabels = optionLabels == null ? List.of() : List.copyOf(optionLabels);
            dismissNoReady = dismissNoReady == null ? () -> false : dismissNoReady;
        }

        String diagnostic() {
            return "dialog=" + boundedCloseField(dialogIdentity)
                + " owner=" + boundedCloseField(ownerIdentity)
                + " ownerMatches=" + ownerMatchesTarget
                + " showing=" + showing
                + " displayable=" + displayable
                + " modal=" + modal
                + " modality=" + boundedCloseField(modalityType)
                + " panes=" + optionPaneCount
                + " optionClasses=" + boundedCloseList(optionClassNames)
                + " optionLabels=" + boundedCloseList(optionLabels)
                + " initial=" + initialOptionIndex;
        }
    }

    static record CloseDialogDecision(CloseDialogOutcome outcome, String diagnostic,
        Runnable dismissNo) {
        CloseDialogDecision {
            outcome = Objects.requireNonNull(outcome, "outcome");
            diagnostic = diagnostic == null ? "" : diagnostic;
        }

        static CloseDialogDecision noDialog(final String diagnostic) {
            return new CloseDialogDecision(CloseDialogOutcome.NO_DIALOG, diagnostic, null);
        }

        static CloseDialogDecision dismissNo(final String diagnostic, final Runnable action) {
            return new CloseDialogDecision(CloseDialogOutcome.DISMISS_NO, diagnostic, action);
        }

        static CloseDialogDecision rejected(final String diagnostic) {
            return new CloseDialogDecision(CloseDialogOutcome.REJECTED, diagnostic, null);
        }
    }

    static record CloseDialogInspection(boolean targetGone, boolean dismissedNo,
        boolean rejected, String diagnostic) {
        static CloseDialogInspection targetGone(final String diagnostic) {
            return new CloseDialogInspection(true, false, false, diagnostic);
        }

        static CloseDialogInspection waiting(final String diagnostic) {
            return new CloseDialogInspection(false, false, false, diagnostic);
        }

        static CloseDialogInspection dismissed(final String diagnostic) {
            return new CloseDialogInspection(false, true, false, diagnostic);
        }

        static CloseDialogInspection rejected(final String diagnostic) {
            return new CloseDialogInspection(false, false, true, diagnostic);
        }
    }

    static record CloseDispatchResult(boolean dispatched, String diagnostic) {
        CloseDispatchResult {
            diagnostic = diagnostic == null ? "" : diagnostic;
        }

        static CloseDispatchResult dispatched(final String diagnostic) {
            return new CloseDispatchResult(true, diagnostic);
        }

        static CloseDispatchResult rejected(final String diagnostic) {
            return new CloseDispatchResult(false, diagnostic);
        }
    }

    private record HostCloseResult(boolean success, String diagnostic) {
        HostCloseResult {
            diagnostic = diagnostic == null ? "" : diagnostic;
        }

        static HostCloseResult success(final String diagnostic) {
            return new HostCloseResult(true, diagnostic);
        }

        static HostCloseResult rejected(final String diagnostic) {
            return new HostCloseResult(false, diagnostic);
        }

        static HostCloseResult timeout(final String diagnostic) {
            return new HostCloseResult(false, diagnostic);
        }
    }

    private record EdtCall<T>(boolean completed, T value, Throwable failure) {
        static <T> EdtCall<T> completed(final T value) {
            return new EdtCall<>(true, value, null);
        }

        static <T> EdtCall<T> failed(final Throwable failure) {
            return new EdtCall<>(true, null, failure);
        }

        static <T> EdtCall<T> timeout() {
            return new EdtCall<>(false, null, null);
        }
    }

    private List<RowWidget> visibleRowWidgets() throws Exception {
        final AtomicReference<List<RowWidget>> found = new AtomicReference<>(List.of());
        SwingUtilities.invokeAndWait(() -> {
            final List<RowWidget> widgets = new ArrayList<>();
            for (final Frame frame : Frame.getFrames()) {
                collectRowWidgets(frame, widgets);
            }
            found.set(widgets);
        });
        return found.get();
    }

    private static void collectRowWidgets(final Container container,
        final List<RowWidget> widgets) {
        for (final Component component : container.getComponents()) {
            if (component instanceof JTree tree && isLiveComponent(tree)) {
                widgets.add(new TreeWidget(tree));
            } else if (component instanceof javax.swing.JList<?> list
                && isLiveComponent(list)) {
                widgets.add(new ListWidget(list));
            } else if (component instanceof javax.swing.JTable table
                && isLiveComponent(table)) {
                // CTreeTable hosts a JTree inside a JTable; the table receives the clicks.
                widgets.add(new TableWidget(table));
            }
            if (component instanceof Container child) {
                collectRowWidgets(child, widgets);
            }
        }
    }

    private static boolean isLiveComponent(final Component component) {
        return component != null && component.isShowing() && component.isDisplayable()
            && component.getParent() != null;
    }

    /** Captures row keys and bounds without calling a renderer, and must run on the EDT. */
    private static List<CapturedRow> captureRowsOnEdt(final Component component,
        final RowKind kind) {
        // The widget was discovered while showing, but may be detached by the time this
        // snapshot runs. Keep its row key/bounds for relocation diagnostics; live validation is
        // intentionally performed again by resolveActiveRow immediately before dispatch.
        if (component == null) return List.of();
        final int count = rowCount(component, kind);
        final List<CapturedRow> rows = new ArrayList<>(Math.max(0, count));
        for (int row = 0; row < count; row++) {
            final Rectangle bounds;
            try {
                bounds = rowBounds(component, kind, row);
            } catch (RuntimeException failure) {
                rows.add(capturedRow(component, kind, row, null, RowKey.unavailable()));
                continue;
            }
            final RowKey key = rowKey(component, kind, row);
            rows.add(capturedRow(component, kind, row, bounds, key));
        }
        return List.copyOf(rows);
    }

    private static CapturedRow capturedRow(final Component component, final RowKind kind,
        final int row, final Rectangle bounds, final RowKey key) {
        return new CapturedRow(kind, component.getClass().getName(),
            componentIdentity(component), componentIdentity(
                SwingUtilities.getWindowAncestor(component)), row,
            key.value(), key.available(), boundsMarker(bounds));
    }

    private static int rowCount(final Component component, final RowKind kind) {
        return switch (kind) {
            case TREE -> ((JTree) component).getRowCount();
            case TABLE -> ((JTable) component).getRowCount();
            case LIST -> ((javax.swing.JList<?>) component).getModel().getSize();
        };
    }

    private static Rectangle rowBounds(final Component component, final RowKind kind,
        final int row) {
        return switch (kind) {
            case TREE -> ((JTree) component).getRowBounds(row);
            case TABLE -> {
                final JTable table = (JTable) component;
                yield table.getColumnCount() == 0 ? null : table.getCellRect(row, 0, true);
            }
            case LIST -> ((javax.swing.JList<?>) component).getCellBounds(row, row);
        };
    }

    private static boolean validBounds(final Rectangle bounds) {
        return bounds != null && bounds.width > 0 && bounds.height > 0;
    }

    private static RowKey rowKey(final Component component, final RowKind kind, final int row) {
        try {
            return switch (kind) {
                case TREE -> treeRowKey((JTree) component, row);
                case TABLE -> tableRowKey((JTable) component, row);
                case LIST -> listRowKey((javax.swing.JList<?>) component, row);
            };
        } catch (RuntimeException failure) {
            return RowKey.unavailable();
        }
    }

    /** Package-private seam for exercising the same row-key calculation as captureRowsOnEdt. */
    static RowKey rowKeyForTest(final Component component, final RowKind kind, final int row) {
        return rowKey(component, kind, row);
    }

    private static RowKey treeRowKey(final JTree tree, final int row) {
        final javax.swing.tree.TreePath path = tree.getPathForRow(row);
        if (path == null) return RowKey.unavailable();
        final StringBuilder key = new StringBuilder("tree:");
        for (final Object value : path.getPath()) {
            final Optional<String> stable = stableRowValue(value);
            if (stable.isEmpty()) return RowKey.unavailable();
            key.append(stable.get());
        }
        return RowKey.of(key.toString());
    }

    private static RowKey tableRowKey(final JTable table, final int row) {
        if (table.getColumnCount() == 0) return RowKey.unavailable();
        final StringBuilder key = new StringBuilder("table:");
        for (int column = 0; column < table.getColumnCount(); column++) {
            final Optional<String> stable = stableRowValue(table.getValueAt(row, column));
            if (stable.isEmpty()) return RowKey.unavailable();
            key.append(stable.get());
        }
        return RowKey.of(key.toString());
    }

    private static RowKey listRowKey(final javax.swing.JList<?> list, final int row) {
        final Optional<String> stable = stableRowValue(list.getModel().getElementAt(row));
        return stable.isEmpty() ? RowKey.unavailable() : RowKey.of("list:" + stable.get());
    }

    /**
     * Returns a full, structured value token for the host's row values. The table/tree/list
     * models expose labels and other value objects through toString; identity-only text cannot
     * relocate a row across a host component replacement, so it is deliberately unavailable.
     */
    private static Optional<String> stableRowValue(final Object value) {
        if (value == null) return Optional.of(stableToken("null", ""));
        final String type = value.getClass().getName();
        final String text;
        try {
            text = value.toString();
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
        if (text == null || looksLikeDefaultIdentityText(type, text)) {
            return Optional.empty();
        }
        return Optional.of(stableToken(type, text));
    }

    private static String stableToken(final String type, final String value) {
        return lengthPrefix(type) + lengthPrefix(value);
    }

    private static String lengthPrefix(final String value) {
        return value.length() + ":" + value;
    }

    private static boolean looksLikeDefaultIdentityText(final String type, final String text) {
        final String prefix = type + '@';
        if (!text.startsWith(prefix) || text.length() == prefix.length()) return false;
        for (int index = prefix.length(); index < text.length(); index++) {
            final char character = text.charAt(index);
            final boolean hex = character >= '0' && character <= '9'
                || character >= 'a' && character <= 'f'
                || character >= 'A' && character <= 'F';
            if (!hex) return false;
        }
        return true;
    }

    private static List<CurrentRow> currentRowsOnEdt() {
        final List<CurrentRow> rows = new ArrayList<>();
        for (final Frame frame : Frame.getFrames()) {
            if (frame.isShowing()) collectCurrentRows(frame, rows);
        }
        return rows;
    }

    private static void collectCurrentRows(final Container container,
        final List<CurrentRow> rows) {
        for (final Component component : container.getComponents()) {
            if (component instanceof JTree tree && isLiveComponent(tree)) {
                addCurrentRows(tree, RowKind.TREE, rows);
            } else if (component instanceof JTable table && isLiveComponent(table)) {
                addCurrentRows(table, RowKind.TABLE, rows);
            } else if (component instanceof javax.swing.JList<?> list
                && isLiveComponent(list)) {
                addCurrentRows(list, RowKind.LIST, rows);
            }
            if (component instanceof Container child) collectCurrentRows(child, rows);
        }
    }

    private static void addCurrentRows(final Component component, final RowKind kind,
        final List<CurrentRow> rows) {
        for (final CapturedRow row : captureRowsOnEdt(component, kind)) {
            final Rectangle bounds = rowBounds(component, kind, row.row());
            if (validBounds(bounds)) rows.add(new CurrentRow(row, component, bounds));
        }
    }

    /** Resolves a capture to one current live row; ambiguous or unkeyed replacement is rejected. */
    private static RowResolution resolveActiveRow(final CapturedRow captured) {
        final List<CurrentRow> all = currentRowsOnEdt();
        return resolveActiveRow(captured, all);
    }

    /** Package-private seam for testing the production resolver with a deterministic live-row set. */
    static RowResolution resolveActiveRowForTest(final CapturedRow captured,
        final List<CurrentRow> currentRows) {
        return resolveActiveRow(captured, currentRows);
    }

    private static RowResolution resolveActiveRow(final CapturedRow captured,
        final List<CurrentRow> allRows) {
        if (captured == null) return RowResolution.unavailable("captured row is unavailable");
        final List<CurrentRow> all = allRows == null ? List.of() : allRows;
        final List<CurrentRow> sameWidgetType = all.stream()
            .filter(row -> row.descriptor().kind() == captured.kind())
            .filter(row -> row.descriptor().widgetClass().equals(captured.widgetClass()))
            .toList();
        if (sameWidgetType.isEmpty()) {
            return RowResolution.unavailable("no live widget of captured type");
        }

        final List<CurrentRow> sameWindow = sameWidgetType.stream()
            .filter(row -> row.descriptor().windowIdentity().equals(captured.windowIdentity()))
            .toList();
        if (sameWindow.isEmpty()) {
            return RowResolution.unavailable("no live row in captured window");
        }
        final List<CurrentRow> candidates = sameWindow;
        if (!captured.rowKeyAvailable()) {
            return RowResolution.unavailable(
                "captured row has no stable key; row index reuse is refused");
        }

        final List<CurrentRow> keyed = candidates.stream()
            .filter(row -> row.descriptor().rowKeyAvailable())
            .filter(row -> row.descriptor().rowKey().equals(captured.rowKey()))
            .toList();
        final List<CurrentRow> sameIdentity = keyed.stream()
            .filter(row -> row.descriptor().widgetIdentity().equals(captured.widgetIdentity()))
            .filter(row -> row.row() == captured.row())
            .toList();
        if (sameIdentity.size() == 1) return RowResolution.available(sameIdentity.get(0));
        if (sameIdentity.size() > 1) {
            return RowResolution.unavailable("captured widget has ambiguous matching rows");
        }
        if (keyed.size() == 1) return RowResolution.available(keyed.get(0));
        if (keyed.isEmpty()) {
            return RowResolution.unavailable("no live row matches captured row key");
        }
        return RowResolution.unavailable("live row key matches are ambiguous: " + keyed.size());
    }

    private static String boundsMarker(final Rectangle bounds) {
        return bounds == null ? "null"
            : "(" + bounds.x + ',' + bounds.y + ',' + bounds.width + ',' + bounds.height + ')';
    }

    /**
     * Component-hierarchy digest recorded when no row widget could be clicked, so a BLOCKED run
     * still identifies which widget the object list actually is. Lists every showing component
     * in every showing window, deduplicated with counts.
     */
    private String hierarchyDigest() {
        final AtomicReference<String> digest = new AtomicReference<>("");
        try {
            SwingUtilities.invokeAndWait(() -> {
                final StringBuilder text = new StringBuilder();
                final Map<String, Integer> counts = new LinkedHashMap<>();
                for (final Window window : Window.getWindows()) {
                    if (!window.isShowing()) continue;
                    text.append("window(").append(window.getClass().getSimpleName());
                    if (window instanceof Frame frame) text.append(":'").append(frame.getTitle()).append('\'');
                    if (window instanceof Dialog dialog) text.append(":'").append(dialog.getTitle()).append('\'');
                    text.append(") ");
                    collectWidgetNames(window, counts);
                }
                counts.forEach((name, count) -> {
                    if (text.length() < 3500) {
                        text.append('<').append(name).append('x').append(count).append("> ");
                    }
                });
                digest.set(text.toString());
            });
        } catch (Exception ignored) {
        }
        return digest.get();
    }

    private static void collectWidgetNames(final Container container,
        final Map<String, Integer> counts) {
        for (final Component component : container.getComponents()) {
            if (component.isShowing()) {
                counts.merge(component.getClass().getName(), 1, Integer::sum);
            }
            if (component instanceof Container child) {
                collectWidgetNames(child, counts);
            }
        }
    }

    private static List<JPopupMenu> popupMenus(final boolean visibleOnly) {
        final List<JPopupMenu> popups = new ArrayList<>();
        for (final MenuElement element : MenuSelectionManager.defaultManager().getSelectedPath()) {
            if (element instanceof JPopupMenu popup
                && (!visibleOnly || popup.isVisible())) {
                addPopup(popups, popup);
            }
        }
        for (final Window window : Window.getWindows()) {
            collectPopupMenus(window, popups, visibleOnly);
        }
        return popups;
    }

    private static void collectPopupMenus(final Container container,
        final List<JPopupMenu> popups, final boolean visibleOnly) {
        for (final Component component : container.getComponents()) {
            if (component instanceof JPopupMenu popup
                && (!visibleOnly || popup.isVisible())) {
                addPopup(popups, popup);
            }
            if (component instanceof Container child) {
                collectPopupMenus(child, popups, visibleOnly);
            }
        }
    }

    private static void addPopup(final List<JPopupMenu> popups, final JPopupMenu candidate) {
        for (final JPopupMenu popup : popups) {
            if (popup == candidate) return;
        }
        popups.add(candidate);
    }

    static JPopupMenu popupForAttempt(final List<JPopupMenu> visibleBefore,
        final List<JPopupMenu> dismissed, final List<JPopupMenu> visibleAfter) {
        for (final JPopupMenu popup : visibleAfter) {
            if (!containsIdentity(visibleBefore, popup) || containsIdentity(dismissed, popup)) {
                return popup;
            }
        }
        return null;
    }

    private static boolean containsIdentity(final List<JPopupMenu> popups,
        final JPopupMenu candidate) {
        for (final JPopupMenu popup : popups) {
            if (popup == candidate) return true;
        }
        return false;
    }

    private static JMenuItem findItem(final JPopupMenu popup, final Set<String> labels,
        final Set<String> seen) {
        return findItem(popup, labels, seen, 0);
    }

    private static JMenuItem findItem(final Container container, final Set<String> labels,
        final Set<String> seen, final int depth) {
        if (depth > 4) return null;
        for (final Component component : container.getComponents()) {
            if (component instanceof JMenuItem item) {
                if (seen.size() < 200) seen.add(item.getText());
                if (labels.contains(item.getText())) return item;
            }
            if (component instanceof Container child) {
                final JMenuItem item = findItem(child, labels, seen, depth + 1);
                if (item != null) return item;
            }
        }
        return null;
    }

    private static PopupAttempt mergePopupAttempts(final PopupAttempt first,
        final PopupAttempt second) {
        return new PopupAttempt(mergePopupLists(first.visibleBefore(), second.visibleBefore()),
            mergePopupLists(first.dismissed(), second.dismissed()));
    }

    private static List<JPopupMenu> mergePopupLists(final List<JPopupMenu> first,
        final List<JPopupMenu> second) {
        final List<JPopupMenu> merged = new ArrayList<>(first.size() + second.size());
        for (final JPopupMenu popup : first) {
            if (!containsIdentity(merged, popup)) merged.add(popup);
        }
        for (final JPopupMenu popup : second) {
            if (!containsIdentity(merged, popup)) merged.add(popup);
        }
        return List.copyOf(merged);
    }

    private static PopupAttempt dismissPopup() throws Exception {
        final AtomicReference<PopupAttempt> attempt = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            final List<JPopupMenu> visibleBefore = popupMenus(true);
            for (final JPopupMenu popup : visibleBefore) popup.setVisible(false);
            MenuSelectionManager.defaultManager().clearSelectedPath();
            // Clearing the selection can repost or retain a host-owned popup; enforce the close
            // after the manager has processed the selected path as well.
            for (final JPopupMenu popup : visibleBefore) popup.setVisible(false);
            final List<JPopupMenu> dismissed = visibleBefore.stream()
                .filter(popup -> !popup.isVisible())
                .toList();
            attempt.set(new PopupAttempt(List.copyOf(visibleBefore), dismissed));
        });
        return attempt.get();
    }

    private void clickItem(final JMenuItem item) throws Exception {
        SwingUtilities.invokeAndWait(item::doClick);
    }

    /**
     * Binds the GUI session to exactly one directory created after the click and waits for a
     * complete, repeatedly identical PSD snapshot.  The file's mtime is compared as stability
     * metadata, but it is never used to choose a candidate.
     */
    private StablePsdSnapshot awaitSessionTempFile(final TempCandidateSnapshot before,
        final int timeoutSeconds, final Properties result) throws Exception {
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("session PSD timeout must be positive");
        }
        return awaitStableSessionFile(before, timeoutSeconds * 1000L, result,
            () -> stopped, this::snapshotTempCandidates, Thread::sleep,
            PLATFORM_FILE_KEY_SUPPLIER);
    }

    /** Offline seam exercising the exact production candidate/readiness helper on real files. */
    static StablePsdSnapshot awaitSessionFileForTest(final Path temporaryRoot,
        final Set<Path> beforeDirectories, final long timeoutMillis,
        final BooleanSupplier stopped, final TriggerSleeper sleeper) throws Exception {
        return awaitSessionFileForTest(temporaryRoot, beforeDirectories, timeoutMillis, stopped,
            sleeper, PLATFORM_FILE_KEY_SUPPLIER);
    }

    /** Offline seam with a controlled platform file-object token for readiness regression tests. */
    static StablePsdSnapshot awaitSessionFileForTest(final Path temporaryRoot,
        final Set<Path> beforeDirectories, final long timeoutMillis,
        final BooleanSupplier stopped, final TriggerSleeper sleeper,
        final FileKeySupplier fileKeySupplier) throws Exception {
        return awaitSessionFileForTest(temporaryRoot, beforeDirectories, timeoutMillis, null,
            stopped, sleeper, fileKeySupplier);
    }

    /** Offline seam retaining the production result diagnostics for readiness regression tests. */
    static StablePsdSnapshot awaitSessionFileForTest(final Path temporaryRoot,
        final Set<Path> beforeDirectories, final long timeoutMillis, final Properties result,
        final BooleanSupplier stopped, final TriggerSleeper sleeper,
        final FileKeySupplier fileKeySupplier) throws Exception {
        final Path root = requireOwnedDirectory(temporaryRoot, "temporary root");
        if (beforeDirectories == null) {
            throw new IllegalArgumentException("before candidate directories are required");
        }
        Objects.requireNonNull(fileKeySupplier, "file key supplier");
        final Set<Path> before = new LinkedHashSet<>();
        for (final Path directory : beforeDirectories) {
            if (directory == null) throw new IllegalArgumentException("null before candidate");
            before.add(directory.toAbsolutePath().normalize());
        }
        return awaitStableSessionFile(new TempCandidateSnapshot(root, before), timeoutMillis,
            result, stopped, () -> snapshotTempCandidates(root), sleeper, fileKeySupplier);
    }

    static StablePsdSnapshot confirmSessionFileForWriteForTest(
        final StablePsdSnapshot snapshot) throws Exception {
        return confirmSessionFileForWrite(snapshot, null, PLATFORM_FILE_KEY_SUPPLIER);
    }

    /** Offline seam with a controlled platform file-object token for write-check tests. */
    static StablePsdSnapshot confirmSessionFileForWriteForTest(
        final StablePsdSnapshot snapshot, final FileKeySupplier fileKeySupplier) throws Exception {
        return confirmSessionFileForWriteForTest(snapshot, null, fileKeySupplier);
    }

    /** Offline seam retaining the production result diagnostics for write-check tests. */
    static StablePsdSnapshot confirmSessionFileForWriteForTest(
        final StablePsdSnapshot snapshot, final Properties result,
        final FileKeySupplier fileKeySupplier) throws Exception {
        return confirmSessionFileForWrite(snapshot, result, fileKeySupplier);
    }

    private static StablePsdSnapshot confirmSessionFileForWrite(final StablePsdSnapshot snapshot,
        final Properties result) throws Exception {
        return confirmSessionFileForWrite(snapshot, result, PLATFORM_FILE_KEY_SUPPLIER);
    }

    private static StablePsdSnapshot confirmSessionFileForWrite(final StablePsdSnapshot snapshot,
        final Properties result, final FileKeySupplier fileKeySupplier) throws Exception {
        if (snapshot == null || snapshot.path() == null || snapshot.observation() == null) {
            throw new SessionFileReadinessException("write precondition has no validated session PSD");
        }
        Objects.requireNonNull(fileKeySupplier, "file key supplier");
        try {
            requireNoSymlinkPath(snapshot.path(), "validated session PSD path");
        } catch (RuntimeException failure) {
            final SessionFileRead rejected = SessionFileRead.failed("REJECTED_PATH",
                "validated session PSD path contains a symlink: " + failure.getMessage());
            recordSessionWriteCheck(result, rejected);
            throw new SessionFileReadinessException(rejected.diagnostic(), failure);
        }
        final SessionFileRead read = readSessionFile(snapshot.path(),
            snapshot.observation().identity(), fileKeySupplier);
        recordSessionWriteCheck(result, read);
        if (!read.complete() || read.bytes() == null
            || !Arrays.equals(snapshot.bytes(), read.bytes())
            || !sameStableObservation(snapshot.observation(), read.observation())) {
            throw new SessionFileReadinessException(
                "validated session PSD changed before mutation: " + read.diagnostic());
        }
        return new StablePsdSnapshot(snapshot.path(), read.bytes(), read.observation());
    }

    /** Reconfirms the complete snapshot and path immediately before the probe's external write. */
    private static void writeSessionFile(final StablePsdSnapshot snapshot,
        final byte[] replacement) throws Exception {
        final StablePsdSnapshot verified = confirmSessionFileForWrite(snapshot, null);
        final Path path = verified.path().toAbsolutePath().normalize();
        requireNoSymlinkPath(path, "session PSD path before write");
        if (Files.isSymbolicLink(path)
            || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new SessionFileReadinessException(
                "session PSD path is not a regular non-symlink file before write: " + path);
        }
        final Path real = path.toRealPath(LinkOption.NOFOLLOW_LINKS);
        requireNoSymlinkPath(real, "session PSD real path before write");
        if (!real.equals(path)) {
            throw new SessionFileReadinessException(
                "session PSD real path changed before write from " + path + " to " + real);
        }
        Files.write(real, replacement);
    }

    private static StablePsdSnapshot awaitStableSessionFile(
        final TempCandidateSnapshot before, final long timeoutMillis, final Properties result,
        final BooleanSupplier stopped, final CandidateSnapshotSupplier snapshots,
        final TriggerSleeper sleeper, final FileKeySupplier fileKeySupplier) throws Exception {
        Objects.requireNonNull(before, "before candidate snapshot");
        Objects.requireNonNull(stopped, "stopped");
        Objects.requireNonNull(snapshots, "candidate snapshot supplier");
        Objects.requireNonNull(sleeper, "session PSD sleeper");
        Objects.requireNonNull(fileKeySupplier, "file key supplier");
        if (SwingUtilities.isEventDispatchThread()) {
            throw new SessionFileReadinessException(
                "session PSD readiness must run off the EDT");
        }
        if (timeoutMillis <= 0) {
            throw new SessionFileReadinessException(
                "session PSD readiness timeout must be positive");
        }

        final long deadline = System.nanoTime()
            + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        Path boundDirectory = null;
        Path boundFile = null;
        FileIdentity boundIdentity = null;
        FileObservation stableObservation = null;
        byte[] stableBytes = null;
        int stableReads = 0;
        int attempt = 0;
        SessionFileRead lastRead = null;
        String lastState = "NOT_STARTED";
        String lastDiagnostic = "session PSD candidate wait has not started";

        while (!stopped.getAsBoolean() && System.nanoTime() < deadline) {
            attempt++;
            final TempCandidateSnapshot after;
            try {
                after = snapshots.snapshot();
            } catch (Exception failure) {
                lastState = "REJECTED";
                lastDiagnostic = "candidate snapshot failed: " + failure;
                recordSessionAttempt(result, attempt, null, Set.of(), null,
                    lastState, null, stableReads, lastDiagnostic);
                throw new SessionFileReadinessException(lastDiagnostic, failure);
            }
            if (!before.root().equals(after.root())) {
                lastState = "REJECTED";
                lastDiagnostic = "temporary root changed from " + before.root()
                    + " to " + after.root();
                recordSessionAttempt(result, attempt, after, Set.of(), boundDirectory,
                    lastState, null, stableReads, lastDiagnostic);
                throw new SessionFileReadinessException(lastDiagnostic);
            }

            final Set<Path> added = new LinkedHashSet<>(after.directories());
            added.removeAll(before.directories());
            if (boundDirectory == null) {
                if (added.isEmpty()) {
                    lastState = "WAITING_NO_NEW_CANDIDATE";
                    lastDiagnostic = "no new PSD candidate directory yet";
                    recordSessionAttempt(result, attempt, after, added, null, lastState,
                        null, stableReads, lastDiagnostic);
                    if (!sleepForSessionPoll(deadline, stopped, sleeper)) break;
                    continue;
                }
                if (added.size() > 1) {
                    lastState = "AMBIGUOUS_CANDIDATES";
                    lastDiagnostic = "multiple new PSD candidate directories: " + added;
                    recordSessionAttempt(result, attempt, after, added, null, lastState,
                        null, stableReads, lastDiagnostic);
                    throw new SessionFileReadinessException(lastDiagnostic);
                }
                boundDirectory = added.iterator().next();
                stableObservation = null;
                stableBytes = null;
                stableReads = 0;
            } else if (added.size() != 1 || !added.contains(boundDirectory)) {
                lastState = added.size() > 1 ? "AMBIGUOUS_CANDIDATES"
                    : "BOUND_CANDIDATE_CHANGED";
                lastDiagnostic = "bound PSD candidate changed or disappeared: bound="
                    + boundDirectory + " added=" + added;
                recordSessionAttempt(result, attempt, after, added, boundDirectory, lastState,
                    null, stableReads, lastDiagnostic);
                throw new SessionFileReadinessException(lastDiagnostic);
            }
            if (result != null) {
                result.setProperty("gui.session.candidateDirectory", boundDirectory.toString());
            }

            final Path expectedFile = boundDirectory.resolve(TEMP_FILE_NAME);
            if (Files.isSymbolicLink(expectedFile)) {
                lastState = "REJECTED_SYMLINK";
                lastDiagnostic = "bound PSD file is a symlink: " + expectedFile;
                recordSessionAttempt(result, attempt, after, added, boundDirectory, lastState,
                    null, stableReads, lastDiagnostic);
                throw new SessionFileReadinessException(lastDiagnostic);
            }
            if (!Files.exists(expectedFile, LinkOption.NOFOLLOW_LINKS)) {
                if (boundFile != null) {
                    lastState = "BOUND_FILE_CHANGED";
                    lastDiagnostic = "bound PSD file disappeared: " + expectedFile;
                    recordSessionAttempt(result, attempt, after, added, boundDirectory, lastState,
                        null, stableReads, lastDiagnostic);
                    throw new SessionFileReadinessException(lastDiagnostic);
                }
                lastState = "WAITING_FILE";
                lastDiagnostic = "bound candidate has not created " + TEMP_FILE_NAME;
                recordSessionAttempt(result, attempt, after, added, boundDirectory, lastState,
                    null, stableReads, lastDiagnostic);
                if (!sleepForSessionPoll(deadline, stopped, sleeper)) break;
                continue;
            }
            if (!Files.isRegularFile(expectedFile, LinkOption.NOFOLLOW_LINKS)) {
                lastState = "REJECTED_NON_REGULAR";
                lastDiagnostic = "bound PSD path is not a regular file: " + expectedFile;
                recordSessionAttempt(result, attempt, after, added, boundDirectory, lastState,
                    null, stableReads, lastDiagnostic);
                throw new SessionFileReadinessException(lastDiagnostic);
            }

            final Path file;
            try {
                file = requireTrackedTempFile(boundDirectory, after.root());
            } catch (Exception failure) {
                lastState = "REJECTED_PATH";
                lastDiagnostic = "bound PSD path validation failed: " + failure;
                recordSessionAttempt(result, attempt, after, added, boundDirectory, lastState,
                    null, stableReads, lastDiagnostic);
                throw new SessionFileReadinessException(lastDiagnostic, failure);
            }
            if (boundFile == null) {
                boundFile = file;
            } else if (!boundFile.equals(file)) {
                lastState = "REPLACED_FILE";
                lastDiagnostic = "bound PSD real path changed from " + boundFile + " to " + file;
                recordSessionAttempt(result, attempt, after, added, boundDirectory, lastState,
                    null, stableReads, lastDiagnostic);
                throw new SessionFileReadinessException(lastDiagnostic);
            }

            final SessionFileRead read = readSessionFile(file, boundIdentity, fileKeySupplier);
            lastRead = read;
            if (read.observation() != null && boundIdentity == null) {
                boundIdentity = read.observation().identity();
            }
            lastState = read.state();
            lastDiagnostic = read.diagnostic();
            recordSessionAttempt(result, attempt, after, added, boundDirectory, lastState,
                read, stableReads, lastDiagnostic);
            if ("REPLACED".equals(read.state()) || "IDENTITY_UNAVAILABLE".equals(read.state())) {
                throw new SessionFileReadinessException(lastDiagnostic);
            }
            if (!read.complete()) {
                stableObservation = null;
                stableBytes = null;
                stableReads = 0;
            } else if (stableObservation != null
                && sameStableObservation(stableObservation, read.observation())
                && Arrays.equals(stableBytes, read.bytes())) {
                stableReads++;
            } else {
                stableObservation = read.observation();
                stableBytes = read.bytes();
                stableReads = 1;
            }
            if (stableReads >= GUI_SESSION_FILE_STABLE_READS) {
                if (result != null) {
                    result.setProperty("gui.session.read.status", "VERIFIED");
                    result.setProperty("gui.session.read.stableReads",
                        Integer.toString(stableReads));
                    result.setProperty("gui.session.read.bytes",
                        Integer.toString(stableBytes.length));
                    result.setProperty("gui.session.read.realPath",
                        stableObservation.realPath());
                    result.setProperty("gui.session.read.sha256",
                        stableObservation.sha256());
                    result.setProperty("gui.session.read.section",
                        stableObservation.structure().section());
                    result.setProperty("gui.session.read.fileKeyStatus",
                        stableObservation.fileKeyStatus());
                    result.setProperty("gui.session.read.identityVerified",
                        Boolean.toString(stableObservation.identityVerified()));
                }
                return new StablePsdSnapshot(boundFile, stableBytes, stableObservation);
            }
            if (!sleepForSessionPoll(deadline, stopped, sleeper)) break;
        }

        final String terminalState = stopped.getAsBoolean() ? "STOPPED" : "TIMEOUT";
        final String terminalDiagnostic = stopped.getAsBoolean()
            ? "session PSD readiness stopped after attempt " + attempt
            : "session PSD readiness timed out after " + timeoutMillis
                + "ms; last=" + lastState + " " + lastDiagnostic;
        recordSessionAttempt(result, attempt + 1, null, Set.of(), boundDirectory, terminalState,
            lastRead, stableReads, terminalDiagnostic);
        throw new SessionFileReadinessException(terminalDiagnostic);
    }

    private static boolean sleepForSessionPoll(final long deadline,
        final BooleanSupplier stopped, final TriggerSleeper sleeper)
        throws SessionFileReadinessException {
        if (stopped.getAsBoolean()) return false;
        final long remainingNanos = deadline - System.nanoTime();
        if (remainingNanos <= 0) return false;
        final long remainingMillis = Math.max(1L,
            TimeUnit.NANOSECONDS.toMillis(remainingNanos));
        try {
            sleeper.sleep(Math.min(GUI_SESSION_FILE_POLL_MILLIS, remainingMillis));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SessionFileReadinessException(
                "session PSD readiness interrupted", interrupted);
        }
        return !stopped.getAsBoolean();
    }

    private static SessionFileRead readSessionFile(final Path file,
        final FileIdentity expectedIdentity, final FileKeySupplier fileKeySupplier) {
        final FileIdentity before;
        try {
            before = readFileIdentity(file, fileKeySupplier);
        } catch (Exception failure) {
            return SessionFileRead.failed("READ_FAILED",
                "PSD metadata before read failed: " + failure);
        }
        if (expectedIdentity != null && !sameFileIdentity(expectedIdentity, before)) {
            return SessionFileRead.replaced(before,
                "PSD file identity changed before read from " + expectedIdentity.diagnostic()
                    + " to " + before.diagnostic() + "; "
                    + identityMismatch(expectedIdentity, before));
        }

        final byte[] bytes;
        try {
            bytes = Files.readAllBytes(before.realPath());
        } catch (Exception failure) {
            return SessionFileRead.failed("READ_FAILED",
                "PSD bytes could not be read: " + failure);
        }
        final String digest;
        try {
            digest = sha256(bytes);
        } catch (Exception failure) {
            return SessionFileRead.failed("READ_FAILED",
                "PSD SHA-256 could not be computed: " + failure);
        }

        final FileIdentity after;
        try {
            after = readFileIdentity(file, fileKeySupplier);
        } catch (Exception failure) {
            return new SessionFileRead("READ_FAILED", bytes,
                new FileObservation(before, digest,
                    PsdStructureValidation.invalid("file", -1,
                        "PSD metadata after read failed: " + failure)),
                "PSD metadata after read failed: " + failure);
        }
        final PsdStructureValidation structure = inspectPsdStructure(bytes);
        final FileObservation observation = new FileObservation(after, digest, structure);
        if (!sameFileIdentity(before, after)) {
            return SessionFileRead.replaced(observation,
                "PSD file identity changed during read from " + before.diagnostic()
                    + " to " + after.diagnostic() + "; "
                    + identityMismatch(before, after));
        }
        if (!sameFileMetadata(before, after)) {
            return new SessionFileRead("CHANGED_DURING_READ", bytes, observation,
                "PSD file metadata changed during read from " + before.diagnostic()
                    + " to " + after.diagnostic() + " section=" + structure.section()
                    + " reason=" + structure.reason());
        }
        if (bytes.length != before.size() || bytes.length != after.size()) {
            return new SessionFileRead("CHANGED_DURING_READ", bytes, observation,
                "PSD byte length " + bytes.length + " disagrees with file size before/after "
                    + before.size() + "/" + after.size() + " section=" + structure.section()
                    + " reason=" + structure.reason());
        }
        if (expectedIdentity != null && !sameFileIdentity(expectedIdentity, after)) {
            return SessionFileRead.replaced(observation,
                "PSD file identity changed from " + expectedIdentity.diagnostic()
                    + " to " + after.diagnostic() + "; "
                    + identityMismatch(expectedIdentity, after));
        }
        if (!structure.complete()) {
            return new SessionFileRead("INCOMPLETE", bytes, observation,
                "PSD structure incomplete section=" + structure.section()
                    + " offset=" + structure.offset() + " reason=" + structure.reason());
        }
        return new SessionFileRead("COMPLETE", bytes, observation,
            "PSD structure complete bytes=" + bytes.length + " sha256=" + digest);
    }

    private static FileIdentity readFileIdentity(final Path file,
        final FileKeySupplier fileKeySupplier) throws Exception {
        Objects.requireNonNull(fileKeySupplier, "file key supplier");
        if (file == null) throw new IllegalArgumentException("PSD file is null");
        final Path normalized = file.toAbsolutePath().normalize();
        requireNoSymlinkPath(normalized, "PSD file");
        if (Files.isSymbolicLink(normalized)
            || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("path is not a regular non-symlink file: " + normalized);
        }
        final Path real = normalized.toRealPath(LinkOption.NOFOLLOW_LINKS);
        requireNoSymlinkPath(real, "PSD file");
        final BasicFileAttributes attributes = Files.readAttributes(real,
            BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) {
            throw new IllegalStateException("path is not a regular file: " + real);
        }
        return new FileIdentity(real, attributes.size(),
            attributes.lastModifiedTime().toMillis(), fileKeySupplier.fileKey(attributes));
    }

    private static boolean sameFileIdentity(final FileIdentity first,
        final FileIdentity second) {
        return first != null && second != null
            && first.realPath().equals(second.realPath())
            && sameFileKey(first.fileKey(), second.fileKey());
    }

    private static boolean sameFileKey(final Object first, final Object second) {
        return first == null && second == null
            || first != null && second != null && Objects.equals(first, second);
    }

    private static String identityMismatch(final FileIdentity first,
        final FileIdentity second) {
        if (first == null || second == null) return "file identity observation is unavailable";
        if (!first.realPath().equals(second.realPath())) {
            return "real path changed from " + first.realPath() + " to " + second.realPath();
        }
        if (first.fileKey() == null && second.fileKey() != null) {
            return "fileKey availability changed UNAVAILABLE→VERIFIED";
        }
        if (first.fileKey() != null && second.fileKey() == null) {
            return "fileKey availability changed VERIFIED→UNAVAILABLE";
        }
        if (first.fileKey() != null && !Objects.equals(first.fileKey(), second.fileKey())) {
            return "fileKey changed while available";
        }
        return "file identity changed";
    }

    private static boolean sameFileMetadata(final FileIdentity first,
        final FileIdentity second) {
        return sameFileIdentity(first, second)
            && first.size() == second.size()
            && first.modifiedMillis() == second.modifiedMillis();
    }

    private static boolean sameStableObservation(final FileObservation first,
        final FileObservation second) {
        return first != null && second != null
            && sameFileIdentity(first.identity(), second.identity())
            && first.identity().size() == second.identity().size()
            && first.identity().modifiedMillis() == second.identity().modifiedMillis()
            && first.sha256().equals(second.sha256())
            && first.structure().equals(second.structure());
    }

    private static void recordSessionAttempt(final Properties result, final int attempt,
        final TempCandidateSnapshot after, final Set<Path> added, final Path boundDirectory,
        final String state, final SessionFileRead read, final int stableReads,
        final String diagnostic) {
        if (result == null) return;
        final String prefix = "gui.session.read.attempt." + attempt + ".";
        result.setProperty(prefix + "state", safeDiagnostic(state));
        result.setProperty(prefix + "stableReads", Integer.toString(stableReads));
        result.setProperty(prefix + "candidateAfter", after == null ? "-1"
            : Integer.toString(after.directories().size()));
        result.setProperty(prefix + "newCandidates", Integer.toString(added.size()));
        result.setProperty(prefix + "boundDirectory", boundDirectory == null
            ? "" : boundDirectory.toString());
        result.setProperty(prefix + "diagnostic", safeDiagnostic(diagnostic));
        if (read != null) {
            final FileObservation observation = read.observation();
            result.setProperty(prefix + "size", observation == null ? "-1"
                : Long.toString(observation.identity().size()));
            result.setProperty(prefix + "byteLength", read.bytes() == null ? "-1"
                : Integer.toString(read.bytes().length));
            result.setProperty(prefix + "mtime", observation == null ? "-1"
                : Long.toString(observation.identity().modifiedMillis()));
            result.setProperty(prefix + "realPath", observation == null
                ? "" : observation.realPath());
            result.setProperty(prefix + "sha256", observation == null
                ? "" : observation.sha256());
            result.setProperty(prefix + "section", observation == null
                ? "unavailable" : observation.structure().section());
            result.setProperty(prefix + "sectionOffset", observation == null
                ? "-1" : Integer.toString(observation.structure().offset()));
            result.setProperty(prefix + "sectionReason", observation == null
                ? "" : safeDiagnostic(observation.structure().reason()));
            result.setProperty(prefix + "fileKeyStatus", observation == null
                ? "UNAVAILABLE" : observation.fileKeyStatus());
            result.setProperty(prefix + "identityVerified", observation == null
                ? "false" : Boolean.toString(observation.identityVerified()));
        } else {
            result.setProperty(prefix + "size", "-1");
            result.setProperty(prefix + "byteLength", "-1");
            result.setProperty(prefix + "mtime", "-1");
            result.setProperty(prefix + "sha256", "");
            result.setProperty(prefix + "section", "unavailable");
            result.setProperty(prefix + "sectionOffset", "-1");
            result.setProperty(prefix + "sectionReason", "");
            result.setProperty(prefix + "realPath", "");
            result.setProperty(prefix + "fileKeyStatus", "UNAVAILABLE");
            result.setProperty(prefix + "identityVerified", "false");
        }
        result.setProperty("gui.session.read.attempts", Integer.toString(attempt));
        result.setProperty("gui.session.read.lastState", safeDiagnostic(state));
        result.setProperty("gui.session.read.lastDiagnostic", safeDiagnostic(diagnostic));
        if (read != null && read.observation() != null) {
            result.setProperty("gui.session.read.lastSize",
                Long.toString(read.observation().identity().size()));
            result.setProperty("gui.session.read.lastByteLength", read.bytes() == null ? "-1"
                : Integer.toString(read.bytes().length));
            result.setProperty("gui.session.read.lastMtime",
                Long.toString(read.observation().identity().modifiedMillis()));
            result.setProperty("gui.session.read.lastRealPath",
                read.observation().realPath());
            result.setProperty("gui.session.read.lastSha256", read.observation().sha256());
            result.setProperty("gui.session.read.lastSection",
                read.observation().structure().section());
            result.setProperty("gui.session.read.lastSectionReason",
                safeDiagnostic(read.observation().structure().reason()));
            result.setProperty("gui.session.read.lastFileKeyStatus",
                read.observation().fileKeyStatus());
            result.setProperty("gui.session.read.lastIdentityVerified",
                Boolean.toString(read.observation().identityVerified()));
        } else {
            result.setProperty("gui.session.read.lastSize", "-1");
            result.setProperty("gui.session.read.lastByteLength", "-1");
            result.setProperty("gui.session.read.lastMtime", "-1");
            result.setProperty("gui.session.read.lastSha256", "");
            result.setProperty("gui.session.read.lastSection", "unavailable");
            result.setProperty("gui.session.read.lastSectionReason", "");
            result.setProperty("gui.session.read.lastRealPath", "");
            result.setProperty("gui.session.read.lastFileKeyStatus", "UNAVAILABLE");
            result.setProperty("gui.session.read.lastIdentityVerified", "false");
        }
    }

    private static void recordSessionWriteCheck(final Properties result,
        final SessionFileRead read) {
        if (result == null) return;
        result.setProperty("gui.session.writeCheck.state", read.state());
        result.setProperty("gui.session.writeCheck.diagnostic", safeDiagnostic(read.diagnostic()));
        if (read.observation() != null) {
            result.setProperty("gui.session.writeCheck.size",
                Long.toString(read.observation().identity().size()));
            result.setProperty("gui.session.writeCheck.byteLength", read.bytes() == null ? "-1"
                : Integer.toString(read.bytes().length));
            result.setProperty("gui.session.writeCheck.mtime",
                Long.toString(read.observation().identity().modifiedMillis()));
            result.setProperty("gui.session.writeCheck.realPath",
                read.observation().realPath());
            result.setProperty("gui.session.writeCheck.sha256", read.observation().sha256());
            result.setProperty("gui.session.writeCheck.section",
                read.observation().structure().section());
            result.setProperty("gui.session.writeCheck.sectionOffset",
                Integer.toString(read.observation().structure().offset()));
            result.setProperty("gui.session.writeCheck.sectionReason",
                safeDiagnostic(read.observation().structure().reason()));
            result.setProperty("gui.session.writeCheck.fileKeyStatus",
                read.observation().fileKeyStatus());
            result.setProperty("gui.session.writeCheck.identityVerified",
                Boolean.toString(read.observation().identityVerified()));
        } else {
            result.setProperty("gui.session.writeCheck.size", "-1");
            result.setProperty("gui.session.writeCheck.byteLength", "-1");
            result.setProperty("gui.session.writeCheck.mtime", "-1");
            result.setProperty("gui.session.writeCheck.sha256", "");
            result.setProperty("gui.session.writeCheck.section", "unavailable");
            result.setProperty("gui.session.writeCheck.sectionOffset", "-1");
            result.setProperty("gui.session.writeCheck.sectionReason", "");
            result.setProperty("gui.session.writeCheck.realPath", "");
            result.setProperty("gui.session.writeCheck.fileKeyStatus", "UNAVAILABLE");
            result.setProperty("gui.session.writeCheck.identityVerified", "false");
        }
    }

    private static String safeDiagnostic(final String value) {
        if (value == null) return "";
        final String singleLine = value.replace('\r', ' ').replace('\n', ' ');
        return singleLine.length() <= SESSION_DIAGNOSTIC_LIMIT
            ? singleLine : singleLine.substring(0, SESSION_DIAGNOSTIC_LIMIT) + "…";
    }

    /** Waits for a target-specific false-to-true replacement without accepting stale state. */
    private AutoImportObservation awaitAutoImport(final Target target,
        final GuiTargetState before,
        final int timeoutSeconds) throws Exception {
        final long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        GuiTargetState last = GuiTargetState.unobserved("no post-click target observation");
        while (System.currentTimeMillis() < deadline && !stopped) {
            last = observeGuiTarget(target);
            if (last.observed()) {
                if (!sameGuiTarget(before, last, target.raw().value())) {
                    return new AutoImportObservation(false, true, last,
                        "GUI target became stale: before=" + before + " after=" + last);
                }
                if (acceptsAutoImport(before, last, target.raw().value())) {
                    return new AutoImportObservation(true, false, last,
                        "target raw isReplaced changed false-to-true");
                }
            }
            Thread.sleep(500);
        }
        return new AutoImportObservation(false, false, last,
            "no target-specific false-to-true replacement observed before timeout");
    }

    private record PopupAttempt(List<JPopupMenu> visibleBefore,
        List<JPopupMenu> dismissed) {}

    private record PopupSnapshot(JPopupMenu popup, int popupCount,
        String association, String markers) {}

    private record PopupCapture(JPopupMenu popup, int popupCount, int maxPopupCount,
        String diagnostic) {
        PopupCapture {
            diagnostic = diagnostic == null ? "" : diagnostic;
        }
    }

    private record RowAttempt(JPopupMenu popup, int popupCount, String diagnostic,
        String dispatchFailureTrace, int dispatchRow, String dispatchComponent,
        boolean terminalRejection) {
        RowAttempt {
            diagnostic = diagnostic == null ? "" : diagnostic;
            dispatchFailureTrace = dispatchFailureTrace == null ? "" : dispatchFailureTrace;
            dispatchComponent = dispatchComponent == null ? "" : dispatchComponent;
        }
    }

    enum RowKind {
        TREE, TABLE, LIST
    }

    record RowKey(String value, boolean available) {
        RowKey {
            value = value == null ? "" : value;
        }

        static RowKey of(final String value) {
            return new RowKey(value, value != null && !value.isBlank());
        }

        static RowKey unavailable() {
            return new RowKey("", false);
        }
    }

    record CapturedRow(RowKind kind, String widgetClass, String widgetIdentity,
        String windowIdentity, int row, String rowKey, boolean rowKeyAvailable, String bounds) {
        CapturedRow {
            widgetClass = widgetClass == null ? "" : widgetClass;
            widgetIdentity = widgetIdentity == null ? "" : widgetIdentity;
            windowIdentity = windowIdentity == null ? "" : windowIdentity;
            rowKey = rowKey == null ? "" : rowKey;
            bounds = bounds == null ? "null" : bounds;
        }

        String rowKeyDiagnostic() {
            return rowKeyAvailable
                ? "available#" + Integer.toHexString(rowKey.hashCode()) : "unavailable";
        }

        String diagnostic() {
            return "widget=" + widgetIdentity
                + " class=" + widgetClass
                + " window=" + windowIdentity
                + " row=" + row
                + " rowKey=" + rowKeyDiagnostic()
                + " bounds=" + bounds;
        }
    }

    record CurrentRow(CapturedRow descriptor, Component component, Rectangle bounds) {
        int row() { return descriptor.row(); }
    }

    record RowResolution(CurrentRow row, String reason) {
        static RowResolution available(final CurrentRow row) {
            return new RowResolution(row, "");
        }

        static RowResolution unavailable(final String reason) {
            return new RowResolution(null, reason == null ? "unknown" : reason);
        }

        boolean available() { return row != null; }
    }

    private record ReviewedTables(List<ExactTableRef> tables, String windowIdentity,
        Window window, boolean proven, String diagnostic) {
        ReviewedTables {
            tables = List.copyOf(tables);
            windowIdentity = windowIdentity == null ? "" : windowIdentity;
            diagnostic = diagnostic == null ? "" : diagnostic;
        }

        static ReviewedTables unavailable(final String diagnostic) {
            return new ReviewedTables(List.of(), "", null, false, diagnostic);
        }
    }

    private record ExactTableRef(JTable table, Class<?> modelClass, String widgetIdentity,
        String windowIdentity, String modelIdentity) {
        String diagnostic() {
            return "widget=" + widgetIdentity + " window=" + windowIdentity
                + " model=" + modelIdentity + " modelClass=" + modelClass.getName();
        }
    }

    static record ExactCapturedRow(ExactHostRowTarget.Identity identity,
        String widgetIdentity, String windowIdentity, String modelIdentity, int viewRow,
        int modelRow, ExactHostRowTarget.NameCell nameCell,
        ExactHostRowTarget.VisibilityLockState state) {
        ExactCapturedRow {
            Objects.requireNonNull(identity, "identity");
            widgetIdentity = requireIdentityText(widgetIdentity, "widgetIdentity");
            windowIdentity = requireIdentityText(windowIdentity, "windowIdentity");
            modelIdentity = requireIdentityText(modelIdentity, "modelIdentity");
            if (viewRow < 0 || modelRow < 0) {
                throw new IllegalArgumentException("exact row coordinates must not be negative");
            }
            Objects.requireNonNull(nameCell, "nameCell");
            Objects.requireNonNull(state, "state");
        }

        String diagnostic() {
            return "identity=" + identity
                + " widget=" + widgetIdentity
                + " window=" + windowIdentity
                + " model=" + modelIdentity
                + " viewRow=" + viewRow
                + " modelRow=" + modelRow
                + " nameCell=" + nameCell.bounds()
                + " click=" + nameCell.clickPoint()
                + " state=" + state;
        }
    }

    enum ExactTargetSelectionStatus {
        AVAILABLE,
        NOT_FOUND,
        AMBIGUOUS,
        REJECTED
    }

    static record ExactTargetSelection(List<ExactCapturedRow> rows,
        ExactTargetSelectionStatus status, String reason) {
        ExactTargetSelection {
            rows = rows == null ? List.of() : List.copyOf(rows);
            status = Objects.requireNonNull(status, "status");
            reason = reason == null ? "" : reason;
        }

        static ExactTargetSelection available(final List<ExactCapturedRow> rows) {
            return new ExactTargetSelection(rows, ExactTargetSelectionStatus.AVAILABLE, "");
        }

        static ExactTargetSelection notFound(final String reason) {
            return new ExactTargetSelection(List.of(), ExactTargetSelectionStatus.NOT_FOUND,
                reason);
        }

        static ExactTargetSelection ambiguous(final String reason) {
            return new ExactTargetSelection(List.of(), ExactTargetSelectionStatus.AMBIGUOUS,
                reason);
        }

        static ExactTargetSelection rejected(final String reason) {
            return new ExactTargetSelection(List.of(), ExactTargetSelectionStatus.REJECTED,
                reason);
        }

        boolean available() { return status == ExactTargetSelectionStatus.AVAILABLE; }
    }

    private record ExactTargetGroup(String windowIdentity,
        ExactHostRowTarget.Identity identity) { }

    static record ExactCurrentRow(ExactCapturedRow descriptor, Component component) {
        ExactCurrentRow {
            Objects.requireNonNull(descriptor, "descriptor");
            Objects.requireNonNull(component, "component");
        }

        String diagnostic() {
            return descriptor.diagnostic() + " componentState=" + componentState(component);
        }
    }

    static record ExactRowResolution(ExactCurrentRow row, String reason) {
        ExactRowResolution {
            reason = reason == null ? "" : reason;
            if (row == null && reason.isBlank()) reason = "exact row unavailable";
        }

        static ExactRowResolution available(final ExactCurrentRow row) {
            return new ExactRowResolution(Objects.requireNonNull(row, "row"), "");
        }

        static ExactRowResolution unavailable(final String reason) {
            return new ExactRowResolution(null, reason);
        }

        boolean available() { return row != null; }

        String diagnostic() {
            return row == null ? "unavailable=" + reason : row.diagnostic();
        }
    }

    private record ExactDispatchCapture(ExactCapturedRow captured,
        ExactHostRowTarget.HostAccessContext context) {
        String diagnostic() { return captured.diagnostic(); }
    }

    private record ExactCapture(List<ExactDispatchCapture> rows, List<String> tableDiagnostics,
        String diagnostic, boolean hostAvailable, ExactTargetSelectionStatus targetStatus) {
        ExactCapture {
            rows = List.copyOf(rows);
            tableDiagnostics = List.copyOf(tableDiagnostics);
            diagnostic = diagnostic == null ? "" : diagnostic;
            targetStatus = Objects.requireNonNull(targetStatus, "targetStatus");
        }
    }

    /** Coordinates and component chosen immediately before a popup-trigger dispatch. */
    static record DispatchTarget(Component component, int x, int y, String diagnostic) {
        DispatchTarget(final Component component, final int x, final int y) {
            this(component, x, y, "");
        }

        DispatchTarget {
            diagnostic = diagnostic == null ? "" : diagnostic;
        }
    }

    private record RowDispatchResult(RightClickDispatch dispatch, int row, String component,
        boolean retryable, String phase, String reason, boolean terminalRejection) {
        static RowDispatchResult success(final RightClickDispatch dispatch,
            final CurrentRow row) {
            return new RowDispatchResult(dispatch, row.row(), componentIdentity(row.component()),
                false, "", "", false);
        }

        static RowDispatchResult success(final RightClickDispatch dispatch,
            final ExactCurrentRow row) {
            return new RowDispatchResult(dispatch, row.descriptor().viewRow(),
                componentIdentity(row.component()), false, "", "", false);
        }

        static RowDispatchResult retry(final CapturedRow captured, final String phase,
            final String reason) {
            return retry(captured.diagnostic(), phase, reason);
        }

        static RowDispatchResult retry(final String captureDiagnostic, final String phase,
            final String reason) {
            final String actual = reason == null || reason.isBlank() ? "unknown" : reason;
            return new RowDispatchResult(
                RightClickDispatch.notDispatched("capture=" + captureDiagnostic
                    + " phase=" + phase + " reason=" + actual),
                -1, "", true, phase, actual, false);
        }

        static RowDispatchResult rejected(final String captureDiagnostic, final String phase,
            final String reason) {
            final String actual = reason == null || reason.isBlank() ? "unknown" : reason;
            return new RowDispatchResult(
                RightClickDispatch.notDispatched("capture=" + captureDiagnostic
                    + " phase=" + phase + " reason=" + actual),
                -1, "", false, phase, actual, true);
        }
    }

    private static final class RowRelocationException extends RuntimeException {
        RowRelocationException(final String message) {
            super(message);
        }
    }

    static int rowDispatchAttemptLimit() {
        return MAX_ROW_DISPATCH_ATTEMPTS;
    }

    @FunctionalInterface
    interface TriggerSleeper {
        void sleep(long millis) throws InterruptedException;
    }

    static record GuiTriggerArm(boolean armed, Path path, String diagnostic) {
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

    static record GuiTriggerWait(boolean ready, Path path, String diagnostic) {
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

    static record GuiWindowBindingDecision(boolean bindNow, boolean waitForBoundWindow,
        String diagnostic) {
        GuiWindowBindingDecision {
            diagnostic = diagnostic == null ? "" : diagnostic;
        }

        static GuiWindowBindingDecision bind(final String diagnostic) {
            return new GuiWindowBindingDecision(true, false, diagnostic);
        }

        static GuiWindowBindingDecision stable(final String diagnostic) {
            return new GuiWindowBindingDecision(false, false, diagnostic);
        }

        static GuiWindowBindingDecision waiting(final String diagnostic) {
            return new GuiWindowBindingDecision(false, true, diagnostic);
        }

        static GuiWindowBindingDecision unbound(final String diagnostic) {
            return new GuiWindowBindingDecision(false, false, diagnostic);
        }
    }

    static record TaskWindowBindingDecision(boolean bound, String diagnostic) {
        TaskWindowBindingDecision {
            diagnostic = diagnostic == null ? "" : diagnostic;
        }

        static TaskWindowBindingDecision bound(final String diagnostic) {
            return new TaskWindowBindingDecision(true, diagnostic);
        }

        static TaskWindowBindingDecision rejected(final String diagnostic) {
            return new TaskWindowBindingDecision(false, diagnostic);
        }
    }

    static record RightClickDispatch(String diagnostic) {
        RightClickDispatch {
            diagnostic = diagnostic == null ? "" : diagnostic;
        }

        static RightClickDispatch notDispatched(final String diagnostic) {
            return new RightClickDispatch("not-dispatched=" + diagnostic);
        }
    }

    private record DispatchFailure(String phase, RuntimeException error) {}

    /** Right-click handler failures are rethrown after both popup-trigger phases are attempted. */
    static final class RightClickDispatchException extends RuntimeException {
        private final RightClickDispatch dispatch;

        RightClickDispatchException(final RightClickDispatch dispatch,
            final List<DispatchFailure> failures) {
            super("right-click dispatch failed phases="
                + failures.stream().map(DispatchFailure::phase).toList()
                + " " + dispatch.diagnostic(), phaseFailure(failures.get(0)));
            this.dispatch = dispatch;
            for (int index = 1; index < failures.size(); index++) {
                addSuppressed(phaseFailure(failures.get(index)));
            }
        }

        RightClickDispatch dispatch() { return dispatch; }
    }

    private static RuntimeException phaseFailure(final DispatchFailure failure) {
        return new RuntimeException("right-click " + failure.phase() + " dispatch failed",
            failure.error());
    }

    static record GuiTargetState(boolean observed, String binding, long generation, String raw,
        boolean rawReplaced, String diagnostic) {
        GuiTargetState {
            binding = binding == null ? "" : binding;
            raw = raw == null ? "" : raw;
            diagnostic = diagnostic == null ? "" : diagnostic;
        }

        static GuiTargetState unobserved(final String diagnostic) {
            return new GuiTargetState(false, "", -1L, "", false, diagnostic);
        }
    }

    private record AutoImportObservation(boolean applied, boolean stale,
        GuiTargetState after, String diagnostic) {}

    enum DiagnosticStatus {
        AVAILABLE,
        UNAVAILABLE
    }

    /** Identity captured from the original task target and rechecked for each diagnostic read. */
    static record TargetIdentity(String documentId, String modelId, String binding,
        String modelImageId, String rawId) {
        TargetIdentity {
            documentId = valueOrUnavailable(documentId);
            modelId = valueOrUnavailable(modelId);
            binding = valueOrUnavailable(binding);
            modelImageId = valueOrUnavailable(modelImageId);
            rawId = valueOrUnavailable(rawId);
        }

        static TargetIdentity unavailable() {
            return new TargetIdentity(
                UNAVAILABLE_VALUE,
                UNAVAILABLE_VALUE,
                UNAVAILABLE_VALUE,
                UNAVAILABLE_VALUE,
                UNAVAILABLE_VALUE
            );
        }
    }

    /**
     * Immutable diagnostic snapshot of public relation/PSD metadata and one fresh native RGB
     * fingerprint. Metadata is evidence about observed bindings only; it is never a substitute
     * for the decoded fingerprint.
     */
    static record DiagnosticObservation(DiagnosticStatus status, String diagnostic,
        String rawIds, String modelImageIds, String selectorProjection, String livePsd,
        boolean relationsAvailable, boolean livePsdAvailable,
        PsdValidationContent.Fingerprint freshNativeRgb, TargetIdentity observedTarget,
        boolean targetIdentityVerified, long freshExportStartedAtEpochMillis,
        long freshExportCompletedAtEpochMillis, long metadataStartedAtEpochMillis,
        long metadataCompletedAtEpochMillis) {
        DiagnosticObservation(final DiagnosticStatus status, final String diagnostic,
            final String rawIds, final String modelImageIds, final String selectorProjection,
            final String livePsd, final boolean relationsAvailable,
            final boolean livePsdAvailable, final PsdValidationContent.Fingerprint freshNativeRgb) {
            this(
                status,
                diagnostic,
                rawIds,
                modelImageIds,
                selectorProjection,
                livePsd,
                relationsAvailable,
                livePsdAvailable,
                freshNativeRgb,
                TargetIdentity.unavailable(),
                false,
                0L,
                0L,
                0L,
                0L
            );
        }

        DiagnosticObservation {
            status = Objects.requireNonNull(status, "status");
            diagnostic = diagnostic == null ? "" : diagnostic;
            rawIds = valueOrUnavailable(rawIds);
            modelImageIds = valueOrUnavailable(modelImageIds);
            selectorProjection = valueOrUnavailable(selectorProjection);
            livePsd = valueOrUnavailable(livePsd);
            observedTarget = observedTarget == null
                ? TargetIdentity.unavailable() : observedTarget;
            if (freshExportStartedAtEpochMillis < 0L
                || freshExportCompletedAtEpochMillis < 0L
                || metadataStartedAtEpochMillis < 0L
                || metadataCompletedAtEpochMillis < 0L) {
                throw new IllegalArgumentException("diagnostic observation times must not be negative");
            }
        }

        static DiagnosticObservation available(final String rawIds, final String modelImageIds,
            final String selectorProjection, final String livePsd,
            final PsdValidationContent.Fingerprint freshNativeRgb) {
            return new DiagnosticObservation(
                DiagnosticStatus.AVAILABLE,
                "",
                rawIds,
                modelImageIds,
                selectorProjection,
                livePsd,
                true,
                true,
                freshNativeRgb
            );
        }

        static DiagnosticObservation unavailable(final String diagnostic) {
            return unavailable(diagnostic, null);
        }

        static DiagnosticObservation unavailable(final String diagnostic,
            final PsdValidationContent.Fingerprint freshNativeRgb) {
            return new DiagnosticObservation(
                DiagnosticStatus.UNAVAILABLE,
                diagnostic,
                UNAVAILABLE_VALUE,
                UNAVAILABLE_VALUE,
                UNAVAILABLE_VALUE,
                UNAVAILABLE_VALUE,
                false,
                false,
                freshNativeRgb
            );
        }

        boolean complete() {
            return status == DiagnosticStatus.AVAILABLE
                && relationsAvailable && livePsdAvailable && freshNativeRgb != null;
        }
    }

    enum SettleStatus {
        STABLE,
        TIMEOUT,
        FAILED,
        UNAVAILABLE
    }

    static record BoundedSettleResult(SettleStatus status, int attempts, long durationMillis,
        String criterion, String diagnostic, boolean stable,
        DiagnosticObservation lastObservation) {
        BoundedSettleResult {
            status = Objects.requireNonNull(status, "status");
            if (attempts < 0) throw new IllegalArgumentException("attempts must not be negative");
            if (durationMillis < 0) {
                throw new IllegalArgumentException("durationMillis must not be negative");
            }
            criterion = Objects.requireNonNull(criterion, "criterion");
            diagnostic = diagnostic == null ? "" : diagnostic;
            if (stable && status != SettleStatus.STABLE) {
                throw new IllegalArgumentException("only STABLE settle results may be stable");
            }
        }
    }

    @FunctionalInterface
    interface ObservationSupplier {
        DiagnosticObservation get() throws Exception;
    }

    @FunctionalInterface
    interface BoundedObservationSupplier {
        DiagnosticObservation get(long remainingMillis) throws Exception;
    }

    @FunctionalInterface
    interface ObservationRecorder {
        void record(DiagnosticObservation observation);
    }

    private static final class ObservationBudgetExceededException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ObservationBudgetExceededException(final String message) {
            super(message);
        }
    }

    private record Target(ArtMeshTextureInputs artMesh,
        dev.turboism.sdk.cubism.id.ModelImageId modelImage, RawImageId raw, boolean rawReplaced,
        TargetIdentity identity) {}
}
