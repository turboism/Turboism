package dev.turboism.tests.plugin;

import dev.turboism.sdk.cubism.CubismPlugin;
import dev.turboism.sdk.cubism.event.CubismOperationEvent;
import dev.turboism.sdk.cubism.history.HistoryChange;
import dev.turboism.sdk.cubism.history.HistoryEntryDetail;
import dev.turboism.sdk.cubism.history.HistoryRelationChange;
import dev.turboism.sdk.cubism.history.HistoryTarget;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.plugin.PluginContext;

import javax.swing.SwingUtilities;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.table.TableModel;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreePath;
import java.awt.Component;
import java.awt.GraphicsConfiguration;
import java.awt.Insets;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * <p>Outside the bounded actor paths below it changes nothing: it writes evidence, publishes
 * nothing, and never mutates the model itself. With {@code -Dturboism.history.nativeUi.automate}
 * the listed actors drive the same real host UI an operator would — the recorded change is then
 * caused by the probe's own UI action, which the artifact names per step.</p>
 */
public final class WindowsHistoryNativeUiIngressProbe implements CubismPlugin {

    private static final long MAX_EVIDENCE_BYTES = 2_097_152L;
    private static final long TERMINAL_RESERVE_BYTES = 4_096L;
    private static final int MAX_RECORDED_EVENTS = 512;
    private static final int MAX_PARAMETER_VALUES = 128;
    private static final int MAX_PARAMETER_ID_LENGTH = 256;
    private static final int MAX_PARAMETER_THREAD_LENGTH = 128;
    private static final int MAX_PARAMETER_PHASE_LENGTH = 16;
    private static final int MAX_PARAMETER_LIFECYCLE_EVENTS = 256;
    private static final int PARAMETER_ACTOR_POLLS = 10;
    private static final long POLL_MILLIS = 250L;

    /**
     * Whether the probe drives the bounded native actions itself.
     *
     * <p>Automation is opt-in through {@code -Dturboism.history.nativeUi.automate=true}: the
     * default stays the operator runbook. When enabled, a step with an actor performs its action
     * through the real host UI — menu accelerators and {@code Robot} input on the visible
     * windows — and a step without one keeps waiting for the operator. An actor that cannot
     * resolve its control reports {@code unresolved} and the operator window stays open, so an
     * automated run degrades to manual pacing instead of failing blind.</p>
     */
    static final boolean AUTOMATE =
        Boolean.parseBoolean(System.getProperty("turboism.history.nativeUi.automate", "false"));

    /** Bound on the component dump written for actor targeting review. */
    private static final int UI_MAP_MAX_COMPONENTS = 400;
    private static final int UI_MAP_MAX_WINDOWS = 8;
    private static final int UI_MAP_MAX_DEPTH = 12;
    private static final int UI_MAP_MAX_CHARS = 65_536;

    /** Smallest edge a component needs before the canvas fallback may treat it as the view. */
    private static final int MIN_CANVAS_EDGE = 128;

    /** Bound on how many candidate surfaces the canvas actor will drag in one step. */
    private static final int MAX_CANVAS_CANDIDATES = 6;

    private static final String INTERNAL_ROOT_PART_ID = "__RootPart__";
    private static final int MAX_PART_GESTURE_PAIRS = 8;
    private static final int PART_GESTURE_SETTLE_POLLS = 12;
    private static final int MAX_PART_GESTURE_EVIDENCE_LINES = 64;
    private static final int MAX_PART_GESTURE_PATH_COMPONENTS = 32;
    private static final int MAX_PART_GESTURE_TEXT_LENGTH = 256;
    /** Maximum number of identical source-point moves admitted before press. */
    private static final int MAX_PART_POINTER_ATTEMPTS = 3;

    /**
     * Deepest level the canvas scan may reach.
     *
     * <p>The r19 run showed the model view's actual GL surface nested deeper than the ui-map's
     * dump bound: every ancestor leaf check failed because the visible leaf rule stopped before
     * the surface, while the surface's own parent chain stayed invisible to a depth-12 walk. The
     * discovery walk gets a deeper bound than the evidence dump; press-point validation below is
     * what keeps a container press from landing on a control.</p>
     */
    private static final int CANVAS_SCAN_DEPTH = UI_MAP_MAX_DEPTH * 2;

    /**
     * How often a pending step re-announces its instruction.
     *
     * <p>The step window opens when the instruction is written, and a human who is not at the
     * desk yet reads nothing. Re-announcing inside the same window means a late operator sees the
     * current instruction instead of losing the step; it costs no extra time because the deadline
     * does not move.</p>
     */
    private static final long REMINDER_MILLIS = 90_000L;

    /** How long an edit step waits for a multi-entry action to finish committing. */
    private static final long ACTION_SETTLE_MILLIS = 2_000L;

    /**
     * How long a navigation step waits.
     *
     * <p>Deliberately short: a native Undo or Redo is one atomic step, and a long settle lets the
     * operator's next keystroke land inside this step's window, which is how the previous three
     * runs misattributed the redo.</p>
     */
    private static final long NAVIGATION_SETTLE_MILLIS = 300L;

    /**
     * How long one sample of the host may take.
     *
     * <p>A sample runs on the Editor thread. If that thread has stopped pumping events — because
     * the Editor is closing, or a modal dialog is up — the wait has to end anyway, so the probe
     * can write its verdicts and its summary instead of hanging with no terminal line.</p>
     */
    private static final long SAMPLE_TIMEOUT_MILLIS = 15_000L;
    private static final long AWAIT_DOCUMENT_MILLIS = 240_000L;
    /**
     * How long one step waits for the operator.
     *
     * <p>Generous on purpose in manual mode: the window opens when the instruction is written,
     * and a human has to read it and walk the mouse to the right part of the Editor before
     * anything happens. Automated runs share the host display with other work, so they default
     * to a much shorter residual window — the actors already verify each attempt against the
     * undo manager themselves, and the window only exists to catch a late commit or an operator
     * who happens to be present. {@code -Dturboism.history.nativeUi.stepTimeoutMillis} overrides
     * either default.</p>
     */
    private static final long STEP_TIMEOUT_MILLIS = Long.parseLong(
        System.getProperty(
            "turboism.history.nativeUi.stepTimeoutMillis",
            AUTOMATE ? "60000" : "420000"));

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
    private final List<ParameterLifecycleEvent> parameterLifecycle = new ArrayList<>();
    private long parameterLifecycleSequence;
    private volatile ParameterChangeObservation parameterActorTermination;
    private volatile PartActorResult partActorResult = PartActorResult.notRun();
    private final List<String> partGestureEvidence = new ArrayList<>();
    private PluginContext context;
    private Path artifact;
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

    @Override
    public float beforeSetParameterValue(final Parameter parameter, final float value) {
        recordParameterLifecycle(
            "before", parameter, readFiniteParameterValue(parameter), finiteValue(value));
        return value;
    }

    @Override
    public void onParameterValueChanged(
        final Parameter parameter,
        final float oldValue,
        final float newValue
    ) {
        recordParameterLifecycle(
            "on", parameter, finiteValue(oldValue), finiteValue(newValue));
    }

