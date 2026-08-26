package dev.turboism.tests.plugin;

import dev.turboism.sdk.cubism.CubismPlugin;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.ui.dialog.HostDialogAction;
import dev.turboism.sdk.ui.dialog.HostDialogMatcher;
import dev.turboism.sdk.ui.dialog.HostDialogOutcome;

import javax.swing.SwingUtilities;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lane C validation probe: drives the real Cubism unsaved-changes confirmation
 * dialog through the public SDK {@code hostDialogs()} service (no private probe
 * logic, no Robot in the framework path; the version-routed close trigger is the
 * same Alt+F4 / WINDOW_CLOSING pair verified by the parameter validation probe).
 *
 * <p>Flow: host/model ready → trigger close (version-routed) → CANCEL the
 * confirmation → record expected=ACTED actual=&lt;outcome&gt; status=PASS|FAIL →
 * verify the dialog disappeared and the host window is still alive → then
 * trigger close again and discard (NO) so the host exits cleanly.</p>
 */
public final class HostDialogAutomationValidationProbe implements CubismPlugin {

    static final String RESULT_RELATIVE = "state/dialog-automation-result.properties";
    static final String PROGRESS_RELATIVE = "logs/dialog-automation-progress.txt";
    static final String READY_MARKER = "DIALOG_AUTO_PROBE_READY";
    static final Duration CONFIRMATION_TIMEOUT = Duration.ofSeconds(30);

