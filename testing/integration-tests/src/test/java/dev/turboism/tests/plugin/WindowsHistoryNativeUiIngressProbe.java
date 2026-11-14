package dev.turboism.tests.plugin;

import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressCanvasActor.canvasCandidates;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressCanvasActor.confirmPoint;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressCanvasActor.fieldCentre;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressCanvasActor.hostWindowRaiser;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressCanvasActor.newWindowSince;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressCanvasActor.pressTarget;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressCanvasActor.visibleBounds;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressInspectorSurface.INSPECTOR_MARKERS;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressInspectorSurface.UI_MAP_MAX_CHARS;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressInspectorSurface.collectEditableFields;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressInspectorSurface.cwidgetCensus;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressInspectorSurface.cwidgetEditor;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressInspectorSurface.cwidgetRect;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressInspectorSurface.cwidgetSurface;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressInspectorSurface.cwidgetTextComponent;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressInspectorSurface.cwidgetTree;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressInspectorSurface.inspectorShowing;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressInspectorSurface.multiplyMarked;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressInspectorSurface.openPalette;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressInspectorSurface.paletteTabSelected;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressInspectorSurface.saturatedBlocks;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressInspectorSurface.uiMap;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressMenuShortcuts.ShortcutResolution;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressMenuShortcuts.menuShortcut;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressMenuShortcuts.shortcutResolution;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressParameterEvidence.MAX_PARAMETER_ID_LENGTH;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressParameterEvidence.MAX_PARAMETER_LIFECYCLE_EVENTS;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressParameterEvidence.MAX_PARAMETER_VALUES;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressParameterEvidence.PARAMETER_MARKERS;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressParameterEvidence.ParameterChangeObservation;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressParameterEvidence.ParameterHistoryAdmission;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressParameterEvidence.ParameterLifecycleAssessment;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressParameterEvidence.ParameterLifecycleEvent;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressParameterEvidence.ParameterLifecycleStatus;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressParameterEvidence.ParameterStateOutcome;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressParameterEvidence.ParameterStateSnapshot;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressParameterEvidence.ParameterValueSample;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressParameterEvidence.assessParameterLifecycle;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressParameterEvidence.changedParameterIds;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressParameterEvidence.compareParameterState;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressParameterEvidence.finiteValue;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressParameterEvidence.parameterDockSliders;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressParameterEvidence.parameterEvidenceJson;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressParameterEvidence.parameterHistoryAdmission;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressParameterEvidence.parameterStateJson;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressParameterEvidence.readFiniteParameterValue;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressParameterEvidence.readParameterSnapshot;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressParameterEvidence.sdkHistory;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressParameterEvidence.settleParameterObservations;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressParameterEvidence.significantSequence;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressParameterEvidence.sliderDragPlan;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressPartActors.ActorPart;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressPartActors.MAX_PART_GESTURE_EVIDENCE_LINES;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressPartActors.MAX_PART_GESTURE_PAIRS;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressPartActors.PART_GESTURE_SETTLE_POLLS;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressPartActors.PartActorResult;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressPartActors.PartDragCheck;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressPartActors.PartDragSettlement;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressPartActors.PartDragSettlementStatus;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressPartActors.PartDragStatus;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressPartActors.PartGestureAttempt;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressPartActors.PartGesturePreparation;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressPartActors.PartGestureStatus;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressPartActors.PartModelSnapshot;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressPartActors.PartPair;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressPartActors.PartPairLayout;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressPartActors.PartPrePressCheck;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressPartActors.admitPartStep;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressPartActors.assessPartDrag;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressPartActors.awaitParameterGesture;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressPartActors.currentPartPrePressSurface;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressPartActors.hasMoved;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressPartActors.locatePartPair;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressPartActors.paired;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressPartActors.partEvidence;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressPartActors.partGestureTargetPoint;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressPartActors.partIndex;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressPartActors.partPairMatches;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressPartActors.robotDrag;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressPartActors.robotPartGestureInput;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressPartActors.runPartGesture;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressPartActors.safePartPairs;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressPartActors.selectPartsTreeRow;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressPartActors.settleMillis;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressPartActors.settlePartDrag;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressPartActors.verifyPartPrePress;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.ACTION_SETTLE_MILLIS;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.AUTOMATE;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.AWAIT_DOCUMENT_MILLIS;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.MAX_RECORDED_EVENTS;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.Observed;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.POLL_MILLIS;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.REMINDER_MILLIS;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.SAMPLE_TIMEOUT_MILLIS;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.STEPS;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.STEP_TIMEOUT_MILLIS;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.Step;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.Verdict;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.checkPartMembershipStep;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.checkStep;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.json;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.onEdt;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.position;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.quoted;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.replacementValue;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.summaryLine;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.typeChar;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.write;

