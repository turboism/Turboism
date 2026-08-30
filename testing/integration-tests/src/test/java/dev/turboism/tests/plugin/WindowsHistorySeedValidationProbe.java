package dev.turboism.tests.plugin;

import dev.turboism.sdk.cubism.CubismPlugin;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.history.HistoryAction;
import dev.turboism.sdk.cubism.history.HistoryMoveResult;
import dev.turboism.sdk.plugin.PluginContext;

import javax.swing.SwingUtilities;
import java.awt.event.KeyEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

/** Manual-test-only SDK writer that creates and restores one native Parameter Undo item. */
public final class WindowsHistorySeedValidationProbe implements CubismPlugin {

    private PluginContext context;
    private Thread worker;

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        context.logger().info("History seed validation probe initialized");
    }

    @Override
    public void enable() {
        worker = new Thread(this::run, "turboism-history-seed-validation");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void disable() {
        if (worker != null) worker.interrupt();
    }

    private void run() {
        final Path artifact = context.paths().dataDir().resolve("history-seed.txt");
        final Path checkpoints = context.paths().dataDir().resolve("history-seed-checkpoints.txt");
        try {
            Files.createDirectories(artifact.getParent());
            if (Files.exists(artifact)) throw new IllegalStateException("History seed evidence already exists");
            write(artifact, "status=RUNNING phase=await-model\n");

            final Parameter parameter = awaitParameter();
            final String id = onEdt(() -> parameter.id().value());
            final float before = onEdt(parameter::getValue);
            final float minimum = onEdt(parameter::getMinimumValue);
            final float maximum = onEdt(parameter::getMaximumValue);
            final float first = valueAt(minimum, maximum, 0.17F);
            final float second = valueAt(minimum, maximum, 0.43F);
            final float third = valueAt(minimum, maximum, 0.71F);

            writeValue(parameter, checkpoints, "WRITE_1", id, first);
            writeValue(parameter, checkpoints, "WRITE_2", id, second);
            writeValue(parameter, checkpoints, "WRITE_3", id, third);
            HistorySnapshot history = context.cubism().history().snapshot();
            Thread.sleep(5_000L);

            // Production move is authorized on the exact host: moving to
            // position 1 must restore the first write's value and keep the
            // redo tail available.
            final HistoryMoveResult attempted = context.cubism().history().moveTo(
                history.generation(), history.revision(), 1
            );
            final float afterAttempt = onEdt(parameter::getValue);
            checkpoint(checkpoints, "MOVE_GATE", id, third, afterAttempt);

            onEdt(() -> { parameter.setValue(before); return null; });
            final float restored = awaitValue(parameter, before);
            checkpoint(checkpoints, "RESTORE", id, before, restored);
            Thread.sleep(5_000L);
            final boolean detailsPassed = history.entries().stream().allMatch(entry -> {
                final HistoryAction action = entry.action().orElseThrow();
                return entry.detailLevel() == HistoryAction.DetailLevel.FULL
                    && action.kind() == HistoryAction.Kind.SET_PARAMETER_VALUE
                    && action.targetType().equals("PARAMETER")
                    && action.targetId().equals(id)
                    && action.property().equals("value")
                    && action.before().isPresent()
                    && action.after().isPresent();
            });

            final boolean passed = history.availability() == HistorySnapshot.Availability.AVAILABLE
                && history.position() == 3 && history.entries().size() == 3
                && attempted.outcome() == HistoryMoveResult.Outcome.MOVED
                && attempted.snapshot().availability() == HistorySnapshot.Availability.AVAILABLE
                && same(afterAttempt, first) && same(restored, before)
                && detailsPassed;
            write(artifact,
                "status=" + (passed ? "PASS" : "FAIL") + "\n"
                    + "parameterId=" + id + "\n"
                    + "before=" + before + "\n"
                    + "first=" + first + "\n"
                    + "second=" + second + "\n"
                    + "third=" + third + "\n"
                    + "historyAtThree=" + history + "\n"
                    + "moveGate=" + attempted + " value=" + afterAttempt + "\n"
                    + "restored=" + restored + "\n"
                    + "historyDetails=" + history.entries().stream().map(entry -> entry.action()).toList() + "\n"
            );
        } catch (Exception exception) {
            try {
                write(artifact, "status=FAIL\nerror=" + exception.getClass().getName() + ": " + safe(exception.getMessage()) + "\n");
            } catch (Exception ignored) {
                context.logger().error("History seed evidence could not be written", exception);
            }
        }
    }

    private Parameter awaitParameter() throws Exception {
        Exception unavailable = null;
        for (int attempt = 0; attempt < 120 && !Thread.currentThread().isInterrupted(); attempt++) {
            try {
                return onEdt(() -> context.cubism().model().active().parameters().all().stream()
                    .filter(parameter -> parameter.getMaximumValue() > parameter.getMinimumValue())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No writable Parameter is available")));
            } catch (Exception exception) {
                unavailable = exception;
                Thread.sleep(500L);
            }
        }
        throw unavailable == null ? new IllegalStateException("History seed was interrupted") : unavailable;
    }

    private void writeValue(
        final Parameter parameter,
        final Path checkpoints,
        final String phase,
        final String id,
        final float value
    ) throws Exception {
        onEdt(() -> { parameter.setValue(value); return null; });
        checkpoint(checkpoints, phase, id, value, awaitValue(parameter, value));
        Thread.sleep(750L);
    }

    static float valueAt(final float minimum, final float maximum, final float fraction) {
        final float value = minimum + (maximum - minimum) * fraction;
        if (!Float.isFinite(value)) throw new IllegalStateException("Parameter range is not finite");
        return value;
    }


    static float alternate(final float value, final float minimum, final float maximum) {
        final float candidate = minimum + (maximum - minimum) * 0.37F;
        if (!Float.isFinite(candidate)) throw new IllegalStateException("Parameter range is not finite");
        if (!same(candidate, value)) return candidate;
        final float fallback = minimum + (maximum - minimum) * 0.63F;
        if (!Float.isFinite(fallback) || same(fallback, value)) throw new IllegalStateException("No distinct Parameter value is available");
        return fallback;
    }

    private static float awaitValue(final Parameter parameter, final float expected) throws Exception {
        float actual = Float.NaN;
        for (int attempt = 0; attempt < 50; attempt++) {
            actual = onEdt(parameter::getValue);
            if (same(actual, expected)) return actual;
            Thread.sleep(100L);
        }
        throw new IllegalStateException("Timed out waiting for Parameter value " + expected + "; actual=" + actual);
    }


    private static <T> T onEdt(final Callable<T> call) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return call.call();
        final AtomicReference<T> result = new AtomicReference<>();
        final AtomicReference<Exception> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(call.call());
            } catch (Exception exception) {
                failure.set(exception);
            }
        });
        if (failure.get() != null) throw failure.get();
        return result.get();
    }

    private static boolean same(final float left, final float right) {
        return Float.compare(left, right) == 0;
    }

    private static String safe(final String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
    }

    private static void checkpoint(
        final Path artifact,
        final String phase,
        final String parameterId,
        final float expected,
        final float actual
    ) throws Exception {
        Files.writeString(
            artifact,
            "phase=" + phase + " parameterId=" + parameterId + " expected=" + expected + " actual=" + actual + "\n",
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        );
    }
    private static void write(final Path artifact, final String value) throws Exception {
        Files.writeString(
            artifact,
            value,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
        );
    }
}