    @Override
    public void afterSetParameterValue(final Parameter parameter, final float value) {
        recordParameterLifecycle(
            "after", parameter, null, finiteValue(value));
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

    /** Records a bounded immutable parameter callback without touching model state. */
    private void recordParameterLifecycle(
        final String phase,
        final Parameter parameter,
        final Float oldValue,
        final Float newValue
    ) {
        try {
            final String parameterId = parameter == null || parameter.id() == null
                ? "" : parameter.id().value();
            final ParameterLifecycleEvent value;
            synchronized (lock) {
                value = new ParameterLifecycleEvent(
                    ++parameterLifecycleSequence,
                    phase,
                    parameterId == null ? "" : parameterId,
                    oldValue,
                    newValue,
                    Thread.currentThread().getName()
                );
                if (parameterLifecycle.size() >= MAX_PARAMETER_LIFECYCLE_EVENTS) {
                    parameterLifecycle.remove(0);
                }
                parameterLifecycle.add(value);
            }
        } catch (Throwable ignored) {
            // A probe callback must never disturb the native parameter operation.
        }
    }

    private static Float finiteValue(final float value) {
        return Float.isFinite(value) ? value : null;
    }

    private static Float finiteValue(final Float value) {
        return value == null || !Float.isFinite(value) ? null : value;
    }

    private static String boundedText(final String value, final int limit) {
        return value == null || value.length() > limit ? "" : value;
    }

    private static Float readFiniteParameterValue(final Parameter parameter) {
        if (parameter == null) return null;
        try {
            return finiteValue(parameter.getValue());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void run() {
        artifact = context.paths().dataDir().resolve("history-native-ui-ingress.jsonl");
        boolean terminalSummaryWritten = false;
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
            String knownSignificant = significantSequence(baseline);
            long knownPosition = position(baseline);
            boolean hookFired = false;
            boolean observerFired = false;
            boolean labelSeen = false;

            for (int index = 0; ready && index < STEPS.size(); index++) {
                final Step step = STEPS.get(index);
                final int start = cursor();
                final long parameterLifecycleStart = parameterLifecycleCursor();
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

                final WindowsHistoryManagerValidationProbe.Snapshot semanticBaseline =
                    "parts-tree-drag".equals(step.id()) ? sample() : null;
                if ("parts-tree-drag".equals(step.id())) {
                    write(artifact, paired(semanticBaseline, step.id() + "-semantic-baseline"), false);
                }

                final ParameterStateSnapshot parameterBefore =
                    "native-parameter".equals(step.id()) ? readParameterSnapshot() : null;
                if (parameterBefore != null) {
                    write(
                        artifact,
                        parameterStateJson("native-parameter", "before", parameterBefore),
                        false
                    );
                }

                ParameterChangeObservation actorParameterTermination = null;
                if (AUTOMATE) {
                    final String actor = act(
                        step, knownSignificant, knownPosition, parameterBefore);
                    actorParameterTermination = parameterActorTermination;
                    write(
                        artifact,
                        "{\"type\":\"actor\",\"phase\":\"" + json(step.id())
                            // The discovery detail legitimately exceeds a label's bound; clipping
                            // it hid why the r9 Parts-tree selection never resolved.
                        + "\",\"result\":\"" + json(actor, 8192) + "\"}\n",
                        false
                    );
                    if ("parts-tree-drag".equals(step.id())) {
                        for (final String evidence : partGestureEvidenceSnapshot()) {
                            write(artifact, evidence, false);
                        }
                        write(artifact, partActorResult.json(step.id()), false);
                        if (!partActorResult.accepted()) {
                            failures.add(step.id() + ":actor-" + partActorResult.code());
                        }
                    }
                    // The map is captured when an actor actually ran — by then the document UI is
                    // populated, and the dump shows the controls the actor just used or missed.
                    if (!"none".equals(actor)) {
                        write(artifact, "{\"type\":\"ui-map\",\"phase\":\"" + json(step.id())
                            + "\",\"at\":\"" + Instant.now()
                            + "\",\"map\":\"" + json(uiMap(), UI_MAP_MAX_CHARS) + "\"}\n", false);
                    }
                }

                if ("native-parameter".equals(step.id())) {
                    final ParameterChangeObservation parameterObservation =
                        awaitParameterChange(parameterBefore, actorParameterTermination);
                    final WindowsHistoryManagerValidationProbe.Snapshot after = sample();
                    final ParameterStateSnapshot parameterAfter = parameterObservation.after();
                    final ParameterStateOutcome valueStatus = parameterObservation.outcome();
                    final ParameterHistoryAdmission historyAdmission =
                        after == null
                            ? ParameterHistoryAdmission.UNAVAILABLE
                            : parameterHistoryAdmission(
                                knownSignificant, significantSequence(after));
                    final Set<String> changedParameterIds = changedParameterIds(
                        parameterBefore, parameterAfter);
                    final List<ParameterLifecycleEvent> lifecycleEvents =
                        parameterLifecycleSince(parameterLifecycleStart);
                    final boolean lifecycleWindowComplete =
                        parameterLifecycleWindowComplete(parameterLifecycleStart);
                    final ParameterLifecycleAssessment lifecycleAssessment =
                        assessParameterLifecycle(
                            lifecycleEvents,
                            parameterBefore,
                            parameterAfter,
                            changedParameterIds,
                            valueStatus == ParameterStateOutcome.CHANGED,
                            lifecycleWindowComplete
                        );

                    write(
                        artifact,
                        parameterStateJson(
                            "native-parameter", "after",
                            parameterAfter == null
                                ? ParameterStateSnapshot.unavailable("no-readback")
                                : parameterAfter
                        ),
                        false
                    );
                    write(
                        artifact,
                        parameterEvidenceJson(
                            "native-parameter",
                            parameterBefore,
                            parameterAfter,
                            valueStatus,
                            historyAdmission,
                            lifecycleAssessment,
                            changedParameterIds,
                            lifecycleEvents
                        ),
                        false
                    );
                    write(artifact, paired(after, step.id()), false);

                    final List<Observed> events;
                    synchronized (lock) {
                        events = List.copyOf(
                            observed.subList(Math.min(start, observed.size()), observed.size()));
                    }
                    for (Observed event : events) {
                        write(artifact, event.json(step.id()), false);
                    }

                    final String parameterDetail = "valueStatus=" + valueStatus.code()
                        + ",historyAdmission=" + historyAdmission.code()
                        + ",lifecycle=" + lifecycleAssessment.status().code()
                        + ",modelCorrelation=" + lifecycleAssessment.modelCorrelation().code();
                    final Verdict valueVerdict = new Verdict(
                        valueStatus == ParameterStateOutcome.CHANGED,
                        "parameter-value-" + valueStatus.code(),
                        parameterDetail
                    );
                    write(artifact, valueVerdict.json(step.id()), false);
                    if (!valueVerdict.ok()) {
                        failures.add(step.id() + ":" + valueVerdict.code());
                    }
                    final Verdict lifecycleVerdict = new Verdict(
                        lifecycleAssessment.status() == ParameterLifecycleStatus.COMPLETE,
                        "parameter-lifecycle-" + lifecycleAssessment.status().code(),
                        parameterDetail
                    );
                    write(artifact, lifecycleVerdict.json(step.id()), false);
                    if (!lifecycleVerdict.ok()) {
                        failures.add(step.id() + ":" + lifecycleVerdict.code());
                    }
                    hookFired |= events.stream().anyMatch(event -> event.phase().equals("before"));
                    observerFired |= events.stream().anyMatch(
                        event -> event.phase().equals("on") || event.phase().equals("after")
                    );
                    labelSeen |= events.stream().anyMatch(
                        event -> event.phase().equals("before") && !event.label().isBlank()
                    );
                    if (after != null) {
                        knownSignificant = significantSequence(after);
                        knownPosition = position(after);
                    }
                    continue;
                }

                final WindowsHistoryManagerValidationProbe.Snapshot after =
                    awaitChange(step, knownSignificant, knownPosition);
                if (after == null) {
                    failures.add(step.id() + ":no-native-change");
                    write(
                        artifact,
                        new Verdict(false, "no-native-change", "unchanged after " + STEP_TIMEOUT_MILLIS + "ms")
                            .json(step.id()),
                        false
                    );
                    if ("parts-tree-drag".equals(step.id())) {
                        final Verdict semanticVerdict = checkPartMembershipStep(
                            sdkHistory(semanticBaseline),
                            null
                        );
                        write(
                            artifact,
                            semanticVerdict.json("parts-tree-drag-semantic"),
                            false
                        );
                        if (!semanticVerdict.ok()) {
                            failures.add(step.id() + ":semantic-" + semanticVerdict.code());
                        }
                        final Verdict partStepVerdict = admitPartStep(partActorResult, semanticVerdict);
                        write(artifact, partStepVerdict.json("parts-tree-drag-result"), false);
                        if (!partStepVerdict.ok() && semanticVerdict.ok()) {
                            failures.add(step.id() + ":" + partStepVerdict.code());
                        }
                    }
                    continue;
                }
                knownSignificant = significantSequence(after);
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
                if ("parts-tree-drag".equals(step.id())) {
                    final Verdict semanticVerdict = checkPartMembershipStep(
                        sdkHistory(semanticBaseline),
                        sdkHistory(after)
                    );
                    write(
                        artifact,
                        semanticVerdict.json("parts-tree-drag-semantic"),
                        false
                    );
                    if (!semanticVerdict.ok()) {
                        failures.add(step.id() + ":semantic-" + semanticVerdict.code());
                    }
                    final Verdict partStepVerdict = admitPartStep(partActorResult, semanticVerdict);
                    write(artifact, partStepVerdict.json("parts-tree-drag-result"), false);
                    if (!partStepVerdict.ok() && semanticVerdict.ok()) {
                        failures.add(step.id() + ":" + partStepVerdict.code());
                    }
                }
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
            terminalSummaryWritten = true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            // The Editor was closed while a step was pending. The runner matches a whole line, so
            // returning silently would leave it with nothing to match and no evidence at all.
            try {
                write(
                    artifact,
                    "{\"type\":\"error\",\"class\":\"interrupted\",\"message\":\""
                        + "the probe was disabled while a step was pending\"}\n"
                        + "{\"type\":\"failures\",\"failures\":[\"probe-disabled-mid-run\"]}\n"
                        + "{\"type\":\"summary\",\"status\":\"FAIL\"}\n",
                    true
                );
                terminalSummaryWritten = true;
            } catch (Exception ignored) {
                context.logger().error("Native UI ingress evidence could not be written", ignored);
            }
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
                terminalSummaryWritten = true;
            } catch (Exception ignored) {
                context.logger().error("Native UI ingress evidence could not be written", ignored);
            }
        } finally {
            if (AUTOMATE && terminalSummaryWritten) {
                try {
                    final WindowsHistoryNativeUiHostClose.CloseResult close =
                        WindowsHistoryNativeUiHostClose.closeIfEligible(
                            AUTOMATE,
                            running,
                            terminalSummaryWritten,
                            System.getProperty(WindowsHistoryNativeUiHostClose.RUN_ID_PROPERTY),
                            System.getProperty(WindowsHistoryNativeUiHostClose.HOST_VERSION_PROPERTY)
                        );
                    context.logger().info(
                        "Native UI ingress automated host close status=" + close.status()
                            + " reason=" + close.reason()
                    );
                } catch (Exception closeFailure) {
                    // The summary was already persisted. Keep the failure visible so the Runner's
                    // normal-exit gate, rather than this probe, decides whether the task passed.
                    context.logger().error(
                        "Native UI ingress automated host close failed; normal exit remains unverified",
                        closeFailure
                    );
                }
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
        final Step step,
        final String knownSignificant,
        final long knownPosition
    ) throws Exception {
        final long deadline = System.currentTimeMillis() + STEP_TIMEOUT_MILLIS;
        long nextReminder = System.currentTimeMillis() + REMINDER_MILLIS;
        // Direction is measured against the last position actually observed, not against the
        // window's opening position. An edit made inside the window raises the position, so a
        // later undo returns to the opening value rather than below it, and a window baseline
        // would never see the step's own move.
        long lastPosition = knownPosition;
        while (System.currentTimeMillis() < deadline) {
            if (!running) throw new InterruptedException("Probe disabled while awaiting the operator");
            Thread.sleep(POLL_MILLIS);
            final WindowsHistoryManagerValidationProbe.Snapshot current = sample();
            if (current == null) continue;
            if (hasMoved(
                step,
                significantSequence(current),
                position(current),
                knownSignificant,
                lastPosition
            )) {
                Thread.sleep(settleMillis(step));
                return sample();
            }
            lastPosition = position(current);
            if (System.currentTimeMillis() >= nextReminder) {
                nextReminder = System.currentTimeMillis() + REMINDER_MILLIS;
                remind(step);
            }
        }
        return null;
    }

    /**
     * Re-announces a step that is still pending, so an operator who arrives late still sees it.
     *
     * <p>Logged rather than published as a new prompt: the step window has not moved, and a second
     * prompt line would read as a second step.</p>
     */
    private void remind(final Step step) {
        try {
            context.logger().info("STILL WAITING [" + step.id() + "] " + step.instruction());
            write(
                artifact,
                "{\"type\":\"reminder\",\"phase\":\"" + json(step.id())
                    + "\",\"at\":\"" + Instant.now() + "\"}\n",
                false
            );
        } catch (Exception ignored) {
            // A reminder is a courtesy; losing one must not fail the step.
        }
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

    /**
     * Runs the bounded native action registered for this step, if any.
     *
     * <p>The return value is evidence, not control flow: it is written to the artifact verbatim
     * and the step still closes only on the native change {@link #awaitChange} observes. An actor
     * that cannot resolve its UI target, or whose action the host rejects, simply produces no
     * significant entry and the operator window runs its course.</p>
     */
    String act(final Step step, final String knownSignificant) {
        return act(step, knownSignificant, -1L, null);
    }

    String act(
        final Step step,
        final String knownSignificant,
        final long knownPosition
    ) {
        return act(step, knownSignificant, knownPosition, null);
    }

    private String act(
        final Step step,
        final String knownSignificant,
        final long knownPosition,
        final ParameterStateSnapshot parameterBefore
    ) {
        if ("native-parameter".equals(step.id())) {
            parameterActorTermination = null;
        }
        if ("parts-tree-drag".equals(step.id())) {
            partActorResult = PartActorResult.notRun();
            synchronized (lock) {
                partGestureEvidence.clear();
            }
        }
        final Thread raiser = hostWindowRaiser();
        try {
            return switch (step.id()) {
                case "parts-tree-drag" -> {
                    final String result = dragPartsTree(knownSignificant);
                    partActorResult = PartActorResult.fromActorResult(result);
                    yield result;
                }
                case "deformer-assign" -> dragPartRow(knownSignificant);
                case "canvas-move", "canvas-deform" -> dragCanvas(knownSignificant);
                case "native-parameter" -> dragParameterSlider(parameterBefore);
                case "native-color" -> editColorField(knownSignificant);
                case "native-undo" -> shortcut(
                    java.awt.event.KeyEvent.VK_Z, knownPosition, step.kind());
                case "native-redo" -> shortcut(
                    java.awt.event.KeyEvent.VK_Y, knownPosition, step.kind());
                default -> "none";
            };
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if ("parts-tree-drag".equals(step.id())) {
                partActorResult = PartActorResult.exception("interrupted", interrupted.toString());
            }
            return "interrupted";
        } catch (Exception failure) {
            final String message = failure.getMessage();
            if ("parts-tree-drag".equals(step.id())) {
                partActorResult = PartActorResult.exception(
                    failure.getClass().getSimpleName(),
                    message == null ? failure.toString() : message
                );
            }
            return "failed:" + failure.getClass().getSimpleName()
                + (message == null ? "" : ":" + message);
        } finally {
            raiser.interrupt();
        }
    }

    /**
     * Starts a daemon that keeps the host frame in front for the actor's whole run: the
     * launcher's own {@code cmd.exe} console shares the display at the same geometry and
     * can re-cover the editor mid-step, sending real Robot input to the console instead.
     */
    private Thread hostWindowRaiser() {
        final Thread thread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        raiseHostWindow();
                    } catch (InterruptedException interrupted) {
                        throw interrupted;
                    } catch (Throwable ignored) {
                        // A missed raise only costs the next interval.
                    }
                    Thread.sleep(2000L);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "native-ui-window-raiser");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * Raises the host frame before a Robot gesture: the launcher's own {@code cmd.exe}
     * console window shares the display at the same geometry and can sit on top of the
     * editor, in which case real input lands on the console instead of the canvas.
     */
    private void raiseHostWindow() throws Exception {
        onEdt(() -> {
            java.awt.Window best = null;
            for (final java.awt.Window window : java.awt.Window.getWindows()) {
                if (window instanceof java.awt.Frame && window.isVisible()
                    && (best == null
                        || (long) window.getWidth() * window.getHeight()
                            > (long) best.getWidth() * best.getHeight())) {
                    best = window;
                }
            }
            if (best != null) {
                best.toFront();
                best.requestFocus();
            }
            return null;
        });
        Thread.sleep(POLL_MILLIS);
    }

    /**
     * Drives only the Part-membership actor. Deformer assignment intentionally keeps the older
     * row actor below: the two gestures share a palette, but they do not share an admission rule.
     */
    private String dragPartsTree(final String knownSignificant) throws Exception {
        final PartModelSnapshot initial;
        try {
            initial = onEdt(this::readPartModel);
        } catch (Exception unavailable) {
            return "unresolved:part-model-unavailable:" + unavailable.getClass().getSimpleName();
        }
        final List<PartPair> pairs = safePartPairs(initial);
        if (pairs.isEmpty()) return "unresolved:no-safe-part-pair";

        String lastSignificant = knownSignificant == null ? "" : knownSignificant;
        final List<String> attempts = new ArrayList<>();
        int pairCount = 0;
        boolean sawVisiblePair = false;
        for (final PartPair pair : pairs) {
            if (pairCount++ >= MAX_PART_GESTURE_PAIRS) break;
            final PartGesturePreparation preparation = onEdt(
                () -> preparePartGesture(pair));
            if (!preparation.ready()) {
                if ("no-safe-visible-pair".equals(preparation.reason())) continue;
                return "unresolved:" + preparation.reason() + ":sourceId="
                    + pair.source().id() + ":targetId=" + pair.target().id();
            }
            sawVisiblePair = true;
            if (!Objects.equals(lastSignificant, preparation.significantSequence())) {
                return "mismatch:significant-changed-before-gesture:"
                    + partEvidence(pair, pair.source().parentId(),
                        pair.source().parentId());
            }

            final java.awt.Point sourcePoint = preparation.layout().source().screenPoint();
            final java.awt.Point targetPoint = partGestureTargetPoint(preparation.layout());
            if (sourcePoint == null || targetPoint == null) {
                return "unresolved:part-screen-point-unavailable:"
                    + partEvidence(pair, pair.source().parentId(),
                        pair.source().parentId());
            }
            final PartGestureAttempt gesture = runPartGesture(
                preparation.layout(),
                pair.source().id(),
                pair.target().id(),
                robotPartGestureInput(),
                WindowsHistoryNativeUiIngressProbe::readMouseInfoPointer,
                () -> currentPartPrePress(pair, preparation.layout())
            );
            appendPartGestureEvidence(gesture.evidence());
            if (gesture.status() != PartGestureStatus.ACCEPTED) {
                final String prefix = gesture.status() == PartGestureStatus.MISMATCH
                    ? "mismatch:"
                    : gesture.status() == PartGestureStatus.EXCEPTION
                        ? "failed:"
                        : "unresolved:";
                return prefix + gesture.code() + ":sourceId=" + pair.source().id()
                    + ":targetId=" + pair.target().id();
            }

            final PartDragSettlement settlement = settlePartDrag(
                PART_GESTURE_SETTLE_POLLS,
                poll -> {
                    Thread.sleep(POLL_MILLIS);
                    final WindowsHistoryManagerValidationProbe.Snapshot history = sample();
                    final String afterSignificant = history == null
                        ? null : significantSequence(history);
                    final PartModelSnapshot after;
                    try {
                        after = onEdt(this::readPartModel);
                    } catch (Exception unavailable) {
                        return new PartDragCheck(
                            PartDragStatus.UNAVAILABLE,
                            "part-readback-unavailable",
                            pair.source().id(),
                            pair.target().id(),
                            pair.source().parentId(),
                            Optional.empty()
                        );
                    }
                    return assessPartDrag(
                        pair,
                        preparation.model(),
                        after,
                        preparation.significantSequence(),
                        afterSignificant
                    );
                }
            );
            final PartDragCheck lastCheck = settlement.lastCheck();
            if (lastCheck != null) attempts.add(lastCheck.evidence());
            if (settlement.status() == PartDragSettlementStatus.CHANGED) {
                return "changed:" + lastCheck.evidence() + ":attempts=" + attempts;
            }
            if (settlement.status() == PartDragSettlementStatus.MISMATCH) {
                return "mismatch:" + lastCheck.evidence() + ":attempts=" + attempts;
            }
            if (settlement.status() == PartDragSettlementStatus.UNAVAILABLE) {
                return "unresolved:" + lastCheck.code() + ":attempts=" + attempts;
            }
            lastSignificant = preparation.significantSequence();
        }
        if (!sawVisiblePair) return "unresolved:no-safe-visible-part-pair";
        return "unresolved:no-membership-change:attempts=" + attempts + ":dnd="
            + onEdt(WindowsHistoryNativeUiIngressProbe::partsTreeDnD);
    }

    private PartGesturePreparation preparePartGesture(final PartPair pair) {
        final PartModelSnapshot current = readPartModel();
        if (!partPairMatches(current, pair)) {
            return PartGesturePreparation.unavailable(current, "part-model-changed");
        }
        final PartPairLayout layout = locatePartPair(pair.source().name(), pair.target().name());
        if (layout == null) {
            return PartGesturePreparation.unavailable(current, "no-safe-visible-pair");
        }
        final WindowsHistoryManagerValidationProbe.Snapshot history;
        try {
            history = WindowsHistoryManagerValidationProbe.sample(context);
        } catch (Exception unavailable) {
            return PartGesturePreparation.unavailable(current, "history-unavailable");
        }
        if (history == null) {
            return PartGesturePreparation.unavailable(current, "history-unavailable");
        }
        return new PartGesturePreparation(
            current,
            layout,
            significantSequence(history),
            ""
        );
    }

    /** Reads the SDK Part graph and the current Parts surface in the final EDT guard. */
    private PartPrePressCheck currentPartPrePress(
        final PartPair pair,
        final PartPairLayout expected
    ) {
        final PartModelSnapshot current;
        try {
            current = readPartModel();
        } catch (Exception unavailable) {
            try {
                final PartPrePressCheck surface = currentPartPrePressSurface(expected);
                return PartPrePressCheck.failure(
                    "part-model-unavailable", expected,
                    surface.currentTable(), surface.currentTree()
                );
            } catch (Exception surfaceUnavailable) {
                return PartPrePressCheck.failure(
                    "part-model-unavailable", expected, null, null
                );
            }
        }
        final PartPrePressCheck surface = currentPartPrePressSurface(expected);
        return verifyPartPrePress(
            expected,
            surface.currentTable(),
            surface.currentTree(),
            pair,
            current
        );
    }

    /** Finds the currently discovered Parts surface without substituting for SDK readback. */
    private static PartPrePressCheck currentPartPrePressSurface(final PartPairLayout expected) {
        JTable firstTable = null;
        JTree firstTree = null;
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (!window.isVisible()) continue;
            final JTable table = findPartsTable(window);
            if (table == null) continue;
            final JTree tree = WindowsMeshEditValidationProbe.extractTree(table);
            if (firstTable == null) {
                firstTable = table;
                firstTree = tree;
            }
            if (table == expected.table()) {
                return verifyPartPrePress(expected, table, tree);
            }
        }
        return verifyPartPrePress(expected, firstTable, firstTree);
    }

    /**
     * Rechecks the object graph and both points after the Robot move/settle window.
     *
     * <p>The comparisons deliberately use object identity for Swing components, models and tree
     * nodes. Display names are retained as bounded evidence and lookup hints only; they do not
     * establish that a press is on the intended SDK Part.</p>
     */
    static PartPrePressCheck verifyPartPrePress(
        final PartPairLayout expected,
        final JTable currentTable,
        final JTree currentTree
    ) {
        if (expected == null || expected.table() == null || expected.tree() == null
            || expected.tableModel() == null || expected.treeModel() == null) {
            return PartPrePressCheck.failure(
                "layout-identity-unavailable", expected, currentTable, currentTree
            );
        }
        if (currentTable == null || currentTree == null) {
            return PartPrePressCheck.failure(
                "current-parts-surface-unavailable", expected, currentTable, currentTree
            );
        }
        if (currentTable != expected.table()) {
            return PartPrePressCheck.failure(
                "table-replaced", expected, currentTable, currentTree
            );
        }
        if (currentTree != expected.tree()) {
            return PartPrePressCheck.failure(
                "tree-replaced", expected, currentTable, currentTree
            );
        }
        if (currentTable.getModel() != expected.tableModel()) {
            return PartPrePressCheck.failure(
                "table-model-replaced", expected, currentTable, currentTree
            );
        }
        if (currentTree.getModel() != expected.treeModel()) {
            return PartPrePressCheck.failure(
                "tree-model-replaced", expected, currentTable, currentTree
            );
        }
        if (currentTable.isShowing() != expected.tableShowing()) {
            return PartPrePressCheck.failure(
                "table-showing-changed", expected, currentTable, currentTree
            );
        }
        final String sourceFailure = verifyPartRowAtPress(
            expected.source(), currentTable, currentTree
        );
        if (sourceFailure != null) {
            return PartPrePressCheck.failure(
                "source-" + sourceFailure, expected, currentTable, currentTree
            );
        }
        final String targetFailure = verifyPartRowAtPress(
            expected.target(), currentTable, currentTree
        );
        if (targetFailure != null) {
            return PartPrePressCheck.failure(
                "target-" + targetFailure, expected, currentTable, currentTree
            );
        }
        return PartPrePressCheck.success(expected, currentTable, currentTree);
    }

    /** Testable readback seam used by the real gesture runner's EDT guard. */
    static PartPrePressCheck verifyPartPrePress(
        final PartPairLayout expected,
        final JTable currentTable,
        final JTree currentTree,
        final PartPair pair,
        final PartModelSnapshot currentModel
    ) {
        if (currentModel == null) {
            return PartPrePressCheck.failure(
                "part-model-unavailable", expected, currentTable, currentTree
            );
        }
        if (pair == null || !partPairMatches(currentModel, pair)) {
            return PartPrePressCheck.failure(
                "part-model-changed", expected, currentTable, currentTree
            );
        }
        return verifyPartPrePress(expected, currentTable, currentTree);
    }

    private static String verifyPartRowAtPress(
        final PartRowLocation expected,
        final JTable table,
        final JTree tree
    ) {
        if (expected == null || expected.node() == null) return "node-unavailable";
        final int row = expected.row();
        if (tree.getRowForPath(expected.path()) != row) return "row-changed";
        final TreePath currentPath = tree.getPathForRow(row);
        if (!samePathNodeIdentity(expected.path(), currentPath)) return "path-changed";
        if (currentPath == null || currentPath.getLastPathComponent() != expected.node()) {
            return "node-replaced";
        }
        if (!Objects.equals(expected.label(), treeLabel(tree, expected.node()))) {
            return "label-changed";
        }
        if (row < 0 || row >= table.getRowCount()) return "table-row-unavailable";
        final Rectangle cell = table.getCellRect(row, expected.column(), true);
        final Rectangle viewport = table.getVisibleRect();
        if (!cell.equals(expected.cell())) return "cell-changed";
        if (!viewport.equals(expected.viewport())) return "viewport-changed";
        if (table.rowAtPoint(expected.localPoint()) != row) return "row-at-point-changed";
        if (table.columnAtPoint(expected.localPoint()) != expected.column()) {
            return "column-at-point-changed";
        }
        if (!viewport.contains(cell.x, cell.y)
            || !viewport.contains(cell.x + cell.width - 1, cell.y + cell.height - 1)) {
            return "cell-not-visible";
        }
        if (expected.screenPoint() != null) {
            try {
                final Point currentScreen = new Point(expected.localPoint());
                SwingUtilities.convertPointToScreen(currentScreen, table);
                if (!currentScreen.equals(expected.screenPoint())) return "screen-point-changed";
                final Point fromScreen = new Point(expected.screenPoint());
                SwingUtilities.convertPointFromScreen(fromScreen, table);
                if (!fromScreen.equals(expected.localPoint())) return "screen-hit-changed";
                final int screenRow = table.rowAtPoint(fromScreen);
                final int screenColumn = table.columnAtPoint(fromScreen);
                final TreePath screenPath = tree.getPathForRow(screenRow);
                if (screenRow != row || screenColumn != expected.column()
                    || !samePathNodeIdentity(expected.path(), screenPath)) {
                    return "screen-row-path-changed";
                }
            } catch (java.awt.IllegalComponentStateException notShowing) {
                return "screen-point-unavailable";
            }
        }
        return null;
    }

    private static boolean samePathNodeIdentity(
        final TreePath expected,
        final TreePath actual
    ) {
        if (expected == null || actual == null || expected.getPathCount() != actual.getPathCount()) {
            return false;
        }
        for (int index = 0; index < expected.getPathCount(); index++) {
            if (expected.getPathComponent(index) != actual.getPathComponent(index)) return false;
        }
        return true;
    }

    /** Creates the real-input endpoint used only by the Parts gesture. */
    private static PartGestureInput robotPartGestureInput() throws Exception {
        final java.awt.Robot robot = new java.awt.Robot();
        return new PartGestureInput() {
            @Override
            public void mouseMove(final int x, final int y) {
                robot.mouseMove(x, y);
            }

            @Override
            public void pause(final long millis) throws InterruptedException {
                Thread.sleep(millis);
            }

            @Override
            public void mousePress() {
                robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            }

            @Override
            public void mouseRelease() {
                robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            }
        };
    }

    /** Java-level pointer observation; it is evidence, not an independent OS truth source. */
    private static Point readMouseInfoPointer() {
        final PointerInfo pointerInfo = MouseInfo.getPointerInfo();
        if (pointerInfo == null) return null;
        final Point location = pointerInfo.getLocation();
        return location == null ? null : new Point(location);
    }

    private static PartPointerEvidence readPointer(
        final int attempt,
        final String phase,
        final Point commandedPoint,
        final PartPointerReadback pointerReadback
    ) {
        final long monotonicNanos = System.nanoTime();
        final String thread = Thread.currentThread().getName();
        try {
            final Point readbackPoint = pointerReadback.read();
            if (readbackPoint == null) {
                return new PartPointerEvidence(
                    phase, attempt, monotonicNanos, thread, commandedPoint, null,
                    "UNAVAILABLE", "null-pointer-info"
                );
            }
            final Point copy = new Point(readbackPoint);
            final boolean matches = commandedPoint != null && commandedPoint.equals(copy);
            return new PartPointerEvidence(
                phase, attempt, monotonicNanos, thread, commandedPoint, copy,
                matches ? "MATCH" : "MISMATCH",
                matches ? "" : "commanded-point-diff"
            );
        } catch (Exception failure) {
            return new PartPointerEvidence(
                phase, attempt, monotonicNanos, thread, commandedPoint, null,
                "UNAVAILABLE", "exception:" + failure.getClass().getSimpleName()
            );
        }
    }

    static PartGestureAttempt runPartGesture(
        final PartPairLayout layout,
        final String intendedSourceId,
        final String intendedTargetId,
        final PartGestureInput input,
        final PartPrePressGuard guard
    ) throws Exception {
        Objects.requireNonNull(layout, "layout");
        return runPartGesture(
            layout,
            intendedSourceId,
            intendedTargetId,
            input,
            () -> layout.source().screenPoint(),
            guard
        );
    }

    /**
     * Runs one bounded Parts gesture. The input is injectable so cleanup and the pre-press guard
     * can be tested with real Swing components without constructing a headless {@link
     * java.awt.Robot}.
     */
    static PartGestureAttempt runPartGesture(
        final PartPairLayout layout,
        final String intendedSourceId,
        final String intendedTargetId,
        final PartGestureInput input,
        final PartPointerReadback pointerReadback,
        final PartPrePressGuard guard
    ) throws Exception {
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(pointerReadback, "pointerReadback");
        Objects.requireNonNull(guard, "guard");
        final PartGestureCapture capture = new PartGestureCapture(
            layout, intendedSourceId, intendedTargetId
        );
        final AtomicBoolean lifecycleOpen = new AtomicBoolean(true);
        boolean listenersInstalled = false;
        boolean pressed = false;
        PartPrePressCheck prePress = null;
        try {
            listenersInstalled = onEdt(
                () -> capture.installIfOpen(lifecycleOpen)
            );
            if (!listenersInstalled) {
                throw new IllegalStateException("gesture-lifecycle-closed-before-install");
            }
            final Point source = layout.source().screenPoint();
            final Point target = layout.target().screenPoint();
            if (source == null || target == null) {
                final PartPrePressCheck unavailable = PartPrePressCheck.failure(
                    "screen-point-unavailable", layout, layout.table(), layout.tree()
                );
                onEdt(() -> {
                    capture.recordPrePress(unavailable);
                    return null;
                });
                return new PartGestureAttempt(
                    PartGestureStatus.UNRESOLVED,
                    unavailable.code(),
                    unavailable,
                    capture.evidence()
                );
            }
            int matchedAttempt = 0;
            for (int attempt = 1; attempt <= MAX_PART_POINTER_ATTEMPTS; attempt++) {
                capture.beginPointerAttempt(attempt);
                capture.recordPointer(PartPointerEvidence.command(attempt, source));
                input.mouseMove(source.x, source.y);
                input.pause(120L);
                final PartPointerEvidence afterMove = readPointer(
                    attempt, "after-move-pointer", source, pointerReadback
                );
                capture.recordPointer(afterMove);
                if ("MATCH".equals(afterMove.outcome())) {
                    matchedAttempt = attempt;
                    break;
                }
                if ("UNAVAILABLE".equals(afterMove.outcome())) {
                    return new PartGestureAttempt(
                        PartGestureStatus.UNRESOLVED,
                        afterMove.failureCode(),
                        prePress,
                        capture.evidence()
                    );
                }
                if (attempt == MAX_PART_POINTER_ATTEMPTS) {
                    return new PartGestureAttempt(
                        PartGestureStatus.UNRESOLVED,
                        afterMove.failureCode(),
                        prePress,
                        capture.evidence()
                    );
                }
                // A retry is admitted only by the same SDK and Swing guard used before press.
                prePress = onEdt(() -> {
                    final PartPrePressCheck checked = guard.check();
                    capture.recordPrePress(checked);
                    return checked;
                });
                if (prePress == null || !prePress.ok()) {
                    return new PartGestureAttempt(
                        PartGestureStatus.UNRESOLVED,
                        prePress == null ? "pre-press-guard-unavailable" : prePress.code(),
                        prePress,
                        capture.evidence()
                    );
                }
            }
            if (matchedAttempt == 0) {
                return new PartGestureAttempt(
                    PartGestureStatus.UNRESOLVED,
                    "after-move-pointer-mismatch",
                    prePress,
                    capture.evidence()
                );
            }
            prePress = onEdt(() -> {
                final PartPrePressCheck checked = guard.check();
                capture.recordPrePress(checked);
                return checked;
            });
            if (prePress == null || !prePress.ok()) {
                return new PartGestureAttempt(
                    PartGestureStatus.UNRESOLVED,
                    prePress == null ? "pre-press-guard-unavailable" : prePress.code(),
                    prePress,
                    capture.evidence()
                );
            }

            final PartPointerEvidence beforePress = readPointer(
                matchedAttempt, "before-press-pointer", source, pointerReadback
            );
            capture.recordPointer(beforePress);
            if (beforePress.failureCode() != null) {
                return new PartGestureAttempt(
                    PartGestureStatus.UNRESOLVED,
                    beforePress.failureCode(),
                    prePress,
                    capture.evidence()
                );
            }

            // Set the flag before calling the injected endpoint: a press that throws may still
            // have reached the native input device, so finally must attempt the release.
            pressed = true;
            input.mousePress();
            input.pause(180L);
            final int segments = 12;
            for (int segment = 1; segment <= segments; segment++) {
                input.mouseMove(
                    source.x + (target.x - source.x) * segment / segments,
                    source.y + (target.y - source.y) * segment / segments
                );
                input.pause(35L);
            }
            input.pause(180L);
            input.mouseRelease();
            pressed = false;
            // Flush queued Swing callbacks and read the bounded event set on the EDT.
            final PartGestureCheck checked = onEdt(capture::checkEvents);
            return new PartGestureAttempt(
                checked.status(),
                checked.code(),
                prePress,
                capture.evidence()
            );
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new PartGestureAttempt(
                PartGestureStatus.EXCEPTION,
                "interrupted",
                prePress,
                capture.evidence()
            );
        } catch (Exception failure) {
            if (prePress == null) {
                final PartPrePressCheck guardFailure = PartPrePressCheck.failure(
                    "guard-failed:" + failure.getClass().getSimpleName(), layout, null, null
                );
                prePress = guardFailure;
                try {
                    onEdt(() -> {
                        capture.recordPrePress(guardFailure);
                        return null;
                    });
                } catch (Exception ignored) {
                    // The input failure remains the actor outcome; layout evidence is retained.
                }
            }
            final String failureCode = (listenersInstalled ? "input-failed:" : "listener-install-failed:")
                + failure.getClass().getSimpleName();
            return new PartGestureAttempt(
                PartGestureStatus.EXCEPTION,
                failureCode,
                prePress,
                capture.evidence()
            );
        } finally {
            // Close admission before any cleanup work. If an invokeLater callback timed out and
            // is still queued, its installIfOpen check must observe the closed gesture.
            lifecycleOpen.set(false);
            if (pressed) {
                try {
                    input.mouseRelease();
                } catch (Exception ignored) {
                    // The original input failure remains the actor outcome; cleanup is best effort.
                }
            }
            try {
                onEdt(() -> {
                    capture.remove();
                    return null;
                });
            } catch (Exception ignored) {
                // The actor outcome already records the gesture failure.
            }
        }
    }

    private void appendPartGestureEvidence(final List<String> evidence) {
        if (evidence == null || evidence.isEmpty()) return;
        synchronized (lock) {
            for (final String line : evidence) {
                if (partGestureEvidence.size() >= MAX_PART_GESTURE_EVIDENCE_LINES) break;
                partGestureEvidence.add(line);
            }
        }
    }

    private List<String> partGestureEvidenceSnapshot() {
        synchronized (lock) {
            return List.copyOf(partGestureEvidence);
        }
    }

    private PartModelSnapshot readPartModel() {
        final CubismModel model = context.cubism().model().active();
        if (model == null || model.id() == null || model.id().value().isBlank()) {
            throw new IllegalStateException("active model identity is unavailable");
        }
        final List<Part> sdkParts = model.parts().all();
        if (sdkParts == null || sdkParts.isEmpty()) {
            throw new IllegalStateException("active model has no readable Parts");
        }
        final List<ActorPart> parts = new ArrayList<>();
        for (final Part part : sdkParts) {
            if (part == null || part.id() == null || part.id().value().isBlank()) {
                throw new IllegalStateException("Part identity is unavailable");
            }
            final Optional<String> parentId = part.parentId().map(value -> value.value());
            final List<String> childIds = part.childIds().stream()
                .map(value -> value.value())
                .toList();
            parts.add(new ActorPart(
                part.id().value(),
                part.name(),
                parentId,
                childIds
            ));
        }
        final PartModelSnapshot snapshot = new PartModelSnapshot(model.id().value(), parts);
        if (partIndex(snapshot) == null) {
            throw new IllegalStateException("Part relationship snapshot is incomplete");
        }
        return snapshot;
    }

    /** Returns only identity-safe source/target pairs; names are lookup hints, never identity. */
    static List<PartPair> safePartPairs(final PartModelSnapshot snapshot) {
        final Map<String, ActorPart> byId = partIndex(snapshot);
        if (byId == null) return List.of();
        final Map<String, Integer> nameCounts = new HashMap<>();
        for (final ActorPart part : byId.values()) {
            if (part.name() != null && !part.name().isBlank()) {
                nameCounts.merge(part.name(), 1, Integer::sum);
            }
        }
        final List<PartPair> pairs = new ArrayList<>();
        for (final ActorPart source : byId.values()) {
            if (!sourceEligible(source) || !uniqueName(source, nameCounts)) continue;
            for (final ActorPart target : byId.values()) {
                if (!targetEligible(target) || !uniqueName(target, nameCounts)
                    || source.id().equals(target.id())
                    || source.parentId().filter(target.id()::equals).isPresent()
                    || isDescendant(source.id(), target.id(), byId)) {
                    continue;
                }
                pairs.add(new PartPair(snapshot.modelId(), source, target));
            }
        }
        return List.copyOf(pairs);
    }

    static boolean partPairMatches(final PartModelSnapshot snapshot, final PartPair pair) {
        if (snapshot == null || pair == null || !Objects.equals(snapshot.modelId(), pair.modelId())) {
            return false;
        }
        final Map<String, ActorPart> byId = partIndex(snapshot);
        if (byId == null) return false;
        return samePartState(pair.source(), byId.get(pair.source().id()))
            && samePartState(pair.target(), byId.get(pair.target().id()));
    }

    private static boolean samePartState(final ActorPart expected, final ActorPart actual) {
        return expected != null && actual != null
            && expected.id().equals(actual.id())
            && Objects.equals(expected.parentId(), actual.parentId())
            && Objects.equals(expected.childIds(), actual.childIds());
    }

    /** Classifies only the selected source's read-only parent transition. */
    static PartDragCheck assessPartDrag(
        final PartPair pair,
        final PartModelSnapshot before,
        final PartModelSnapshot after,
        final String beforeSignificant,
        final String afterSignificant
    ) {
        final ActorPart beforeSource = partOf(before, pair == null ? null : pair.source().id());
        final ActorPart afterSource = partOf(after, pair == null ? null : pair.source().id());
        final Optional<String> parentBefore = parentOf(beforeSource);
        final Optional<String> parentAfter = parentOf(afterSource);
        final String sourceId = pair == null || pair.source() == null ? "" : pair.source().id();
        final String targetId = pair == null || pair.target() == null ? "" : pair.target().id();
        if (pair == null || before == null || after == null
            || !Objects.equals(before.modelId(), after.modelId())
            || !Objects.equals(pair.modelId(), after.modelId())) {
            return new PartDragCheck(
                PartDragStatus.MISMATCH, "model-changed", sourceId, targetId,
                parentBefore, parentAfter
            );
        }
        if (beforeSource == null || afterSource == null || partOf(after, targetId) == null) {
            return new PartDragCheck(
                PartDragStatus.UNAVAILABLE, "selected-part-unavailable", sourceId, targetId,
                parentBefore, parentAfter
            );
        }
        if (parentBefore.filter(targetId::equals).isPresent()) {
            return new PartDragCheck(
                PartDragStatus.MISMATCH, "same-parent", sourceId, targetId,
                parentBefore, parentAfter
            );
        }
        if (parentAfter.filter(targetId::equals).isPresent()
            && !Objects.equals(parentBefore, parentAfter)) {
            return new PartDragCheck(
                PartDragStatus.CHANGED, "selected-parent-changed", sourceId, targetId,
                parentBefore, parentAfter
            );
        }
        if (!Objects.equals(parentBefore, parentAfter)) {
            return new PartDragCheck(
                PartDragStatus.MISMATCH, "wrong-parent", sourceId, targetId,
                parentBefore, parentAfter
            );
        }
        if (beforeSignificant == null || afterSignificant == null) {
            return new PartDragCheck(
                PartDragStatus.UNAVAILABLE, "history-unavailable", sourceId, targetId,
                parentBefore, parentAfter
            );
        }
        if (!Objects.equals(beforeSignificant, afterSignificant)) {
            return new PartDragCheck(
                PartDragStatus.MISMATCH, "unselected-significant-change", sourceId, targetId,
                parentBefore, parentAfter
            );
        }
        return new PartDragCheck(
            PartDragStatus.NO_CHANGE, "no-selected-parent-change", sourceId, targetId,
            parentBefore, parentAfter
        );
    }

    /**
     * Bounded readback decision used by the Parts actor after one real gesture.
     *
     * <p>A no-change sample is intentionally not a retry signal: the host may commit the parent
     * transition later in the bounded window. Only a complete window of no-change samples permits
     * the caller to try another safe candidate. Unknown readback and mismatches terminate the
     * current actor so an unverified gesture is never sent again.</p>
     */
    static PartDragSettlement settlePartDrag(
        final int maxPolls,
        final PartDragReadback readback
    ) throws Exception {
        if (maxPolls <= 0) throw new IllegalArgumentException("maxPolls must be positive");
        Objects.requireNonNull(readback, "readback");
        PartDragCheck lastCheck = null;
        for (int poll = 0; poll < maxPolls; poll++) {
            lastCheck = readback.read(poll);
            if (lastCheck == null) {
                lastCheck = new PartDragCheck(
                    PartDragStatus.UNAVAILABLE,
                    "part-readback-unavailable",
                    "",
                    "",
                    Optional.empty(),
                    Optional.empty()
                );
            }
            if (lastCheck.status() == PartDragStatus.CHANGED) {
                return new PartDragSettlement(PartDragSettlementStatus.CHANGED, lastCheck);
            }
            if (lastCheck.status() == PartDragStatus.MISMATCH) {
                return new PartDragSettlement(PartDragSettlementStatus.MISMATCH, lastCheck);
            }
            if (lastCheck.status() == PartDragStatus.UNAVAILABLE) {
                return new PartDragSettlement(PartDragSettlementStatus.UNAVAILABLE, lastCheck);
            }
        }
        return new PartDragSettlement(PartDragSettlementStatus.RETRY, lastCheck);
    }

    private static String partEvidence(
        final PartPair pair,
        final Optional<String> parentBefore,
        final Optional<String> parentAfter
    ) {
        return "sourceId=" + pair.source().id()
            + ":targetId=" + pair.target().id()
            + ":parentBefore=" + parentText(parentBefore)
            + ":parentAfter=" + parentText(parentAfter);
    }

    private static String parentText(final Optional<String> parent) {
        return parent == null ? "<unavailable>" : parent.orElse("<none>");
    }

    private static ActorPart partOf(
        final PartModelSnapshot snapshot,
        final String id
    ) {
        if (snapshot == null || id == null) return null;
        final Map<String, ActorPart> byId = partIndex(snapshot);
        return byId == null ? null : byId.get(id);
    }

    private static Optional<String> parentOf(final ActorPart part) {
        return part == null ? Optional.empty() : part.parentId();
    }

    private static boolean sourceEligible(final ActorPart part) {
        return part != null
            && !INTERNAL_ROOT_PART_ID.equals(part.id())
            && part.parentId().isPresent();
    }

    private static boolean targetEligible(final ActorPart part) {
        return part != null && !INTERNAL_ROOT_PART_ID.equals(part.id())
            && part.parentId().isPresent();
    }

    private static boolean uniqueName(
        final ActorPart part,
        final Map<String, Integer> nameCounts
    ) {
        return part.name() != null && !part.name().isBlank()
            && nameCounts.getOrDefault(part.name(), 0) == 1;
    }

    private static boolean isDescendant(
        final String sourceId,
        final String candidateId,
        final Map<String, ActorPart> byId
    ) {
        final ArrayDeque<String> pending = new ArrayDeque<>();
        final Set<String> visited = new HashSet<>();
        pending.add(sourceId);
        while (!pending.isEmpty()) {
            final String current = pending.removeFirst();
            final ActorPart part = byId.get(current);
            if (part == null || !visited.add(current)) continue;
            for (final String childId : part.childIds()) {
                if (candidateId.equals(childId)) return true;
                pending.addLast(childId);
            }
        }
        return false;
    }

    /** Validates the complete read-only relation snapshot before any UI gesture is attempted. */
    private static Map<String, ActorPart> partIndex(final PartModelSnapshot snapshot) {
        if (snapshot == null || snapshot.modelId() == null || snapshot.modelId().isBlank()
            || snapshot.parts() == null || snapshot.parts().isEmpty()) {
            return null;
        }
        final Map<String, ActorPart> byId = new LinkedHashMap<>();
        for (final ActorPart part : snapshot.parts()) {
            if (part == null || part.id() == null || part.id().isBlank()
                || byId.put(part.id(), part) != null) {
                return null;
            }
            final Set<String> childIds = new HashSet<>();
            for (final String childId : part.childIds()) {
                if (childId == null || childId.isBlank() || !childIds.add(childId)) return null;
            }
        }
        for (final ActorPart part : byId.values()) {
            if (part.parentId().filter(parent -> !byId.containsKey(parent)).isPresent()) {
                return null;
            }
            for (final String childId : part.childIds()) {
                final ActorPart child = byId.get(childId);
                if (child == null || child.parentId().filter(part.id()::equals).isEmpty()) {
                    return null;
                }
            }
        }
        for (final ActorPart part : byId.values()) {
            final Set<String> visited = new HashSet<>();
            ActorPart current = part;
            while (current.parentId().isPresent()) {
                if (!visited.add(current.id())) return null;
                current = byId.get(current.parentId().orElseThrow());
                if (current == null) return null;
            }
        }
        return byId;
    }

    private static PartPairLayout locatePartPair(
        final String sourceName,
        final String targetName
    ) {
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (!window.isVisible()) continue;
            final javax.swing.JTable table = findPartsTable(window);
            if (table == null || !table.isShowing()) continue;
            final javax.swing.JTree tree = WindowsMeshEditValidationProbe.extractTree(table);
            if (tree == null) continue;
            final PartPairLayout local = locatePartPairRows(table, tree, sourceName, targetName);
            if (local == null) continue;
            try {
                return local.withScreenPoints(table);
            } catch (java.awt.IllegalComponentStateException notShowing) {
                // The palette was replaced between layout and conversion; re-discover it.
            }
        }
        return null;
    }

    /**
     * Computes one final shared viewport layout. Package-private for the headless Swing test: it
     * exercises the same expansion, scrolling, row/path and point checks as the native actor.
     */
    static PartPairLayout locatePartPairRows(
        final javax.swing.JTable table,
        final javax.swing.JTree tree,
        final String sourceName,
        final String targetName
    ) {
        if (table == null || tree == null || sourceName == null || targetName == null
            || sourceName.equals(targetName)) return null;
        javax.swing.tree.TreePath sourcePath = uniquePartPath(tree, sourceName);
        javax.swing.tree.TreePath targetPath = uniquePartPath(tree, targetName);
        if (sourcePath == null || targetPath == null || sourcePath.equals(targetPath)) return null;
        expandAncestors(tree, sourcePath);
        expandAncestors(tree, targetPath);
        tree.expandPath(targetPath);

        sourcePath = uniquePartPath(tree, sourceName);
        targetPath = uniquePartPath(tree, targetName);
        PartRowLocation source = partRowLocation(table, tree, sourcePath);
        PartRowLocation target = partRowLocation(table, tree, targetPath);
        if (source == null || target == null) return null;
        table.scrollRectToVisible(target.cell());

        // Target scrolling can move the source. Resolve both again before considering a gesture.
        sourcePath = uniquePartPath(tree, sourceName);
        targetPath = uniquePartPath(tree, targetName);
        source = partRowLocation(table, tree, sourcePath);
        target = partRowLocation(table, tree, targetPath);
        if (source == null || target == null) return null;
        if (!fullyVisible(table, source.cell()) || !fullyVisible(table, target.cell())) {
            final java.awt.Rectangle union = source.cell().union(target.cell());
            if (union.height > table.getVisibleRect().height
                || union.width > table.getVisibleRect().width) {
                return null;
            }
            table.scrollRectToVisible(union);
            // A final read is required after the shared scroll as well; no point from before it
            // may be reused for Robot input.
            sourcePath = uniquePartPath(tree, sourceName);
            targetPath = uniquePartPath(tree, targetName);
            source = partRowLocation(table, tree, sourcePath);
            target = partRowLocation(table, tree, targetPath);
        }
        if (source == null || target == null
            || !fullyVisible(table, source.cell()) || !fullyVisible(table, target.cell())) {
            return null;
        }
        return new PartPairLayout(
            table,
            tree,
            table.getModel(),
            tree.getModel(),
            source,
            target,
            table.isShowing()
        );
    }

    /** Returns the already-verified target screen point without applying an unverified offset. */
    static java.awt.Point partGestureTargetPoint(final PartPairLayout layout) {
        if (layout == null || layout.target() == null || layout.target().screenPoint() == null) {
            return null;
        }
        return new java.awt.Point(layout.target().screenPoint());
    }

    private static javax.swing.tree.TreePath uniquePartPath(
        final javax.swing.JTree tree,
        final String name
    ) {
        final List<javax.swing.tree.TreePath> paths =
            WindowsMeshEditValidationProbe.findTreePaths(tree, name);
        return paths.size() == 1 ? paths.get(0) : null;
    }

    private static String treeLabel(final JTree tree, final Object node) {
        if (tree == null || node == null) return "";
        try {
            return boundedText(
                tree.convertValueToText(node, false, false, false, 0, false),
                MAX_PART_GESTURE_TEXT_LENGTH
            );
        } catch (RuntimeException failure) {
            return boundedText(String.valueOf(node), MAX_PART_GESTURE_TEXT_LENGTH);
        }
    }

    private static String treePathLabel(final JTree tree, final TreePath path) {
        if (tree == null || path == null) return "";
        final StringBuilder label = new StringBuilder();
        final int count = Math.min(path.getPathCount(), MAX_PART_GESTURE_PATH_COMPONENTS);
        for (int index = 0; index < count; index++) {
            if (index > 0) label.append('/');
            label.append(treeLabel(tree, path.getPathComponent(index)));
        }
        return boundedText(label.toString(), MAX_PART_GESTURE_TEXT_LENGTH);
    }

    private static void expandAncestors(
        final javax.swing.JTree tree,
        final javax.swing.tree.TreePath path
    ) {
        javax.swing.tree.TreePath parent = path == null ? null : path.getParentPath();
        while (parent != null) {
            tree.expandPath(parent);
            parent = parent.getParentPath();
        }
    }

    private static PartRowLocation partRowLocation(
        final javax.swing.JTable table,
        final javax.swing.JTree tree,
        final javax.swing.tree.TreePath path
    ) {
        if (path == null) return null;
        final int row = tree.getRowForPath(path);
        if (row < 0 || row >= table.getRowCount() || !path.equals(tree.getPathForRow(row))) {
            return null;
        }
        int column = 0;
        int widest = -1;
        for (int index = 0; index < table.getColumnCount(); index++) {
            final int width = table.getColumnModel().getColumn(index).getWidth();
            if (width > widest) {
                widest = width;
                column = index;
            }
        }
        final java.awt.Rectangle cell = table.getCellRect(row, column, true);
        final java.awt.Rectangle viewport = table.getVisibleRect();
        if (cell.isEmpty() || viewport.isEmpty()) return null;
        final java.awt.Point local = new java.awt.Point(
            cell.x + Math.max(1, cell.width / 2),
            cell.y + Math.max(1, cell.height / 2));
        if (table.rowAtPoint(local) != row) return null;
        final Object node = path.getLastPathComponent();
        return new PartRowLocation(
            path,
            node,
            treeLabel(tree, node),
            row,
            column,
            cell,
            viewport,
            local,
            null
        );
    }

    private static boolean fullyVisible(
        final javax.swing.JTable table,
        final java.awt.Rectangle cell
    ) {
        final java.awt.Rectangle viewport = table.getVisibleRect();
        return viewport.contains(cell.x, cell.y)
            && viewport.contains(cell.x + cell.width - 1, cell.y + cell.height - 1);
    }

    /**
     * Drags one Part row onto another in the Parts palette.
     *
     * <p>Reuses the mesh-edit probe's exact Parts-tree discovery: it finds the tree by its
     * listener/owner classes rather than a title, expands and scrolls the named rows into view,
     * and returns their owner-table cell centers in screen coordinates. The drag itself is real
     * input — Robot press, interpolated move, release — so the host's own drop handling decides
     * whether a hierarchy entry is committed.</p>
     */
    private String dragPartRow(final String knownSignificant) throws Exception {
        final List<String> names = onEdt(() ->
            context.cubism().model().active().parts().all().stream()
                .map(dev.turboism.sdk.cubism.model.Part::name)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList());
        if (names.size() < 2) return "unresolved:fewer-than-two-parts";
        // Press the rendered row directly and never pre-select it first: a press on the
        // already-selected row can begin label editing instead of a drag. The row point comes
        // from the Parts tree-table's widest column — the name column — because the first
        // column's cell centre can land on a checkbox. The SDK lists every part including the
        // model's root container, which never owns a row, so unresolved names are skipped.
        String source = null;
        int[] fromPoint = null;
        for (final String name : names) {
            final int[] point = onEdt(
                () -> WindowsHistoryNativeUiIngressProbe.partsRowPoint(name));
            if (point != null) {
                source = name;
                fromPoint = point;
                break;
            }
        }
        if (fromPoint == null) {
            return "unresolved:no-selectable-part-row:" + names;
        }
        final int fromX = fromPoint[0];
        final int fromY = fromPoint[1];
        final ArrayList<String> tried = new ArrayList<>();
        for (final String name : names) {
            if (name.equals(source) || tried.size() >= 3) continue;
            final int[] toPoint = onEdt(
                () -> WindowsHistoryNativeUiIngressProbe.partsRowPoint(name));
            if (toPoint == null) continue;
            tried.add(name);
            final int toX = toPoint[0];
            final int toY = toPoint[1];
            final int height = toPoint[2];
            final int edgeY = toY + Math.max(1, height / 2) - 1;
            final int[][] drops = {{toX, toY}, {toX, edgeY}};
            for (int drop = 0; drop < drops.length; drop++) {
                robotDrag(fromX, fromY, drops[drop][0], drops[drop][1]);
                for (int settle = 0; settle < 12; settle++) {
                    Thread.sleep(POLL_MILLIS);
                    if (!significantSequence(sample()).equals(knownSignificant)) {
                        return "dragged:" + source + "->" + name
                            + ":drop=" + (drop == 0 ? "on-row" : "row-edge")
                            + ":targets=" + tried.size();
                    }
                }
            }
        }
        if (tried.isEmpty()) {
            return "unresolved:no-target-row:" + names;
        }
        return "dragged:" + source + "->tried=" + tried + ":no-significant-entry:"
            + onEdt(WindowsHistoryNativeUiIngressProbe::partsTreeDnD);
    }

    /**
     * The Parts tree-table row's centre in screen coordinates on its widest column.
     *
     * <p>Runs on the EDT. The embedded {@code JTree} is a renderer component — never showing —
     * so the row index comes from its model and the press point from the owner table's cell
     * rect; the widest column is the name column, the only place a row drag can start.</p>
     *
     * @return {@code [x, y, rowHeight]} in screen coordinates, or {@code null} when no Parts
     *     tree-table row renders {@code displayName}
     */
    private static int[] partsRowPoint(final String displayName) {
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (!window.isVisible()) continue;
            final javax.swing.JTable table = findPartsTable(window);
            if (table == null || !table.isShowing()) continue;
            final javax.swing.JTree tree =
                WindowsMeshEditValidationProbe.extractTree(table);
            if (tree == null) continue;
            final java.util.List<javax.swing.tree.TreePath> paths =
                WindowsMeshEditValidationProbe.findTreePaths(tree, displayName);
            if (paths.size() != 1) continue;
            // Collapsed parents give the path no row at all — expand first.
            tree.expandPath(paths.get(0).getParentPath());
            final int row = tree.getRowForPath(paths.get(0));
            if (row < 0 || row >= table.getRowCount()) continue;
            int column = 0;
            int widest = -1;
            for (int index = 0; index < table.getColumnCount(); index++) {
                final int width = table.getColumnModel().getColumn(index).getWidth();
                if (width > widest) {
                    widest = width;
                    column = index;
                }
            }
            final java.awt.Rectangle rect = table.getCellRect(row, column, true);
            if (rect.isEmpty()) continue;
            table.scrollRectToVisible(rect);
            final java.awt.Point centre = new java.awt.Point(
                rect.x + Math.max(1, rect.width / 2),
                rect.y + Math.max(1, rect.height / 2));
            SwingUtilities.convertPointToScreen(centre, table);
            return new int[] {centre.x, centre.y, rect.height};
        }
        return null;
    }

    private static javax.swing.JTable findPartsTable(final java.awt.Component component) {
        if (component instanceof javax.swing.JTable table
            && table.getClass().getName().contains("PartsTreeTable")) {
            return table;
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                final javax.swing.JTable found = findPartsTable(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * Reports whether the Parts tree supports Swing drag-and-drop at all.
     *
     * <p>Only consulted when both drops missed: if the host's tree has no drag gesture or no
     * drop handler, no amount of pointer accuracy will produce an entry and the step needs a
     * different interaction. The report is bounded to the trees that look like the Parts
     * palette.</p>
     */
    private static String partsTreeDnD() {
        final StringBuilder out = new StringBuilder(2048);
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (window.isVisible()) collectTreeDnD(window, out, 0);
        }
        return out.length() > 0 ? out.toString() : "no-trees";
    }

    private static void collectTreeDnD(
        final java.awt.Component component,
        final StringBuilder out,
        final int depth
    ) {
        if (depth > UI_MAP_MAX_DEPTH || !component.isVisible() || out.length() > 1800) return;
        if (component instanceof javax.swing.JTree tree) {
            final java.awt.Container owner = SwingUtilities.getAncestorOfClass(
                javax.swing.JTable.class, tree);
            out.append("tree:").append(tree.getClass().getName())
                .append(":drag=").append(tree.getDragEnabled())
                .append(":drop=").append(tree.getDropTarget() != null)
                .append(":handler=").append(tree.getTransferHandler() == null
                    ? "none" : tree.getTransferHandler().getClass().getSimpleName())
                .append(":owner=").append(owner == null ? "none" : owner.getClass().getName())
                .append(';');
        } else if (component instanceof javax.swing.JTable table
            && table.getClass().getName().toLowerCase(java.util.Locale.ROOT)
                .contains("treetable")) {
            // A TreeTable embeds its JTree as a cell renderer, so the drag gesture and the
            // transfer handler live on the table — checking only the tree would report a DnD
            // that can never fire.
            out.append("table:").append(table.getClass().getName())
                .append(":drag=").append(table.getDragEnabled())
                .append(":drop=").append(table.getDropTarget() != null)
                .append(":mode=").append(table.getDropMode())
                .append(":handler=").append(table.getTransferHandler() == null
                    ? "none" : table.getTransferHandler().getClass().getName())
                .append(';');
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                collectTreeDnD(child, out, depth + 1);
            }
        }
    }

    /**
     * Drags inside the model canvas the way the operator does: left button held down, moved,
     * released.
     *
     * <p>The surface is identified structurally — a canvas/GL class name first, then the largest
     * showing leaf components across every visible window, because the r11 run showed the model
     * view lives in a {@code JDialog}, not the main frame — and every attempted pick is recorded
     * so a run that hit the wrong surface is visible in the evidence. A press that misses the
     * model starts a marquee selection, which is insignificant and leaves the step open, so the
     * actor works across a bounded set of surfaces and press points and samples the undo manager
     * after each drag: the first press that lands on the object produces the significant entry
     * being reviewed and the retries stop.</p>
     */
    private String dragCanvas(final String knownSignificant) throws Exception {
        // The r14 run proved the component tree can be mid-relayout when the actor runs — an
        // invisible ancestor prunes the whole subtree and discovery comes back empty while the
        // post-action map shows a perfectly good surface. Give the layout a few bounded chances
        // to settle before reporting the miss.
        List<java.awt.Component> candidates =
            onEdt(WindowsHistoryNativeUiIngressProbe::canvasCandidates);
        for (int retry = 0; candidates.isEmpty() && retry < 4; retry++) {
            Thread.sleep(POLL_MILLIS);
            candidates = onEdt(WindowsHistoryNativeUiIngressProbe::canvasCandidates);
        }
        if (candidates.isEmpty()) {
            // The r13 run returned empty here while the post-action map showed a showing leaf —
            // record what was actually rejected so the next run names the reason instead of
            // needing another guess.
            return "unresolved:no-canvas-component:" + onEdt(
                WindowsHistoryNativeUiIngressProbe::canvasRejects);
        }
        final ArrayList<String> tried = new ArrayList<>();
        // Centre of the visible region first — the model sits centered on load — then a second
        // point before moving to the next candidate surface.
        final int[][] fractions = {{1, 2, 1, 2}, {1, 3, 1, 2}, {2, 3, 1, 2}};
        final java.util.Set<java.awt.Point> pressed = new java.util.HashSet<>();
        for (final java.awt.Component canvas : candidates) {
            final java.awt.Rectangle visible = onEdt(() -> visibleBounds(canvas));
            if (visible.isEmpty()) continue;
            final int dx = Math.min(60, Math.max(10, visible.width / 8));
            final int dy = Math.min(40, Math.max(10, visible.height / 8));
            for (final int[] fraction : fractions) {
                final java.awt.Point local = new java.awt.Point(
                    visible.x + Math.max(1, visible.width * fraction[0] / fraction[1]),
                    visible.y + Math.max(1, visible.height * fraction[2] / fraction[3]));
                final java.awt.Point press = new java.awt.Point(local);
                SwingUtilities.convertPointToScreen(press, canvas);
                if (!pressed.add(press)) continue;
                final String hit = onEdt(() -> pressTarget(canvas, local));
                if (hit == null) continue;
                robotDrag(press.x, press.y, press.x + dx, press.y + dy);
                // The host commits the undo entry after the release; give it a short bounded
                // settle before deciding the press missed the model.
                for (int settle = 0; settle < 12; settle++) {
                    Thread.sleep(POLL_MILLIS);
                    if (!significantSequence(sample()).equals(knownSignificant)) {
                        return "dragged:" + canvas.getClass().getName() + visible
                            + ":hit=" + hit + ":attempt=" + (tried.size() + 1);
                    }
                }
                tried.add(hit + "@" + press.x + "," + press.y);
            }
        }
        return "dragged:" + tried.size() + "-points:no-significant-entry:" + String.join("|", tried);
    }

    /**
     * Drags one Parameter-palette slider thumb the way the operator does: left button held on the
     * thumb, moved along the track, released.
     *
     * <p>Every showing horizontal slider in any visible window is a candidate — the Parameter
     * palette's sliders are the common case. A candidate is abandoned only after its bounded
     * readback window remains unchanged; the first actual parameter transition stops the actor,
     * independently of native Undo admission.</p>
     */
    private String dragParameterSlider(final ParameterStateSnapshot parameterBefore) throws Exception {
        if (parameterBefore == null || !parameterBefore.available()) {
            final String reason = parameterBefore == null
                ? "no-before-readback" : parameterBefore.reason();
            recordParameterActorTermination(new ParameterChangeObservation(
                parameterBefore != null && "model-changed".equals(reason)
                    ? ParameterStateOutcome.MODEL_CHANGED : ParameterStateOutcome.UNAVAILABLE,
                parameterBefore,
                reason
            ));
            return "unresolved:parameter-state:" + reason;
        }
        // The Parameter palette's value rows are CSlider widgets whose Swing mirrors are
        // com.live2d.ui.swingImpl.A (a JSlider subclass). They only exist inside the
        // parameter tab's dock column — the status-bar zoom slider is also an A, so the
        // palette rows must be scoped to that column. Readback, not Undo admission, decides
        // whether this actor stops after a gesture.
        final java.awt.Robot robot = new java.awt.Robot();
        String paletteNote = "";
        List<javax.swing.JSlider> dockSliders = onEdt(
            WindowsHistoryNativeUiIngressProbe::parameterDockSliders);
        if (dockSliders.isEmpty()) {
            paletteNote = "palette=" + openPalette(
                PARAMETER_MARKERS,
                () -> !parameterDockSliders().isEmpty());
            for (int retry = 0; dockSliders.isEmpty() && retry < 12; retry++) {
                Thread.sleep(POLL_MILLIS);
                dockSliders = onEdt(
                    WindowsHistoryNativeUiIngressProbe::parameterDockSliders);
            }
            if (dockSliders.isEmpty()) {
                paletteNote += ":scope=" + onEdt(
                    WindowsHistoryNativeUiIngressProbe::paletteClassCensus);
            }
        }
        final ArrayList<String> tried = new ArrayList<>();
        for (final javax.swing.JSlider slider : dockSliders) {
            final int[] plan = onEdt(() -> sliderDragPlan(slider));
            if (plan == null) continue;
            robotDrag(plan[0], plan[1], plan[2], plan[3]);
            final ParameterChangeObservation changed = awaitParameterGestureAndRecord(parameterBefore);
            if (changed.outcome() == ParameterStateOutcome.CHANGED) {
                return "changed:param-row:" + slider.getClass().getName()
                    + ":attempt=" + (tried.size() + 1);
            }
            if (changed.outcome() != ParameterStateOutcome.UNCHANGED) {
                return "unresolved:parameter-state:" + changed.outcome().code();
            }
            tried.add("dock:" + slider.getClass().getSimpleName()
                + "@" + plan[0] + "," + plan[1]);
            if (tried.size() >= 6) break;
        }
        // Palettes whose rows live only in the CWidget tree expose no Swing sliders —
        // their row widgets still carry on-screen rects a Robot drag can ride. The
        // parameter value control is CSlidableFloat: a scrub gestures sideways, and a
        // double-click opens its embedded text editor for direct typing.
        if (tried.size() < 6) {
            final List<Object> cwRows = onEdt(
                WindowsHistoryNativeUiIngressProbe::parameterCWidgetRows);
            for (final Object rowWidget : cwRows) {
                final java.awt.Component surface = onEdt(() -> cwidgetSurface(rowWidget));
                final int[] at = surface == null
                    ? null : onEdt(() -> fieldCentre(surface));
                if (at == null) continue;
                robotDrag(at[0] - 6, at[1], at[0] + 18, at[1]);
                ParameterChangeObservation changed = awaitParameterGestureAndRecord(parameterBefore);
                if (changed.outcome() == ParameterStateOutcome.CHANGED) {
                    return "changed:cslider:attempt=" + (tried.size() + 1);
                }
                if (changed.outcome() != ParameterStateOutcome.UNCHANGED) {
                    return "unresolved:parameter-state:" + changed.outcome().code();
                }
                robotDrag(at[0], at[1] - 6, at[0], at[1] + 14);
                changed = awaitParameterGestureAndRecord(parameterBefore);
                if (changed.outcome() == ParameterStateOutcome.CHANGED) {
                    return "changed:cslider-v:attempt=" + (tried.size() + 1);
                }
                if (changed.outcome() != ParameterStateOutcome.UNCHANGED) {
                    return "unresolved:parameter-state:" + changed.outcome().code();
                }
                // The scrub may be below the field's slide threshold — a double-click
                // opens the embedded JTextField so a typed value commits the edit.
                robot.mouseMove(at[0], at[1]);
                robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                Thread.sleep(70L);
                robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                Thread.sleep(POLL_MILLIS);
                final javax.swing.text.JTextComponent editor = onEdt(
                    () -> cwidgetEditor(rowWidget));
                if (editor != null) {
                    final int[] eat = onEdt(() -> fieldCentre(editor));
                    if (eat != null) {
                        robot.mouseMove(eat[0], eat[1]);
                        robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                        robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                        Thread.sleep(120L);
                        robot.keyPress(java.awt.event.KeyEvent.VK_CONTROL);
                        robot.keyPress(java.awt.event.KeyEvent.VK_A);
                        robot.keyRelease(java.awt.event.KeyEvent.VK_A);
                        robot.keyRelease(java.awt.event.KeyEvent.VK_CONTROL);
                        Thread.sleep(60L);
                        for (final char digit : "37".toCharArray()) {
                            typeChar(robot, digit);
                        }
                        robot.keyPress(java.awt.event.KeyEvent.VK_ENTER);
                        robot.keyRelease(java.awt.event.KeyEvent.VK_ENTER);
                        changed = awaitParameterGestureAndRecord(parameterBefore);
                        if (changed.outcome() == ParameterStateOutcome.CHANGED) {
                            return "changed:typed-cslidable:attempt=" + (tried.size() + 1);
                        }
                        if (changed.outcome() != ParameterStateOutcome.UNCHANGED) {
                            return "unresolved:parameter-state:" + changed.outcome().code();
                        }
                    }
                }
                tried.add("cw@" + at[0] + "," + at[1]);
                if (tried.size() >= 6) break;
            }
        }
        List<java.awt.Component> rows = onEdt(
            WindowsHistoryNativeUiIngressProbe::parameterRows);
        for (final java.awt.Component row : rows) {
            final int[] at = onEdt(() -> fieldCentre(row));
            if (at == null) continue;
            robotDrag(at[0] - 8, at[1], at[0] + 24, at[1]);
            final ParameterChangeObservation changed = awaitParameterGestureAndRecord(parameterBefore);
            if (changed.outcome() == ParameterStateOutcome.CHANGED) {
                return "changed:param-row:" + row.getClass().getName()
                    + ":attempt=" + (tried.size() + 1);
            }
            if (changed.outcome() != ParameterStateOutcome.UNCHANGED) {
                return "unresolved:parameter-state:" + changed.outcome().code();
            }
            tried.add(row.getClass().getSimpleName() + "@" + at[0] + "," + at[1]);
            if (tried.size() >= 6) break;
        }
        List<javax.swing.JSlider> sliders = onEdt(
            WindowsHistoryNativeUiIngressProbe::parameterSliders);
        if (sliders.isEmpty() && rows.isEmpty()) {
            recordParameterActorTermination(new ParameterChangeObservation(
                ParameterStateOutcome.UNCHANGED,
                parameterBefore,
                "no-slider-or-param-row"
            ));
            return "unresolved:no-slider-or-param-row:"
                + paletteNote + ":"
                + onEdt(WindowsHistoryNativeUiIngressProbe::canvasRejects);
        }
        for (final javax.swing.JSlider slider : sliders) {
            final int[] plan = onEdt(() -> sliderDragPlan(slider));
            if (plan == null) continue;
            robotDrag(plan[0], plan[1], plan[2], plan[3]);
            final ParameterChangeObservation changed = awaitParameterGestureAndRecord(parameterBefore);
            if (changed.outcome() == ParameterStateOutcome.CHANGED) {
                return "changed:" + slider.getClass().getName()
                    + ":attempt=" + (tried.size() + 1);
            }
            if (changed.outcome() != ParameterStateOutcome.UNCHANGED) {
                return "unresolved:parameter-state:" + changed.outcome().code();
            }
            tried.add(slider.getClass().getSimpleName() + "@" + plan[0] + "," + plan[1]);
            if (tried.size() >= 6) break;
        }
        recordParameterActorTermination(new ParameterChangeObservation(
            ParameterStateOutcome.UNCHANGED,
            parameterBefore,
            "no-parameter-value-change"
        ));
        return "unresolved:no-parameter-value-change:" + tried.size() + "-candidates:"
            + String.join("|", tried)
            + (paletteNote.isEmpty() ? "" : ":" + paletteNote)
            + ":" + onEdt(WindowsHistoryNativeUiIngressProbe::parameterWidgetCensus);
    }

    /** Bounded post-gesture readback used to stop the actor on the first actual value change. */
    private ParameterChangeObservation awaitParameterGesture(
        final ParameterStateSnapshot before
    ) throws Exception {
        ParameterStateSnapshot latest = before;
        for (int poll = 0; poll < PARAMETER_ACTOR_POLLS; poll++) {
            Thread.sleep(POLL_MILLIS);
            final ParameterStateSnapshot current = readParameterSnapshot();
            final ParameterStateOutcome outcome = compareParameterState(before, current);
            if (outcome != ParameterStateOutcome.UNCHANGED) {
                return new ParameterChangeObservation(outcome, current, outcome.code());
            }
            latest = current;
        }
        return new ParameterChangeObservation(
            ParameterStateOutcome.UNCHANGED, latest, "gesture-window-expired");
    }

    private ParameterChangeObservation awaitParameterGestureAndRecord(
        final ParameterStateSnapshot before
    ) throws Exception {
        final ParameterChangeObservation observation = awaitParameterGesture(before);
        recordParameterActorTermination(observation);
        return observation;
    }

    private void recordParameterActorTermination(
        final ParameterChangeObservation observation
    ) {
        if (observation != null) parameterActorTermination = observation;
    }

    /**
     * Waits for a native parameter value transition without consulting Undo as the exit signal.
     * Any read failure or model change stops the actor fail-closed.
     */
    private ParameterChangeObservation awaitParameterChange(
        final ParameterStateSnapshot before
    ) throws Exception {
        if (before == null || !before.available()) {
            return new ParameterChangeObservation(
                ParameterStateOutcome.UNAVAILABLE,
                null,
                before == null ? "no-before-readback" : before.reason()
            );
        }
        final long deadline = System.currentTimeMillis() + STEP_TIMEOUT_MILLIS;
        ParameterStateSnapshot latest = before;
        while (System.currentTimeMillis() < deadline) {
            if (!running) throw new InterruptedException("Probe disabled while awaiting a parameter");
            Thread.sleep(POLL_MILLIS);
            final ParameterStateSnapshot current = readParameterSnapshot();
            final ParameterStateOutcome outcome = compareParameterState(before, current);
            if (outcome == ParameterStateOutcome.CHANGED) {
                return settleParameterChange(
                    before,
                    new ParameterChangeObservation(outcome, current, outcome.code())
                );
            }
            if (outcome != ParameterStateOutcome.UNCHANGED) {
                return new ParameterChangeObservation(outcome, current, outcome.code());
            }
            latest = current;
        }
        return new ParameterChangeObservation(
            ParameterStateOutcome.UNCHANGED, latest, "parameter-window-expired");
    }

    private ParameterChangeObservation awaitParameterChange(
        final ParameterStateSnapshot before,
        final ParameterChangeObservation actorTermination
    ) throws Exception {
        if (actorTermination != null) {
            if (actorTermination.outcome() == ParameterStateOutcome.UNAVAILABLE
                || actorTermination.outcome() == ParameterStateOutcome.MODEL_CHANGED) {
                return actorTermination;
            }
            if (actorTermination.outcome() == ParameterStateOutcome.CHANGED) {
                return settleParameterChange(before, actorTermination);
            }
        }
        return awaitParameterChange(before);
    }

    private ParameterChangeObservation settleParameterChange(
        final ParameterStateSnapshot before,
        final ParameterChangeObservation detected
    ) throws Exception {
        final List<ParameterChangeObservation> settleObservations = new ArrayList<>();
        final long deadline = System.currentTimeMillis() + ACTION_SETTLE_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (!running) throw new InterruptedException("Probe disabled while settling a parameter");
            Thread.sleep(POLL_MILLIS);
            final ParameterStateSnapshot current = readParameterSnapshot();
            final ParameterStateOutcome outcome = compareParameterState(before, current);
            if (outcome == ParameterStateOutcome.UNAVAILABLE
                || outcome == ParameterStateOutcome.MODEL_CHANGED) {
                settleObservations.add(new ParameterChangeObservation(
                    outcome, current, outcome.code()));
                return settleParameterObservations(detected, settleObservations);
            }
            // Every successful read is a candidate final state. In particular, an unchanged
            // readback proves that the gesture returned to its before value and must not be
            // replaced by the earlier changed observation.
            settleObservations.add(new ParameterChangeObservation(outcome, current, outcome.code()));
        }
        return settleParameterObservations(detected, settleObservations);
    }

    /** Reads the active model and its bounded parameter values on the host EDT. */
    private ParameterStateSnapshot readParameterSnapshot() {
        try {
            final ParameterStateSnapshot snapshot = onEdt(this::readParameterSnapshotOnEdt);
            return snapshot == null
                ? ParameterStateSnapshot.unavailable("null-readback") : snapshot;
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            return ParameterStateSnapshot.unavailable(
                "read-failed:" + failure.getClass().getSimpleName());
        }
    }

    private ParameterStateSnapshot readParameterSnapshotOnEdt() {
        try {
            if (context == null) return ParameterStateSnapshot.unavailable("no-context");
            final CubismModel model = context.cubism().model().active();
            if (model == null || model.id() == null || model.id().value() == null
                || model.id().value().isBlank()
                || model.id().value().length() > MAX_PARAMETER_ID_LENGTH) {
                return ParameterStateSnapshot.unavailable("model-identity-unavailable");
            }
            final String modelId = model.id().value();
            final List<Parameter> parameters = model.parameters().all();
            if (parameters == null) {
                return ParameterStateSnapshot.unavailable("parameters-unavailable");
            }
            if (parameters.size() > MAX_PARAMETER_VALUES) {
                return ParameterStateSnapshot.unavailable("parameter-snapshot-limit");
            }
            final Set<String> ids = new LinkedHashSet<>();
            final List<ParameterValueSample> values = new ArrayList<>(parameters.size());
            for (final Parameter parameter : parameters) {
                if (parameter == null || parameter.id() == null
                    || parameter.id().value() == null
                    || parameter.id().value().isBlank()
                    || parameter.id().value().length() > MAX_PARAMETER_ID_LENGTH) {
                    return ParameterStateSnapshot.unavailable("parameter-identity-unavailable");
                }
                final String parameterId = parameter.id().value();
                if (!ids.add(parameterId)) {
                    return ParameterStateSnapshot.unavailable("duplicate-parameter-id");
                }
                final float value = parameter.getValue();
                if (!Float.isFinite(value)) {
                    return ParameterStateSnapshot.unavailable("non-finite-parameter-value");
                }
                values.add(new ParameterValueSample(parameterId, value));
            }
            final CubismModel activeAgain = context.cubism().model().active();
            if (activeAgain == null || activeAgain.id() == null
                || !modelId.equals(activeAgain.id().value())) {
                return ParameterStateSnapshot.unavailable("model-changed");
            }
            return ParameterStateSnapshot.available(modelId, values);
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            return ParameterStateSnapshot.unavailable(
                "read-failed:" + failure.getClass().getSimpleName());
        }
    }

    /**
     * The CWidget census inside the selected parameter tab's dock column — every widget
     * class, rect and label the palette actually instantiated. Runs on the EDT.
     */
    private static String parameterWidgetCensus() {
        for (final javax.swing.AbstractButton tab
            : paletteTabs(PARAMETER_MARKERS, new StringBuilder(1))) {
            if (!tab.isSelected()) continue;
            java.awt.Container scope = tab.getParent();
            while (scope != null && scope.getParent() != null
                && scope.getParent().getWidth() <= 200) {
                scope = scope.getParent();
            }
            if (scope == null) continue;
            Object root = cwidgetOf(scope);
            if (root == null) root = cwidgetTop(cwidgetOf(tab));
            final List<Object> widgets = cwidgetTree(root);
            return "pcw{n=" + widgets.size() + ":" + cwidgetCensus(widgets, 1400) + "}";
        }
        return "pcw{no-selected-tab}";
    }

    /**
     * Every showing enabled horizontal slider wide enough to grab.
     *
     * <p>Runs on the EDT. A slider narrower than 48px is a layout artefact, not a parameter row,
     * and a disabled slider cannot take the drag.</p>
     */
    private static List<javax.swing.JSlider> parameterSliders() {
        final List<javax.swing.JSlider> found = new ArrayList<>();
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (window.isVisible()) collectSliders(window, found, 0);
        }
        return found;
    }