    private PluginContext context;
    private Thread validationThread;

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        context.logger().info("Host dialog automation validation probe initialized");
    }

    @Override
    public void enable() {
        validationThread = new Thread(this::runValidation, "turboism-dialog-automation-validation");
        validationThread.setDaemon(true);
        validationThread.start();
    }

    @Override
    public void disable() {
        if (validationThread != null) {
            validationThread.interrupt();
        }
    }

    private void runValidation() {
        final long startedNanos = System.nanoTime();
        final Path home = Path.of(requireProperty("turboism.home"));
        final Path result = home.resolve(RESULT_RELATIVE);
        final Path progress = home.resolve(PROGRESS_RELATIVE);
        final String hostVersion = requireProperty("turboism.validation.hostVersion");
        try {
            awaitHostModelReady(context, progress);
            context.logger().info(READY_MARKER + " hostVersion=" + hostVersion);

            // An untouched document closes without a confirmation dialog, so make an unsaved
            // change through the public SDK first: the close confirmation must then appear.
            makeUnsavedChange(context);
            context.logger().info("DIALOG_AUTO_UNSAVED_CHANGE made");

            final Window hostWindow = onHostThread(() ->
                WindowsParameterValidationProbe.selectHostWindow(Window.getWindows()));
            final WindowsParameterValidationProbe.HostCloseRoute route =
                WindowsParameterValidationProbe.hostCloseRoute(hostVersion);

            // 1. First close request: the unsaved-changes confirmation must appear and CANCEL must keep the host alive.
            final HostDialogOutcome cancelOutcome = requestCloseAndAct(route, hostWindow, HostDialogAction.CANCEL);
            context.logger().info("DIALOG_AUTO_CANCEL outcome=" + cancelOutcome);
            if (cancelOutcome != HostDialogOutcome.ACTED) {
                context.logger().info("DIALOG_AUTO_SNAPSHOTS " + context.hostDialogs().snapshots());
            }

            final boolean dialogGone = context.hostDialogs().snapshots().isEmpty();
            final boolean hostAlive = onHostThread(() -> hostWindow.isDisplayable() && hostWindow.isVisible());

            final boolean passed = cancelOutcome == HostDialogOutcome.ACTED && dialogGone && hostAlive;
            writeResult(
                result,
                System.getProperty("turboism.validation.runId", "unknown"),
                hostVersion,
                cancelOutcome,
                passed,
                (System.nanoTime() - startedNanos) / 1_000_000L
            );
            context.logger().info("DIALOG_AUTO_RESULT status=" + (passed ? "PASS" : "FAIL")
                + " expected=ACTED actual=" + cancelOutcome
                + " dialogGone=" + dialogGone + " hostAlive=" + hostAlive
                + " result=" + result);

            // 2. Final close request: discard (NO) so the host exits cleanly.
            final HostDialogOutcome discardOutcome = requestCloseAndAct(route, hostWindow, HostDialogAction.NO);
            context.logger().info("DIALOG_AUTO_FINAL_CLOSE outcome=" + discardOutcome
                + " (NOT_FOUND is acceptable when the host closed without a confirmation)");
        } catch (Exception failure) {
            context.logger().error("DIALOG_AUTO_RESULT status=FAIL", failure);
            try {
                writeResult(
                    result,
                    System.getProperty("turboism.validation.runId", "unknown"),
                    hostVersion,
                    HostDialogOutcome.UNSUPPORTED,
                    false,
                    (System.nanoTime() - startedNanos) / 1_000_000L
                );
            } catch (Exception writeFailure) {
                context.logger().error("Dialog automation result file could not be written", writeFailure);
            }
        }
    }

    /** Triggers the version-routed close and acts on the resulting confirmation dialog. */
    private HostDialogOutcome requestCloseAndAct(
        final WindowsParameterValidationProbe.HostCloseRoute route,
        final Window hostWindow,
        final HostDialogAction action
    ) throws Exception {
        triggerClose(route, hostWindow);
        return context.hostDialogs().act(
            HostDialogMatcher.anyConfirmation(), action, CONFIRMATION_TIMEOUT
        );
    }

    private static void triggerClose(
        final WindowsParameterValidationProbe.HostCloseRoute route,
        final Window hostWindow
    ) throws Exception {
        if (route == WindowsParameterValidationProbe.HostCloseRoute.ROBOT_ALT_F4) {
            // Robot Alt+F4 is a global keystroke: it lands on whatever window owns the focus,
            // so bring the host window to front and wait for focus to settle first.
            onHostThread(() -> {
                hostWindow.toFront();
                hostWindow.requestFocus();
                return null;
            });
            Thread.sleep(800L);
            pressAltF4();
        } else {
            SwingUtilities.invokeLater(() -> hostWindow.dispatchEvent(
                new WindowEvent(hostWindow, WindowEvent.WINDOW_CLOSING)
            ));
        }
    }

    /** Writes one parameter value via the public SDK to mark the document as unsaved. */
    private static void makeUnsavedChange(final PluginContext context) throws Exception {
        final CubismModel model = onHostThread(context.cubism().model()::active);
        final dev.turboism.sdk.cubism.model.Parameter parameter = onHostThread(() -> {
            final var all = model.parameters().all();
            if (all.isEmpty()) {
                throw new IllegalStateException("No parameter is available to modify");
            }
            return all.get(0);
        });
        final float minimum = onHostThread(parameter::getMinimumValue);
        final float maximum = onHostThread(parameter::getMaximumValue);
        final float before = onHostThread(parameter::getValue);
        final float target = Math.abs(before - minimum) > 1e-4f ? minimum : (minimum + maximum) / 2f;
        onHostThread(() -> {
            parameter.setValue(target);
            return null;
        });
        context.logger().info("DIALOG_AUTO_PARAM_WRITE before=" + before + " target=" + target);
    }

    private static void pressAltF4() throws Exception {
        final java.awt.Robot robot = new java.awt.Robot();
        robot.keyPress(java.awt.event.KeyEvent.VK_ALT);
        try {
            robot.keyPress(java.awt.event.KeyEvent.VK_F4);
        } finally {
            robot.keyRelease(java.awt.event.KeyEvent.VK_F4);
            robot.keyRelease(java.awt.event.KeyEvent.VK_ALT);
        }
    }

    /** Bounded wait for the fixture model (opened by the launcher) to be fully readable. */
    private static void awaitHostModelReady(final PluginContext context, final Path progress) throws Exception {
        for (int attempt = 0; attempt < 120 && !Thread.currentThread().isInterrupted(); attempt++) {
            try {
                final CubismModel model = onHostThread(
                    () -> context.cubism().model().active()
                );
                onHostThread(() -> {
                    if (model.drawables().all().isEmpty()) {
                        throw new IllegalStateException("No ArtMesh is available");
                    }
                    return null;
                });
                return;
            } catch (Exception exception) {
                Files.writeString(
                    progress,
                    "status=RUNNING phase=await-model attempt=" + attempt
                        + " error=" + exception.getClass().getSimpleName()
                        + ": " + exception.getMessage() + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
                );
                Thread.sleep(1000L);
            }
        }
        throw new IllegalStateException("Fixture model did not become ready within the budget");
    }

    /** Writes the structured PASS/FAIL result consumed by the generic host runner. */
    static void writeResult(
        final Path result,
        final String runId,
        final String hostVersion,
        final HostDialogOutcome outcome,
        final boolean passed,
        final long durationMillis
    ) throws Exception {
        Files.createDirectories(result.getParent());
        Files.writeString(
            result,
            "schemaVersion=1\n"
                + "runId=" + runId + "\n"
                + "hostVersion=" + hostVersion + "\n"
                + "expected=ACTED\n"
                + "actual=" + outcome + "\n"
                + "durationMillis=" + durationMillis + "\n"
                + "status=" + (passed ? "PASS" : "FAIL") + "\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
        );
    }

    private static String requireProperty(final String name) {
        final String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set");
        }
        return value;
    }

    private static <T> T onHostThread(final java.util.concurrent.Callable<T> call) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return call.call();
        }
        final AtomicReference<T> result = new AtomicReference<>();
        final AtomicReference<Exception> failure = new AtomicReference<>();
        final java.util.concurrent.CountDownLatch completed = new java.util.concurrent.CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            try {
                result.set(call.call());
            } catch (Exception exception) {
                failure.set(exception);
            } finally {
                completed.countDown();
            }
        });
        if (!completed.await(5L, java.util.concurrent.TimeUnit.SECONDS)) {
            throw new IllegalStateException("Cubism EDT did not accept the probe within 5 seconds");
        }
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }
}
