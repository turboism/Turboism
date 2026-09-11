package dev.turboism.tests.plugin;

import dev.turboism.sdk.cubism.CubismPlugin;
import dev.turboism.sdk.cubism.event.CubismOperationEvent;
import dev.turboism.sdk.plugin.PluginContext;

import javax.swing.SwingUtilities;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manual-test-only probe: records what an operator's own Editor edits look like to Turboism.
 *
 * <p>The automated probes drive the host through the SDK, so they can never show what the
 * {@code before} phase sees for an edit made by clicking and dragging in the native user
 * interface. This probe therefore hands the actions to the operator: it publishes one
 * instruction, waits until the native undo manager actually moves, and then records the native
 * entries the action produced together with every semantic event Turboism published for it.</p>
 *
 * <p>It changes nothing. It writes evidence, publishes nothing, and never mutates the model, so
 * the only cause of a recorded change is the operator's own action.</p>
 */
public final class WindowsHistoryNativeUiIngressProbe implements CubismPlugin {

    private static final long MAX_EVIDENCE_BYTES = 2_097_152L;
    private static final long TERMINAL_RESERVE_BYTES = 4_096L;
    private static final int MAX_RECORDED_EVENTS = 512;
    private static final long POLL_MILLIS = 250L;

    /**
     * How long one sample of the host may take.
     *
     * <p>A sample runs on the Editor thread. If that thread has stopped pumping events — because
     * the Editor is closing, or a modal dialog is up — the wait has to end anyway, so the probe
     * can write its verdicts and its summary instead of hanging with no terminal line.</p>
     */
    private static final long SAMPLE_TIMEOUT_MILLIS = 15_000L;
    private static final long SETTLE_MILLIS = 2_000L;
    private static final long AWAIT_DOCUMENT_MILLIS = 240_000L;
    /**
     * How long one step waits for the operator.
     *
     * <p>Generous on purpose: the window opens when the instruction is written, and a human has to
     * read it and walk the mouse to the right part of the Editor before anything happens.</p>
     */
    private static final long STEP_TIMEOUT_MILLIS = 420_000L;

    /**
     * Operator steps in order.
     *
     * <p>The first six are the native action families whose ingress is being reviewed, split so
     * that each one produces a shape a decoder can be judged against: a whole-object move
     * translates every point by the same vector, while a mesh deformation does not. The last two
     * are a native Undo and Redo of whatever the operator left on the stack.</p>
     */
    private static final List<Step> STEPS = List.of(
        new Step(
            "parts-tree-drag",
            "ACTION",
            "Drag one Part onto a different Part in the Parts tree, then release."
        ),
        new Step(
            "deformer-assign",
            "ACTION",
            "Assign a different Deformer target to one Part or ArtMesh and confirm it: the"
                + " Deformers palette must show the new target. Selecting the object alone is not"
                + " an assignment and does not count."
        ),
        new Step(
            "canvas-move",
            "ACTION",
            "Move ONE model object as a whole on the canvas with the mouse, then release. Do not"
                + " edit its vertices or mesh points."
        ),
        new Step(
            "canvas-deform",
            "ACTION",
            "Deform ONE model object instead of moving it: drag a single mesh point (or use the"
                + " mesh edit tool) so its shape changes, then release."
        ),
        new Step(
            "native-parameter",
            "ACTION",
            "Change one Parameter value in the native Parameter palette only (drag its slider or"
                + " type a value), then release. Do not move any object."
        ),
        new Step(
            "native-color",
            "ACTION",
            "Change only the multiply colour (正片叠底色) of one drawable in the native"
                + " palette, then confirm. Do not move the object and do not edit its mesh."
        ),
        new Step(
            "native-undo",
            "UNDO",
            "Press the native Undo shortcut once (Ctrl+Z)."
        ),
        new Step(
            "native-redo",
            "REDO",
            "Press the native Redo shortcut once (Ctrl+Y)."
        )
    );