    private static void collectSliders(
        final java.awt.Component component,
        final List<javax.swing.JSlider> found,
        final int depth
    ) {
        if (depth > CANVAS_SCAN_DEPTH || !component.isVisible()) return;
        if (component instanceof javax.swing.JSlider slider
            && slider.isShowing() && slider.isEnabled()
            && slider.getOrientation() == javax.swing.JSlider.HORIZONTAL
            && slider.getWidth() >= 48) {
            found.add(slider);
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                collectSliders(child, found, depth + 1);
            }
        }
    }

    /**
     * The showing Parameter-palette rows a drag could move.
     *
     * <p>The palette's value widgets are host classes under {@code palette.parameter}, not
     * {@code JSlider} — the r20 run only found the status-bar zoom slider, which changes the
     * view, not the model. A component living in the parameter palette package, wide enough to
     * be a value row, is a candidate; the Robot drags a few pixels along its horizontal centre
     * and the undo manager decides whether the gesture was a value change.</p>
     */
    private static List<java.awt.Component> parameterRows() {
        final List<java.awt.Component> found = new ArrayList<>();
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (window.isVisible()) collectParameterRows(window, found, 0);
        }
        return found;
    }

    private static void collectParameterRows(
        final java.awt.Component component,
        final List<java.awt.Component> found,
        final int depth
    ) {
        if (depth > CANVAS_SCAN_DEPTH || !component.isVisible()) return;
        final String className = component.getClass().getName();
        if (component.isShowing() && className.contains(".palette.parameter")
            && !(component instanceof javax.swing.AbstractButton)
            && !(component instanceof javax.swing.text.JTextComponent)
            && component.getWidth() >= 48 && component.getWidth() <= 400
            && component.getHeight() >= 10 && component.getHeight() <= 48
            && !found.contains(component)) {
            found.add(component);
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                collectParameterRows(child, found, depth + 1);
            }
        }
    }

    /**
     * Opens a palette through the host's own Window menu and reports the result.
     *
     * <p>The fixture layout has palettes closed: the only slider on screen is the status-bar
     * zoom. The frame keeps a fully populated {@link javax.swing.JMenuBar} — the palette items
     * are checkbox mirrors whose programmatic {@code doClick} does not fire the host's toggle —
     * so the menu is opened through the {@link javax.swing.MenuSelectionManager} and the item
     * is clicked with a real Robot press, exactly as the operator does.</p>
     */
    private String openPalette(
        final String[] itemMarkers,
        final java.util.function.Supplier<Boolean> realized
    ) throws Exception {
        final StringBuilder diag = new StringBuilder(320);
        // Palettes dock as tabs — the ui-map shows a 参数 tab (com.live2d.ui.swingImpl.K) in
        // the right dock's tab bar. A real press on the tab realizes the palette.
        final java.awt.Robot robot = new java.awt.Robot();
        for (int attempt = 0; attempt < 6; attempt++) {
            // Several tabs can carry the same palette name in different dock groups —
            // cycle through them instead of pressing the first one four times.
            final List<javax.swing.AbstractButton> tabs =
                onEdt(() -> paletteTabs(itemMarkers, diag));
            if (tabs.isEmpty()) {
                diag.append("tab-miss;");
                break;
            }
            final javax.swing.AbstractButton tabButton =
                tabs.get(attempt % tabs.size());
            final int[] tab = onEdt(() -> fieldCentre(tabButton));
            if (tab == null) {
                diag.append("tab-not-showing;");
                break;
            }
            // K tabs are toggles — re-pressing a tab that is already selected switches
            // its palette back off. Only press it when it is not selected.
            if (!tabButton.isSelected()) {
                robot.mouseMove(tab[0], tab[1]);
                Thread.sleep(60L);
                robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                Thread.sleep(80L);
                robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            }
            for (int poll = 0; poll < 8; poll++) {
                Thread.sleep(POLL_MILLIS);
                if (Boolean.TRUE.equals(onEdt(realized::get))) {
                    return "tab-click:" + diag;
                }
            }
            diag.append("tab-no-realize:sel=").append(tabButton.isSelected()).append(';');
        }
        final javax.swing.JMenu menu = onEdt(() -> windowMenu(diag));
        if (menu == null) return "no-window-menu:" + diag;
        onEdt(() -> {
            if (menu.getParent() instanceof javax.swing.JMenuBar bar) {
                javax.swing.MenuSelectionManager.defaultManager().setSelectedPath(
                    new javax.swing.MenuElement[] {bar, menu, menu.getPopupMenu()});
            } else {
                menu.setPopupMenuVisible(true);
            }
            return true;
        });
        Thread.sleep(600L);
        final javax.swing.JMenuItem jmi = onEdt(() -> popupItem(menu, itemMarkers, diag));
        if (jmi == null) {
            onEdt(() -> {
                javax.swing.MenuSelectionManager.defaultManager().clearSelectedPath();
                return true;
            });
            return "menu-no-palette-item:" + diag + "|" + onEdt(
                WindowsHistoryNativeUiIngressProbe::windowMenuTexts);
        }
        // The mirror maps back to its real CMenuItem through the host's component→widget
        // registry (com.live2d.ui.k.a). Clicking the CMenuItem fires the Kotlin action.
        final Object widget = widgetOf(jmi, diag);
        if (widget != null) {
            // The check item can be flagged open while its dock node was never built —
            // toggling alone does not realize the palette. Also try the menu's
            // "show panels" entry and its "reset state" entry, which rebuild the dock
            // layout, then re-toggle the palette item.
            final Object showPanels = onEdt(
                () -> menuWidget(menu, SHOW_PANELS_MARKERS, diag));
            final Object resetState = onEdt(
                () -> menuWidget(menu, RESET_STATE_MARKERS, diag));
            // The workspace submenu carries layout presets — switching layout rebuilds the
            // whole dock, palettes included. It is a JMenu inside the popup: open it and
            // take its first real item.
            final Object workspace = onEdt(() -> {
                final javax.swing.JMenuItem sub = popupItem(menu, WORKSPACE_MARKERS, diag);
                if (!(sub instanceof javax.swing.JMenu submenu)) return null;
                javax.swing.MenuSelectionManager.defaultManager().setSelectedPath(
                    new javax.swing.MenuElement[] {
                        menu.getParent() instanceof javax.swing.JMenuBar b ? b : menu,
                        menu, menu.getPopupMenu(), submenu, submenu.getPopupMenu()});
                diag.append("sub{");
                for (java.awt.Component component : submenu.getPopupMenu().getComponents()) {
                    if (component instanceof javax.swing.JMenu) continue;
                    if (!(component instanceof javax.swing.JMenuItem leaf)
                        || leaf.getText() == null || leaf.getText().isBlank()) continue;
                    diag.append(leaf.getText()).append(',');
                    // Layout presets only — skip save/manage entries that write host state.
                    if (containsAny(leaf.getText(), WORKSPACE_SKIP_MARKERS)) continue;
                    return widgetOf(leaf, diag);
                }
                diag.append('}');
                return null;
            });
            final StringBuilder note = new StringBuilder(
                "menu-doClick:" + widget.getClass().getSimpleName());
            final Object[] sequence =
                {widget, showPanels, widget, resetState, widget, workspace, widget};
            for (final Object target : sequence) {
                if (target == null) {
                    note.append(":skip-null");
                    continue;
                }
                onEdt(() -> {
                    target.getClass().getMethod("doClick").invoke(target);
                    return true;
                });
                for (int poll = 0; poll < 8; poll++) {
                    Thread.sleep(POLL_MILLIS);
                    if (Boolean.TRUE.equals(onEdt(realized::get))) {
                        onEdt(() -> {
                            javax.swing.MenuSelectionManager.defaultManager()
                                .clearSelectedPath();
                            return true;
                        });
                        return note.append(":opened").toString();
                    }
                }
                note.append(":sel=").append(jmi.isSelected());
            }
            onEdt(() -> {
                javax.swing.MenuSelectionManager.defaultManager().clearSelectedPath();
                return true;
            });
            return note.append(":not-realized:").append(diag).toString();
        }
        // Fallback: a real Robot press on the showing item.
        final int[] at = onEdt(() -> jmi.isShowing() ? fieldCentre(jmi) : null);
        if (at == null) {
            onEdt(() -> {
                javax.swing.MenuSelectionManager.defaultManager().clearSelectedPath();
                return true;
            });
            return "menu-item-not-showing:" + diag;
        }
        robot.mouseMove(at[0], at[1]);
        Thread.sleep(80L);
        robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
        Thread.sleep(80L);
        robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
        Thread.sleep(600L);
        return "menu-click:" + diag;
    }

    /** The Window-ish top menu of the host frame — the {@code s} JMenu carrying the palette
     * items. Prefers the frame JMenuBar, then scans the title-bar menubar in the layered
     * pane. Runs on the EDT. */
    private static javax.swing.JMenu windowMenu(final StringBuilder diag) {
        for (java.awt.Frame frame : java.awt.Frame.getFrames()) {
            if (!(frame instanceof javax.swing.JFrame swingFrame) || !frame.isVisible()) continue;
            final javax.swing.JMenuBar bar = swingFrame.getJMenuBar();
            if (bar != null) {
                diag.append("bar=").append(bar.getClass().getName())
                    .append(':').append(bar.getMenuCount()).append(';');
                for (int index = 0; index < bar.getMenuCount(); index++) {
                    final javax.swing.JMenu menu = bar.getMenu(index);
                    if (menu != null && menu.getText() != null
                        && containsAny(menu.getText(), MENU_WINDOW_MARKERS)) return menu;
                }
            } else {
                diag.append("no-bar;");
            }
            final javax.swing.JMenu inTree = windowMenuInTree(frame, 0);
            if (inTree != null) return inTree;
        }
        return null;
    }

    private static javax.swing.JMenu windowMenuInTree(
        final java.awt.Component component,
        final int depth
    ) {
        if (depth > CANVAS_SCAN_DEPTH || !component.isVisible()) return null;
        if (component instanceof javax.swing.JMenu menu && menu.getText() != null
            && containsAny(menu.getText(), MENU_WINDOW_MARKERS)) return menu;
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                final javax.swing.JMenu found = windowMenuInTree(child, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * The screen centre of a dock tab button carrying a palette name — the host renders dock
     * tabs as small buttons (com.live2d.ui.swingImpl.K) in a 21px-high strip. Menu-bar titles
     * and popup items are excluded; a tab is small, showing, and below the toolbar band.
     * Runs on the EDT.
     */
    private static javax.swing.AbstractButton paletteTab(
        final String[] markers,
        final StringBuilder diag
    ) {
        javax.swing.AbstractButton best = null;
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (!window.isVisible()) continue;
            final javax.swing.AbstractButton hit = paletteTabIn(window, markers, 0, diag);
            if (hit != null) best = hit;
        }
        return best;
    }

    /** Every showing dock tab whose text carries a marker, across all windows. */
    private static List<javax.swing.AbstractButton> paletteTabs(
        final String[] markers,
        final StringBuilder diag
    ) {
        final List<javax.swing.AbstractButton> found = new ArrayList<>();
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (!window.isVisible()) continue;
            collectPaletteTabs(window, markers, found, 0, diag);
        }
        return found;
    }

    private static void collectPaletteTabs(
        final java.awt.Component component,
        final String[] markers,
        final List<javax.swing.AbstractButton> found,
        final int depth,
        final StringBuilder diag
    ) {
        if (depth > CANVAS_SCAN_DEPTH || !component.isVisible()) return;
        if (component instanceof javax.swing.AbstractButton button
            && !(component instanceof javax.swing.JMenuItem)
            && button.isShowing() && button.getText() != null
            && containsAny(button.getText(), markers)
            && button.getHeight() <= 30 && button.getWidth() <= 200) {
            final java.awt.Point p = component.getLocationOnScreen();
            if (p.y > 42 && !found.contains(button)) {
                diag.append("tab=").append(button.getClass().getSimpleName())
                    .append('[').append(button.getText()).append(']')
                    .append('@').append(p.x + button.getWidth() / 2)
                    .append(',').append(p.y + button.getHeight() / 2).append(';');
                found.add(button);
            }
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                collectPaletteTabs(child, markers, found, depth + 1, diag);
            }
        }
    }

    private static javax.swing.AbstractButton paletteTabIn(
        final java.awt.Component component,
        final String[] markers,
        final int depth,
        final StringBuilder diag
    ) {
        if (depth > CANVAS_SCAN_DEPTH || !component.isVisible()) return null;
        if (component instanceof javax.swing.AbstractButton button
            && !(component instanceof javax.swing.JMenuItem)
            && button.isShowing() && button.getText() != null
            && containsAny(button.getText(), markers)
            && button.getHeight() <= 30 && button.getWidth() <= 200) {
            final java.awt.Point p = component.getLocationOnScreen();
            // The menu strip lives at y<42; dock tabs sit below it.
            if (p.y > 42) {
                diag.append("tab=").append(button.getClass().getSimpleName())
                    .append('[').append(button.getText()).append(']')
                    .append('@').append(p.x + button.getWidth() / 2)
                    .append(',').append(p.y + button.getHeight() / 2).append(';');
                return button;
            }
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                final javax.swing.AbstractButton hit =
                    paletteTabIn(child, markers, depth + 1, diag);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    /** Whether a dock tab carrying one of {@code markers} is currently selected. */
    private static boolean paletteTabSelected(final String[] markers) {
        return selectedPaletteTab(markers) != null;
    }

    /** The CWidget behind the popup item whose text matches a marker, or null. The menu must
     * already be open. Runs on the EDT. */
    private static Object menuWidget(
        final javax.swing.JMenu menu,
        final String[] markers,
        final StringBuilder diag
    ) {
        final javax.swing.JMenuItem jmi = popupItem(menu, markers, diag);
        return jmi == null ? null : widgetOf(jmi, diag);
    }

    /** The host CWidget registered for a mirror component, via {@code com.live2d.ui.k.a}. */
    private static Object widgetOf(
        final javax.swing.JMenuItem jmi,
        final StringBuilder diag
    ) {
        try {
            final Class<?> registry = Class.forName(
                "com.live2d.ui.k", true, jmi.getClass().getClassLoader());
            return registry.getMethod("a", java.awt.Component.class).invoke(null, jmi);
        } catch (final Exception e) {
            diag.append("registry-threw:").append(e.getClass().getSimpleName()).append(';');
            return null;
        }
    }

    /** The popup item whose text matches a marker — the menu is already open. Runs on the
     * EDT. */
    private static javax.swing.JMenuItem popupItem(
        final javax.swing.JMenu menu,
        final String[] markers,
        final StringBuilder diag
    ) {
        final javax.swing.JPopupMenu popup = menu == null ? null : menu.getPopupMenu();
        if (popup == null) {
            diag.append("no-popup;");
            return null;
        }
        diag.append("items{");
        for (java.awt.Component component : popup.getComponents()) {
            if (!(component instanceof javax.swing.JMenuItem jmi)) continue;
            diag.append(jmi.getText() == null ? "?" : jmi.getText()).append(',');
            if (jmi.getText() != null && containsAny(jmi.getText(), markers)) {
                diag.append('}');
                return jmi;
            }
        }
        diag.append('}');
        return null;
    }

    /** The texts under the Window-ish top menu, for diagnostics when the palette item is absent. */
    private static String windowMenuTexts() {
        for (java.awt.Frame frame : java.awt.Frame.getFrames()) {
            if (!(frame instanceof javax.swing.JFrame swingFrame) || !frame.isVisible()) continue;
            final javax.swing.JMenuBar bar = swingFrame.getJMenuBar();
            if (bar == null) continue;
            final StringBuilder out = new StringBuilder(512);
            for (int index = 0; index < bar.getMenuCount(); index++) {
                final javax.swing.JMenu menu = bar.getMenu(index);
                if (menu == null || menu.getText() == null
                    || !containsAny(menu.getText(), MENU_WINDOW_MARKERS)) continue;
                for (java.awt.Component component : menu.getMenuComponents()) {
                    if (component instanceof javax.swing.JMenuItem child) {
                        out.append(child.getText() == null ? "?" : child.getText()).append(';');
                    }
                }
            }
            return out.length() > 0 ? out.toString() : "window-menu-empty";
        }
        return "no-menubar";
    }

    private static final String[] MENU_WINDOW_MARKERS = {"视窗", "ウィンドウ", "Window"};
    private static final String[] PARAMETER_MARKERS = {"参数", "パラメータ", "arameter"};
    private static final String[] INSPECTOR_MARKERS = {"检视", "检查", "インスペクタ", "nspector"};
    private static final String[] SHOW_PANELS_MARKERS =
        {"显示面板", "パネルを表示", "how panels", "anels"};
    private static final String[] RESET_STATE_MARKERS =
        {"重置状态", "状態をリセット", "eset state", "eset"};
    private static final String[] WORKSPACE_MARKERS =
        {"工作区", "ワークスペース", "orkspace"};
    private static final String[] WORKSPACE_SKIP_MARKERS =
        {"保存", "管理", "新建", "删除", "編集", "管理", "ave", "anage", "ew ", "elete"};

    /**
     * A bounded census of palette-package components per visible window: which windows exist,
     * and how many {@code palette.parameter} / {@code palette.inspector} components each one
     * holds — visible or not — so a palette that opened collapsed still shows up.
     */
    private static String paletteClassCensus() {
        final StringBuilder out = new StringBuilder(480);
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (!window.isVisible()) continue;
            final int[] counts = {0, 0};
            censusPaletteClasses(window, counts, 0);
            out.append(window.getClass().getSimpleName()).append('[')
                .append(window.getWidth()).append('x').append(window.getHeight()).append(']')
                .append(":param=").append(counts[0])
                .append(":insp=").append(counts[1]).append(';');
            if (out.length() > 440) break;
        }
        return out.toString();
    }

    private static void censusPaletteClasses(
        final java.awt.Component component,
        final int[] counts,
        final int depth
    ) {
        if (depth > CANVAS_SCAN_DEPTH + 4) return;
        final String className = component.getClass().getName();
        if (className.contains(".palette.parameter")) counts[0]++;
        if (className.contains(".palette.inspector")) counts[1]++;
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                censusPaletteClasses(child, counts, depth + 1);
            }
        }
    }

    private static boolean containsAny(final String text, final String[] markers) {
        for (final String marker : markers) {
            if (text.contains(marker)) return true;
        }
        return false;
    }

    /**
     * The screen line a thumb drag for {@code slider} should follow: press at the thumb, drag
     * right by an eighth of the track.
     *
     * @return {@code [fromX, fromY, toX, toY]} in screen coordinates, or {@code null} when the
     *     slider has no room to move
     */
    private static int[] sliderDragPlan(final javax.swing.JSlider slider) {
        if (!slider.isShowing()) return null;
        final int range = slider.getMaximum() - slider.getMinimum();
        if (range <= 0 || slider.getWidth() < 48) return null;
        final double fraction =
            (double) (slider.getValue() - slider.getMinimum()) / (double) range;
        // The track rarely spans the whole component; keep the press inside it.
        final int localX = Math.max(6, Math.min(
            slider.getWidth() - 6, (int) Math.round(fraction * slider.getWidth())));
        final int localY = slider.getHeight() / 2;
        final int delta = Math.max(6, slider.getWidth() / 8);
        if (localX + delta > slider.getWidth() - 2 && slider.getValue() == slider.getMaximum()) {
            return null;
        }
        final java.awt.Point from = new java.awt.Point(localX, localY);
        SwingUtilities.convertPointToScreen(from, slider);
        return new int[] {from.x, from.y, from.x + delta, from.y};
    }

    /**
     * Edits one colour text field the way the operator does: click, select all, type a different
     * hex colour, confirm with Enter.
     *
     * <p>The inspector's multiply/screen colour rows are text fields carrying {@code #RRGGBB};
     * any showing editable field whose text already matches that shape is a candidate, and a
     * field whose commit produces no undo entry is skipped for the next one. A field that rejects
     * the value — wrong palette, read-only, not a colour — leaves the step waiting for the
     * operator like every other missed actor.</p>
     */
    private String editColorField(final String knownSignificant) throws Exception {
        List<javax.swing.text.JTextComponent> fields = onEdt(
            WindowsHistoryNativeUiIngressProbe::colorFields);
        for (int retry = 0; fields.isEmpty() && retry < 4; retry++) {
            Thread.sleep(POLL_MILLIS);
            fields = onEdt(WindowsHistoryNativeUiIngressProbe::colorFields);
        }
        final java.awt.Robot robot = new java.awt.Robot();
        int swatchCount = -1;
        final StringBuilder dialogDiag = new StringBuilder(160);
        final String[] cwidgetNote = {null};
        if (fields.isEmpty()) {
            // The inspector palette — where the multiply-colour field lives — may not be
            // open in the fixture layout; open it first so it can observe the selection.
            final String palette = openPalette(
                INSPECTOR_MARKERS,
                () -> inspectorShowing() || paletteTabSelected(INSPECTOR_MARKERS));
            // Then select a drawable — the inspector fills its property rows, colour
            // swatch included, when a selection event arrives while it is visible. A plain
            // click selects, it does not drag, so no undo entry is produced by this step.
            final String selection = selectDrawableRow(robot);
            for (int retry = 0; fields.isEmpty() && retry < 8; retry++) {
                Thread.sleep(POLL_MILLIS);
                fields = onEdt(WindowsHistoryNativeUiIngressProbe::colorFields);
            }
            if (fields.isEmpty()
                && (inspectorShowing()
                    || onEdt(() -> paletteTabSelected(INSPECTOR_MARKERS)))) {
                // The inspector paints custom CWidgets onto one panel — Swing sees no
                // children there, but every mirror maps back through com.live2d.ui.k
                // and CWidget.traverse exposes the real controls: the colour row
                // holds a CColorChooserButton plus an ARGB CTextField whose
                // JTextComponent mirror accepts real Robot typing.
                final List<javax.swing.text.JTextComponent> argb = new ArrayList<>();
                final List<javax.swing.text.JTextComponent> argbOther = new ArrayList<>();
                final List<java.awt.Component> chooser = new ArrayList<>();
                final List<java.awt.Component> chooserOther = new ArrayList<>();
                for (int scan = 0; scan < 4; scan++) {
                final Object contentWidget = onEdt(
                    WindowsHistoryNativeUiIngressProbe::inspectorContentWidget);
                final java.awt.Container dockScope = onEdt(
                    WindowsHistoryNativeUiIngressProbe::inspectorDockScope);
                final java.awt.Rectangle dockRect = onEdt(() -> {
                    if (dockScope == null || !dockScope.isShowing()) return null;
                    final java.awt.Point origin = dockScope.getLocationOnScreen();
                    return new java.awt.Rectangle(
                        origin.x, origin.y,
                        dockScope.getWidth(), dockScope.getHeight());
                });
                cwidgetNote[0] = onEdt(() -> {
                    final Object root = contentWidget;
                    final List<Object> widgets = cwidgetTree(root);
                    for (final Object widget : widgets) {
                        if (dockRect != null) {
                            final java.awt.Rectangle rect = cwidgetRect(widget);
                            if (rect == null || !rect.intersects(dockRect)) {
                                continue;
                            }
                        }
                        final javax.swing.text.JTextComponent text =
                            cwidgetTextComponent(widget);
                        if (text != null) {
                            (multiplyMarked(widget) ? argb : argbOther).add(text);
                            continue;
                        }
                        if (widget.getClass().getName()
                            .endsWith("CColorChooserButton")) {
                            final java.awt.Component surface = cwidgetSurface(widget);
                            if (surface != null) {
                                (multiplyMarked(widget) ? chooser : chooserOther)
                                    .add(surface);
                            }
                        }
                    }
                    return "cw{root=" + (root == null ? "null"
                        : root.getClass().getSimpleName())
                        + ":n=" + widgets.size()
                        + ":" + cwidgetCensus(widgets) + "}";
                });
                // The inspector fills its widget tree a beat after the selection
                // lands — rescan until the colour controls materialise.
                if (!argb.isEmpty() || !argbOther.isEmpty()
                    || !chooser.isEmpty() || !chooserOther.isEmpty()) {
                    break;
                }
                Thread.sleep(POLL_MILLIS);
                }
                fields.addAll(argb);
                fields.addAll(argbOther);
                // The swatch is a small coloured component inside the inspector palette;
                // pressing it opens the colour editor dialog where the hex field lives. A
                // swatch that opens some other dialog leaves it up — dismiss it with Escape
                // so it cannot swallow the next step's input.
                final List<java.awt.Component> swatches = new ArrayList<>(chooser);
                swatches.addAll(chooserOther);
                if (fields.isEmpty()) {
                    swatches.addAll(onEdt(
                        WindowsHistoryNativeUiIngressProbe::colorSwatches));
                }
                if (swatches.isEmpty()) {
                    // The inspector paints its property rows onto one component rather than
                    // hosting Swing children — the swatch is a painted square on a row. Scan
                    // the panel's actual pixels for saturated colour blocks and press those.
                    final java.awt.Rectangle panel = onEdt(
                        WindowsHistoryNativeUiIngressProbe::inspectorContentBounds);
                    final int[] bounds = panel == null ? null
                        : new int[] {panel.x, panel.y, panel.width, panel.height};
                    if (bounds != null) {
                        for (final int[] point : saturatedBlocks(robot, bounds)) {
                            final int fx = point[0];
                            final int fy = point[1];
                            swatches.add(new java.awt.Component() {
                                @Override public boolean isShowing() { return true; }
                                @Override public java.awt.Point getLocationOnScreen() {
                                    return new java.awt.Point(fx, fy);
                                }
                                @Override public int getWidth() { return 1; }
                                @Override public int getHeight() { return 1; }
                            });
                        }
                    }
                }
                swatchCount = swatches.size();
                int swatchAttempts = 0;
                for (final java.awt.Component swatch : swatches) {
                    if (swatchAttempts >= 14) break;
                    final int[] at = onEdt(() -> fieldCentre(swatch));
                    if (at == null) continue;
                    swatchAttempts++;
                    final java.util.Set<java.awt.Window> before =
                        onEdt(WindowsHistoryNativeUiIngressProbe::visibleWindows);
                    robot.mouseMove(at[0], at[1]);
                    robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                    robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                    java.awt.Window opened = null;
                    for (int poll = 0; poll < 8; poll++) {
                        Thread.sleep(POLL_MILLIS);
                        fields = onEdt(WindowsHistoryNativeUiIngressProbe::colorFields);
                        if (!fields.isEmpty()) break;
                        opened = onEdt(() -> newWindowSince(before));
                        if (opened != null) break;
                    }
                    if (!fields.isEmpty()) break;
                    if (opened != null) {
                        // A swatch press opened the colour editor — its value fields may be
                        // decimal RGB rather than hex. Edit one and let the dialog confirm.
                        dumpWindowScreenshot(robot, opened, "dialog");
                        final StringBuilder dialogNote = new StringBuilder(80);
                        final String edited = editDialogField(
                            robot, opened, knownSignificant, dialogNote);
                        if (edited != null) return "edited-dialog:" + edited;
                        dialogDiag.append("dialog:").append(opened.getClass().getSimpleName())
                            .append('{').append(dialogNote).append('}');
                        robot.keyPress(java.awt.event.KeyEvent.VK_ESCAPE);
                        robot.keyRelease(java.awt.event.KeyEvent.VK_ESCAPE);
                        Thread.sleep(POLL_MILLIS);
                    }
                }
            }
            if (fields.isEmpty()) {
                final String dock = onEdt(
                    WindowsHistoryNativeUiIngressProbe::inspectorLeafCensus);
                dumpRegionScreenshot(robot, "inspector");
                return "unresolved:no-color-field:palette=" + palette
                    + ":sel=" + selection
                    + ":swatches=" + swatchCount
                    + ":" + dialogDiag
                    + ":" + cwidgetNote[0]
                    + ":dock{" + dock + "}";
            }
        }
        final ArrayList<String> tried = new ArrayList<>();
        for (final javax.swing.text.JTextComponent field : fields) {
            final int[] at = onEdt(() -> fieldCentre(field));
            if (at == null) continue;
            final String previous = onEdt(field::getText);
            final String replacement = previous != null && previous.contains("33")
                ? "2244CC" : "CC4433";
            robot.mouseMove(at[0], at[1]);
            robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            Thread.sleep(150L);
            robot.keyPress(java.awt.event.KeyEvent.VK_CONTROL);
            robot.keyPress(java.awt.event.KeyEvent.VK_A);
            robot.keyRelease(java.awt.event.KeyEvent.VK_A);
            robot.keyRelease(java.awt.event.KeyEvent.VK_CONTROL);
            Thread.sleep(80L);
            for (final char digit : replacement.toCharArray()) {
                typeChar(robot, digit);
            }
            robot.keyPress(java.awt.event.KeyEvent.VK_ENTER);
            robot.keyRelease(java.awt.event.KeyEvent.VK_ENTER);
            for (int settle = 0; settle < 12; settle++) {
                Thread.sleep(POLL_MILLIS);
                if (!significantSequence(sample()).equals(knownSignificant)) {
                    return "edited:" + field.getClass().getName()
                        + ":attempt=" + (tried.size() + 1);
                }
            }
            tried.add(field.getClass().getSimpleName() + "@" + at[0] + "," + at[1]);
            if (tried.size() >= 4) break;
        }
        return "edited:" + tried.size() + "-fields:no-significant-entry:" + String.join("|", tried);
    }

    /** Every showing editable text component whose content already looks like a hex colour. */
    private static List<javax.swing.text.JTextComponent> colorFields() {
        final List<javax.swing.text.JTextComponent> found = new ArrayList<>();
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (window.isVisible()) collectColorFields(window, found, 0);
        }
        return found;
    }

    private static void collectColorFields(
        final java.awt.Component component,
        final List<javax.swing.text.JTextComponent> found,
        final int depth
    ) {
        if (depth > CANVAS_SCAN_DEPTH || !component.isVisible()) return;
        if (component instanceof javax.swing.text.JTextComponent text
            && text.isShowing() && text.isEnabled() && text.isEditable()) {
            final String value = text.getText();
            if (value != null && value.trim().matches("#?[0-9a-fA-F]{6}")) {
                found.add(text);
            }
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                collectColorFields(child, found, depth + 1);
            }
        }
    }

    /**
     * Presses the first ArtMesh row found in the Parts tree so the inspector palette shows
     * that drawable's property rows, colour included.
     */
    private String selectDrawableRow(final java.awt.Robot robot) throws Exception {
        final List<String> drawableNames = onEdt(() ->
            context.cubism().model().active().drawables().all().stream()
                .map(dev.turboism.sdk.cubism.model.Drawable::name)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList());
        int rows = 0;
        final ArrayList<String> tried = new ArrayList<>();
        for (final String name : drawableNames) {
            if (tried.size() >= 6) break;
            final int[] point = onEdt(
                () -> WindowsHistoryNativeUiIngressProbe.partsRowPoint(name));
            if (point == null) continue;
            rows++;
            robot.mouseMove(point[0], point[1]);
            Thread.sleep(60L);
            robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            Thread.sleep(80L);
            robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            Thread.sleep(POLL_MILLIS);
            // The SDK runtime selection projection is an unimplemented stub, so verify
            // at the widget that owns the row: a press that selected it leaves the Parts
            // table's selection on that row.
            final int selectedRow = onEdt(
                WindowsHistoryNativeUiIngressProbe::partsTableSelectedRow);
            if (selectedRow >= 0) return "row:" + name + ":tableRow=" + selectedRow;
            tried.add(name);
        }
        // A row press that did not register as a host selection (mis-hit column, focus
        // quirk) still leaves the inspector empty. Drive the tree's own selection model
        // on the EDT — the Parts palette propagates tree selection to the Editor, which
        // is what fills the inspector. This commits no undo entry.
        for (final String name : drawableNames) {
            if (tried.size() >= 8) break;
            final boolean selected = onEdt(() -> selectPartsTreeRow(name));
            if (!selected) continue;
            Thread.sleep(POLL_MILLIS);
            final int selectedRow = onEdt(
                WindowsHistoryNativeUiIngressProbe::partsTableSelectedRow);
            if (selectedRow >= 0) {
                return "tree-select:" + name + ":tableRow=" + selectedRow;
            }
            tried.add("edt:" + name);
        }
        // Fallback: a single press on the model canvas selects the drawable under the
        // cursor — that selection is what populates the inspector's property rows. It
        // commits only a selection entry, never an edit.
        final int[] canvasCentre = onEdt(() -> {
            for (final java.awt.Component canvas : canvasCandidates()) {
                final java.awt.Rectangle visible = visibleBounds(canvas);
                if (visible.isEmpty()) continue;
                final java.awt.Point centre = new java.awt.Point(
                    visible.x + visible.width / 2,
                    visible.y + visible.height / 2);
                SwingUtilities.convertPointToScreen(centre, canvas);
                return new int[] {centre.x, centre.y};
            }
            return null;
        });
        if (canvasCentre != null) {
            // Try a small grid of canvas points — the model does not cover the whole view.
            final int[][] offsets = {{0, 0}, {-60, 0}, {60, 0}, {0, -60}, {0, 60}};
            for (final int[] offset : offsets) {
                robot.mouseMove(canvasCentre[0] + offset[0], canvasCentre[1] + offset[1]);
                Thread.sleep(60L);
                robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                Thread.sleep(80L);
                robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                Thread.sleep(POLL_MILLIS);
                final int selectedRow = onEdt(
                    WindowsHistoryNativeUiIngressProbe::partsTableSelectedRow);
                if (selectedRow >= 0) {
                    return "canvas@" + (canvasCentre[0] + offset[0]) + ","
                        + (canvasCentre[1] + offset[1]) + ":tableRow=" + selectedRow;
                }
            }
            return "canvas-miss:tried-rows=" + tried;
        }
        return "none:names=" + drawableNames.size() + ":rows=" + rows
            + ":tried=" + tried + ":tree=" + onEdt(
                WindowsHistoryNativeUiIngressProbe::partsTreeLabels);
    }

    /**
     * Selects the Parts-tree row for {@code displayName} through the table/tree selection
     * models. The palette forwards tree selection to the Editor selection, which is what
     * the inspector palette renders. Runs on the EDT; returns whether a row was set.
     */
    private static boolean selectPartsTreeRow(final String displayName) {
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (!window.isVisible()) continue;
            final javax.swing.JTable table = findPartsTable(window);
            if (table == null || !table.isShowing()) continue;
            final javax.swing.JTree tree =
                WindowsMeshEditValidationProbe.extractTree(table);
            if (tree == null) continue;
            final java.util.List<javax.swing.tree.TreePath> paths =
                WindowsMeshEditValidationProbe.findTreePaths(tree, displayName);
            if (paths.size() != 1) continue;
            tree.expandPath(paths.get(0).getParentPath());
            final int row = tree.getRowForPath(paths.get(0));
            if (row < 0) continue;
            tree.setSelectionPath(paths.get(0));
            table.setRowSelectionInterval(row, row);
            table.scrollRectToVisible(table.getCellRect(row, 0, true));
            return true;
        }
        return false;
    }

    /** The Parts palette table's selected row, or {@code -1}. Runs on the EDT. */
    private static int partsTableSelectedRow() {
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (!window.isVisible()) continue;
            final javax.swing.JTable table = findPartsTable(window);
            if (table != null && table.isShowing() && table.getSelectedRow() >= 0) {
                return table.getSelectedRow();
            }
        }
        return -1;
    }

    /** A bounded sample of the labels the Parts tree actually renders. Runs on the EDT. */
    private static String partsTreeLabels() {
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (!window.isVisible()) continue;
            final javax.swing.JTable table = findPartsTable(window);
            if (table == null) continue;
            final javax.swing.JTree tree =
                WindowsMeshEditValidationProbe.extractTree(table);
            if (tree == null) continue;
            final ArrayList<String> labels = new ArrayList<>();
            final ArrayDeque<Object> pending = new ArrayDeque<>();
            pending.add(tree.getModel().getRoot());
            while (!pending.isEmpty() && labels.size() < 28) {
                final Object node = pending.removeFirst();
                labels.add(tree.convertValueToText(node, false, false, false, 0, false));
                final int children = tree.getModel().getChildCount(node);
                for (int index = 0; index < children; index++) {
                    pending.addLast(tree.getModel().getChild(node, index));
                }
            }
            return "[" + String.join(",", labels) + "]";
        }
        return "no-parts-tree";
    }

    /** Whether any component from the inspector palette package is instantiated and showing. */
    private static boolean inspectorShowing() {
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (window.isVisible() && hasPackageComponent(window, ".palette.inspector", 0)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasPackageComponent(
        final java.awt.Component component,
        final String packageMarker,
        final int depth
    ) {
        if (depth > CANVAS_SCAN_DEPTH + 4) return false;
        if (component.getClass().getName().contains(packageMarker) && component.isShowing()) {
            return true;
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                if (hasPackageComponent(child, packageMarker, depth + 1)) return true;
            }
        }
        return false;
    }

    /**
     * Small pressable components inside the inspector palette — the multiply/screen colour
     * swatches among them open the host colour editor. When no {@code .palette.inspector}
     * component exists the search falls back to the dock region that holds the currently
     * selected inspector tab: the palette contents may be built from generic classes.
     */
    private static List<java.awt.Component> colorSwatches() {
        final List<java.awt.Component> found = new ArrayList<>();
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (window.isVisible()) collectColorSwatches(window, found, false, 0);
        }
        if (found.isEmpty()) {
            final javax.swing.AbstractButton tab = selectedPaletteTab(INSPECTOR_MARKERS);
            if (tab != null) {
                // The palette content lives in a sibling cell of the same dock column, not
                // inside the tab's own cell — climb to the column (the narrow container
                // holding every docked palette) and search it, skipping the tab strips.
                java.awt.Container scope = tab.getParent();
                while (scope != null && scope.getParent() != null
                    && scope.getParent().getWidth() <= 200) {
                    scope = scope.getParent();
                }
                if (scope != null) {
                    collectColorSwatches(scope, found, true, null, 0);
                }
            }
        }
        return found;
    }

    private static void collectColorSwatches(
        final java.awt.Component component,
        final List<java.awt.Component> found,
        final boolean insideInspector,
        final int depth
    ) {
        collectColorSwatches(component, found, insideInspector, null, depth);
    }

    private static void collectColorSwatches(
        final java.awt.Component component,
        final List<java.awt.Component> found,
        final boolean insideInspector,
        final java.awt.Container exclude,
        final int depth
    ) {
        if (depth > CANVAS_SCAN_DEPTH + 4 || !component.isVisible()) return;
        if (exclude != null && component == exclude) return;
        final boolean inInspector = insideInspector
            || component.getClass().getName().contains(".palette.inspector");
        if (inInspector && component.isShowing() && component.isEnabled()
            && component.getHeight() <= 40 && component.getHeight() >= 8
            && component.getWidth() <= 120 && component.getWidth() >= 8
            // Dock tab buttons are not swatches — pressing one just switches palettes.
            && !component.getClass().getName().equals("com.live2d.ui.swingImpl.K")
            && (component instanceof javax.swing.AbstractButton
                    && !(component instanceof javax.swing.JMenuItem)
                || looksLikeSwatch(component))) {
            found.add(component);
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                collectColorSwatches(child, found, inInspector, exclude, depth + 1);
            }
        }
    }

    /**
     * Whether a component plausibly renders a colour swatch: a leaf whose background is a
     * saturated, non-grey, non-default colour.
     */
    private static boolean looksLikeSwatch(final java.awt.Component component) {
        if (component instanceof java.awt.Container container
            && container.getComponentCount() > 0) {
            return false;
        }
        final java.awt.Color bg = component.getBackground();
        if (bg == null) return false;
        final int max = Math.max(bg.getRed(), Math.max(bg.getGreen(), bg.getBlue()));
        final int min = Math.min(bg.getRed(), Math.min(bg.getGreen(), bg.getBlue()));
        return max - min >= 48;
    }

    /**
     * The inspector palette's content panel — the largest showing {@code u} container in the
     * selected inspector tab's dock column. The inspector paints its property rows onto this
     * single component, so it is the click surface for row scanning. Runs on the EDT.
     */
    /**
     * The dock column that hosts the inspector palette — the narrow container holding
     * every docked palette on that edge. Null unless the inspector tab is selected.
     */
    private static java.awt.Container inspectorDockScope() {
        return dockScopeFor(INSPECTOR_MARKERS);
    }

    /**
     * The dock column hosting the palette whose tab matches the markers — the narrow
     * container holding every docked palette on that edge. Null unless the tab is
     * selected.
     */
    private static java.awt.Container dockScopeFor(final String[] markers) {
        final javax.swing.AbstractButton tab = selectedPaletteTab(markers);
        if (tab == null) return null;
        java.awt.Container scope = tab.getParent();
        while (scope != null && scope.getParent() != null
            && scope.getParent().getWidth() <= 200) {
            scope = scope.getParent();
        }
        return scope;
    }

    /** The selected dock tab carrying one of {@code markers}, or null. Runs on the EDT. */
    private static javax.swing.AbstractButton selectedPaletteTab(final String[] markers) {
        for (final javax.swing.AbstractButton tab
            : paletteTabs(markers, new StringBuilder(1))) {
            if (tab.isSelected()) return tab;
        }
        return null;
    }

    /**
     * The sliders inside the Parameter palette's dock columns — the palette's value rows
     * are CSlider widgets mirrored as {@code swingImpl.A} JSliders. Every selected
     * parameter tab's column is searched. Runs on the EDT.
     */
    private static List<javax.swing.JSlider> parameterDockSliders() {
        final List<javax.swing.JSlider> found = new ArrayList<>();
        for (final javax.swing.AbstractButton tab
            : paletteTabs(PARAMETER_MARKERS, new StringBuilder(1))) {
            if (!tab.isSelected()) continue;
            java.awt.Container scope = tab.getParent();
            while (scope != null && scope.getParent() != null
                && scope.getParent().getWidth() <= 200) {
                scope = scope.getParent();
            }
            if (scope != null) collectSliders(scope, found, 0);
        }
        return found;
    }

    /**
     * Click surfaces over every showing {@code CSlider} CWidget inside a selected
     * parameter tab's dock column — for palettes whose rows exist only in the custom
     * widget tree, not as Swing children. Runs on the EDT.
     */
    private static List<Object> parameterCWidgetRows() {
        final List<Object> found = new ArrayList<>();
        final List<Object> backup = new ArrayList<>();
        for (final javax.swing.AbstractButton tab
            : paletteTabs(PARAMETER_MARKERS, new StringBuilder(1))) {
            if (!tab.isSelected()) continue;
            java.awt.Container scope = tab.getParent();
            while (scope != null && scope.getParent() != null
                && scope.getParent().getWidth() <= 200) {
                scope = scope.getParent();
            }
            if (scope == null || !scope.isShowing()) continue;
            final java.awt.Point origin = scope.getLocationOnScreen();
            final java.awt.Rectangle dockRect = new java.awt.Rectangle(
                origin.x, origin.y, scope.getWidth(), scope.getHeight());
            Object root = cwidgetOf(scope);
            if (root == null) root = cwidgetTop(cwidgetOf(tab));
            for (final Object widget : cwidgetTree(root)) {
                final String name = widget.getClass().getName();
                // The row's value control is a CSlidableFloat — a horizontal drag
                // across it scrubs the parameter. Other palette.parameter widgets
                // (the row surface, its value bar) qualify as weaker candidates.
                final int rank = name.endsWith("CSlidableFloat") ? 0
                    : name.endsWith("CSlider") ? 1
                    : name.contains(".palette.parameter") ? 2 : -1;
                if (rank < 0) continue;
                final java.awt.Rectangle rect = cwidgetRect(widget);
                if (rect == null || !rect.intersects(dockRect)
                    || rect.width < 24 || rect.height < 6
                    || rect.height > 60) continue;
                if (cwidgetShowing(widget)) {
                    (rank == 0 ? found : backup).add(widget);
                }
            }
        }
        found.addAll(backup);
        return found;
    }

    /**
     * The editable text component inside a CWidget's Swing mirror — e.g. a
     * CSlidableFloat's embedded editor once the row enters edit mode. Null when the
     * mirror hosts none. Runs on the EDT.
     */
    private static javax.swing.text.JTextComponent cwidgetEditor(final Object widget) {
        try {
            final Object mirror = widget.getClass().getMethod("getJComponent")
                .invoke(widget);
            if (!(mirror instanceof java.awt.Component component)) return null;
            final javax.swing.text.JTextComponent[] hit = {null};
            collectEditableText(component, hit, 0);
            return hit[0];
        } catch (final Exception e) {
            return null;
        }
    }

    private static void collectEditableText(
        final java.awt.Component component,
        final javax.swing.text.JTextComponent[] hit,
        final int depth
    ) {
        if (hit[0] != null || depth > 8 || !component.isVisible()) return;
        if (component instanceof javax.swing.text.JTextComponent text
            && text.isShowing() && text.isEnabled() && text.isEditable()) {
            hit[0] = text;
            return;
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                collectEditableText(child, hit, depth + 1);
            }
        }
    }

    /**
     * Finds the custom-widget content belonging to the selected inspector tab.
     *
     * <p>The dock column can contain two stacked panes: the upper tool-details pane and the
     * lower inspector pane. The Swing mirror exposes the whole column as one {@code u}, and the
     * first {@code CBorderPane} in its widget tree is therefore not a reliable content root. We
     * keep the selected tab's actual screen rectangle, enumerate a bounded widget tree, and only
     * accept visible candidates wholly inside the dock and below that tab. A visible-content score
     * makes the chosen candidate the panel rather than one of its small child controls.</p>
     */
    private static Object inspectorContentWidget() {
        final java.awt.Container scope = inspectorDockScope();
        final javax.swing.AbstractButton tab = selectedPaletteTab(INSPECTOR_MARKERS);
        if (scope == null || tab == null) return null;
        final java.awt.Rectangle dockRect = screenBounds(scope);
        final Object tabWidget = cwidgetOf(tab);
        final java.awt.Rectangle tabRect = tabWidget == null
            ? screenBounds(tab) : cwidgetRect(tabWidget);
        final Object root = cwidgetTop(tabWidget);
        if (dockRect == null || tabRect == null || root == null) return null;

        final List<InspectorContentCandidate> candidates = new ArrayList<>();
        for (final Object widget : cwidgetTree(root)) {
            final java.awt.Rectangle rect = cwidgetRect(widget);
            if (rect == null || rect.width < 100 || rect.height < 120) continue;
            final int contentScore = visibleInspectorContentScore(widget, tabRect, dockRect);
            if (contentScore > 0) {
                candidates.add(new InspectorContentCandidate(
                    widget, rect, cwidgetShowing(widget), contentScore));
            }
        }
        final InspectorContentCandidate selected = selectInspectorContentCandidate(
            candidates, tabRect, dockRect);
        return selected == null ? null : selected.widget();
    }

    /**
     * The smallest seam for the inspector-root rule. It deliberately uses relative widget
     * geometry, selected-tab ownership and visible content rather than a fixed screen coordinate.
     */
    static InspectorContentCandidate selectInspectorContentCandidate(
        final List<InspectorContentCandidate> candidates,
        final java.awt.Rectangle selectedTabRect,
        final java.awt.Rectangle dockRect
    ) {
        if (selectedTabRect == null || dockRect == null) return null;
        final int tabBottom = selectedTabRect.y + selectedTabRect.height;
        InspectorContentCandidate best = null;
        for (final InspectorContentCandidate candidate : candidates) {
            final java.awt.Rectangle rect = candidate.rect();
            if (candidate.widget() == null || !candidate.showing()
                || rect == null || rect.isEmpty() || candidate.contentScore() <= 0
                || !dockRect.contains(rect) || rect.y < tabBottom) {
                continue;
            }
            if (best == null || candidate.contentScore() > best.contentScore()) {
                best = candidate;
            }
        }
        return best;
    }

    private static int visibleInspectorContentScore(
        final Object widget,
        final java.awt.Rectangle selectedTabRect,
        final java.awt.Rectangle dockRect
    ) {
        final int tabBottom = selectedTabRect.y + selectedTabRect.height;
        int score = 0;
        for (final Object child : cwidgetTree(widget)) {
            final java.awt.Rectangle rect = cwidgetRect(child);
            if (rect == null || !cwidgetShowing(child) || !dockRect.contains(rect)
                || rect.y < tabBottom) {
                continue;
            }
            final String name = child.getClass().getName();
            score += name.endsWith("CColorChooserButton") || name.endsWith("CTextField")
                ? 4 : 1;
        }
        return score;
    }

    /** The selected inspector content's real screen rectangle, or null. Runs on the EDT. */
    private static java.awt.Rectangle inspectorContentBounds() {
        final Object widget = inspectorContentWidget();
        final java.awt.Rectangle rect = widget == null ? null : cwidgetRect(widget);
        return rect == null ? null : new java.awt.Rectangle(rect);
    }

    private static java.awt.Rectangle screenBounds(final java.awt.Component component) {
        if (component == null || !component.isShowing()) return null;
        try {
            final java.awt.Point origin = component.getLocationOnScreen();
            return new java.awt.Rectangle(
                origin.x, origin.y, component.getWidth(), component.getHeight());
        } catch (java.awt.IllegalComponentStateException notShowing) {
            return null;
        }
    }

    /** Candidate root from a selected dock's bounded CWidget traversal. */
    record InspectorContentCandidate(
        Object widget,
        java.awt.Rectangle rect,
        boolean showing,
        int contentScore
    ) {
    }

    /**
     * The custom {@code CWidget} behind a Swing mirror component, resolved through the
     * host's {@code com.live2d.ui.k} registry. Null when the component is not a mirror.
     */
    private static Object cwidgetOf(final java.awt.Component component) {
        if (component == null) return null;
        try {
            final Class<?> registry = Class.forName(
                "com.live2d.ui.k", true, component.getClass().getClassLoader());
            return registry.getMethod("a", java.awt.Component.class)
                .invoke(null, component);
        } catch (final Exception e) {
            return null;
        }
    }

    /** The topmost CWidget ancestor of a widget, or the widget itself. */
    private static Object cwidgetTop(final Object widget) {
        Object cursor = widget;
        while (cursor != null) {
            try {
                final Object parent = cursor.getClass().getMethod("getParent")
                    .invoke(cursor);
                if (parent == null) return cursor;
                cursor = parent;
            } catch (final Exception e) {
                return cursor;
            }
        }
        return cursor;
    }

    /** Every CWidget under the root, depth-first. Runs on the EDT. */
    private static List<Object> cwidgetTree(final Object root) {
        final List<Object> found = new ArrayList<>();
        if (root == null) return found;
        for (final boolean direction : new boolean[] {true, false}) {
            try {
                final java.util.Iterator<?> it = (java.util.Iterator<?>)
                    root.getClass().getMethod("traverse", boolean.class)
                        .invoke(root, direction);
                while (it.hasNext()) {
                    found.add(it.next());
                }
            } catch (final Exception ignored) {
            }
            if (!found.isEmpty()) break;
        }
        return found;
    }

    private static java.awt.Rectangle cwidgetRect(final Object widget) {
        try {
            final Object rect = widget.getClass().getMethod("getRectOnScreen")
                .invoke(widget);
            return (java.awt.Rectangle) rect.getClass().getMethod("getJrect")
                .invoke(rect);
        } catch (final Exception e) {
            return null;
        }
    }

    private static boolean cwidgetShowing(final Object widget) {
        try {
            return Boolean.TRUE.equals(widget.getClass()
                .getMethod("isShowing").invoke(widget));
        } catch (final Exception e) {
            return false;
        }
    }

    /**
     * A {@code CTextField}'s editable {@code JTextComponent} mirror — real Robot typing
     * into it is a native edit. Null for any other widget.
     */
    private static javax.swing.text.JTextComponent cwidgetTextComponent(
        final Object widget
    ) {
        try {
            final Object text = widget.getClass().getMethod("getJTextComponent")
                .invoke(widget);
            return text instanceof javax.swing.text.JTextComponent tc
                && tc.isShowing() && tc.isEnabled() && tc.isEditable() ? tc : null;
        } catch (final Exception e) {
            return null;
        }
    }

    private static String cwidgetText(final Object widget) {
        for (final String method : new String[] {"getText", "getName", "getToolTipText"}) {
            try {
                final Object value = widget.getClass().getMethod(method).invoke(widget);
                if (value instanceof String s && !s.isBlank()) return s;
            } catch (final Exception ignored) {
            }
        }
        return "";
    }

    /**
     * Whether a widget sits in an inspector row carrying the multiply-colour label —
     * climbs the row containers it belongs to looking for a sibling text widget with
     * the marker text.
     */
    private static boolean multiplyMarked(final Object widget) {
        Object cursor = widget;
        for (int up = 0; cursor != null && up < 5; up++) {
            try {
                final Object parent = cursor.getClass().getMethod("getParent")
                    .invoke(cursor);
                if (parent == null) return false;
                final Object children = parent.getClass().getMethod("getChildren")
                    .invoke(parent);
                if (children instanceof List<?> siblings) {
                    for (final Object sibling : siblings) {
                        if (cwidgetText(sibling)
                            .matches(".*(正片叠底|乗算|乘算|[Mm]ultiply).*")) {
                            return true;
                        }
                    }
                }
                cursor = parent;
            } catch (final Exception e) {
                return false;
            }
        }
        return false;
    }

    /**
     * A click-surface pseudo-component over the CWidget's on-screen rect so the Robot
     * can press controls that have no visible Swing mirror of their own.
     */
    private static java.awt.Component cwidgetSurface(final Object widget) {
        if (!cwidgetShowing(widget)) return null;
        final java.awt.Rectangle rect = cwidgetRect(widget);
        if (rect == null || rect.isEmpty()) return null;
        return new java.awt.Component() {
            @Override public boolean isShowing() { return true; }
            @Override public java.awt.Point getLocationOnScreen() {
                return rect.getLocation();
            }
            @Override public int getWidth() { return rect.width; }
            @Override public int getHeight() { return rect.height; }
        };
    }

    /** A compact census of a CWidget subtree for diagnostics. */
    private static String cwidgetCensus(final List<Object> widgets) {
        return cwidgetCensus(widgets, 560);
    }

    private static String cwidgetCensus(final List<Object> widgets, final int cap) {
        final StringBuilder out = new StringBuilder(cap + 64);
        for (final Object widget : widgets) {
            if (out.length() > cap) {
                out.append("...");
                break;
            }
            out.append(widget.getClass().getSimpleName());
            final java.awt.Rectangle rect = cwidgetRect(widget);
            if (rect != null) {
                out.append('@').append(rect.x).append(',').append(rect.y)
                    .append(' ').append(rect.width).append('x').append(rect.height);
            }
            if (!cwidgetShowing(widget)) out.append(":hidden");
            final String text = cwidgetText(widget);
            if (!text.isBlank()) out.append('[').append(text).append(']');
            out.append(';');
        }
        return out.toString();
    }

    /**
     * Captures the inspector content panel's screen pixels into the evidence stream as a
     * base64 PNG so a failed colour step shows what the palette actually rendered.
     */
    private void dumpRegionScreenshot(final java.awt.Robot robot, final String name) {
        try {
            final java.awt.Rectangle rect = onEdt(
                WindowsHistoryNativeUiIngressProbe::inspectorContentBounds);
            if (rect == null || rect.width < 4 || rect.height < 4) return;
            final java.awt.Rectangle clipped = rect.intersection(
                new java.awt.Rectangle(
                    java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getMaximumWindowBounds()));
            if (clipped.width < 4 || clipped.height < 4) return;
            final java.awt.image.BufferedImage image = robot.createScreenCapture(clipped);
            final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", out);
            final byte[] png = out.toByteArray();
            write(artifact, "{\"type\":\"screenshot\",\"region\":" + quoted(name)
                + ",\"png\":\"" + java.util.Base64.getEncoder().encodeToString(png)
                + "\"}\n", false);
        } catch (Throwable ignored) {
            // Diagnostics must never fail the step.
        }
    }

    private void dumpWindowScreenshot(
        final java.awt.Robot robot,
        final java.awt.Window window,
        final String name
    ) {
        try {
            final java.awt.Rectangle rect = onEdt(() -> {
                if (!window.isShowing()) return null;
                return new java.awt.Rectangle(
                    window.getX(), window.getY(), window.getWidth(), window.getHeight());
            });
            if (rect == null || rect.width < 4 || rect.height < 4) return;
            final java.awt.Rectangle clipped = rect.intersection(new java.awt.Rectangle(
                java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getMaximumWindowBounds()));
            if (clipped.width < 4 || clipped.height < 4) return;
            rect.setBounds(clipped);
            final java.awt.image.BufferedImage image = robot.createScreenCapture(rect);
            final java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", png);
            write(artifact, "{\"type\":\"screenshot\",\"region\":" + quoted(name)
                + ",\"width\":" + rect.width + ",\"height\":" + rect.height
                + ",\"png\":\"" + java.util.Base64.getEncoder().encodeToString(png.toByteArray())
                + "\"}\n", false);
        } catch (Throwable ignored) {
            // Diagnostics must never fail the step.
        }
    }

    /**
     * Scans a screen rectangle for saturated colour blocks — the centres of small regions
     * whose pixels stay vividly coloured are the inspector's painted colour swatches.
     */
    private static List<int[]> saturatedBlocks(
        final java.awt.Robot robot,
        final int[] bounds
    ) {
        final List<int[]> points = new ArrayList<>();
        int lastY = -100;
        for (int y = bounds[1] + 6; y < bounds[1] + bounds[3] - 6; y += 4) {
            int runStart = -1;
            int runEnd = -1;
            for (int x = bounds[0] + 6; x < bounds[0] + bounds[2] - 6; x += 3) {
                final java.awt.Color pixel = robot.getPixelColor(x, y);
                final int max = Math.max(pixel.getRed(),
                    Math.max(pixel.getGreen(), pixel.getBlue()));
                final int min = Math.min(pixel.getRed(),
                    Math.min(pixel.getGreen(), pixel.getBlue()));
                if (max - min >= 64 && max >= 90) {
                    if (runStart < 0) runStart = x;
                    runEnd = x;
                } else if (runStart >= 0) {
                    if (runEnd - runStart >= 8 && runEnd - runStart <= 60
                        && y - lastY > 18) {
                        points.add(new int[] {(runStart + runEnd) / 2, y});
                        lastY = y;
                    }
                    runStart = -1;
                }
            }
            if (runStart >= 0 && runEnd - runStart >= 8 && runEnd - runStart <= 60
                && y - lastY > 18) {
                points.add(new int[] {(runStart + runEnd) / 2, y});
                lastY = y;
            }
        }
        return points;
    }

    /**
     * A census of leaf components inside the selected inspector tab's dock region — class,
     * bounds and background — so a missed swatch shows what the palette actually contains.
     * Runs on the EDT.
     */
    private static String inspectorLeafCensus() {
        final javax.swing.AbstractButton tab = selectedPaletteTab(INSPECTOR_MARKERS);
        if (tab == null) return "no-tab";
        java.awt.Container scope = tab.getParent();
        while (scope != null && scope.getParent() != null
            && scope.getParent().getWidth() <= 200) {
            scope = scope.getParent();
        }
        if (scope == null) return "no-scope";
        final StringBuilder out = new StringBuilder(1200);
        collectLeafCensus(scope, out, null, 0);
        return out.length() > 0 ? out.toString() : "empty";
    }

    private static void collectLeafCensus(
        final java.awt.Component component,
        final StringBuilder out,
        final java.awt.Container exclude,
        final int depth
    ) {
        if (depth > CANVAS_SCAN_DEPTH + 4 || !component.isVisible()
            || out.length() > 1100) return;
        if (exclude != null && component == exclude) return;
        if (!(component instanceof java.awt.Container container)
            || container.getComponentCount() == 0) {
            final java.awt.Color bg = component.getBackground();
            out.append(component.getClass().getSimpleName())
                .append('[').append(component.getWidth()).append('x')
                .append(component.getHeight()).append(']');
            if (bg != null) {
                out.append('#').append(Integer.toHexString(bg.getRGB() & 0xFFFFFF));
            }
            out.append(';');
            return;
        }
        for (java.awt.Component child : container.getComponents()) {
            collectLeafCensus(child, out, exclude, depth + 1);
        }
    }

    private static java.util.Set<java.awt.Window> visibleWindows() {
        final java.util.Set<java.awt.Window> set = new java.util.HashSet<>();
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (window.isVisible()) set.add(window);
        }
        return set;
    }

    private static java.awt.Window newWindowSince(final java.util.Set<java.awt.Window> before) {
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (window.isVisible() && !before.contains(window)) return window;
        }
        return null;
    }

    /**
     * Edits one editable text field inside a just-opened dialog and confirms it.
     *
     * <p>The replacement adapts to the field's current content: a hex colour gets another
     * hex colour, an integer channel gets a shifted value, anything else is skipped. The
     * dialog is then confirmed with Enter, and when that does not move the significant
     * sequence, with the first OK-looking button.
     *
     * @return a short note for the actor result, or {@code null} when nothing committed
     */
    private String editDialogField(
        final java.awt.Robot robot,
        final java.awt.Window dialog,
        final String knownSignificant,
        final StringBuilder note
    ) throws Exception {
        final List<javax.swing.text.JTextComponent> fields = onEdt(() -> {
            final List<javax.swing.text.JTextComponent> found = new ArrayList<>();
            collectEditableFields(dialog, found, 0);
            return found;
        });
        final ArrayList<String> tried = new ArrayList<>();
        for (final javax.swing.text.JTextComponent field : fields) {
            if (tried.size() >= 4) break;
            final int[] at = onEdt(() -> fieldCentre(field));
            if (at == null) continue;
            final String previous = onEdt(field::getText);
            final String replacement = replacementValue(previous);
            if (replacement == null) continue;
            tried.add((previous == null ? "?" : previous.trim()) + "->" + replacement);
            note.append(previous == null ? "?" : previous.trim())
                .append("->").append(replacement).append(';');
            robot.mouseMove(at[0], at[1]);
            robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            Thread.sleep(150L);
            robot.keyPress(java.awt.event.KeyEvent.VK_CONTROL);
            robot.keyPress(java.awt.event.KeyEvent.VK_A);
            robot.keyRelease(java.awt.event.KeyEvent.VK_A);
            robot.keyRelease(java.awt.event.KeyEvent.VK_CONTROL);
            Thread.sleep(80L);
            for (final char digit : replacement.toCharArray()) {
                typeChar(robot, digit);
            }
            robot.keyPress(java.awt.event.KeyEvent.VK_ENTER);
            robot.keyRelease(java.awt.event.KeyEvent.VK_ENTER);
            for (int settle = 0; settle < 10; settle++) {
                Thread.sleep(POLL_MILLIS);
                if (!significantSequence(sample()).equals(knownSignificant)) {
                    return "field:" + replacement;
                }
            }
            // Enter alone may only apply the field — the dialog still needs its OK button.
            final int[] ok = onEdt(() -> confirmPoint(dialog));
            if (ok != null) {
                robot.mouseMove(ok[0], ok[1]);
                robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                for (int settle = 0; settle < 10; settle++) {
                    Thread.sleep(POLL_MILLIS);
                    if (!significantSequence(sample()).equals(knownSignificant)) {
                        return "field-ok:" + replacement;
                    }
                }
            }
        }
        if (fields.isEmpty()) note.append("no-editable-fields;");
        return null;
    }

    /** A plausible replacement for a colour-editor field's current text, or null. */
    private static String replacementValue(final String previous) {
        if (previous == null) return null;
        final String value = previous.trim();
        if (value.matches("#?[0-9a-fA-F]{6}")) {
            return value.contains("33") ? "2244CC" : "CC4433";
        }
        if (value.matches("\\d{1,3}")) {
            final int channel = Integer.parseInt(value);
            return String.valueOf((channel + 96) % 256);
        }
        if (value.matches("\\d+\\.\\d+")) {
            return value.startsWith("0.5") ? "0.7" : "0.5";
        }
        return null;
    }

    /** Every showing editable text component inside one window. */
    private static void collectEditableFields(
        final java.awt.Component component,
        final List<javax.swing.text.JTextComponent> found,
        final int depth
    ) {
        if (depth > CANVAS_SCAN_DEPTH || !component.isVisible()) return;
        if (component instanceof javax.swing.text.JTextComponent text
            && text.isShowing() && text.isEnabled() && text.isEditable()) {
            found.add(text);
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                collectEditableFields(child, found, depth + 1);
            }
        }
    }

    /** The screen centre of a dialog's OK-looking button, or null. Runs on the EDT. */
    private static int[] confirmPoint(final java.awt.Window dialog) {
        final javax.swing.AbstractButton[] found = {null};
        findConfirmButton(dialog, found, 0);
        return found[0] == null ? null : fieldCentre(found[0]);
    }

    private static final String[] CONFIRM_MARKERS =
        {"OK", "确定", "適用", "适用", "Apply"};

    private static void findConfirmButton(
        final java.awt.Component component,
        final javax.swing.AbstractButton[] found,
        final int depth
    ) {
        if (depth > CANVAS_SCAN_DEPTH || !component.isVisible() || found[0] != null) return;
        if (component instanceof javax.swing.AbstractButton button
            && button.isShowing() && button.isEnabled() && button.getText() != null
            && containsAny(button.getText(), CONFIRM_MARKERS)) {
            found[0] = button;
            return;
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                findConfirmButton(child, found, depth + 1);
            }
        }
    }

    private static int visibleWindowCount() {
        int count = 0;
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (window.isVisible()) count++;
        }
        return count;
    }

    /** The screen centre of a showing component, or {@code null} when it is not showing. */
    private static int[] fieldCentre(final java.awt.Component component) {
        if (!component.isShowing()) return null;
        try {
            final java.awt.Point origin = component.getLocationOnScreen();
            return new int[] {
                origin.x + component.getWidth() / 2,
                origin.y + component.getHeight() / 2
            };
        } catch (java.awt.IllegalComponentStateException notShowing) {
            return null;
        }
    }

    /** Types one ASCII hex character through the Robot. */
    private static void typeChar(final java.awt.Robot robot, final char digit) throws Exception {
        final int key = switch (digit) {
            case '0' -> java.awt.event.KeyEvent.VK_0;
            case '1' -> java.awt.event.KeyEvent.VK_1;
            case '2' -> java.awt.event.KeyEvent.VK_2;
            case '3' -> java.awt.event.KeyEvent.VK_3;
            case '4' -> java.awt.event.KeyEvent.VK_4;
            case '5' -> java.awt.event.KeyEvent.VK_5;
            case '6' -> java.awt.event.KeyEvent.VK_6;
            case '7' -> java.awt.event.KeyEvent.VK_7;
            case '8' -> java.awt.event.KeyEvent.VK_8;
            case '9' -> java.awt.event.KeyEvent.VK_9;
            default -> java.awt.event.KeyEvent.VK_A + (Character.toUpperCase(digit) - 'A');
        };
        robot.keyPress(key);
        robot.keyRelease(key);
        Thread.sleep(25L);
    }

    /**
     * The rectangle of a component that is actually on screen.
     *
     * <p>A {@code JComponent} inside a viewport can extend far beyond what is rendered; pressing
     * at the bounds centre would land on whatever happens to sit at that screen point. Plain
     * heavyweight components have no such clipping, so their bounds are the visible region.</p>
     */
    private static java.awt.Rectangle visibleBounds(final java.awt.Component component) {
        if (component instanceof javax.swing.JComponent swing) return swing.getVisibleRect();
        return new java.awt.Rectangle(0, 0, component.getWidth(), component.getHeight());
    }

    /**
     * Sends one native Ctrl+key shortcut exactly as the operator would: the enabled menu
     * accelerator first, then a focused-window Robot keystroke only when every post-menu history
     * position sample was available and unchanged. Unknown evidence is recorded as unresolved so
     * a menu action can never be repeated merely because the probe could not observe it.
     */
    private String shortcut(
        final int key,
        final long knownPosition,
        final String kind
    ) throws Exception {
        String route = "none";
        primeMenus();
        if (Boolean.TRUE.equals(onEdt(() -> menuShortcut(key)))) {
            route = "menu-accelerator";
            if (knownPosition < 0L) {
                return route + ":unresolved:unknown-position";
            }
            // Undo/Redo do not change the entry sequence; their only reliable delivery signal is
            // the cursor moving. Once it moves, even in the wrong direction, do not send Robot a
            // second shortcut and risk applying two native actions.
            final List<Long> positionSamples = new ArrayList<>();
            for (int poll = 0; poll < 10; poll++) {
                Thread.sleep(POLL_MILLIS);
                final WindowsHistoryManagerValidationProbe.Snapshot current = sample();
                positionSamples.add(current == null ? null : position(current));
                final ShortcutResolution resolution = shortcutResolution(
                    kind, knownPosition, positionSamples);
                if (resolution == ShortcutResolution.DELIVERED) return route;
                if (resolution == ShortcutResolution.WRONG_DIRECTION) {
                    return route + ":wrong-direction";
                }
            }
            final ShortcutResolution resolution = shortcutResolution(
                kind, knownPosition, positionSamples);
            if (resolution != ShortcutResolution.FALLBACK) {
                return route + ":unresolved:" + switch (resolution) {
                    case UNKNOWN -> "unknown-position";
                    case UNAVAILABLE -> "history-position-unavailable";
                    default -> "history-position-unresolved";
                };
            }
        }
        final java.awt.Robot robot = new java.awt.Robot();
        // No focusing click: a press on the model canvas is itself recorded as a drag-select
        // undo entry, which the shortcut would then consume instead of the operator's edit.
        Thread.sleep(300L);
        robot.keyPress(java.awt.event.KeyEvent.VK_CONTROL);
        robot.keyPress(key);
        robot.keyRelease(key);
        robot.keyRelease(java.awt.event.KeyEvent.VK_CONTROL);
        return route.equals("none") ? "robot" : route + "+robot";
    }

    /**
     * Opens every top menu briefly so the mirror items bind their real actions — they do so
     * lazily on menu open, and a {@code doClick} before that is a silent no-op.
     */
    private void primeMenus() throws Exception {
        final javax.swing.JMenuBar bar = onEdt(() -> {
            for (java.awt.Frame frame : java.awt.Frame.getFrames()) {
                if (frame instanceof javax.swing.JFrame swingFrame && frame.isVisible()
                    && swingFrame.getJMenuBar() != null) {
                    return swingFrame.getJMenuBar();
                }
            }
            return null;
        });
        if (bar == null || primedMenus) return;
        onEdt(() -> {
            for (int index = 0; index < bar.getMenuCount(); index++) {
                final javax.swing.JMenu top = bar.getMenu(index);
                if (top != null) top.setPopupMenuVisible(true);
            }
            return true;
        });
        Thread.sleep(400L);
        onEdt(() -> {
            javax.swing.MenuSelectionManager.defaultManager().clearSelectedPath();
            for (int index = 0; index < bar.getMenuCount(); index++) {
                final javax.swing.JMenu top = bar.getMenu(index);
                if (top != null) top.setPopupMenuVisible(false);
            }
            return true;
        });
        Thread.sleep(150L);
        primedMenus = true;
    }

    private volatile boolean primedMenus;

    /** Clicks the enabled menu item carrying a Ctrl+key accelerator, when one exists. */
    private static boolean menuShortcut(final int key) {
        for (java.awt.Frame frame : java.awt.Frame.getFrames()) {
            if (!(frame instanceof javax.swing.JFrame swingFrame) || !frame.isVisible()) continue;
            final javax.swing.JMenuBar bar = swingFrame.getJMenuBar();
            if (bar == null) continue;
            for (int index = 0; index < bar.getMenuCount(); index++) {
                final javax.swing.JMenuItem match = findMenuShortcut(bar.getMenu(index), key);
                if (match != null && match.isEnabled()) {
                    // The real action lives on the host's CMenuItem — resolve the mirror
                    // through the component→widget registry before falling back to the
                    // mirror's own doClick, which can be a silent no-op.
                    final Object widget = widgetOf(match, new StringBuilder(1));
                    if (widget != null) {
                        try {
                            widget.getClass().getMethod("doClick").invoke(widget);
                            return true;
                        } catch (Exception ignored) {
                            // fall through to the mirror click
                        }
                    }
                    match.doClick(0);
                    return true;
                }
            }
        }
        return false;
    }

    private static javax.swing.JMenuItem findMenuShortcut(
        final javax.swing.JMenuItem item,
        final int key
    ) {
        if (item == null) return null;
        final javax.swing.KeyStroke accelerator = item.getAccelerator();
        if (accelerator != null && accelerator.getKeyCode() == key
            && (accelerator.getModifiers() & java.awt.event.InputEvent.CTRL_DOWN_MASK) != 0) {
            return item;
        }
        if (item instanceof javax.swing.JMenu menu) {
            for (java.awt.Component component : menu.getMenuComponents()) {
                if (component instanceof javax.swing.JMenuItem child) {
                    final javax.swing.JMenuItem found = findMenuShortcut(child, key);
                    if (found != null) return found;
                }
            }
        }
        return null;
    }

    /** One Robot drag between two screen points, interpolated so the host sees real motion. */
    private static void robotDrag(
        final int fromX,
        final int fromY,
        final int toX,
        final int toY
    ) throws Exception {
        final java.awt.Robot robot = new java.awt.Robot();
        robot.mouseMove(fromX, fromY);
        Thread.sleep(120L);
        robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
        Thread.sleep(180L);
        final int segments = 12;
        for (int segment = 1; segment <= segments; segment++) {
            robot.mouseMove(
                fromX + (toX - fromX) * segment / segments,
                fromY + (toY - fromY) * segment / segments
            );
            Thread.sleep(35L);
        }
        Thread.sleep(180L);
        robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
    }

    /** The visible Cubism document window, preferring the one whose title names the model. */
    private static java.awt.Frame hostFrame() {
        java.awt.Frame fallback = null;
        for (java.awt.Frame frame : java.awt.Frame.getFrames()) {
            if (!frame.isVisible()) continue;
            final String title = frame.getTitle();
            if (title != null && title.contains(".cmo3")) return frame;
            if (title != null && title.contains("Cubism")) fallback = frame;
            if (fallback == null) fallback = frame;
        }
        return fallback;
    }

    /**
     * The components most likely to be the model canvas, best first.
     *
     * <p>The document view does not have to live in a {@code Frame} — the r11 run showed the
     * model window is a {@code JDialog} — so discovery scans every visible window. A class whose
     * name carries a canvas/GL marker wins over the structural fallback, which keeps the largest
     * showing leaves that are not interactive controls; a press inside a palette button or a
     * slider would prove the wrong family, so those leaves never qualify.</p>
     */
    private static List<java.awt.Component> canvasCandidates() {
        final List<java.awt.Component> named = new ArrayList<>();
        final List<java.awt.Component> regions = new ArrayList<>();
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (window.isVisible()) collectCanvas(window, named, regions, 0);
        }
        regions.sort((first, second) -> Long.compare(
            (long) second.getWidth() * second.getHeight(),
            (long) first.getWidth() * first.getHeight()));
        // A region whose centre resolves to a host-class component is the model view itself —
        // press those first; a region whose centre resolves to a control is refused entirely so
        // its remaining fractions can never press a button the panel happens to wrap. Palette
        // regions are demoted below the document view: the r20 run showed the Parts palette's
        // inner cell component winning the host-class bucket and turning a "canvas move" into a
        // Parts drag.
        final List<java.awt.Component> hostHits = new ArrayList<>();
        final List<java.awt.Component> palettes = new ArrayList<>();
        final List<java.awt.Component> plain = new ArrayList<>();
        for (final java.awt.Component region : regions) {
            final String hit = pressTarget(
                region, new java.awt.Point(region.getWidth() / 2, region.getHeight() / 2));
            if (hit == null) continue;
            if (hit.contains(".palette.") || region.getClass().getName().contains(".palette.")) {
                palettes.add(region);
            } else if (isHostClassName(hit)) {
                hostHits.add(region);
            } else {
                plain.add(region);
            }
        }
        final List<java.awt.Component> candidates = new ArrayList<>(named);
        final java.util.Set<java.awt.Rectangle> seen = new java.util.HashSet<>();
        for (final java.awt.Component candidate : named) {
            final java.awt.Rectangle rect = screenRect(candidate);
            if (rect != null) seen.add(rect);
        }
        for (final java.awt.Component region : concat(hostHits, concat(palettes, plain))) {
            if (candidates.size() >= MAX_CANVAS_CANDIDATES) break;
            if (candidates.contains(region)) continue;
            // Two nested panels often cover the same pixels; one press point per region keeps the
            // bounded attempts spread across distinct screen areas instead of repeating a drag.
            final java.awt.Rectangle rect = screenRect(region);
            if (rect == null || !seen.add(rect)) continue;
            candidates.add(region);
        }
        return candidates;
    }

    private static boolean isHostClassName(final String className) {
        return !className.startsWith("javax.swing.")
            && !className.startsWith("java.awt.")
            && !className.startsWith("sun.")
            && !className.startsWith("com.formdev.flatlaf.");
    }

    private static List<java.awt.Component> concat(
        final List<java.awt.Component> first,
        final List<java.awt.Component> second
    ) {
        final List<java.awt.Component> all = new ArrayList<>(first.size() + second.size());
        all.addAll(first);
        all.addAll(second);
        return all;
    }

    /** The component's on-screen rectangle, or {@code null} when it is not showing. */
    private static java.awt.Rectangle screenRect(final java.awt.Component component) {
        if (!component.isShowing()) return null;
        try {
            final java.awt.Point origin = component.getLocationOnScreen();
            return new java.awt.Rectangle(
                origin.x, origin.y, component.getWidth(), component.getHeight());
        } catch (java.awt.IllegalComponentStateException notShowing) {
            return null;
        }
    }

    /**
     * Whether a press on this component is allowed to reach the host.
     *
     * <p>A candidate only supplies a screen region — the Robot press lands on whatever component
     * the host's own hit-testing finds. Interactive controls are still refused at the point level
     * by {@link #pressTarget}, so a container whose centre resolves to the GL view is a safe drag
     * surface even though it is not a leaf.</p>
     */
    private static boolean isExcludedControl(final java.awt.Component component) {
        return component instanceof javax.swing.AbstractButton
            || component instanceof javax.swing.JSlider
            || component instanceof javax.swing.JComboBox<?>
            || component instanceof javax.swing.text.JTextComponent
            || component instanceof javax.swing.JSpinner
            || component instanceof java.awt.Scrollbar;
    }

    /**
     * The component a press at {@code local} would actually reach, or {@code null} when the point
     * is refused.
     *
     * <p>Refused means the deepest showing component at the point is an interactive control, or a
     * sliver thinner than a drag target — the pane dividers are a few pixels wide and pressing one
     * would resize the dock layout instead of moving the model.</p>
     */
    private static String pressTarget(final java.awt.Component canvas, final java.awt.Point local) {
        java.awt.Component hit = javax.swing.SwingUtilities.getDeepestComponentAt(
            canvas, local.x, local.y);
        if (hit == null) hit = canvas;
        if (isExcludedControl(hit)) return null;
        if (hit.getWidth() < 8 || hit.getHeight() < 8) return null;
        return hit.getClass().getName();
    }

    /**
     * A bounded report of why every plausible canvas leaf was rejected.
     *
     * <p>Only consulted when discovery came back empty: it walks the same windows and records
     * each leaf that was large enough to matter along with the check that excluded it, so an
     * empty candidate list explains itself in the artifact.</p>
     */
    private static String canvasRejects() {
        final StringBuilder out = new StringBuilder(4096);
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (!window.isVisible()) continue;
            out.append("W:").append(window.getClass().getSimpleName())
                .append(":showing=").append(window.isShowing()).append(';');
            collectRejects(window, out, 0);
        }
        return out.length() > 0 ? out.substring(0, Math.min(out.length(), 4000)) : "no-windows";
    }

    private static void collectRejects(
        final java.awt.Component component,
        final StringBuilder out,
        final int depth
    ) {
        if (depth > CANVAS_SCAN_DEPTH || !component.isVisible() || out.length() > 3800) return;
        boolean visibleLeaf = true;
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                if (child.isShowing()) {
                    visibleLeaf = false;
                    break;
                }
            }
        }
        if (component.getWidth() >= MIN_CANVAS_EDGE
            && component.getHeight() >= MIN_CANVAS_EDGE) {
            final String reason = !component.isShowing() ? "not-showing"
                : isExcludedControl(component) ? "control"
                : !visibleLeaf ? "container"
                : "eligible";
            out.append(component.getClass().getName())
                .append('[').append(component.getWidth()).append('x')
                .append(component.getHeight()).append(']')
                .append('@').append(depth)
                .append('=').append(reason).append(';');
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                collectRejects(child, out, depth + 1);
            }
        }
    }

    private static void collectCanvas(
        final java.awt.Component component,
        final List<java.awt.Component> named,
        final List<java.awt.Component> regions,
        final int depth
    ) {
        if (depth > CANVAS_SCAN_DEPTH || !component.isVisible()) return;
        final String className = component.getClass().getName();
        // Only a host/JOGL class may win by name: a Swing widget whose name happens to contain a
        // canvas/GL marker (JToggleButton carries "gl", JViewport carries "View") would be
        // dragged like the canvas and could toggle a mode instead of moving the model. A plain
        // java.awt.Canvas stays eligible because a heavyweight GL surface is exactly that class.
        // The marker is matched on the simple name, not the package — the r21 run promoted
        // CPartsTreeTable.g into this bucket because every com.live2d.cubism.view.* class carries
        // the ".view." package segment.
        final boolean hostClass = component instanceof java.awt.Canvas
            || (!className.startsWith("javax.swing.")
                && !className.startsWith("java.awt.")
                && !className.startsWith("sun."));
        if (component.isShowing() && hostClass && !className.contains(".palette.")
            && component.getClass().getSimpleName()
                .matches(".*[cC]anvas.*|.*[gG][lL].*|.*[vV]iew.*")
            && !named.contains(component)) {
            named.add(component);
        }
        // A drag surface only has to mark a plausible press region: the r19 run showed the model
        // view's ancestors rejecting the leaf rule while the GL surface itself sat deeper than
        // the old scan reached. Every showing non-control component above the minimum area is a
        // region candidate; {@link #pressTarget} refuses any point that resolves to a control or
        // a divider sliver, so a container entry can never press a button it happens to contain.
        if (component.isShowing()
            && component.getWidth() >= MIN_CANVAS_EDGE && component.getHeight() >= MIN_CANVAS_EDGE
            && !isExcludedControl(component)
            && !(component instanceof java.awt.Window)) {
            regions.add(component);
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                collectCanvas(child, named, regions, depth + 1);
            }
        }
    }

    /**
     * A bounded dump of the visible component tree, written once per automated run.
     *
     * <p>The map exists for actor review: it records which windows, trees, tables, buttons and
     * sliders were actually present, so a future step can be targeted at a real control instead
     * of a guessed one. Class names and bounds only — no text longer than the existing label
     * bound, and nothing a label-only evidence rule would not already carry.</p>
     */
    private String uiMap() throws Exception {
        return onEdt(() -> {
            final StringBuilder out = new StringBuilder(UI_MAP_MAX_CHARS);
            final int[] seen = {0};
            int windows = 0;
            for (java.awt.Window window : java.awt.Window.getWindows()) {
                if (!window.isVisible()) continue;
                if (windows >= UI_MAP_MAX_WINDOWS || seen[0] >= UI_MAP_MAX_COMPONENTS) break;
                windows++;
                out.append("W:").append(window.getClass().getSimpleName())
                    .append('(')
                    .append(WindowsHistoryManagerValidationProbe.boundedLabel(window.getName()))
                    .append(')')
                    .append(boundsOf(window)).append('\n');
                mapComponent(window, 0, out, seen);
            }
            return out.toString();
        });
    }

    private static void mapComponent(
        final java.awt.Component component,
        final int depth,
        final StringBuilder out,
        final int[] seen
    ) {
        if (seen[0] >= UI_MAP_MAX_COMPONENTS || depth > UI_MAP_MAX_DEPTH
            || out.length() >= UI_MAP_MAX_CHARS - 256) {
            return;
        }
        seen[0]++;
        out.append("  ".repeat(Math.min(depth, 16)))
            .append(component.getClass().getName())
            .append(boundsOf(component));
        if (!component.isShowing()) out.append(":hidden");
        if (component instanceof javax.swing.JTree tree) {
            out.append(":rows=").append(tree.getRowCount());
        } else if (component instanceof javax.swing.JTable table) {
            out.append(":rows=").append(table.getRowCount())
                .append("x").append(table.getColumnCount());
        } else if (component instanceof javax.swing.AbstractButton button) {
            out.append(":text=")
                .append(WindowsHistoryManagerValidationProbe.boundedLabel(button.getText()));
        } else if (component instanceof javax.swing.JSlider slider) {
            out.append(":range=").append(slider.getMinimum())
                .append('-').append(slider.getMaximum())
                .append('=').append(slider.getValue());
        } else if (component instanceof javax.swing.JComboBox<?> combo) {
            out.append(":items=").append(combo.getItemCount());
        }
        out.append('\n');
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                mapComponent(child, depth + 1, out, seen);
            }
        }
    }

    private static String boundsOf(final java.awt.Component component) {
        try {
            final java.awt.Point origin = component.isShowing()
                ? component.getLocationOnScreen()
                : new java.awt.Point(0, 0);
            return "[" + origin.x + ',' + origin.y + ','
                + component.getWidth() + 'x' + component.getHeight() + ']';
        } catch (java.awt.IllegalComponentStateException notShowing) {
            return "[hidden]";
        }
    }

    /**
     * Runs one callable on the host event thread with a bound.
     *
     * <p>Same discipline as {@link #sample()}: the wait has to end even when the host thread has
     * stopped pumping, because actor discovery must never park the worker past the step
     * deadline.</p>
     */
    private static <T> T onEdt(final java.util.concurrent.Callable<T> call) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return call.call();
        final AtomicReference<T> result = new AtomicReference<>();
        final AtomicReference<Exception> failure = new AtomicReference<>();
        final CountDownLatch completed = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            try {
                result.set(call.call());
            } catch (Exception exception) {
                failure.set(exception);
            } finally {
                completed.countDown();
            }
        });
        if (!completed.await(SAMPLE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("Cubism EDT did not accept the action in time");
        }
        if (failure.get() != null) throw failure.get();
        return result.get();
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
     * <p>An action must have a hook-presence {@code before} event and at least one complete
     * host-UI confirmation pair. The generic {@code before} event is deliberately not associated
     * with a confirmation pair: native before and confirmed events may carry different sequences.
     * A native Undo or Redo is not an edit entry at all, so it must produce no {@code before} event
     * and exactly one confirmation pair whose operation and origin both identify the navigation.</p>
     */
    static Verdict checkStep(final Step step, final List<Observed> events) {
        final List<Observed> safeEvents = events == null ? List.of() : events;
        final long before = countPhase(safeEvents, "before");
        final long confirmed = safeEvents.stream()
            .filter(event -> event != null
                && ("on".equals(event.phase()) || "after".equals(event.phase())))
            .count();
        final String seen = "before=" + before + ",confirmed=" + confirmed
            + ",events=" + safeEvents.size();
        if (step == null || !supportedStepKind(step.kind())) {
            return new Verdict(false, "unknown-step-kind", seen);
        }
        if ("ACTION".equals(step.kind())) {
            if (before == 0) return new Verdict(false, "no-before-event", seen);
        } else if (before != 0) {
            return new Verdict(false, "navigation-was-announced-as-an-edit", seen);
        }
        final ConfirmationCheck confirmations = checkConfirmations(safeEvents);
        if (!confirmations.valid()) {
            return new Verdict(false, confirmations.code(), seen);
        }
        if ("ACTION".equals(step.kind())) {
            if (confirmed == 0 || confirmations.pairs().isEmpty()) {
                return new Verdict(false, "no-confirmed-event", seen);
            }
            if (confirmations.pairs().stream()
                .anyMatch(pair -> !"HOST_UI".equals(pair.on().origin()))) {
                return new Verdict(false, "confirmation-not-host-ui", seen);
            }
            return new Verdict(true, "action-hook-and-host-ui-pair-observed", seen);
        }
        if (confirmations.pairs().isEmpty()) {
            return new Verdict(false, "navigation-not-attributed", seen);
        }
        if (confirmations.pairs().size() != 1 || safeEvents.size() != 2) {
            return new Verdict(false, "navigation-requires-one-confirmation-pair", seen);
        }
        final ConfirmationPair pair = confirmations.pairs().get(0);
        if (!step.kind().equals(pair.on().operation())
            || !step.kind().equals(pair.on().origin())) {
            return new Verdict(false, "navigation-not-attributed", seen);
        }
        return new Verdict(true, "navigation-pair-confirmed-without-an-edit", seen);
    }

    /**
     * Checks the typed SDK history delta for the Parts-tree semantic claim.
     *
     * <p>This is deliberately independent of {@link #checkStep(Step, List)}. The event check proves
     * only that the native UI ingress carried a complete event pair; this check proves only that a
     * newly admitted, applied SDK entry contains one complete Part-membership relation. In
     * particular, two one-sided native entries are never coalesced here.</p>
     */
    static Verdict checkPartMembershipStep(
        final WindowsHistoryManagerValidationProbe.SdkHistorySnapshot before,
        final WindowsHistoryManagerValidationProbe.SdkHistorySnapshot after
    ) {
        final String seen = historySeen(before, after);
        final String gateFailure = historyGateFailure(before, after);
        if (gateFailure != null) return new Verdict(false, gateFailure, seen);

        final Set<String> beforeIds = new HashSet<>();
        for (WindowsHistoryManagerValidationProbe.SdkEntry entry : before.entries()) {
            if (entry != null && stableEntryId(entry.entryId())) beforeIds.add(entry.entryId());
        }
        final List<WindowsHistoryManagerValidationProbe.SdkEntry> candidates = after.entries().stream()
            .filter(Objects::nonNull)
            .filter(entry -> entry.index() >= 0 && entry.index() < after.position())
            .filter(entry -> stableEntryId(entry.entryId()))
            .filter(entry -> !beforeIds.contains(entry.entryId()))
            .toList();
        if (candidates.isEmpty()) {
            return new Verdict(false, "no-new-entry", seen + ",candidates=0");
        }

        final List<PartCandidate> assessments = candidates.stream()
            .map(WindowsHistoryNativeUiIngressProbe::assessPartCandidate)
            .toList();
        final List<PartCandidate> relationCandidates = assessments.stream()
            .filter(PartCandidate::hasRelation)
            .toList();
        final String candidateSeen = seen
            + ",candidates=" + candidates.size()
            + ",relationCandidates=" + relationCandidates.size();
        if (relationCandidates.isEmpty()) {
            return new Verdict(false, "no-proven-membership-change", candidateSeen);
        }
        if (relationCandidates.size() > 1) {
            final boolean allPartial = relationCandidates.stream()
                .allMatch(candidate -> "partial-relation".equals(candidateFailureCode(candidate)));
            return new Verdict(
                false,
                allPartial ? "partial-relation" : "ambiguous-candidate",
                candidateSeen
            );
        }

        final PartCandidate candidate = relationCandidates.get(0);
        final String candidateFailure = candidateFailureCode(candidate);
        if (!"proven-membership-change".equals(candidateFailure)) {
            return new Verdict(false, candidateFailure, candidateSeen);
        }
        // A label-only/unstructured entry can be a selection side effect. Any additional typed
        // facts are evidence that the delta is not one unambiguous membership edit.
        if (assessments.stream()
            .filter(other -> other != candidate)
            .anyMatch(PartCandidate::structured)) {
            return new Verdict(false, "ambiguous-candidate", candidateSeen);
        }
        return new Verdict(true, "proven-membership-change", candidateSeen);
    }

    /**
     * Keeps an automated actor failure terminal while preserving the raw semantic verdict. Manual
     * steps have {@link PartActorOutcome#NOT_RUN} and therefore retain the existing semantic gate.
     */
    static Verdict admitPartStep(final PartActorResult actor, final Verdict semantic) {
        if (semantic == null) return new Verdict(false, "semantic-verdict-unavailable", "");
        if (actor == null || actor.outcome() == PartActorOutcome.NOT_RUN || actor.accepted()) {
            return semantic;
        }
        return new Verdict(
            false,
            "actor-" + actor.code(),
            "actorOutcome=" + actor.outcome().name()
                + ",actorEvidence=" + actor.evidence()
                + ",semantic=" + semantic.code()
        );
    }

    private static String historyGateFailure(
        final WindowsHistoryManagerValidationProbe.SdkHistorySnapshot before,
        final WindowsHistoryManagerValidationProbe.SdkHistorySnapshot after
    ) {
        if (!usableSdkHistory(before) || !usableSdkHistory(after)) return "history-unavailable";
        if (!sameNonBlank(before.documentBindingId(), after.documentBindingId())
            || !sameNonBlank(before.managerBindingId(), after.managerBindingId())
            || before.generation() != after.generation()) {
            return "binding-changed";
        }
        if (truncatedSdkHistory(before) || truncatedSdkHistory(after)) return "history-truncated";
        if (!completeEntryIdentities(before) || !completeEntryIdentities(after)) {
            return "history-unavailable";
        }
        return null;
    }

    private static boolean usableSdkHistory(
        final WindowsHistoryManagerValidationProbe.SdkHistorySnapshot snapshot
    ) {
        return snapshot != null
            && "AVAILABLE".equals(snapshot.availability())
            && nonBlank(snapshot.documentBindingId())
            && nonBlank(snapshot.managerBindingId())
            && snapshot.entries() != null;
    }

    private static boolean truncatedSdkHistory(
        final WindowsHistoryManagerValidationProbe.SdkHistorySnapshot snapshot
    ) {
        return snapshot.totalEntries() != snapshot.entries().size();
    }

    private static boolean completeEntryIdentities(
        final WindowsHistoryManagerValidationProbe.SdkHistorySnapshot snapshot
    ) {
        final Set<String> identities = new HashSet<>();
        for (WindowsHistoryManagerValidationProbe.SdkEntry entry : snapshot.entries()) {
            if (entry == null || !stableEntryId(entry.entryId())
                || !identities.add(entry.entryId())) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameNonBlank(final String left, final String right) {
        return nonBlank(left) && Objects.equals(left, right);
    }

    private static boolean nonBlank(final String value) {
        return value != null && !value.isBlank();
    }

    private static boolean stableEntryId(final String value) {
        return nonBlank(value);
    }

    private static String historySeen(
        final WindowsHistoryManagerValidationProbe.SdkHistorySnapshot before,
        final WindowsHistoryManagerValidationProbe.SdkHistorySnapshot after
    ) {
        return "before=" + sdkHistorySeen(before) + ",after=" + sdkHistorySeen(after);
    }

    private static String sdkHistorySeen(
        final WindowsHistoryManagerValidationProbe.SdkHistorySnapshot snapshot
    ) {
        if (snapshot == null) return "null";
        return snapshot.availability()
            + ":generation=" + snapshot.generation()
            + ":position=" + snapshot.position()
            + ":total=" + snapshot.totalEntries();
    }

    private static PartCandidate assessPartCandidate(
        final WindowsHistoryManagerValidationProbe.SdkEntry entry
    ) {
        final HistoryEntryDetail detail = entry.detail();
        if (detail == null) {
            return new PartCandidate(
                entry.entryId(),
                true,
                true,
                false,
                Set.of(),
                List.of(),
                true
            );
        }
        final DetailWalk walk = walkDetail(detail, 0, new int[] {0});
        return new PartCandidate(
            entry.entryId(),
            walk.structured(),
            walk.complete(),
            walk.unstableTarget(),
            walk.targetKeys(),
            walk.relations(),
            walk.hasNonRelationChange()
        );
    }

    private static String candidateFailureCode(final PartCandidate candidate) {
        if (!candidate.hasRelation()) return "no-proven-membership-change";
        if (candidate.hasNonRelationChange()) return "ambiguous-candidate";
        if (!candidate.complete()) return "partial-relation";
        if (candidate.relations().size() != 1 || candidate.unstableTarget()
            || candidate.targetKeys().size() != 1) {
            return "ambiguous-candidate";
        }
        return switch (candidate.relations().get(0).status()) {
            case COMPLETE -> "proven-membership-change";
            case PARTIAL -> "partial-relation";
            case INVALID_TARGET_INDEX -> "target-index-invalid";
            case SAME_ENDPOINT, NON_MEMBERSHIP -> "no-proven-membership-change";
        };
    }

    private static DetailWalk walkDetail(
        final HistoryEntryDetail detail,
        final int depth,
        final int[] nodes
    ) {
        if (depth > WindowsHistoryManagerValidationProbe.MAX_DETAIL_DEPTH
            || nodes[0] >= WindowsHistoryManagerValidationProbe.MAX_DETAIL_NODES) {
            return DetailWalk.incomplete();
        }
        nodes[0]++;
        final Set<TargetKey> targetKeys = new LinkedHashSet<>();
        boolean unstableTarget = false;
        for (HistoryTarget target : detail.targets()) {
            if (!stableTarget(target)) {
                unstableTarget = true;
            } else {
                targetKeys.add(targetKey(target));
            }
        }
        final List<RelationObservation> relations = new ArrayList<>();
        boolean hasNonRelationChange = false;
        for (HistoryChange change : detail.changes()) {
            if (change.relation().isEmpty()) {
                hasNonRelationChange = true;
                continue;
            }
            relations.add(relationObservation(detail, change));
        }

        boolean complete = true;
        final boolean structured = !detail.targets().isEmpty()
            || !detail.changes().isEmpty()
            || detail.group().isPresent();
        if (detail.group().isPresent()) {
            final var group = detail.group().orElseThrow();
            if (group.truncated()
                || group.observedChildCount() < group.children().size()
                || group.children().size() > WindowsHistoryManagerValidationProbe.MAX_DETAIL_NODES) {
                complete = false;
            }
            if (depth >= WindowsHistoryManagerValidationProbe.MAX_DETAIL_DEPTH
                && !group.children().isEmpty()) {
                complete = false;
            } else {
                for (HistoryEntryDetail child : group.children()) {
                    if (nodes[0] >= WindowsHistoryManagerValidationProbe.MAX_DETAIL_NODES) {
                        complete = false;
                        break;
                    }
                    final DetailWalk childWalk = walkDetail(child, depth + 1, nodes);
                    complete &= childWalk.complete();
                    unstableTarget |= childWalk.unstableTarget();
                    hasNonRelationChange |= childWalk.hasNonRelationChange();
                    targetKeys.addAll(childWalk.targetKeys());
                    relations.addAll(childWalk.relations());
                }
            }
        }
        return new DetailWalk(
            complete,
            structured,
            unstableTarget,
            targetKeys,
            relations,
            hasNonRelationChange
        );
    }

    private static RelationObservation relationObservation(
        final HistoryEntryDetail detail,
        final HistoryChange change
    ) {
        final HistoryRelationChange relation = change.relation().orElseThrow();
        if (relation.kind() != HistoryRelationChange.Kind.PART_MEMBERSHIP) {
            return new RelationObservation(RelationStatus.NON_MEMBERSHIP);
        }
        if (change.targetIndex().isEmpty()) {
            return new RelationObservation(RelationStatus.INVALID_TARGET_INDEX);
        }
        final int targetIndex = change.targetIndex().orElse(-1);
        if (targetIndex < 0 || targetIndex >= detail.targets().size()) {
            return new RelationObservation(RelationStatus.INVALID_TARGET_INDEX);
        }
        if (!stableTarget(detail.targets().get(targetIndex))) {
            return new RelationObservation(RelationStatus.PARTIAL);
        }
        final EndpointIdentity before = endpointIdentity(relation.before());
        final EndpointIdentity after = endpointIdentity(relation.after());
        if (before == null || after == null) return new RelationObservation(RelationStatus.PARTIAL);
        if (sameEndpoint(before, after)) {
            return new RelationObservation(RelationStatus.SAME_ENDPOINT);
        }
        return new RelationObservation(RelationStatus.COMPLETE);
    }

    private static boolean stableTarget(final HistoryTarget target) {
        return target != null
            && nonBlank(target.type())
            && target.id().filter(WindowsHistoryNativeUiIngressProbe::nonBlank).isPresent();
    }

    private static TargetKey targetKey(final HistoryTarget target) {
        return new TargetKey(target.type(), target.id().orElseThrow());
    }

    private static EndpointIdentity endpointIdentity(
        final HistoryRelationChange.Endpoint endpoint
    ) {
        return switch (endpoint.state()) {
            case ROOT -> endpoint.target().isEmpty()
                ? new EndpointIdentity(HistoryRelationChange.State.ROOT, null)
                : null;
            case TARGET -> endpoint.target().filter(WindowsHistoryNativeUiIngressProbe::stableTarget)
                .map(WindowsHistoryNativeUiIngressProbe::targetKey)
                .map(key -> new EndpointIdentity(HistoryRelationChange.State.TARGET, key))
                .orElse(null);
            case UNKNOWN -> null;
        };
    }

    private static boolean sameEndpoint(
        final EndpointIdentity left,
        final EndpointIdentity right
    ) {
        if (left.state() != right.state()) return false;
        return left.state() == HistoryRelationChange.State.ROOT
            || Objects.equals(left.target(), right.target());
    }

    private enum RelationStatus {
        COMPLETE,
        PARTIAL,
        INVALID_TARGET_INDEX,
        SAME_ENDPOINT,
        NON_MEMBERSHIP
    }

    private record TargetKey(String type, String id) {
    }

    private record EndpointIdentity(
        HistoryRelationChange.State state,
        TargetKey target
    ) {
    }

    private record RelationObservation(RelationStatus status) {
    }

    private record DetailWalk(
        boolean complete,
        boolean structured,
        boolean unstableTarget,
        Set<TargetKey> targetKeys,
        List<RelationObservation> relations,
        boolean hasNonRelationChange
    ) {
        private DetailWalk {
            targetKeys = Set.copyOf(targetKeys);
            relations = List.copyOf(relations);
        }

        private static DetailWalk incomplete() {
            return new DetailWalk(false, true, true, Set.of(), List.of(), true);
        }
    }

    private record PartCandidate(
        String entryId,
        boolean structured,
        boolean complete,
        boolean unstableTarget,
        Set<TargetKey> targetKeys,
        List<RelationObservation> relations,
        boolean hasNonRelationChange
    ) {
        private PartCandidate {
            targetKeys = Set.copyOf(targetKeys);
            relations = List.copyOf(relations);
        }

        private boolean hasRelation() {
            return !relations.isEmpty();
        }
    }

    private static WindowsHistoryManagerValidationProbe.SdkHistorySnapshot sdkHistory(
        final WindowsHistoryManagerValidationProbe.Snapshot snapshot
    ) {
        return snapshot == null ? null : snapshot.sdkHistory();
    }

    private static long countPhase(final List<Observed> events, final String phase) {
        return events.stream()
            .filter(event -> event != null && phase.equals(event.phase()))
            .count();
    }

    private static boolean supportedStepKind(final String kind) {
        return "ACTION".equals(kind) || "UNDO".equals(kind) || "REDO".equals(kind);
    }

    /** Validates only the confirmed lifecycle; before events remain independent hook evidence. */
    private static ConfirmationCheck checkConfirmations(final List<Observed> events) {
        final Set<Long> beforeSequences = new HashSet<>();
        final Map<Long, Observed> onBySequence = new LinkedHashMap<>();
        final Map<Long, Observed> afterBySequence = new HashMap<>();
        for (Observed event : events) {
            if (event == null) return ConfirmationCheck.invalid("null-event");
            final String phase = event.phase();
            if ("before".equals(phase)) {
                if (!beforeSequences.add(event.sequence())) {
                    return ConfirmationCheck.invalid("duplicate-confirmation-phase");
                }
                continue;
            }
            if ("on".equals(phase)) {
                if (onBySequence.containsKey(event.sequence())) {
                    return ConfirmationCheck.invalid("duplicate-confirmation-phase");
                }
                if (afterBySequence.containsKey(event.sequence())) {
                    return ConfirmationCheck.invalid("confirmation-reversed");
                }
                onBySequence.put(event.sequence(), event);
                continue;
            }
            if ("after".equals(phase)) {
                if (afterBySequence.containsKey(event.sequence())) {
                    return ConfirmationCheck.invalid("duplicate-confirmation-phase");
                }
                final Observed on = onBySequence.get(event.sequence());
                if (on == null) {
                    return ConfirmationCheck.invalid(
                        onBySequence.isEmpty()
                            ? "orphan-after-event"
                            : "confirmation-sequence-mismatch"
                    );
                }
                if (blank(on.operation()) || blank(on.origin())) {
                    return ConfirmationCheck.invalid("confirmation-fields-missing");
                }
                if (!sameConfirmationFields(on, event)) {
                    return ConfirmationCheck.invalid("confirmation-fields-mismatch");
                }
                afterBySequence.put(event.sequence(), event);
                continue;
            }
            return ConfirmationCheck.invalid("unknown-event-phase");
        }
        final List<ConfirmationPair> pairs = new ArrayList<>(onBySequence.size());
        for (Map.Entry<Long, Observed> entry : onBySequence.entrySet()) {
            final Observed after = afterBySequence.get(entry.getKey());
            if (after == null) return ConfirmationCheck.invalid("orphan-on-event");
            pairs.add(new ConfirmationPair(entry.getValue(), after));
        }
        return new ConfirmationCheck(true, "", List.copyOf(pairs));
    }

    private static boolean sameConfirmationFields(
        final Observed on,
        final Observed after
    ) {
        return Objects.equals(on.operation(), after.operation())
            && Objects.equals(on.origin(), after.origin())
            && Objects.equals(on.subjectId(), after.subjectId());
    }

    private static boolean blank(final String value) {
        return value == null || value.isBlank();
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
    static String significantSequence(final WindowsHistoryManagerValidationProbe.Snapshot snapshot) {
        if (snapshot == null) return "";
        return significantSequence(snapshot.current().entries());
    }

    /** Package-private so a focused test can pin the boundary rule without a live host. */
    static String significantSequence(final List<WindowsHistoryManagerValidationProbe.Entry> entries) {
        final StringBuilder sequence = new StringBuilder();
        for (WindowsHistoryManagerValidationProbe.Entry entry : entries) {
            if (!entry.significant()) continue;
            if (sequence.length() > 0) sequence.append('|');
            sequence.append(entry.index()).append(':').append(entry.label());
        }
        return sequence.toString();
    }

    private long parameterLifecycleCursor() {
        synchronized (lock) {
            return parameterLifecycleSequence;
        }
    }

    private List<ParameterLifecycleEvent> parameterLifecycleSince(final long cursor) {
        synchronized (lock) {
            return parameterLifecycle.stream()
                .filter(event -> event.sequence() > cursor)
                .toList();
        }
    }

    private boolean parameterLifecycleWindowComplete(final long cursor) {
        synchronized (lock) {
            for (final ParameterLifecycleEvent event : parameterLifecycle) {
                if (event.sequence() > cursor) return event.sequence() == cursor + 1L;
            }
            return parameterLifecycleSequence == cursor;
        }
    }

    static ParameterStateOutcome compareParameterState(
        final ParameterStateSnapshot before,
        final ParameterStateSnapshot after
    ) {
        if (before == null || after == null || !before.available() || !after.available()) {
            return ParameterStateOutcome.UNAVAILABLE;
        }
        if (!before.modelId().equals(after.modelId())) {
            return ParameterStateOutcome.MODEL_CHANGED;
        }
        final Map<String, Float> beforeValues = parameterValueMap(before);
        final Map<String, Float> afterValues = parameterValueMap(after);
        if (beforeValues == null || afterValues == null
            || !beforeValues.keySet().equals(afterValues.keySet())) {
            return ParameterStateOutcome.UNAVAILABLE;
        }
        for (final Map.Entry<String, Float> entry : beforeValues.entrySet()) {
            if (Float.compare(entry.getValue(), afterValues.get(entry.getKey())) != 0) {
                return ParameterStateOutcome.CHANGED;
            }
        }
        return ParameterStateOutcome.UNCHANGED;
    }

    static Set<String> changedParameterIds(
        final ParameterStateSnapshot before,
        final ParameterStateSnapshot after
    ) {
        if (compareParameterState(before, after) != ParameterStateOutcome.CHANGED) {
            return Set.of();
        }
        final Map<String, Float> beforeValues = parameterValueMap(before);
        final Map<String, Float> afterValues = parameterValueMap(after);
        final Set<String> changed = new LinkedHashSet<>();
        for (final Map.Entry<String, Float> entry : beforeValues.entrySet()) {
            if (Float.compare(entry.getValue(), afterValues.get(entry.getKey())) != 0) {
                changed.add(entry.getKey());
            }
        }
        return Collections.unmodifiableSet(changed);
    }

    private static Map<String, Float> parameterValueMap(final ParameterStateSnapshot snapshot) {
        if (snapshot == null || !snapshot.available()) return null;
        final Map<String, Float> values = new HashMap<>();
        for (final ParameterValueSample sample : snapshot.values()) {
            if (sample == null || values.put(sample.parameterId(), sample.value()) != null) {
                return null;
            }
        }
        return values;
    }

    static ParameterHistoryAdmission parameterHistoryAdmission(
        final String beforeSignificant,
        final String afterSignificant
    ) {
        if (beforeSignificant == null || afterSignificant == null) {
            return ParameterHistoryAdmission.UNAVAILABLE;
        }
        return beforeSignificant.equals(afterSignificant)
            ? ParameterHistoryAdmission.NOT_OBSERVED
            : ParameterHistoryAdmission.OBSERVED;
    }

    static List<ParameterLifecycleEvent> relevantParameterLifecycle(
        final List<ParameterLifecycleEvent> events,
        final Set<String> parameterIds
    ) {
        if (events == null || events.isEmpty() || parameterIds == null || parameterIds.isEmpty()) {
            return List.of();
        }
        return events.stream()
            .filter(event -> event != null && parameterIds.contains(event.parameterId()))
            .toList();
    }

    static ParameterLifecycleStatus parameterLifecycleStatus(
        final List<ParameterLifecycleEvent> events,
        final Set<String> changedIds,
        final boolean valueChanged
    ) {
        return parameterLifecycleStatus(events, null, null, changedIds, valueChanged, true);
    }

    static ParameterLifecycleStatus parameterLifecycleStatus(
        final List<ParameterLifecycleEvent> events,
        final ParameterStateSnapshot before,
        final ParameterStateSnapshot after,
        final Set<String> changedIds,
        final boolean valueChanged
    ) {
        return parameterLifecycleStatus(
            events, before, after, changedIds, valueChanged, true);
    }

    static ParameterLifecycleStatus parameterLifecycleStatus(
        final List<ParameterLifecycleEvent> events,
        final ParameterStateSnapshot before,
        final ParameterStateSnapshot after,
        final Set<String> changedIds,
        final boolean valueChanged,
        final boolean lifecycleWindowComplete
    ) {
        if (!lifecycleWindowComplete) return ParameterLifecycleStatus.INCOMPLETE;
        if (events == null || events.isEmpty()) return ParameterLifecycleStatus.MISSING;
        if (changedIds == null || changedIds.isEmpty()) {
            return ParameterLifecycleStatus.OBSERVED_UNRELATED;
        }
        if (!valueChanged) return ParameterLifecycleStatus.INCOMPLETE;
        if (before == null || after == null || !before.available() || !after.available()
            || !before.modelId().equals(after.modelId())) {
            return ParameterLifecycleStatus.UNAVAILABLE;
        }
        final Map<String, Float> beforeValues = parameterValueMap(before);
        final Map<String, Float> afterValues = parameterValueMap(after);
        final Set<String> actualChangedIds = changedParameterIds(before, after);
        if (beforeValues == null || afterValues == null
            || !actualChangedIds.equals(changedIds)) {
            return ParameterLifecycleStatus.UNAVAILABLE;
        }
        if (!strictLifecycleSequence(events)) return ParameterLifecycleStatus.INCOMPLETE;
        final List<ParameterLifecycleEvent> relevant = relevantParameterLifecycle(events, changedIds);
        if (relevant.isEmpty()) return ParameterLifecycleStatus.OBSERVED_UNRELATED;
        for (final String changedId : changedIds) {
            final Float beforeValue = beforeValues.get(changedId);
            final Float afterValue = afterValues.get(changedId);
            if (beforeValue == null || afterValue == null) {
                return ParameterLifecycleStatus.UNAVAILABLE;
            }
            final List<ParameterLifecycleEvent> perParameter = relevant.stream()
                .filter(event -> event.parameterId().equals(changedId))
                .toList();
            if (!validParameterLifecycle(perParameter, beforeValue, afterValue)) {
                return ParameterLifecycleStatus.INCOMPLETE;
            }
        }
        return ParameterLifecycleStatus.COMPLETE;
    }

    static ParameterLifecycleAssessment assessParameterLifecycle(
        final List<ParameterLifecycleEvent> events,
        final ParameterStateSnapshot before,
        final ParameterStateSnapshot after,
        final Set<String> changedIds,
        final boolean valueChanged
    ) {
        return assessParameterLifecycle(
            events, before, after, changedIds, valueChanged, true);
    }

    static ParameterLifecycleAssessment assessParameterLifecycle(
        final List<ParameterLifecycleEvent> events,
        final ParameterStateSnapshot before,
        final ParameterStateSnapshot after,
        final Set<String> changedIds,
        final boolean valueChanged,
        final boolean lifecycleWindowComplete
    ) {
        return new ParameterLifecycleAssessment(
            parameterLifecycleStatus(
                events, before, after, changedIds, valueChanged, lifecycleWindowComplete),
            ParameterModelCorrelation.UNAVAILABLE
        );
    }

    private static boolean strictLifecycleSequence(
        final List<ParameterLifecycleEvent> events
    ) {
        long previous = Long.MIN_VALUE;
        for (final ParameterLifecycleEvent event : events) {
            if (event == null || event.sequence() <= previous) return false;
            previous = event.sequence();
        }
        return true;
    }

    private static boolean validParameterLifecycle(
        final List<ParameterLifecycleEvent> events,
        final float beforeValue,
        final float afterValue
    ) {
        if (events == null || events.isEmpty() || Float.compare(beforeValue, afterValue) == 0) {
            return false;
        }
        boolean open = false;
        boolean segmentChanged = false;
        boolean segmentNeutral = false;
        boolean sawOn = false;
        boolean sawAfter = false;
        float current = beforeValue;
        for (final ParameterLifecycleEvent event : events) {
            if (event == null) return false;
            switch (event.phase()) {
                case "before" -> {
                    if (open || !finite(event.oldValue()) || !finite(event.newValue())
                        || !same(event.oldValue(), current)) return false;
                    open = true;
                    segmentChanged = false;
                    segmentNeutral = same(event.oldValue(), event.newValue())
                        && same(event.newValue(), current);
                }
                case "on" -> {
                    if (!open || !finite(event.oldValue()) || !finite(event.newValue())
                        || !same(event.oldValue(), current)
                        || !movesToward(event.oldValue(), event.newValue(), beforeValue, afterValue)) {
                        return false;
                    }
                    current = event.newValue();
                    segmentChanged = true;
                    segmentNeutral = false;
                    sawOn = true;
                }
                case "after" -> {
                    if (!open || (!segmentChanged && !segmentNeutral) || !finite(event.newValue())
                        || !same(event.newValue(), current)
                        || !within(event.newValue(), beforeValue, afterValue)) return false;
                    open = false;
                    segmentChanged = false;
                    segmentNeutral = false;
                    sawAfter = true;
                }
                default -> {
                    return false;
                }
            }
        }
        return !open && sawOn && sawAfter && same(current, afterValue);
    }

    private static boolean finite(final Float value) {
        return value != null && Float.isFinite(value);
    }

    private static boolean same(final Float left, final float right) {
        return finite(left) && Float.compare(left, right) == 0;
    }

    private static boolean within(final Float value, final float start, final float end) {
        if (!finite(value)) return false;
        final float low = Math.min(start, end);
        final float high = Math.max(start, end);
        return value >= low && value <= high;
    }

    private static boolean movesToward(
        final Float oldValue,
        final Float newValue,
        final float start,
        final float end
    ) {
        if (!finite(oldValue) || !finite(newValue)
            || !within(oldValue, start, end) || !within(newValue, start, end)) {
            return false;
        }
        return end > start
            ? newValue > oldValue && newValue <= end
            : newValue < oldValue && newValue >= end;
    }

    static ParameterChangeObservation preserveParameterActorOutcome(
        final ParameterChangeObservation actorObservation,
        final ParameterChangeObservation followupObservation
    ) {
        return settleParameterObservations(
            actorObservation,
            followupObservation == null ? List.of() : List.of(followupObservation)
        );
    }

    /**
     * Applies the same final-readback decision used by the real settle loop to a bounded trace.
     * Unresolved actor outcomes are terminal, while every successful follow-up observation is a
     * new final candidate, including a readback that is unchanged from the original value.
     */
    static ParameterChangeObservation settleParameterObservations(
        final ParameterChangeObservation actorObservation,
        final List<ParameterChangeObservation> settleObservations
    ) {
        if (actorObservation != null
            && (actorObservation.outcome() == ParameterStateOutcome.UNAVAILABLE
                || actorObservation.outcome() == ParameterStateOutcome.MODEL_CHANGED)) {
            return actorObservation;
        }
        ParameterChangeObservation latest = actorObservation;
        if (settleObservations == null) return latest;
        for (final ParameterChangeObservation observation : settleObservations) {
            if (observation == null) continue;
            if (observation.outcome() == ParameterStateOutcome.UNAVAILABLE
                || observation.outcome() == ParameterStateOutcome.MODEL_CHANGED) {
                return observation;
            }
            latest = observation;
        }
        return latest;
    }

    private String parameterStateJson(
        final String step,
        final String phase,
        final ParameterStateSnapshot snapshot
    ) {
        return "{\"type\":\"parameter-state\",\"step\":\""
            + json(step) + "\",\"phase\":\"" + json(phase)
            + "\",\"availability\":\"" + snapshot.availability().code()
            + "\",\"modelId\":\"" + json(snapshot.modelId())
            + "\",\"reason\":\"" + json(snapshot.reason())
            + "\",\"parameters\":["
            + snapshot.values().stream().map(ParameterValueSample::json)
                .reduce((left, right) -> left + "," + right).orElse("")
            + "]}\n";
    }

    private String parameterEvidenceJson(
        final String step,
        final ParameterStateSnapshot before,
        final ParameterStateSnapshot after,
        final ParameterStateOutcome valueStatus,
        final ParameterHistoryAdmission historyAdmission,
        final ParameterLifecycleAssessment lifecycleAssessment,
        final Set<String> changedIds,
        final List<ParameterLifecycleEvent> lifecycleEvents
    ) {
        final Set<String> relevantIds = changedIds == null ? Set.of() : changedIds;
        return "{\"type\":\"parameter-evidence\",\"step\":\"" + json(step)
            + "\",\"modelIdBefore\":\"" + json(modelId(before))
            + "\",\"modelIdAfter\":\"" + json(modelId(after))
            + "\",\"valueStatus\":\"" + valueStatus.code()
            + "\",\"historyAdmission\":\"" + historyAdmission.code()
            + "\",\"lifecycle\":\"" + lifecycleAssessment.status().code()
            + "\",\"modelCorrelation\":\""
            + lifecycleAssessment.modelCorrelation().code()
            + "\",\"changedParameterIds\":["
            + relevantIds.stream().map(WindowsHistoryNativeUiIngressProbe::quoted)
                .reduce((left, right) -> left + "," + right).orElse("")
            + "],\"callbacks\":["
            + (lifecycleEvents == null ? "" : lifecycleEvents.stream()
                .map(event -> event.json(relevantIds))
                .reduce((left, right) -> left + "," + right).orElse(""))
            + "]}\n";
    }

    private static String modelId(final ParameterStateSnapshot snapshot) {
        return snapshot == null ? "" : snapshot.modelId();
    }

    enum ShortcutResolution {
        DELIVERED,
        WRONG_DIRECTION,
        /** The menu action was measured unchanged; Robot may be used as a fallback. */
        FALLBACK,
        /** The baseline position was not valid, so menu delivery cannot be classified. */
        UNKNOWN,
        /** One or more post-menu position samples were unavailable. */
        UNAVAILABLE
    }

    /**
     * Resolves whether a menu-delivered navigation already happened. An opposite move is still a
     * response and must not be repeated; {@link #hasMoved} will reject it for the requested step.
     */
    static ShortcutResolution shortcutResolution(
        final String kind,
        final long knownPosition,
        final long currentPosition
    ) {
        if (knownPosition < 0L) return ShortcutResolution.UNKNOWN;
        if (currentPosition < 0L) return ShortcutResolution.UNAVAILABLE;
        if (currentPosition == knownPosition) return ShortcutResolution.FALLBACK;
        final boolean expected = "UNDO".equals(kind)
            ? currentPosition < knownPosition
            : "REDO".equals(kind) && currentPosition > knownPosition;
        return expected
            ? ShortcutResolution.DELIVERED
            : ShortcutResolution.WRONG_DIRECTION;
    }

    /**
     * Resolves the bounded sample window after a menu accelerator. A missing sample remains an
     * unknown observation even when another sample was unchanged: without a complete window the
     * probe cannot prove that the menu did not already execute.
     */
    static ShortcutResolution shortcutResolution(
        final String kind,
        final long knownPosition,
        final List<Long> currentPositions
    ) {
        if (knownPosition < 0L) return ShortcutResolution.UNKNOWN;
        if (currentPositions == null || currentPositions.isEmpty()) {
            return ShortcutResolution.UNAVAILABLE;
        }
        boolean unavailable = false;
        for (final Long currentPosition : currentPositions) {
            if (currentPosition == null) {
                unavailable = true;
                continue;
            }
            final ShortcutResolution resolution = shortcutResolution(
                kind, knownPosition, currentPosition.longValue());
            if (resolution == ShortcutResolution.DELIVERED
                || resolution == ShortcutResolution.WRONG_DIRECTION) {
                return resolution;
            }
            unavailable |= resolution == ShortcutResolution.UNAVAILABLE;
        }
        return unavailable ? ShortcutResolution.UNAVAILABLE : ShortcutResolution.FALLBACK;
    }

    /**
     * Whether the operator's step has produced what that step is supposed to produce.
     *
     * <p>An ACTION step is closed by the <em>significant</em> entries changing, never by the undo
     * position moving: the host records a selection as an ordinary entry, so closing on the entry
     * count or the position lets the click that precedes an action satisfy the step, and the real
     * action then lands in the next step's window. Undo and Redo are the opposite case — they
     * change the position and add no entry at all — so those steps are closed by the position.</p>
     */
    static boolean hasMoved(
        final Step step,
        final String currentSignificant,
        final long currentPosition,
        final String knownSignificant,
        final long knownPosition
    ) {
        if (currentSignificant == null) return false;
        if (step.kind().equals("ACTION")) {
            return !currentSignificant.equals(knownSignificant);
        }
        // Navigation is only closed by a move in its own direction, measured against the last
        // observed position. A selection also moves the position forwards, so accepting any change
        // would let the click before the operator's real action close an Undo step; and comparing
        // against the window's opening position would miss an undo that follows an edit made
        // inside the same window.
        if (step.kind().equals("UNDO")) return currentPosition < knownPosition;
        if (step.kind().equals("REDO")) return currentPosition > knownPosition;
        return false;
    }

    /** How long this step settles after its signal appears. */
    static long settleMillis(final Step step) {
        return step.kind().equals("ACTION")
            ? ACTION_SETTLE_MILLIS
            : NAVIGATION_SETTLE_MILLIS;
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
        return json(value, 512);
    }

    /**
     * Escapes a string for the JSONL artifact, bounded to {@code maxCodePoints} code points.
     *
     * <p>The default bound sizes a label; the ui-map carries the larger component-tree bound
     * instead, since clipping it at a label's size made the first automated run's discovery
     * evidence unusable.</p>
     */
    static String json(final String value, final int maxCodePoints) {
        if (value == null) return "";
        final StringBuilder escaped = new StringBuilder();
        value.codePoints().limit(maxCodePoints).forEach(codePoint -> {
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

    enum ParameterStateAvailability {
        AVAILABLE("available"),
        UNAVAILABLE("unavailable");

        private final String code;

        ParameterStateAvailability(final String code) {
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    enum ParameterStateOutcome {
        CHANGED("changed"),
        UNCHANGED("unchanged"),
        MODEL_CHANGED("model-changed"),
        UNAVAILABLE("unavailable");

        private final String code;

        ParameterStateOutcome(final String code) {
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    enum ParameterHistoryAdmission {
        OBSERVED("observed"),
        NOT_OBSERVED("not-observed"),
        UNAVAILABLE("unavailable");

        private final String code;

        ParameterHistoryAdmission(final String code) {
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    enum ParameterLifecycleStatus {
        COMPLETE("complete"),
        MISSING("missing"),
        INCOMPLETE("incomplete"),
        OBSERVED_UNRELATED("observed-unrelated"),
        UNAVAILABLE("unavailable");

        private final String code;

        ParameterLifecycleStatus(final String code) {
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    enum ParameterModelCorrelation {
        UNAVAILABLE("unavailable");

        private final String code;

        ParameterModelCorrelation(final String code) {
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    record ParameterLifecycleAssessment(
        ParameterLifecycleStatus status,
        ParameterModelCorrelation modelCorrelation
    ) {
        ParameterLifecycleAssessment {
            status = Objects.requireNonNull(status, "status");
            modelCorrelation = Objects.requireNonNull(modelCorrelation, "modelCorrelation");
        }
    }

    record ParameterValueSample(String parameterId, float value) {
        ParameterValueSample {
            parameterId = Objects.requireNonNull(parameterId, "parameterId");
            if (parameterId.isBlank() || parameterId.length() > MAX_PARAMETER_ID_LENGTH) {
                throw new IllegalArgumentException("parameterId is unavailable");
            }
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("parameter value must be finite");
            }
        }

        String json() {
            return "{\"id\":\"" + WindowsHistoryNativeUiIngressProbe.json(parameterId)
                + "\",\"value\":" + Float.toString(value) + "}";
        }
    }

    record ParameterStateSnapshot(
        ParameterStateAvailability availability,
        String modelId,
        List<ParameterValueSample> values,
        String reason
    ) {
        ParameterStateSnapshot {
            availability = Objects.requireNonNull(availability, "availability");
            modelId = modelId == null ? "" : modelId;
            values = List.copyOf(Objects.requireNonNull(values, "values"));
            reason = reason == null ? "" : reason;
            if (modelId.length() > MAX_PARAMETER_ID_LENGTH) {
                throw new IllegalArgumentException("modelId is unavailable");
            }
            if (availability == ParameterStateAvailability.AVAILABLE
                && (modelId.isBlank() || values.size() > MAX_PARAMETER_VALUES)) {
                throw new IllegalArgumentException("available parameter snapshot is invalid");
            }
        }

        static ParameterStateSnapshot available(
            final String modelId,
            final List<ParameterValueSample> values
        ) {
            return new ParameterStateSnapshot(
                ParameterStateAvailability.AVAILABLE, modelId, values, "");
        }

        static ParameterStateSnapshot unavailable(final String reason) {
            return new ParameterStateSnapshot(
                ParameterStateAvailability.UNAVAILABLE, "", List.of(),
                reason == null || reason.isBlank() ? "unavailable" : reason);
        }

        boolean available() {
            return availability == ParameterStateAvailability.AVAILABLE;
        }
    }

    record ParameterChangeObservation(
        ParameterStateOutcome outcome,
        ParameterStateSnapshot after,
        String reason
    ) {
        ParameterChangeObservation {
            outcome = Objects.requireNonNull(outcome, "outcome");
            reason = reason == null ? "" : reason;
        }
    }

    record ParameterLifecycleEvent(
        long sequence,
        String phase,
        String parameterId,
        Float oldValue,
        Float newValue,
        String thread
    ) {
        ParameterLifecycleEvent {
            phase = boundedText(phase, MAX_PARAMETER_PHASE_LENGTH);
            parameterId = boundedText(parameterId, MAX_PARAMETER_ID_LENGTH);
            oldValue = finiteValue(oldValue);
            newValue = finiteValue(newValue);
            thread = boundedText(thread, MAX_PARAMETER_THREAD_LENGTH);
        }

        String json(final Set<String> relatedIds) {
            final boolean related = relatedIds != null && relatedIds.contains(parameterId);
            return "{\"sequence\":" + sequence
                + ",\"phase\":\"" + WindowsHistoryNativeUiIngressProbe.json(phase)
                + "\",\"parameterId\":\"" + WindowsHistoryNativeUiIngressProbe.json(parameterId)
                + "\",\"oldValue\":" + numberOrNull(oldValue)
                + ",\"newValue\":" + numberOrNull(newValue)
                + ",\"thread\":\"" + WindowsHistoryNativeUiIngressProbe.json(thread)
                + "\",\"related\":" + related + "}";
        }

        private static String numberOrNull(final Float value) {
            return value == null ? "null" : Float.toString(value);
        }
    }

    @FunctionalInterface
    interface PartPrePressGuard {
        PartPrePressCheck check() throws Exception;
    }

    @FunctionalInterface
    interface PartPointerReadback {
        Point read() throws Exception;
    }

    record PartPointerEvidence(
        String phase,
        int attempt,
        long monotonicNanos,
        String thread,
        Point commandedPoint,
        Point readbackPoint,
        String outcome,
        String reason
    ) {
        PartPointerEvidence {
            if (attempt < 1 || attempt > MAX_PART_POINTER_ATTEMPTS) {
                throw new IllegalArgumentException("pointer attempt out of bounds: " + attempt);
            }
            phase = boundedText(phase, MAX_PART_GESTURE_TEXT_LENGTH);
            thread = boundedText(thread, MAX_PART_GESTURE_TEXT_LENGTH);
            commandedPoint = commandedPoint == null ? null : new Point(commandedPoint);
            readbackPoint = readbackPoint == null ? null : new Point(readbackPoint);
            outcome = boundedText(outcome, MAX_PART_GESTURE_TEXT_LENGTH);
            reason = boundedText(reason, MAX_PART_GESTURE_TEXT_LENGTH);
        }

        PartPointerEvidence(
            final String phase,
            final long monotonicNanos,
            final String thread,
            final Point commandedPoint,
            final Point readbackPoint,
            final String outcome,
            final String reason
        ) {
            this(
                phase, 1, monotonicNanos, thread, commandedPoint, readbackPoint, outcome, reason
            );
        }

        static PartPointerEvidence command(final int attempt, final Point point) {
            return new PartPointerEvidence(
                "source-command", attempt, System.nanoTime(), Thread.currentThread().getName(),
                point, null, "COMMANDED", ""
            );
        }

        static PartPointerEvidence command(final Point point) {
            return command(1, point);
        }

        String failureCode() {
            return switch (outcome) {
                case "COMMANDED", "MATCH" -> null;
                case "UNAVAILABLE" -> phase + "-unavailable";
                default -> phase + "-mismatch";
            };
        }

        String json() {
            return "{\"type\":\"part-pointer\",\"phase\":\""
                + WindowsHistoryNativeUiIngressProbe.json(phase)
                + "\",\"attempt\":" + attempt
                + ",\"monotonicNanos\":" + monotonicNanos
                + ",\"thread\":\""
                + WindowsHistoryNativeUiIngressProbe.json(thread)
                + "\",\"commandedPoint\":" + pointJson(commandedPoint)
                + ",\"readbackPoint\":" + pointJson(readbackPoint)
                + ",\"outcome\":\""
                + WindowsHistoryNativeUiIngressProbe.json(outcome)
                + "\",\"reason\":\""
                + WindowsHistoryNativeUiIngressProbe.json(reason)
                + "\"}\n";
        }
    }

    interface PartGestureInput {
        void mouseMove(int x, int y) throws Exception;

        void pause(long millis) throws Exception;

        void mousePress() throws Exception;

        void mouseRelease() throws Exception;
    }

    enum PartGestureStatus {
        ACCEPTED,
        MISMATCH,
        UNRESOLVED,
        EXCEPTION
    }

    record PartGestureAttempt(
        PartGestureStatus status,
        String code,
        PartPrePressCheck prePress,
        List<String> evidence
    ) {
        PartGestureAttempt {
            Objects.requireNonNull(status, "status");
            code = boundedText(code, MAX_PART_GESTURE_TEXT_LENGTH);
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }

    record PartGestureCheck(PartGestureStatus status, String code) {
        PartGestureCheck {
            Objects.requireNonNull(status, "status");
            code = boundedText(code, MAX_PART_GESTURE_TEXT_LENGTH);
        }
    }

    record PartPrePressCheck(
        boolean ok,
        String code,
        PartPairLayout expected,
        JTable currentTable,
        JTree currentTree
    ) {
        PartPrePressCheck {
            code = boundedText(code, MAX_PART_GESTURE_TEXT_LENGTH);
        }

        static PartPrePressCheck success(
            final PartPairLayout expected,
            final JTable currentTable,
            final JTree currentTree
        ) {
            return new PartPrePressCheck(true, "stable-structure", expected, currentTable, currentTree);
        }

        static PartPrePressCheck failure(
            final String code,
            final PartPairLayout expected,
            final JTable currentTable,
            final JTree currentTree
        ) {
            return new PartPrePressCheck(false, code, expected, currentTable, currentTree);
        }
    }

    /**
     * Temporary, non-consuming evidence listener for one Parts gesture. It is deliberately not a
     * global AWT listener: only the discovered JTable and its extracted JTree are observed, and
     * both listeners are removed in the gesture's finally block.
     */
    static final class PartGestureCapture {
        private final PartPairLayout layout;
        private final String intendedSourceId;
        private final String intendedTargetId;
        private final List<PartGestureEvent> events = new ArrayList<>();
        private final List<String> evidence = new ArrayList<>();
        private final Map<Integer, PartGestureEvent> firstMouseMovedByAttempt = new LinkedHashMap<>();
        private int pointerAttempt;
        private final MouseAdapter listener = new MouseAdapter() {
            @Override
            public void mousePressed(final MouseEvent event) {
                recordOnce("actual-press", event);
            }

            @Override
            public void mouseDragged(final MouseEvent event) {
                recordOnce("first-drag", event);
            }

            @Override
            public void mouseMoved(final MouseEvent event) {
                recordMouseMovedOnce(event);
            }

            @Override
            public void mouseReleased(final MouseEvent event) {
                recordOnce("release", event);
            }
        };
        private boolean tableInstalled;
        private boolean treeInstalled;

        PartGestureCapture(
            final PartPairLayout layout,
            final String intendedSourceId,
            final String intendedTargetId
        ) {
            this.layout = Objects.requireNonNull(layout, "layout");
            this.intendedSourceId = boundedText(intendedSourceId, MAX_PART_GESTURE_TEXT_LENGTH);
            this.intendedTargetId = boundedText(intendedTargetId, MAX_PART_GESTURE_TEXT_LENGTH);
        }

        void recordLayout() {
            addEvidence(layoutJson(layout, intendedSourceId, intendedTargetId));
        }

        void recordPrePress(final PartPrePressCheck check) {
            addEvidence(prePressJson(check, intendedSourceId, intendedTargetId));
        }

        void recordPointer(final PartPointerEvidence pointer) {
            if (pointer != null) addEvidence(pointer.json());
        }

        void beginPointerAttempt(final int attempt) {
            if (attempt < 1 || attempt > MAX_PART_POINTER_ATTEMPTS) {
                throw new IllegalArgumentException("pointer attempt out of bounds: " + attempt);
            }
            synchronized (events) {
                pointerAttempt = attempt;
            }
        }

        boolean installIfOpen(final AtomicBoolean lifecycleOpen) {
            if (!SwingUtilities.isEventDispatchThread()) {
                throw new IllegalStateException("Parts gesture listeners must install on the EDT");
            }
            if (lifecycleOpen == null || !lifecycleOpen.get()) return false;
            recordLayout();
            // The caller can time out while this EDT callback is queued. Recheck after evidence
            // capture so a late callback cannot install listeners after the gesture has closed.
            if (!lifecycleOpen.get()) return false;
            install();
            return true;
        }

        void install() {
            if (!SwingUtilities.isEventDispatchThread()) {
                throw new IllegalStateException("Parts gesture listeners must install on the EDT");
            }
            final JTable table = layout.table();
            if (table == null) throw new IllegalStateException("Parts table identity is unavailable");
            // Mark before each add: a custom/host component may add the listener and then throw.
            // remove() is idempotent for a listener that was not actually added.
            tableInstalled = true;
            table.addMouseListener(listener);
            table.addMouseMotionListener(listener);
            final JTree tree = layout.tree();
            if (tree != null) {
                treeInstalled = true;
                tree.addMouseListener(listener);
                tree.addMouseMotionListener(listener);
            }
        }

        void remove() {
            if (!SwingUtilities.isEventDispatchThread()) {
                throw new IllegalStateException("Parts gesture listeners must remove on the EDT");
            }
            if (tableInstalled && layout.table() != null) {
                layout.table().removeMouseListener(listener);
                layout.table().removeMouseMotionListener(listener);
                tableInstalled = false;
            }
            if (treeInstalled && layout.tree() != null) {
                layout.tree().removeMouseListener(listener);
                layout.tree().removeMouseMotionListener(listener);
                treeInstalled = false;
            }
        }

        /** Package-private test seam for a real Swing MouseEvent with an arbitrary source. */
        void recordMouseEventForTest(final String phase, final MouseEvent event) {
            if ("mouse-moved".equals(phase)) {
                recordMouseMovedOnce(event);
            } else {
                record(phase, event);
            }
        }

        PartGestureCheck checkEvents() {
            synchronized (events) {
                final PartGestureEvent press = eventLocked("actual-press");
                if (press == null) {
                    return new PartGestureCheck(PartGestureStatus.UNRESOLVED, "actual-press-missing");
                }
                if (!press.componentMatch() || !press.tableModelMatch() || !press.treeModelMatch()
                    || !press.sourceNodeMatch() || press.row() != layout.source().row()) {
                    return new PartGestureCheck(PartGestureStatus.MISMATCH, "actual-press-target-mismatch");
                }
                final PartGestureEvent drag = eventLocked("first-drag");
                if (drag == null) {
                    return new PartGestureCheck(PartGestureStatus.UNRESOLVED, "first-drag-missing");
                }
                if (!drag.componentMatch() || !drag.tableModelMatch() || !drag.treeModelMatch()) {
                    return new PartGestureCheck(PartGestureStatus.MISMATCH, "first-drag-surface-mismatch");
                }
                final PartGestureEvent release = eventLocked("release");
                if (release == null) {
                    return new PartGestureCheck(PartGestureStatus.UNRESOLVED, "release-missing");
                }
                if (!release.componentMatch() || !release.tableModelMatch() || !release.treeModelMatch()
                    || !release.targetNodeMatch()) {
                    return new PartGestureCheck(PartGestureStatus.MISMATCH, "release-target-mismatch");
                }
                return new PartGestureCheck(PartGestureStatus.ACCEPTED, "press-drag-release-matched");
            }
        }

        List<String> evidence() {
            synchronized (events) {
                return List.copyOf(evidence);
            }
        }

        private void recordOnce(final String phase, final MouseEvent event) {
            synchronized (events) {
                if (eventLocked(phase) != null) return;
            }
            record(phase, event);
        }

        private void recordMouseMovedOnce(final MouseEvent event) {
            if (event == null) return;
            synchronized (events) {
                if (pointerAttempt < 1 || firstMouseMovedByAttempt.containsKey(pointerAttempt)) {
                    return;
                }
                final PartGestureEvent firstMouseMoved = describe("mouse-moved", event);
                firstMouseMovedByAttempt.put(pointerAttempt, firstMouseMoved);
                addEvidence(firstMouseMoved.json(
                    layout, intendedSourceId, intendedTargetId, pointerAttempt
                ));
            }
        }

        private void record(final String phase, final MouseEvent event) {
            if (event == null) return;
            final PartGestureEvent observed = describe(phase, event);
            synchronized (events) {
                if (eventLocked(phase) != null || events.size() >= 3) return;
                events.add(observed);
                addEvidence(observed.json(layout, intendedSourceId, intendedTargetId));
            }
        }

        private PartGestureEvent eventLocked(final String phase) {
            return events.stream()
                .filter(value -> phase.equals(value.phase()))
                .findFirst()
                .orElse(null);
        }

        private PartGestureEvent describe(final String phase, final MouseEvent event) {
            final Component source = event.getComponent();
            final JTable table = layout.table();
            final JTree tree = layout.tree();
            Point local = null;
            TreePath path = null;
            int row = -1;
            int column = -1;
            if (source == table && table != null) {
                local = event.getPoint();
                row = table.rowAtPoint(local);
                column = table.columnAtPoint(local);
                path = tree == null ? null : tree.getPathForRow(row);
            } else if (source == tree && tree != null) {
                row = tree.getRowForLocation(event.getX(), event.getY());
                path = tree.getPathForRow(row);
            }
            final Object node = path == null ? null : path.getLastPathComponent();
            Rectangle cell = new Rectangle();
            Rectangle viewport = new Rectangle();
            if (table != null && row >= 0 && column >= 0
                && row < table.getRowCount() && column < table.getColumnCount()) {
                cell = table.getCellRect(row, column, true);
                viewport = table.getVisibleRect();
            }
            final Object sourceModel = source instanceof JTable sourceTable
                ? sourceTable.getModel()
                : source instanceof JTree sourceTree ? sourceTree.getModel() : null;
            final boolean tableModelMatch = source instanceof JTable sourceTable
                && sourceTable.getModel() == layout.tableModel();
            final boolean treeModelMatch = layout.treeModel() != null
                && layout.tree() != null
                && layout.tree().getModel() == layout.treeModel();
            return new PartGestureEvent(
                phase,
                SwingUtilities.isEventDispatchThread(),
                Thread.currentThread().getName(),
                source,
                sourceModel,
                row,
                column,
                path,
                node,
                local,
                new Point(event.getXOnScreen(), event.getYOnScreen()),
                cell,
                viewport,
                source == table,
                tableModelMatch,
                treeModelMatch,
                node == layout.source().node(),
                node == layout.target().node()
            );
        }

        private void addEvidence(final String line) {
            synchronized (events) {
                if (evidence.size() < MAX_PART_GESTURE_EVIDENCE_LINES) evidence.add(line);
            }
        }
    }

    record PartGestureEvent(
        String phase,
        boolean edt,
        String thread,
        Component source,
        Object sourceModel,
        int row,
        int column,
        TreePath path,
        Object node,
        Point local,
        Point screen,
        Rectangle cell,
        Rectangle viewport,
        boolean componentMatch,
        boolean tableModelMatch,
        boolean treeModelMatch,
        boolean sourceNodeMatch,
        boolean targetNodeMatch
    ) {
        PartGestureEvent {
            phase = boundedText(phase, MAX_PART_GESTURE_TEXT_LENGTH);
            thread = boundedText(thread, MAX_PART_GESTURE_TEXT_LENGTH);
            local = local == null ? null : new Point(local);
            screen = screen == null ? null : new Point(screen);
            cell = cell == null ? new Rectangle() : new Rectangle(cell);
            viewport = viewport == null ? new Rectangle() : new Rectangle(viewport);
        }

        String json(
            final PartPairLayout layout,
            final String intendedSourceId,
            final String intendedTargetId
        ) {
            return json(layout, intendedSourceId, intendedTargetId, "");
        }

        String json(
            final PartPairLayout layout,
            final String intendedSourceId,
            final String intendedTargetId,
            final int attempt
        ) {
            if (attempt < 1 || attempt > MAX_PART_POINTER_ATTEMPTS) {
                throw new IllegalArgumentException("pointer attempt out of bounds: " + attempt);
            }
            return json(
                layout, intendedSourceId, intendedTargetId, ",\"attempt\":" + attempt
            );
        }

        private String json(
            final PartPairLayout layout,
            final String intendedSourceId,
            final String intendedTargetId,
            final String attemptField
        ) {
            final JTree tree = layout.tree();
            return "{\"type\":\"part-gesture\",\"phase\":\""
                + WindowsHistoryNativeUiIngressProbe.json(phase)
                + "\"" + attemptField
                + ",\"threadEDT\":" + edt
                + ",\"thread\":\"" + WindowsHistoryNativeUiIngressProbe.json(thread)
                + "\",\"eventSource\":\""
                + WindowsHistoryNativeUiIngressProbe.json(source == null ? "" : source.getClass().getName())
                + "\",\"eventSourceIdentityHash\":" + identityHash(source)
                + ",\"eventSourceModelIdentityHash\":" + identityHash(sourceModel)
                + ",\"tableIdentityHash\":" + identityHash(layout.table())
                + ",\"treeIdentityHash\":" + identityHash(layout.tree())
                + ",\"tableModelIdentityHash\":" + identityHash(layout.tableModel())
                + ",\"treeModelIdentityHash\":" + identityHash(layout.treeModel())
                + ",\"intendedSourceId\":\""
                + WindowsHistoryNativeUiIngressProbe.json(intendedSourceId)
                + "\",\"intendedTargetId\":\""
                + WindowsHistoryNativeUiIngressProbe.json(intendedTargetId)
                + "\",\"row\":" + row
                + ",\"column\":" + column
                + ",\"pathLabel\":\""
                + WindowsHistoryNativeUiIngressProbe.json(treePathLabel(tree, path))
                + "\",\"lastNodeLabel\":\""
                + WindowsHistoryNativeUiIngressProbe.json(treeLabel(tree, node))
                + "\",\"lastNodeIdentityHash\":" + identityHash(node)
                + ",\"componentMatch\":" + componentMatch
                + ",\"tableModelMatch\":" + tableModelMatch
                + ",\"treeModelMatch\":" + treeModelMatch
                + ",\"sourceNodeObjectMatch\":" + sourceNodeMatch
                + ",\"targetNodeObjectMatch\":" + targetNodeMatch
                + ",\"local\":" + pointJson(local)
                + ",\"screen\":" + pointJson(screen)
                + ",\"cell\":" + rectangleJson(cell)
                + ",\"viewport\":" + rectangleJson(viewport)
                + "}\n";
        }
    }

    private static String layoutJson(
        final PartPairLayout layout,
        final String intendedSourceId,
        final String intendedTargetId
    ) {
        return "{\"type\":\"part-gesture\",\"phase\":\"layout\",\"threadEDT\":"
            + SwingUtilities.isEventDispatchThread()
            + ",\"thread\":\"" + json(Thread.currentThread().getName())
            + "\",\"tableIdentityHash\":" + identityHash(layout.table())
            + ",\"treeIdentityHash\":" + identityHash(layout.tree())
            + ",\"tableModelIdentityHash\":" + identityHash(layout.tableModel())
            + ",\"treeModelIdentityHash\":" + identityHash(layout.treeModel())
            + ",\"tableShowing\":" + layout.tableShowing()
            + ",\"intendedSourceId\":\"" + json(intendedSourceId)
            + "\",\"intendedTargetId\":\"" + json(intendedTargetId)
            + "\",\"surface\":" + partSurfaceJson(layout.table())
            + ",\"source\":" + rowJson(layout.source(), layout.tree())
            + ",\"target\":" + rowJson(layout.target(), layout.tree())
            + "}\n";
    }

    private static String prePressJson(
        final PartPrePressCheck check,
        final String intendedSourceId,
        final String intendedTargetId
    ) {
        final PartPairLayout layout = check == null ? null : check.expected();
        final JTable currentTable = check == null ? null : check.currentTable();
        final JTree currentTree = check == null ? null : check.currentTree();
        return "{\"type\":\"part-gesture\",\"phase\":\"pre-press\",\"threadEDT\":"
            + SwingUtilities.isEventDispatchThread()
            + ",\"thread\":\"" + json(Thread.currentThread().getName())
            + "\",\"ok\":" + (check != null && check.ok())
            + ",\"code\":\"" + json(check == null ? "missing-check" : check.code())
            + "\",\"intendedSourceId\":\"" + json(intendedSourceId)
            + "\",\"intendedTargetId\":\"" + json(intendedTargetId)
            + "\",\"expectedTableIdentityHash\":" + identityHash(layout == null ? null : layout.table())
            + ",\"expectedTreeIdentityHash\":" + identityHash(layout == null ? null : layout.tree())
            + ",\"expectedTableModelIdentityHash\":"
            + identityHash(layout == null ? null : layout.tableModel())
            + ",\"expectedTreeModelIdentityHash\":"
            + identityHash(layout == null ? null : layout.treeModel())
            + ",\"expectedTableShowing\":"
            + (layout != null && layout.table() != null && layout.tableShowing())
            + ",\"currentTableIdentityHash\":" + identityHash(currentTable)
            + ",\"currentTreeIdentityHash\":" + identityHash(currentTree)
            + ",\"currentTableModelIdentityHash\":"
            + identityHash(currentTable == null ? null : currentTable.getModel())
            + ",\"currentTreeModelIdentityHash\":"
            + identityHash(currentTree == null ? null : currentTree.getModel())
            + ",\"currentTableShowing\":"
            + (currentTable != null && currentTable.isShowing())
            + ",\"source\":" + rowJson(layout == null ? null : layout.source(),
                layout == null ? null : layout.tree())
            + ",\"target\":" + rowJson(layout == null ? null : layout.target(),
                layout == null ? null : layout.tree())
            + "}\n";
    }

    private static String partSurfaceJson(final JTable table) {
        Window window = null;
        try {
            window = table == null ? null : SwingUtilities.getWindowAncestor(table);
        } catch (RuntimeException ignored) {
            // An unavailable host surface is represented by null fields below.
        }

        Point tableLocation = null;
        if (table != null && table.isShowing()) {
            try {
                tableLocation = new Point(table.getLocationOnScreen());
            } catch (RuntimeException ignored) {
                // Keep an explicit null for a non-showing or transitioning surface.
            }
        }

        Rectangle windowBounds = null;
        Insets windowInsets = null;
        if (window != null) {
            try {
                windowBounds = new Rectangle(window.getBounds());
            } catch (RuntimeException ignored) {
                // Keep an explicit null when the window bounds cannot be observed.
            }
            try {
                windowInsets = window.getInsets();
            } catch (RuntimeException ignored) {
                // Keep an explicit null when the window insets cannot be observed.
            }
        }

        GraphicsConfiguration graphicsConfiguration = null;
        try {
            if (table != null) graphicsConfiguration = table.getGraphicsConfiguration();
            if (graphicsConfiguration == null && window != null) {
                graphicsConfiguration = window.getGraphicsConfiguration();
            }
        } catch (RuntimeException ignored) {
            // Keep explicit null graphics fields when the surface is transitioning.
        }
        Rectangle graphicsBounds = null;
        AffineTransform defaultTransform = null;
        if (graphicsConfiguration != null) {
            try {
                graphicsBounds = new Rectangle(graphicsConfiguration.getBounds());
            } catch (RuntimeException ignored) {
                // Keep an explicit null when graphics bounds are unavailable.
            }
            try {
                defaultTransform = new AffineTransform(
                    graphicsConfiguration.getDefaultTransform()
                );
            } catch (RuntimeException ignored) {
                // Keep an explicit null when the device transform is unavailable.
            }
        }
        return "{\"tableLocationOnScreen\":" + pointJson(tableLocation)
            + ",\"windowBounds\":" + rectangleJson(windowBounds)
            + ",\"windowInsets\":" + insetsJson(windowInsets)
            + ",\"graphicsConfigurationBounds\":" + rectangleJson(graphicsBounds)
            + ",\"graphicsConfigurationDefaultTransform\":"
            + transformJson(defaultTransform)
            + "}";
    }

    private static String insetsJson(final Insets insets) {
        if (insets == null) return "null";
        return "{\"top\":" + insets.top
            + ",\"left\":" + insets.left
            + ",\"bottom\":" + insets.bottom
            + ",\"right\":" + insets.right + "}";
    }

    private static String transformJson(final AffineTransform transform) {
        if (transform == null) return "null";
        return "{\"scaleX\":" + transform.getScaleX()
            + ",\"scaleY\":" + transform.getScaleY()
            + ",\"shearX\":" + transform.getShearX()
            + ",\"shearY\":" + transform.getShearY()
            + ",\"translateX\":" + transform.getTranslateX()
            + ",\"translateY\":" + transform.getTranslateY() + "}";
    }

    private static String rowJson(final PartRowLocation row, final JTree tree) {
        if (row == null) return "null";
        return "{\"row\":" + row.row()
            + ",\"column\":" + row.column()
            + ",\"pathLabel\":\"" + json(treePathLabel(tree, row.path()))
            + "\",\"lastNodeLabel\":\"" + json(row.label())
            + "\",\"lastNodeIdentityHash\":" + identityHash(row.node())
            + ",\"local\":" + pointJson(row.localPoint())
            + ",\"screen\":" + pointJson(row.screenPoint())
            + ",\"cell\":" + rectangleJson(row.cell())
            + ",\"viewport\":" + rectangleJson(row.viewport())
            + "}";
    }

    private static String pointJson(final Point point) {
        return point == null ? "null" : "{\"x\":" + point.x + ",\"y\":" + point.y + "}";
    }

    private static String rectangleJson(final Rectangle rectangle) {
        if (rectangle == null) return "null";
        return "{\"x\":" + rectangle.x + ",\"y\":" + rectangle.y
            + ",\"width\":" + rectangle.width + ",\"height\":" + rectangle.height + "}";
    }

    private static int identityHash(final Object value) {
        return value == null ? 0 : System.identityHashCode(value);
    }

    /** Immutable read-only Part facts used by the Parts actor. */
    record ActorPart(
        String id,
        String name,
        Optional<String> parentId,
        List<String> childIds
    ) {
        ActorPart {
            Objects.requireNonNull(id, "id");
            parentId = parentId == null ? Optional.empty() : parentId;
            childIds = childIds == null ? List.of() : List.copyOf(childIds);
        }
    }

    record PartModelSnapshot(String modelId, List<ActorPart> parts) {
        PartModelSnapshot {
            Objects.requireNonNull(modelId, "modelId");
            parts = parts == null ? List.of() : List.copyOf(parts);
        }
    }

    record PartPair(String modelId, ActorPart source, ActorPart target) {
        PartPair {
            Objects.requireNonNull(modelId, "modelId");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(target, "target");
        }
    }

    enum PartDragStatus {
        CHANGED,
        NO_CHANGE,
        MISMATCH,
        UNAVAILABLE
    }

    @FunctionalInterface
    interface PartDragReadback {
        PartDragCheck read(int poll) throws Exception;
    }

    enum PartDragSettlementStatus {
        CHANGED,
        MISMATCH,
        UNAVAILABLE,
        RETRY
    }

    record PartDragSettlement(
        PartDragSettlementStatus status,
        PartDragCheck lastCheck
    ) {
        PartDragSettlement {
            Objects.requireNonNull(status, "status");
        }
    }

    record PartDragCheck(
        PartDragStatus status,
        String code,
        String sourceId,
        String targetId,
        Optional<String> parentBefore,
        Optional<String> parentAfter
    ) {
        PartDragCheck {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(code, "code");
            parentBefore = parentBefore == null ? Optional.empty() : parentBefore;
            parentAfter = parentAfter == null ? Optional.empty() : parentAfter;
        }

        String evidence() {
            return "code=" + code
                + ":sourceId=" + sourceId
                + ":targetId=" + targetId
                + ":parentBefore=" + parentText(parentBefore)
                + ":parentAfter=" + parentText(parentAfter);
        }
    }

    enum PartActorOutcome {
        NOT_RUN,
        CHANGED,
        MISMATCH,
        UNRESOLVED,
        EXCEPTION
    }

    record PartActorResult(PartActorOutcome outcome, String code, String evidence) {
        PartActorResult {
            Objects.requireNonNull(outcome, "outcome");
            code = boundedText(code, MAX_PART_GESTURE_TEXT_LENGTH);
            evidence = boundedText(evidence, MAX_PART_GESTURE_TEXT_LENGTH);
        }

        static PartActorResult notRun() {
            return new PartActorResult(PartActorOutcome.NOT_RUN, "not-run", "");
        }

        static PartActorResult fromActorResult(final String result) {
            final String safe = result == null ? "" : result;
            if (safe.startsWith("changed:")) {
                return new PartActorResult(PartActorOutcome.CHANGED, "changed", safe);
            }
            if (safe.startsWith("mismatch:")) {
                return new PartActorResult(PartActorOutcome.MISMATCH, "mismatch", safe);
            }
            if (safe.startsWith("unresolved:")) {
                return new PartActorResult(PartActorOutcome.UNRESOLVED, "unresolved", safe);
            }
            if (safe.startsWith("failed:") || "interrupted".equals(safe)) {
                return exception("actor-exception", safe);
            }
            return exception("actor-result-unclassified", safe);
        }

        static PartActorResult exception(final String code, final String evidence) {
            return new PartActorResult(PartActorOutcome.EXCEPTION, code, evidence);
        }

        boolean accepted() {
            return outcome == PartActorOutcome.CHANGED;
        }

        String json(final String step) {
            return "{\"type\":\"part-actor-outcome\",\"step\":\""
                + WindowsHistoryNativeUiIngressProbe.json(step)
                + "\",\"outcome\":\"" + WindowsHistoryNativeUiIngressProbe.json(outcome.name())
                + "\",\"code\":\"" + WindowsHistoryNativeUiIngressProbe.json(code)
                + "\",\"accepted\":" + accepted()
                + ",\"evidence\":\"" + WindowsHistoryNativeUiIngressProbe.json(evidence)
                + "\"}\n";
        }
    }

    record PartGesturePreparation(
        PartModelSnapshot model,
        PartPairLayout layout,
        String significantSequence,
        String reason
    ) {
        boolean ready() {
            return layout != null && reason != null && reason.isBlank();
        }

        static PartGesturePreparation unavailable(
            final PartModelSnapshot model,
            final String reason
        ) {
            return new PartGesturePreparation(model, null, null, reason);
        }
    }

    record PartRowLocation(
        TreePath path,
        Object node,
        String label,
        int row,
        int column,
        Rectangle cell,
        Rectangle viewport,
        Point localPoint,
        Point screenPoint
    ) {
        PartRowLocation {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(node, "node");
            label = boundedText(label, MAX_PART_GESTURE_TEXT_LENGTH);
            cell = new Rectangle(Objects.requireNonNull(cell, "cell"));
            viewport = new Rectangle(Objects.requireNonNull(viewport, "viewport"));
            localPoint = new Point(Objects.requireNonNull(localPoint, "localPoint"));
            screenPoint = screenPoint == null ? null : new Point(screenPoint);
        }

        PartRowLocation(
            final TreePath path,
            final int row,
            final int column,
            final Rectangle cell,
            final Rectangle viewport,
            final Point localPoint,
            final Point screenPoint
        ) {
            this(
                path,
                path == null ? null : path.getLastPathComponent(),
                path == null ? "" : String.valueOf(path.getLastPathComponent()),
                row,
                column,
                cell,
                viewport,
                localPoint,
                screenPoint
            );
        }

        PartRowLocation withScreenPoint(final Point point) {
            return new PartRowLocation(path, node, label, row, column, cell, viewport, localPoint, point);
        }
    }

    record PartPairLayout(
        JTable table,
        JTree tree,
        TableModel tableModel,
        TreeModel treeModel,
        PartRowLocation source,
        PartRowLocation target,
        boolean tableShowing
    ) {
        PartPairLayout {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(target, "target");
        }

        PartPairLayout(final PartRowLocation source, final PartRowLocation target) {
            this(null, null, null, null, source, target, false);
        }

        PartPairLayout withScreenPoints(final JTable table) {
            return new PartPairLayout(
                this.table,
                this.tree,
                this.tableModel,
                this.treeModel,
                source.withScreenPoint(toScreenPoint(table, source.localPoint())),
                target.withScreenPoint(toScreenPoint(table, target.localPoint())),
                tableShowing
            );
        }
    }

    private static Point toScreenPoint(
        final JTable table,
        final Point local
    ) {
        final java.awt.Point screen = new java.awt.Point(local);
        SwingUtilities.convertPointToScreen(screen, table);
        return screen;
    }

    /** One operator instruction and the kind of native outcome it must produce. */
    record Step(String id, String kind, String instruction) {
    }

    private record ConfirmationPair(Observed on, Observed after) {
    }

    private record ConfirmationCheck(
        boolean valid,
        String code,
        List<ConfirmationPair> pairs
    ) {
        private static ConfirmationCheck invalid(final String code) {
            return new ConfirmationCheck(false, code, List.of());
        }
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