import dev.turboism.sdk.cubism.CubismPlugin;
import dev.turboism.sdk.cubism.event.CubismOperationEvent;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.plugin.PluginContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

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
        recordParameterLifecycle("before", parameter, readFiniteParameterValue(parameter), finiteValue(value));
        return value;
    }

    @Override
    public void onParameterValueChanged(final Parameter parameter, final float oldValue, final float newValue) {
        recordParameterLifecycle("on", parameter, finiteValue(oldValue), finiteValue(newValue));
    }

    @Override
    public void afterSetParameterValue(final Parameter parameter, final float value) {
        recordParameterLifecycle("after", parameter, null, finiteValue(value));
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
                    Instant.now().toString());
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
            final String phase, final Parameter parameter, final Float oldValue, final Float newValue) {
        try {
            final String parameterId = parameter == null || parameter.id() == null
                    ? ""
                    : parameter.id().value();
            final ParameterLifecycleEvent value;
            synchronized (lock) {
                value = new ParameterLifecycleEvent(
                        ++parameterLifecycleSequence,
                        phase,
                        parameterId == null ? "" : parameterId,
                        oldValue,
                        newValue,
                        Thread.currentThread().getName());
                if (parameterLifecycle.size() >= MAX_PARAMETER_LIFECYCLE_EVENTS) {
                    parameterLifecycle.remove(0);
                }
                parameterLifecycle.add(value);
            }
        } catch (Throwable ignored) {
            // A probe callback must never disturb the native parameter operation.
        }
    }

    private void run() {
        artifact = context.paths().dataDir().resolve("history-native-ui-ingress.jsonl");
        boolean terminalSummaryWritten = false;
        try {
            Files.createDirectories(artifact.getParent());
            if (Files.exists(artifact)) throw new IllegalStateException("Native UI ingress evidence already exists");
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
                final String instruction =
                        "STEP " + (index + 1) + "/" + STEPS.size() + " [" + step.id() + "] " + step.instruction();
                write(
                        artifact,
                        "{\"type\":\"prompt\",\"phase\":\"" + json(step.id()) + "\",\"instruction\":\""
                                + json(step.instruction()) + "\",\"openedAt\":\"" + Instant.now()
                                + "\",\"expiresAt\":\""
                                + Instant.ofEpochMilli(System.currentTimeMillis() + STEP_TIMEOUT_MILLIS) + "\"}\n",
                        false);
                context.logger().info(instruction);

                final WindowsHistoryManagerValidationProbe.Snapshot semanticBaseline =
                        "parts-tree-drag".equals(step.id()) ? sample() : null;
                if ("parts-tree-drag".equals(step.id())) {
                    write(artifact, paired(semanticBaseline, step.id() + "-semantic-baseline"), false);
                }

                final ParameterStateSnapshot parameterBefore = "native-parameter".equals(step.id())
                        ? readParameterSnapshot(this::readParameterSnapshotOnEdt)
                        : null;
                if (parameterBefore != null) {
                    write(artifact, parameterStateJson("native-parameter", "before", parameterBefore), false);
                }

                ParameterChangeObservation actorParameterTermination = null;
                if (AUTOMATE) {
                    final String actor = act(step, knownSignificant, knownPosition, parameterBefore);
                    actorParameterTermination = parameterActorTermination;
                    write(
                            artifact,
                            "{\"type\":\"actor\",\"phase\":\"" + json(step.id())
                                    // The discovery detail legitimately exceeds a label's bound; clipping
                                    // it hid why the r9 Parts-tree selection never resolved.
                                    + "\",\"result\":\"" + json(actor, 8192) + "\"}\n",
                            false);
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
                        write(
                                artifact,
                                "{\"type\":\"ui-map\",\"phase\":\"" + json(step.id())
                                        + "\",\"at\":\"" + Instant.now()
                                        + "\",\"map\":\"" + json(uiMap(), UI_MAP_MAX_CHARS) + "\"}\n",
                                false);
                    }
                }

                if ("native-parameter".equals(step.id())) {
                    final ParameterChangeObservation parameterObservation =
                            awaitParameterChange(parameterBefore, actorParameterTermination);
                    final WindowsHistoryManagerValidationProbe.Snapshot after = sample();
                    final ParameterStateSnapshot parameterAfter = parameterObservation.after();
                    final ParameterStateOutcome valueStatus = parameterObservation.outcome();
                    final ParameterHistoryAdmission historyAdmission = after == null
                            ? ParameterHistoryAdmission.UNAVAILABLE
                            : parameterHistoryAdmission(knownSignificant, significantSequence(after));
                    final Set<String> changedParameterIds = changedParameterIds(parameterBefore, parameterAfter);
                    final List<ParameterLifecycleEvent> lifecycleEvents =
                            parameterLifecycleSince(parameterLifecycleStart);
                    final boolean lifecycleWindowComplete = parameterLifecycleWindowComplete(parameterLifecycleStart);
                    final ParameterLifecycleAssessment lifecycleAssessment = assessParameterLifecycle(
                            lifecycleEvents,
                            parameterBefore,
                            parameterAfter,
                            changedParameterIds,
                            valueStatus == ParameterStateOutcome.CHANGED,
                            lifecycleWindowComplete);

                    write(
                            artifact,
                            parameterStateJson(
                                    "native-parameter",
                                    "after",
                                    parameterAfter == null
                                            ? ParameterStateSnapshot.unavailable("no-readback")
                                            : parameterAfter),
                            false);
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
                                    lifecycleEvents),
                            false);
                    write(artifact, paired(after, step.id()), false);

                    final List<Observed> events;
                    synchronized (lock) {
                        events = List.copyOf(observed.subList(Math.min(start, observed.size()), observed.size()));
                    }
                    for (Observed event : events) {
                        write(artifact, event.json(step.id()), false);
                    }

                    final String parameterDetail = "valueStatus=" + valueStatus.code()
                            + ",historyAdmission=" + historyAdmission.code()
                            + ",lifecycle=" + lifecycleAssessment.status().code() + ",modelCorrelation="
                            + lifecycleAssessment.modelCorrelation().code();
                    final Verdict valueVerdict = new Verdict(
                            valueStatus == ParameterStateOutcome.CHANGED,
                            "parameter-value-" + valueStatus.code(),
                            parameterDetail);
                    write(artifact, valueVerdict.json(step.id()), false);
                    if (!valueVerdict.ok()) {
                        failures.add(step.id() + ":" + valueVerdict.code());
                    }
                    final Verdict lifecycleVerdict = new Verdict(
                            lifecycleAssessment.status() == ParameterLifecycleStatus.COMPLETE,
                            "parameter-lifecycle-"
                                    + lifecycleAssessment.status().code(),
                            parameterDetail);
                    write(artifact, lifecycleVerdict.json(step.id()), false);
                    if (!lifecycleVerdict.ok()) {
                        failures.add(step.id() + ":" + lifecycleVerdict.code());
                    }
                    hookFired |= events.stream().anyMatch(event -> event.phase().equals("before"));
                    observerFired |= events.stream()
                            .anyMatch(event ->
                                    event.phase().equals("on") || event.phase().equals("after"));
                    labelSeen |= events.stream()
                            .anyMatch(event -> event.phase().equals("before")
                                    && !event.label().isBlank());
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
                            false);
                    if ("parts-tree-drag".equals(step.id())) {
                        final Verdict semanticVerdict = checkPartMembershipStep(sdkHistory(semanticBaseline), null);
                        write(artifact, semanticVerdict.json("parts-tree-drag-semantic"), false);
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
                    final Verdict semanticVerdict =
                            checkPartMembershipStep(sdkHistory(semanticBaseline), sdkHistory(after));
                    write(artifact, semanticVerdict.json("parts-tree-drag-semantic"), false);
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
                observerFired |= events.stream()
                        .anyMatch(event ->
                                event.phase().equals("on") || event.phase().equals("after"));
                labelSeen |= events.stream()
                        .anyMatch(event ->
                                event.phase().equals("before") && !event.label().isBlank());
            }

            final WindowsHistoryManagerValidationProbe.Snapshot finalSnapshot = sample();
            write(artifact, paired(finalSnapshot, "final"), false);
            write(
                    artifact,
                    new Verdict(
                                    hookFired,
                                    "hook-fired",
                                    hookFired ? "at least one before event observed" : "no before event observed")
                            .json("overall"),
                    false);
            write(
                    artifact,
                    new Verdict(
                                    observerFired,
                                    "observer-fired",
                                    observerFired
                                            ? "the ingress attached and published at least one operation"
                                            : "no operation was published for any native change")
                            .json("overall"),
                    false);
            write(
                    artifact,
                    new Verdict(
                                    labelSeen,
                                    "label-transported",
                                    labelSeen
                                            ? "at least one before event carried the native edit name"
                                            : "every before event carried an empty name")
                            .json("overall"),
                    false);
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
                                    failures.stream()
                                            .map(WindowsHistoryNativeUiIngressVerdicts::quoted)
                                            .toList())
                            + "]}\n",
                    false);
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
                        true);
                terminalSummaryWritten = true;
            } catch (Exception ignored) {
                context.logger().error("Native UI ingress evidence could not be written", ignored);
            }
        } catch (Exception exception) {
            context.logger().error("Native UI ingress probe failed", exception);
            try {
                write(
                        artifact,
                        "{\"type\":\"error\",\"class\":\""
                                + json(exception.getClass().getName())
                                + "\",\"message\":\"" + json(exception.getMessage()) + "\"}\n"
                                + "{\"type\":\"summary\",\"status\":\"FAIL\"}\n",
                        true);
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
                                    System.getProperty(WindowsHistoryNativeUiHostClose.HOST_VERSION_PROPERTY));
                    context.logger()
                            .info("Native UI ingress automated host close status=" + close.status() + " reason="
                                    + close.reason());
                } catch (Exception closeFailure) {
                    // The summary was already persisted. Keep the failure visible so the Runner's
                    // normal-exit gate, rather than this probe, decides whether the task passed.
                    context.logger()
                            .error(
                                    "Native UI ingress automated host close failed; normal exit remains unverified",
                                    closeFailure);
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
            final Step step, final String knownSignificant, final long knownPosition) throws Exception {
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
            if (hasMoved(step, significantSequence(current), position(current), knownSignificant, lastPosition)) {
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
                    "{\"type\":\"reminder\",\"phase\":\"" + json(step.id()) + "\",\"at\":\"" + Instant.now() + "\"}\n",
                    false);
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

    String act(final Step step, final String knownSignificant, final long knownPosition) {
        return act(step, knownSignificant, knownPosition, null);
    }

    private String act(
            final Step step,
            final String knownSignificant,
            final long knownPosition,
            final ParameterStateSnapshot parameterBefore) {
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
                case "native-undo" -> shortcut(java.awt.event.KeyEvent.VK_Z, knownPosition, step.kind());
                case "native-redo" -> shortcut(java.awt.event.KeyEvent.VK_Y, knownPosition, step.kind());
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
                        failure.getClass().getSimpleName(), message == null ? failure.toString() : message);
            }
            return "failed:" + failure.getClass().getSimpleName() + (message == null ? "" : ":" + message);
        } finally {
            raiser.interrupt();
        }
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
            final PartGesturePreparation preparation = onEdt(() -> preparePartGesture(pair));
            if (!preparation.ready()) {
                if ("no-safe-visible-pair".equals(preparation.reason())) continue;
                return "unresolved:" + preparation.reason() + ":sourceId="
                        + pair.source().id() + ":targetId=" + pair.target().id();
            }
            sawVisiblePair = true;
            if (!Objects.equals(lastSignificant, preparation.significantSequence())) {
                return "mismatch:significant-changed-before-gesture:"
                        + partEvidence(
                                pair, pair.source().parentId(), pair.source().parentId());
            }

            final java.awt.Point sourcePoint = preparation.layout().source().screenPoint();
            final java.awt.Point targetPoint = partGestureTargetPoint(preparation.layout());
            if (sourcePoint == null || targetPoint == null) {
                return "unresolved:part-screen-point-unavailable:"
                        + partEvidence(
                                pair, pair.source().parentId(), pair.source().parentId());
            }
            final PartGestureAttempt gesture = runPartGesture(
                    preparation.layout(),
                    pair.source().id(),
                    pair.target().id(),
                    robotPartGestureInput(),
                    WindowsHistoryNativeUiIngressVerdicts::readMouseInfoPointer,
                    () -> currentPartPrePress(pair, preparation.layout()));
            appendPartGestureEvidence(gesture.evidence());
            if (gesture.status() != PartGestureStatus.ACCEPTED) {
                final String prefix = gesture.status() == PartGestureStatus.MISMATCH
                        ? "mismatch:"
                        : gesture.status() == PartGestureStatus.EXCEPTION ? "failed:" : "unresolved:";
                return prefix + gesture.code() + ":sourceId=" + pair.source().id() + ":targetId="
                        + pair.target().id();
            }

            final PartDragSettlement settlement = settlePartDrag(PART_GESTURE_SETTLE_POLLS, poll -> {
                Thread.sleep(POLL_MILLIS);
                final WindowsHistoryManagerValidationProbe.Snapshot history = sample();
                final String afterSignificant = history == null ? null : significantSequence(history);
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
                            Optional.empty());
                }
                return assessPartDrag(
                        pair, preparation.model(), after, preparation.significantSequence(), afterSignificant);
            });
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
                + onEdt(WindowsHistoryNativeUiIngressPartActors::partsTreeDnD);
    }

    private PartGesturePreparation preparePartGesture(final PartPair pair) {
        final PartModelSnapshot current = readPartModel();
        if (!partPairMatches(current, pair)) return PartGesturePreparation.unavailable(current, "part-model-changed");
        final PartPairLayout layout =
                locatePartPair(pair.source().name(), pair.target().name());
        if (layout == null) return PartGesturePreparation.unavailable(current, "no-safe-visible-pair");
        final WindowsHistoryManagerValidationProbe.Snapshot history;
        try {
            history = WindowsHistoryManagerValidationProbe.sample(context);
        } catch (Exception unavailable) {
            return PartGesturePreparation.unavailable(current, "history-unavailable");
        }
        if (history == null) return PartGesturePreparation.unavailable(current, "history-unavailable");
        return new PartGesturePreparation(current, layout, significantSequence(history), "");
    }

    /** Reads the SDK Part graph and the current Parts surface in the final EDT guard. */
    private PartPrePressCheck currentPartPrePress(final PartPair pair, final PartPairLayout expected) {
        final PartModelSnapshot current;
        try {
            current = readPartModel();
        } catch (Exception unavailable) {
            try {
                final PartPrePressCheck surface = currentPartPrePressSurface(expected);
                return PartPrePressCheck.failure(
                        "part-model-unavailable", expected, surface.currentTable(), surface.currentTree());
            } catch (Exception surfaceUnavailable) {
                return PartPrePressCheck.failure("part-model-unavailable", expected, null, null);
            }
        }
        final PartPrePressCheck surface = currentPartPrePressSurface(expected);
        return verifyPartPrePress(expected, surface.currentTable(), surface.currentTree(), pair, current);
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
            final List<String> childIds =
                    part.childIds().stream().map(value -> value.value()).toList();
            parts.add(new ActorPart(part.id().value(), part.name(), parentId, childIds));
        }
        final PartModelSnapshot snapshot = new PartModelSnapshot(model.id().value(), parts);
        if (partIndex(snapshot) == null) throw new IllegalStateException("Part relationship snapshot is incomplete");
        return snapshot;
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
        final List<String> names = onEdt(() -> context.cubism().model().active().parts().all().stream()
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
            final int[] point = onEdt(() -> WindowsHistoryNativeUiIngressPartActors.partsRowPoint(name));
            if (point != null) {
                source = name;
                fromPoint = point;
                break;
            }
        }
        if (fromPoint == null) return "unresolved:no-selectable-part-row:" + names;
        final int fromX = fromPoint[0];
        final int fromY = fromPoint[1];
        final ArrayList<String> tried = new ArrayList<>();
        for (final String name : names) {
            if (name.equals(source) || tried.size() >= 3) continue;
            final int[] toPoint = onEdt(() -> WindowsHistoryNativeUiIngressPartActors.partsRowPoint(name));
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
                        return "dragged:" + source + "->" + name + ":drop=" + (drop == 0 ? "on-row" : "row-edge")
                                + ":targets=" + tried.size();
                    }
                }
            }
        }
        if (tried.isEmpty()) return "unresolved:no-target-row:" + names;
        return "dragged:" + source + "->tried=" + tried + ":no-significant-entry:"
                + onEdt(WindowsHistoryNativeUiIngressPartActors::partsTreeDnD);
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
        List<java.awt.Component> candidates = onEdt(WindowsHistoryNativeUiIngressCanvasActor::canvasCandidates);
        for (int retry = 0; candidates.isEmpty() && retry < 4; retry++) {
            Thread.sleep(POLL_MILLIS);
            candidates = onEdt(WindowsHistoryNativeUiIngressCanvasActor::canvasCandidates);
        }
        if (candidates.isEmpty()) {
            // The r13 run returned empty here while the post-action map showed a showing leaf —
            // record what was actually rejected so the next run names the reason instead of
            // needing another guess.
            return "unresolved:no-canvas-component:"
                    + onEdt(WindowsHistoryNativeUiIngressInspectorSurface::canvasRejects);
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
                        return "dragged:" + canvas.getClass().getName() + visible + ":hit=" + hit + ":attempt="
                                + (tried.size() + 1);
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
            final String reason = parameterBefore == null ? "no-before-readback" : parameterBefore.reason();
            recordParameterActorTermination(new ParameterChangeObservation(
                    parameterBefore != null && "model-changed".equals(reason)
                            ? ParameterStateOutcome.MODEL_CHANGED
                            : ParameterStateOutcome.UNAVAILABLE,
                    parameterBefore,
                    reason));
            return "unresolved:parameter-state:" + reason;
        }
        // The Parameter palette's value rows are CSlider widgets whose Swing mirrors are
        // com.live2d.ui.swingImpl.A (a JSlider subclass). They only exist inside the
        // parameter tab's dock column — the status-bar zoom slider is also an A, so the
        // palette rows must be scoped to that column. Readback, not Undo admission, decides
        // whether this actor stops after a gesture.
        final java.awt.Robot robot = new java.awt.Robot();
        String paletteNote = "";
        List<javax.swing.JSlider> dockSliders =
                onEdt(WindowsHistoryNativeUiIngressParameterEvidence::parameterDockSliders);
        if (dockSliders.isEmpty()) {
            paletteNote = "palette="
                    + openPalette(
                            PARAMETER_MARKERS, () -> !parameterDockSliders().isEmpty());
            for (int retry = 0; dockSliders.isEmpty() && retry < 12; retry++) {
                Thread.sleep(POLL_MILLIS);
                dockSliders = onEdt(WindowsHistoryNativeUiIngressParameterEvidence::parameterDockSliders);
            }
            if (dockSliders.isEmpty()) {
                paletteNote += ":scope=" + onEdt(WindowsHistoryNativeUiIngressInspectorSurface::paletteClassCensus);
            }
        }
        final ArrayList<String> tried = new ArrayList<>();
        for (final javax.swing.JSlider slider : dockSliders) {
            final int[] plan = onEdt(() -> sliderDragPlan(slider));
            if (plan == null) continue;
            robotDrag(plan[0], plan[1], plan[2], plan[3]);
            final ParameterChangeObservation changed = awaitParameterGestureAndRecord(parameterBefore);
            if (changed.outcome() == ParameterStateOutcome.CHANGED) {
                return "changed:param-row:" + slider.getClass().getName() + ":attempt=" + (tried.size() + 1);
            }
            if (changed.outcome() != ParameterStateOutcome.UNCHANGED) {
                return "unresolved:parameter-state:" + changed.outcome().code();
            }
            tried.add("dock:" + slider.getClass().getSimpleName() + "@" + plan[0] + "," + plan[1]);
            if (tried.size() >= 6) break;
        }
        // Palettes whose rows live only in the CWidget tree expose no Swing sliders —
        // their row widgets still carry on-screen rects a Robot drag can ride. The
        // parameter value control is CSlidableFloat: a scrub gestures sideways, and a
        // double-click opens its embedded text editor for direct typing.
        if (tried.size() < 6) {
            final List<Object> cwRows = onEdt(WindowsHistoryNativeUiIngressParameterEvidence::parameterCWidgetRows);
            for (final Object rowWidget : cwRows) {
                final java.awt.Component surface = onEdt(() -> cwidgetSurface(rowWidget));
                final int[] at = surface == null ? null : onEdt(() -> fieldCentre(surface));
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
                final javax.swing.text.JTextComponent editor = onEdt(() -> cwidgetEditor(rowWidget));
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
                            return "unresolved:parameter-state:"
                                    + changed.outcome().code();
                        }
                    }
                }
                tried.add("cw@" + at[0] + "," + at[1]);
                if (tried.size() >= 6) break;
            }
        }
        List<java.awt.Component> rows = onEdt(WindowsHistoryNativeUiIngressParameterEvidence::parameterRows);
        for (final java.awt.Component row : rows) {
            final int[] at = onEdt(() -> fieldCentre(row));
            if (at == null) continue;
            robotDrag(at[0] - 8, at[1], at[0] + 24, at[1]);
            final ParameterChangeObservation changed = awaitParameterGestureAndRecord(parameterBefore);
            if (changed.outcome() == ParameterStateOutcome.CHANGED) {
                return "changed:param-row:" + row.getClass().getName() + ":attempt=" + (tried.size() + 1);
            }
            if (changed.outcome() != ParameterStateOutcome.UNCHANGED) {
                return "unresolved:parameter-state:" + changed.outcome().code();
            }
            tried.add(row.getClass().getSimpleName() + "@" + at[0] + "," + at[1]);
            if (tried.size() >= 6) break;
        }
        List<javax.swing.JSlider> sliders = onEdt(WindowsHistoryNativeUiIngressParameterEvidence::parameterSliders);
        if (sliders.isEmpty() && rows.isEmpty()) {
            recordParameterActorTermination(new ParameterChangeObservation(
                    ParameterStateOutcome.UNCHANGED, parameterBefore, "no-slider-or-param-row"));
            return "unresolved:no-slider-or-param-row:" + paletteNote + ":"
                    + onEdt(WindowsHistoryNativeUiIngressInspectorSurface::canvasRejects);
        }
        for (final javax.swing.JSlider slider : sliders) {
            final int[] plan = onEdt(() -> sliderDragPlan(slider));
            if (plan == null) continue;
            robotDrag(plan[0], plan[1], plan[2], plan[3]);
            final ParameterChangeObservation changed = awaitParameterGestureAndRecord(parameterBefore);
            if (changed.outcome() == ParameterStateOutcome.CHANGED) {
                return "changed:" + slider.getClass().getName() + ":attempt=" + (tried.size() + 1);
            }
            if (changed.outcome() != ParameterStateOutcome.UNCHANGED) {
                return "unresolved:parameter-state:" + changed.outcome().code();
            }
            tried.add(slider.getClass().getSimpleName() + "@" + plan[0] + "," + plan[1]);
            if (tried.size() >= 6) break;
        }
        recordParameterActorTermination(new ParameterChangeObservation(
                ParameterStateOutcome.UNCHANGED, parameterBefore, "no-parameter-value-change"));
        return "unresolved:no-parameter-value-change:" + tried.size() + "-candidates:" + String.join("|", tried)
                + (paletteNote.isEmpty() ? "" : ":" + paletteNote)
                + ":" + onEdt(WindowsHistoryNativeUiIngressParameterEvidence::parameterWidgetCensus);
    }

    private ParameterChangeObservation awaitParameterGestureAndRecord(final ParameterStateSnapshot before)
            throws Exception {
        final ParameterChangeObservation observation = awaitParameterGesture(before, this::readParameterSnapshotOnEdt);
        recordParameterActorTermination(observation);
        return observation;
    }

    private void recordParameterActorTermination(final ParameterChangeObservation observation) {
        if (observation != null) parameterActorTermination = observation;
    }

    /**
     * Waits for a native parameter value transition without consulting Undo as the exit signal.
     * Any read failure or model change stops the actor fail-closed.
     */
    private ParameterChangeObservation awaitParameterChange(final ParameterStateSnapshot before) throws Exception {
        if (before == null || !before.available()) {
            return new ParameterChangeObservation(
                    ParameterStateOutcome.UNAVAILABLE, null, before == null ? "no-before-readback" : before.reason());
        }
        final long deadline = System.currentTimeMillis() + STEP_TIMEOUT_MILLIS;
        ParameterStateSnapshot latest = before;
        while (System.currentTimeMillis() < deadline) {
            if (!running) throw new InterruptedException("Probe disabled while awaiting a parameter");
            Thread.sleep(POLL_MILLIS);
            final ParameterStateSnapshot current = readParameterSnapshot(this::readParameterSnapshotOnEdt);
            final ParameterStateOutcome outcome = compareParameterState(before, current);
            if (outcome == ParameterStateOutcome.CHANGED) {
                return settleParameterChange(before, new ParameterChangeObservation(outcome, current, outcome.code()));
            }
            if (outcome != ParameterStateOutcome.UNCHANGED) {
                return new ParameterChangeObservation(outcome, current, outcome.code());
            }
            latest = current;
        }
        return new ParameterChangeObservation(ParameterStateOutcome.UNCHANGED, latest, "parameter-window-expired");
    }

    private ParameterChangeObservation awaitParameterChange(
            final ParameterStateSnapshot before, final ParameterChangeObservation actorTermination) throws Exception {
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
            final ParameterStateSnapshot before, final ParameterChangeObservation detected) throws Exception {
        final List<ParameterChangeObservation> settleObservations = new ArrayList<>();
        final long deadline = System.currentTimeMillis() + ACTION_SETTLE_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (!running) throw new InterruptedException("Probe disabled while settling a parameter");
            Thread.sleep(POLL_MILLIS);
            final ParameterStateSnapshot current = readParameterSnapshot(this::readParameterSnapshotOnEdt);
            final ParameterStateOutcome outcome = compareParameterState(before, current);
            if (outcome == ParameterStateOutcome.UNAVAILABLE || outcome == ParameterStateOutcome.MODEL_CHANGED) {
                settleObservations.add(new ParameterChangeObservation(outcome, current, outcome.code()));
                return settleParameterObservations(detected, settleObservations);
            }
            // Every successful read is a candidate final state. In particular, an unchanged
            // readback proves that the gesture returned to its before value and must not be
            // replaced by the earlier changed observation.
            settleObservations.add(new ParameterChangeObservation(outcome, current, outcome.code()));
        }
        return settleParameterObservations(detected, settleObservations);
    }

    private ParameterStateSnapshot readParameterSnapshotOnEdt() {
        try {
            if (context == null) return ParameterStateSnapshot.unavailable("no-context");
            final CubismModel model = context.cubism().model().active();
            if (model == null
                    || model.id() == null
                    || model.id().value() == null
                    || model.id().value().isBlank()
                    || model.id().value().length() > MAX_PARAMETER_ID_LENGTH) {
                return ParameterStateSnapshot.unavailable("model-identity-unavailable");
            }
            final String modelId = model.id().value();
            final List<Parameter> parameters = model.parameters().all();
            if (parameters == null) return ParameterStateSnapshot.unavailable("parameters-unavailable");
            if (parameters.size() > MAX_PARAMETER_VALUES) {
                return ParameterStateSnapshot.unavailable("parameter-snapshot-limit");
            }
            final Set<String> ids = new LinkedHashSet<>();
            final List<ParameterValueSample> values = new ArrayList<>(parameters.size());
            for (final Parameter parameter : parameters) {
                if (parameter == null
                        || parameter.id() == null
                        || parameter.id().value() == null
                        || parameter.id().value().isBlank()
                        || parameter.id().value().length() > MAX_PARAMETER_ID_LENGTH) {
                    return ParameterStateSnapshot.unavailable("parameter-identity-unavailable");
                }
                final String parameterId = parameter.id().value();
                if (!ids.add(parameterId)) return ParameterStateSnapshot.unavailable("duplicate-parameter-id");
                final float value = parameter.getValue();
                if (!Float.isFinite(value)) return ParameterStateSnapshot.unavailable("non-finite-parameter-value");
                values.add(new ParameterValueSample(parameterId, value));
            }
            final CubismModel activeAgain = context.cubism().model().active();
            if (activeAgain == null
                    || activeAgain.id() == null
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
        List<javax.swing.text.JTextComponent> fields =
                onEdt(WindowsHistoryNativeUiIngressInspectorSurface::colorFields);
        for (int retry = 0; fields.isEmpty() && retry < 4; retry++) {
            Thread.sleep(POLL_MILLIS);
            fields = onEdt(WindowsHistoryNativeUiIngressInspectorSurface::colorFields);
        }
        final java.awt.Robot robot = new java.awt.Robot();
        int swatchCount = -1;
        final StringBuilder dialogDiag = new StringBuilder(160);
        final String[] cwidgetNote = {null};
        if (fields.isEmpty()) {
            // The inspector palette — where the multiply-colour field lives — may not be
            // open in the fixture layout; open it first so it can observe the selection.
            final String palette =
                    openPalette(INSPECTOR_MARKERS, () -> inspectorShowing() || paletteTabSelected(INSPECTOR_MARKERS));
            // Then select a drawable — the inspector fills its property rows, colour
            // swatch included, when a selection event arrives while it is visible. A plain
            // click selects, it does not drag, so no undo entry is produced by this step.
            final String selection = selectDrawableRow(robot);
            for (int retry = 0; fields.isEmpty() && retry < 8; retry++) {
                Thread.sleep(POLL_MILLIS);
                fields = onEdt(WindowsHistoryNativeUiIngressInspectorSurface::colorFields);
            }
            if (fields.isEmpty() && (inspectorShowing() || onEdt(() -> paletteTabSelected(INSPECTOR_MARKERS)))) {
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
                    final Object contentWidget =
                            onEdt(WindowsHistoryNativeUiIngressInspectorSurface::inspectorContentWidget);
                    final java.awt.Container dockScope =
                            onEdt(WindowsHistoryNativeUiIngressInspectorSurface::inspectorDockScope);
                    final java.awt.Rectangle dockRect = onEdt(() -> {
                        if (dockScope == null || !dockScope.isShowing()) return null;
                        final java.awt.Point origin = dockScope.getLocationOnScreen();
                        return new java.awt.Rectangle(origin.x, origin.y, dockScope.getWidth(), dockScope.getHeight());
                    });
                    cwidgetNote[0] = onEdt(() -> {
                        final Object root = contentWidget;
                        final List<Object> widgets = cwidgetTree(root);
                        for (final Object widget : widgets) {
                            if (dockRect != null) {
                                final java.awt.Rectangle rect = cwidgetRect(widget);
                                if (rect == null || !rect.intersects(dockRect)) continue;
                            }
                            final javax.swing.text.JTextComponent text = cwidgetTextComponent(widget);
                            if (text != null) {
                                (multiplyMarked(widget) ? argb : argbOther).add(text);
                                continue;
                            }
                            if (widget.getClass().getName().endsWith("CColorChooserButton")) {
                                final java.awt.Component surface = cwidgetSurface(widget);
                                if (surface != null) {
                                    (multiplyMarked(widget) ? chooser : chooserOther).add(surface);
                                }
                            }
                        }
                        return "cw{root="
                                + (root == null ? "null" : root.getClass().getSimpleName()) + ":n=" + widgets.size()
                                + ":" + cwidgetCensus(widgets) + "}";
                    });
                    // The inspector fills its widget tree a beat after the selection
                    // lands — rescan until the colour controls materialise.
                    if (!argb.isEmpty() || !argbOther.isEmpty() || !chooser.isEmpty() || !chooserOther.isEmpty()) break;
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
                    swatches.addAll(onEdt(WindowsHistoryNativeUiIngressInspectorSurface::colorSwatches));
                }
                if (swatches.isEmpty()) {
                    // The inspector paints its property rows onto one component rather than
                    // hosting Swing children — the swatch is a painted square on a row. Scan
                    // the panel's actual pixels for saturated colour blocks and press those.
                    final java.awt.Rectangle panel =
                            onEdt(WindowsHistoryNativeUiIngressInspectorSurface::inspectorContentBounds);
                    final int[] bounds = panel == null ? null : new int[] {panel.x, panel.y, panel.width, panel.height};
                    if (bounds != null) {
                        for (final int[] point : saturatedBlocks(robot, bounds)) {
                            final int fx = point[0];
                            final int fy = point[1];
                            swatches.add(new java.awt.Component() {
                                @Override
                                public boolean isShowing() {
                                    return true;
                                }

                                @Override
                                public java.awt.Point getLocationOnScreen() {
                                    return new java.awt.Point(fx, fy);
                                }

                                @Override
                                public int getWidth() {
                                    return 1;
                                }

                                @Override
                                public int getHeight() {
                                    return 1;
                                }
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
                            onEdt(WindowsHistoryNativeUiIngressCanvasActor::visibleWindows);
                    robot.mouseMove(at[0], at[1]);
                    robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                    robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                    java.awt.Window opened = null;
                    for (int poll = 0; poll < 8; poll++) {
                        Thread.sleep(POLL_MILLIS);
                        fields = onEdt(WindowsHistoryNativeUiIngressInspectorSurface::colorFields);
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
                        final String edited = editDialogField(robot, opened, knownSignificant, dialogNote);
                        if (edited != null) return "edited-dialog:" + edited;
                        dialogDiag
                                .append("dialog:")
                                .append(opened.getClass().getSimpleName())
                                .append('{')
                                .append(dialogNote)
                                .append('}');
                        robot.keyPress(java.awt.event.KeyEvent.VK_ESCAPE);
                        robot.keyRelease(java.awt.event.KeyEvent.VK_ESCAPE);
                        Thread.sleep(POLL_MILLIS);
                    }
                }
            }
            if (fields.isEmpty()) {
                final String dock = onEdt(WindowsHistoryNativeUiIngressInspectorSurface::inspectorLeafCensus);
                dumpRegionScreenshot(robot, "inspector");
                return "unresolved:no-color-field:palette=" + palette + ":sel=" + selection + ":swatches=" + swatchCount
                        + ":" + dialogDiag + ":" + cwidgetNote[0] + ":dock{" + dock + "}";
            }
        }
        final ArrayList<String> tried = new ArrayList<>();
        for (final javax.swing.text.JTextComponent field : fields) {
            final int[] at = onEdt(() -> fieldCentre(field));
            if (at == null) continue;
            final String previous = onEdt(field::getText);
            final String replacement = previous != null && previous.contains("33") ? "2244CC" : "CC4433";
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
                    return "edited:" + field.getClass().getName() + ":attempt=" + (tried.size() + 1);
                }
            }
            tried.add(field.getClass().getSimpleName() + "@" + at[0] + "," + at[1]);
            if (tried.size() >= 4) break;
        }
        return "edited:" + tried.size() + "-fields:no-significant-entry:" + String.join("|", tried);
    }

    /**
     * Presses the first ArtMesh row found in the Parts tree so the inspector palette shows
     * that drawable's property rows, colour included.
     */
    private String selectDrawableRow(final java.awt.Robot robot) throws Exception {
        final List<String> drawableNames = onEdt(() -> context.cubism().model().active().drawables().all().stream()
                .map(dev.turboism.sdk.cubism.model.Drawable::name)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList());
        int rows = 0;
        final ArrayList<String> tried = new ArrayList<>();
        for (final String name : drawableNames) {
            if (tried.size() >= 6) break;
            final int[] point = onEdt(() -> WindowsHistoryNativeUiIngressPartActors.partsRowPoint(name));
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
            final int selectedRow = onEdt(WindowsHistoryNativeUiIngressPartActors::partsTableSelectedRow);
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
            final int selectedRow = onEdt(WindowsHistoryNativeUiIngressPartActors::partsTableSelectedRow);
            if (selectedRow >= 0) return "tree-select:" + name + ":tableRow=" + selectedRow;
            tried.add("edt:" + name);
        }
        // Fallback: a single press on the model canvas selects the drawable under the
        // cursor — that selection is what populates the inspector's property rows. It
        // commits only a selection entry, never an edit.
        final int[] canvasCentre = onEdt(() -> {
            for (final java.awt.Component canvas : canvasCandidates()) {
                final java.awt.Rectangle visible = visibleBounds(canvas);
                if (visible.isEmpty()) continue;
                final java.awt.Point centre =
                        new java.awt.Point(visible.x + visible.width / 2, visible.y + visible.height / 2);
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
                final int selectedRow = onEdt(WindowsHistoryNativeUiIngressPartActors::partsTableSelectedRow);
                if (selectedRow >= 0) {
                    return "canvas@" + (canvasCentre[0] + offset[0]) + "," + (canvasCentre[1] + offset[1])
                            + ":tableRow=" + selectedRow;
                }
            }
            return "canvas-miss:tried-rows=" + tried;
        }
        return "none:names=" + drawableNames.size() + ":rows=" + rows + ":tried=" + tried + ":tree="
                + onEdt(WindowsHistoryNativeUiIngressPartActors::partsTreeLabels);
    }

    /**
     * Captures the inspector content panel's screen pixels into the evidence stream as a
     * base64 PNG so a failed colour step shows what the palette actually rendered.
     */
    private void dumpRegionScreenshot(final java.awt.Robot robot, final String name) {
        try {
            final java.awt.Rectangle rect =
                    onEdt(WindowsHistoryNativeUiIngressInspectorSurface::inspectorContentBounds);
            if (rect == null || rect.width < 4 || rect.height < 4) return;
            final java.awt.Rectangle clipped = rect.intersection(new java.awt.Rectangle(
                    java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds()));
            if (clipped.width < 4 || clipped.height < 4) return;
            final java.awt.image.BufferedImage image = robot.createScreenCapture(clipped);
            final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", out);
            final byte[] png = out.toByteArray();
            write(
                    artifact,
                    "{\"type\":\"screenshot\",\"region\":" + quoted(name) + ",\"png\":\""
                            + java.util.Base64.getEncoder().encodeToString(png) + "\"}\n",
                    false);
        } catch (Throwable ignored) {
            // Diagnostics must never fail the step.
        }
    }

    private void dumpWindowScreenshot(final java.awt.Robot robot, final java.awt.Window window, final String name) {
        try {
            final java.awt.Rectangle rect = onEdt(() -> {
                if (!window.isShowing()) return null;
                return new java.awt.Rectangle(window.getX(), window.getY(), window.getWidth(), window.getHeight());
            });
            if (rect == null || rect.width < 4 || rect.height < 4) return;
            final java.awt.Rectangle clipped = rect.intersection(new java.awt.Rectangle(
                    java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds()));
            if (clipped.width < 4 || clipped.height < 4) return;
            rect.setBounds(clipped);
            final java.awt.image.BufferedImage image = robot.createScreenCapture(rect);
            final java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", png);
            write(
                    artifact,
                    "{\"type\":\"screenshot\",\"region\":" + quoted(name)
                            + ",\"width\":" + rect.width + ",\"height\":" + rect.height
                            + ",\"png\":\"" + java.util.Base64.getEncoder().encodeToString(png.toByteArray()) + "\"}\n",
                    false);
        } catch (Throwable ignored) {
            // Diagnostics must never fail the step.
        }
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
            final StringBuilder note)
            throws Exception {
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
                    .append("->")
                    .append(replacement)
                    .append(';');
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
                if (!significantSequence(sample()).equals(knownSignificant)) return "field:" + replacement;
            }
            // Enter alone may only apply the field — the dialog still needs its OK button.
            final int[] ok = onEdt(() -> confirmPoint(dialog));
            if (ok != null) {
                robot.mouseMove(ok[0], ok[1]);
                robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                for (int settle = 0; settle < 10; settle++) {
                    Thread.sleep(POLL_MILLIS);
                    if (!significantSequence(sample()).equals(knownSignificant)) return "field-ok:" + replacement;
                }
            }
        }
        if (fields.isEmpty()) note.append("no-editable-fields;");
        return null;
    }

    /**
     * Sends one native Ctrl+key shortcut exactly as the operator would: the enabled menu
     * accelerator first, then a focused-window Robot keystroke only when every post-menu history
     * position sample was available and unchanged. Unknown evidence is recorded as unresolved so
     * a menu action can never be repeated merely because the probe could not observe it.
     */
    private String shortcut(final int key, final long knownPosition, final String kind) throws Exception {
        String route = "none";
        primeMenus();
        if (Boolean.TRUE.equals(onEdt(() -> menuShortcut(key)))) {
            route = "menu-accelerator";
            if (knownPosition < 0L) return route + ":unresolved:unknown-position";
            // Undo/Redo do not change the entry sequence; their only reliable delivery signal is
            // the cursor moving. Once it moves, even in the wrong direction, do not send Robot a
            // second shortcut and risk applying two native actions.
            final List<Long> positionSamples = new ArrayList<>();
            for (int poll = 0; poll < 10; poll++) {
                Thread.sleep(POLL_MILLIS);
                final WindowsHistoryManagerValidationProbe.Snapshot current = sample();
                positionSamples.add(current == null ? null : position(current));
                final ShortcutResolution resolution = shortcutResolution(kind, knownPosition, positionSamples);
                if (resolution == ShortcutResolution.DELIVERED) return route;
                if (resolution == ShortcutResolution.WRONG_DIRECTION) return route + ":wrong-direction";
            }
            final ShortcutResolution resolution = shortcutResolution(kind, knownPosition, positionSamples);
            if (resolution != ShortcutResolution.FALLBACK) {
                return route + ":unresolved:"
                        + switch (resolution) {
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
                if (frame instanceof javax.swing.JFrame swingFrame
                        && frame.isVisible()
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

    private WindowsHistoryManagerValidationProbe.Snapshot sample() throws Exception {
        final AtomicReference<WindowsHistoryManagerValidationProbe.Snapshot> result = new AtomicReference<>();
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
            context.logger().warn("Native UI ingress probe sampling timed out after " + SAMPLE_TIMEOUT_MILLIS + "ms");
            return null;
        }
        if (failure.get() != null) return null;
        return result.get();
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

    private int cursor() {
        synchronized (lock) {
            return observed.size();
        }
    }
}