    private final Object lock = new Object();
    private final List<Observed> observed = new ArrayList<>();
    private PluginContext context;
    private Thread worker;
    private volatile boolean running;

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        context.logger().info("Native UI ingress probe initialized");
    }

    @Override
    public void enable() {
        running = true;
        worker = new Thread(this::run, "turboism-history-native-ui-ingress");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void disable() {
        running = false;
        final Thread current = worker;
        if (current != null) {
            current.interrupt();
            try {
                current.join(5_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void beforeCubismOperation(final CubismOperationEvent event) {
        record("before", event);
    }

    @Override
    public void onCubismOperationConfirmed(final CubismOperationEvent event) {
        record("on", event);
    }

    @Override
    public void afterCubismOperation(final CubismOperationEvent event) {
        record("after", event);
    }

    /**
     * Records one semantic event.
     *
     * <p>Runs on the host's own thread, so it allocates one small immutable record and returns.
     * Nothing here may throw: the runtime isolates a failing hook, but a probe that throws would
     * lose the evidence for the very operation it exists to describe.</p>
     */
    private void record(final String phase, final CubismOperationEvent event) {
        try {
            if (event == null) return;
            final Observed value = new Observed(
                phase,
                event.sequence(),
                event.operation() == null ? "" : event.operation().name(),
                event.origin() == null ? "" : event.origin().name(),
                event.subjectId().orElse(""),
                event.label().orElse(""),
                Thread.currentThread().getName(),
                Instant.now().toString()
            );
            synchronized (lock) {
                observed.add(value);
                if (observed.size() > MAX_RECORDED_EVENTS) {
                    observed.remove(0);
                }
            }
        } catch (Throwable ignored) {
            // Observing must never disturb the operation being observed.
        }
    }

    private void run() {
        final Path artifact = context.paths().dataDir().resolve("history-native-ui-ingress.jsonl");
        try {
            Files.createDirectories(artifact.getParent());
            if (Files.exists(artifact)) {
                throw new IllegalStateException("Native UI ingress evidence already exists");
            }
            final List<String> failures = new ArrayList<>();
            write(artifact, "{\"type\":\"event\",\"phase\":\"start\"}\n", false);
            final boolean ready = awaitDocument();
            write(artifact, "{\"type\":\"event\",\"phase\":\"document\",\"ready\":" + ready + "}\n", false);
            if (!ready) {
                failures.add("no-native-document");
            }
            final WindowsHistoryManagerValidationProbe.Snapshot baseline = sample();
            write(artifact, paired(baseline, "baseline"), false);
            long knownEdits = significantEntries(baseline);
            long knownPosition = position(baseline);
            boolean hookFired = false;
            boolean observerFired = false;
            boolean labelSeen = false;

            for (int index = 0; ready && index < STEPS.size(); index++) {
                final Step step = STEPS.get(index);
                final int start = cursor();
                final String instruction = "STEP " + (index + 1) + "/" + STEPS.size()
                    + " [" + step.id() + "] " + step.instruction();
                write(
                    artifact,
                    "{\"type\":\"prompt\",\"phase\":\"" + json(step.id()) + "\",\"instruction\":\""
                        + json(step.instruction()) + "\",\"openedAt\":\"" + Instant.now()
                        + "\",\"expiresAt\":\""
                        + Instant.ofEpochMilli(System.currentTimeMillis() + STEP_TIMEOUT_MILLIS)
                        + "\"}\n",
                    false
                );
                context.logger().info(instruction);

                final WindowsHistoryManagerValidationProbe.Snapshot after =
                    awaitChange(knownEdits, knownPosition);
                if (after == null) {
                    failures.add(step.id() + ":no-native-change");
                    write(
                        artifact,
                        new Verdict(false, "no-native-change", "unchanged after " + STEP_TIMEOUT_MILLIS + "ms")
                            .json(step.id()),
                        false
                    );
                    continue;
                }
                knownEdits = significantEntries(after);
                knownPosition = position(after);
                write(artifact, paired(after, step.id()), false);
                final List<Observed> events;
                synchronized (lock) {
                    events = List.copyOf(observed.subList(Math.min(start, observed.size()), observed.size()));
                }
                for (Observed event : events) {
                    write(artifact, event.json(step.id()), false);
                }
                final Verdict verdict = checkStep(step, events);
                write(artifact, verdict.json(step.id()), false);
                if (!verdict.ok()) failures.add(step.id() + ":" + verdict.code());
                hookFired |= events.stream().anyMatch(event -> event.phase().equals("before"));
                observerFired |= events.stream().anyMatch(
                    event -> event.phase().equals("on") || event.phase().equals("after")
                );
                labelSeen |= events.stream().anyMatch(
                    event -> event.phase().equals("before") && !event.label().isBlank()
                );
            }

            final WindowsHistoryManagerValidationProbe.Snapshot finalSnapshot = sample();
            write(artifact, paired(finalSnapshot, "final"), false);
            write(
                artifact,
                new Verdict(
                    hookFired,
                    "hook-fired",
                    hookFired ? "at least one before event observed" : "no before event observed"
                ).json("overall"),
                false
            );
            write(
                artifact,
                new Verdict(
                    observerFired,
                    "observer-fired",
                    observerFired
                        ? "the ingress attached and published at least one operation"
                        : "no operation was published for any native change"
                ).json("overall"),
                false
            );
            write(
                artifact,
                new Verdict(
                    labelSeen,
                    "label-transported",
                    labelSeen
                        ? "at least one before event carried the native edit name"
                        : "every before event carried an empty name"
                ).json("overall"),
                false
            );
            // Only the hook firing is gated. A family whose native edit name is empty is a finding
            // to report, not a probe failure: the label is presentation, and the operation is
            // identified structurally.
            if (!hookFired) failures.add("hook-did-not-fire");
            if (!observerFired) failures.add("observer-did-not-fire");
            // The failure list is its own line: the host runner detects the terminal result by
            // comparing a whole line, so the summary line must be exactly what it looks for.
            write(
                artifact,
                "{\"type\":\"failures\",\"failures\":["
                    + String.join(
                        ",",
                        failures.stream().map(WindowsHistoryNativeUiIngressProbe::quoted).toList()
                    )
                    + "]}\n",
                false
            );
            write(artifact, summaryLine(failures.isEmpty()), true);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            context.logger().error("Native UI ingress probe failed", exception);
            try {
                write(
                    artifact,
                    "{\"type\":\"error\",\"class\":\"" + json(exception.getClass().getName())
                        + "\",\"message\":\"" + json(exception.getMessage()) + "\"}\n"
                        + "{\"type\":\"summary\",\"status\":\"FAIL\"}\n",
                    true
                );
            } catch (Exception ignored) {
                context.logger().error("Native UI ingress evidence could not be written", ignored);
            }
        }
    }

    /**
     * Waits for the operator's action, then for the runtime to settle.
     *
     * <p>Detecting the native position, or the number of <em>significant</em> entries, moving is
     * what proves the operator's action reached the native undo manager; a prompt alone would let
     * the probe record an unrelated edit.</p>
     *
     * <p>Significant entries rather than all entries, because a selection is itself an ordinary
     * native undo entry. The host marks each entry's significance, so a step whose instruction
     * begins with a click would otherwise be closed by the click that preceded the real edit and
     * every following step would be attributed to the previous step's family. Every selection
     * entry the probe has recorded so far is insignificant and every real edit is significant,
     * which makes this a fact the host already states rather than a rule about labels.</p>
     *
     * <p>The wait is bounded, and so is each sample: a sample is taken on the Editor thread, so an
     * Editor that has stopped pumping events would otherwise park this thread for the life of the
     * process and the run would end with no verdict and no summary at all.</p>
     */
    private WindowsHistoryManagerValidationProbe.Snapshot awaitChange(
        final long knownEdits,
        final long knownPosition
    ) throws Exception {
        final long deadline = System.currentTimeMillis() + STEP_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (!running) throw new InterruptedException("Probe disabled while awaiting the operator");
            Thread.sleep(POLL_MILLIS);
            final WindowsHistoryManagerValidationProbe.Snapshot current = sample();
            if (current == null) continue;
            if (significantEntries(current) != knownEdits || position(current) != knownPosition) {
                Thread.sleep(SETTLE_MILLIS);
                return sample();
            }
        }
        return null;
    }

    private boolean awaitDocument() throws Exception {
        final long deadline = System.currentTimeMillis() + AWAIT_DOCUMENT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (!running) throw new InterruptedException("Probe disabled while awaiting the document");
            Thread.sleep(1_000L);
            if (sample() != null) return true;
        }
        return false;
    }

    private WindowsHistoryManagerValidationProbe.Snapshot sample() throws Exception {
        final AtomicReference<WindowsHistoryManagerValidationProbe.Snapshot> result =
            new AtomicReference<>();
        final AtomicReference<Exception> failure = new AtomicReference<>();
        final CountDownLatch sampled = new CountDownLatch(1);
        // invokeLater rather than invokeAndWait: the wait has to be bounded, because an Editor
        // whose event thread has stopped pumping would otherwise park this thread for the life of
        // the process and the run would end with no verdict, no failure list and no summary.
        SwingUtilities.invokeLater(() -> {
            try {
                result.set(WindowsHistoryManagerValidationProbe.sample(context));
            } catch (Exception exception) {
                failure.set(exception);
            } finally {
                sampled.countDown();
            }
        });
        if (!sampled.await(SAMPLE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            context.logger().warn(
                "Native UI ingress probe sampling timed out after " + SAMPLE_TIMEOUT_MILLIS + "ms"
            );
            return null;
        }
        if (failure.get() != null) return null;
        return result.get();
    }

    /**
     * Checks one step's events against what that step is supposed to be.
     *
     * <p>An action the operator performs must be observed as a host edit and must be announced by
     * the entry hook first. A native Undo or Redo is not an edit entry at all, so it must produce
     * no {@code before} event and must be attributed as navigation.</p>
     */
    static Verdict checkStep(final Step step, final List<Observed> events) {
        final long before = events.stream().filter(event -> event.phase().equals("before")).count();
        final long confirmed = events.stream()
            .filter(event -> event.phase().equals("on") || event.phase().equals("after"))
            .count();
        final String seen = "before=" + before + ",confirmed=" + confirmed
            + ",events=" + events.size();
        if (step.kind().equals("ACTION")) {
            if (before == 0) return new Verdict(false, "no-before-event", seen);
            if (confirmed == 0) return new Verdict(false, "no-confirmed-event", seen);
            return new Verdict(true, "action-announced-before-it-was-observed", seen);
        }
        if (before != 0) return new Verdict(false, "navigation-was-announced-as-an-edit", seen);
        final boolean attributed = events.stream().anyMatch(event -> event.origin().equals(step.kind()));
        if (!attributed) return new Verdict(false, "navigation-not-attributed", seen);
        return new Verdict(true, "navigation-attributed-without-an-edit", seen);
    }

    /**
     * The native change signal: how many <em>significant</em> entries the undo manager holds.
     *
     * <p>Entry count rather than position, because an edit made after an Undo replaces the
     * redoable tail and can leave the position unchanged.</p>
     *
     * <p>Significant entries rather than all entries, because the host records a selection as an
     * ordinary undo entry. Counting those would close a step on the click that precedes the
     * operator's real action, which is what happened on the previous run.</p>
     */
    static long significantEntries(final WindowsHistoryManagerValidationProbe.Snapshot snapshot) {
        if (snapshot == null) return -1L;
        return significantEntries(snapshot.current().entries());
    }

    /** Package-private so a focused test can pin the boundary rule without a live host. */
    static long significantEntries(final List<WindowsHistoryManagerValidationProbe.Entry> entries) {
        return entries.stream().filter(WindowsHistoryManagerValidationProbe.Entry::significant).count();
    }

    private static long position(final WindowsHistoryManagerValidationProbe.Snapshot snapshot) {
        return snapshot == null ? -1L : snapshot.current().position();
    }

    private int cursor() {
        synchronized (lock) {
            return observed.size();
        }
    }

    private String paired(
        final WindowsHistoryManagerValidationProbe.Snapshot snapshot,
        final String phase
    ) {
        if (snapshot == null) {
            return "{\"type\":\"paired-snapshot\",\"phase\":\"" + json(phase)
                + "\",\"unavailable\":true}\n";
        }
        return snapshot.pairedJson(phase) + "\n";
    }

    private void write(final Path artifact, final String value, final boolean terminal) throws Exception {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        final long existing = Files.exists(artifact) ? Files.size(artifact) : 0L;
        final long reserve = terminal ? 0L : TERMINAL_RESERVE_BYTES;
        if (bytes.length > MAX_EVIDENCE_BYTES
            || existing + bytes.length > MAX_EVIDENCE_BYTES - reserve) {
            throw new IllegalStateException("Native UI ingress evidence budget exhausted");
        }
        Files.writeString(
            artifact,
            value,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        );
    }

    /**
     * {@return the terminal result line the host runner matches}
     *
     * <p>The runner compares one whole line, so nothing else may share it.</p>
     */
    static String summaryLine(final boolean passed) {
        return passed
            ? "{\"type\":\"summary\",\"status\":\"PASS\"}\n"
            : "{\"type\":\"summary\",\"status\":\"FAIL\"}\n";
    }

    private static String quoted(final String value) {
        return "\"" + json(value) + "\"";
    }

    static String json(final String value) {
        if (value == null) return "";
        final StringBuilder escaped = new StringBuilder();
        value.codePoints().limit(512).forEach(codePoint -> {
            switch (codePoint) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (codePoint < 0x20) escaped.append(String.format("\\u%04x", codePoint));
                    else escaped.appendCodePoint(codePoint);
                }
            }
        });
        return escaped.toString();
    }

    /** One operator instruction and the kind of native outcome it must produce. */
    record Step(String id, String kind, String instruction) {
    }

    /** One semantic event as the probe saw it. */
    record Observed(
        String phase,
        long sequence,
        String operation,
        String origin,
        String subjectId,
        String label,
        String thread,
        String observedAt
    ) {
        String json(final String step) {
            return "{\"type\":\"semantic-event\",\"step\":\"" + WindowsHistoryNativeUiIngressProbe.json(step)
                + "\",\"phase\":\"" + WindowsHistoryNativeUiIngressProbe.json(phase)
                + "\",\"sequence\":" + sequence
                + ",\"operation\":\"" + WindowsHistoryNativeUiIngressProbe.json(operation)
                + "\",\"origin\":\"" + WindowsHistoryNativeUiIngressProbe.json(origin)
                + "\",\"subjectId\":\"" + WindowsHistoryNativeUiIngressProbe.json(subjectId)
                + "\",\"label\":\"" + WindowsHistoryNativeUiIngressProbe.json(label)
                + "\",\"thread\":\"" + WindowsHistoryNativeUiIngressProbe.json(thread)
                + "\",\"observedAt\":\"" + WindowsHistoryNativeUiIngressProbe.json(observedAt) + "\"}\n";
        }
    }

    /** One step's verdict. */
    record Verdict(boolean ok, String code, String detail) {
        String json(final String step) {
            return "{\"type\":\"check\",\"check\":\""
                + WindowsHistoryNativeUiIngressProbe.json(step + "-" + code) + "\",\"ok\":" + ok
                + ",\"detail\":\"" + WindowsHistoryNativeUiIngressProbe.json(detail) + "\"}\n";
        }
    }
}
