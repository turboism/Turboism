package dev.turboism.tests.plugin;

import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressCanvasActor.CANVAS_SCAN_DEPTH;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressCanvasActor.within;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressInspectorSurface.cwidgetCensus;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressInspectorSurface.cwidgetOf;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressInspectorSurface.cwidgetRect;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressInspectorSurface.cwidgetShowing;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressInspectorSurface.cwidgetTop;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressInspectorSurface.cwidgetTree;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressInspectorSurface.paletteTabs;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressPartActors.movesToward;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.EndpointIdentity;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.Observed;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.TargetKey;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.boundedText;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.finite;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.json;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.modelId;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.nonBlank;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.onEdt;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.same;

import dev.turboism.sdk.cubism.history.HistoryRelationChange;
import dev.turboism.sdk.cubism.history.HistoryTarget;
import dev.turboism.sdk.cubism.model.Parameter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.swing.SwingUtilities;

/**
 * Parameter lifecycle evidence: snapshots, change observations, and admission rules.
 *
 * <p>A parameter edit is evidence only when the published lifecycle events agree with the sampled
 * slider values. The comparison names the first disagreement so a rejected step is reviewable.</p>
 */
final class WindowsHistoryNativeUiIngressParameterEvidence {
    static final int MAX_PARAMETER_VALUES = 128;
    static final int MAX_PARAMETER_ID_LENGTH = 256;
    static final int MAX_PARAMETER_THREAD_LENGTH = 128;
    static final int MAX_PARAMETER_PHASE_LENGTH = 16;
    static final int MAX_PARAMETER_LIFECYCLE_EVENTS = 256;
    static final int PARAMETER_ACTOR_POLLS = 10;

    static Float finiteValue(final float value) {
        return Float.isFinite(value) ? value : null;
    }

    static Float finiteValue(final Float value) {
        return value == null || !Float.isFinite(value) ? null : value;
    }

    static Float readFiniteParameterValue(final Parameter parameter) {
        if (parameter == null) return null;
        try {
            return finiteValue(parameter.getValue());
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Reads the active model and its bounded parameter values on the host EDT.
     *
     * <p>The probe owns the plugin context, so it supplies the EDT readback; this helper only
     * bounds the result and turns a failed read into an explicit unavailability code.</p>
     */
    static ParameterStateSnapshot readParameterSnapshot(
            final java.util.function.Supplier<ParameterStateSnapshot> edtReadback) {
        try {
            final ParameterStateSnapshot snapshot = onEdt(edtReadback::get);
            return snapshot == null ? ParameterStateSnapshot.unavailable("null-readback") : snapshot;
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
    static String parameterWidgetCensus() {
        for (final javax.swing.AbstractButton tab : paletteTabs(PARAMETER_MARKERS, new StringBuilder(1))) {
            if (!tab.isSelected()) continue;
            java.awt.Container scope = tab.getParent();
            while (scope != null
                    && scope.getParent() != null
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
    static List<javax.swing.JSlider> parameterSliders() {
        final List<javax.swing.JSlider> found = new ArrayList<>();
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (window.isVisible()) collectSliders(window, found, 0);
        }
        return found;
    }

    static void collectSliders(
            final java.awt.Component component, final List<javax.swing.JSlider> found, final int depth) {
        if (depth > CANVAS_SCAN_DEPTH || !component.isVisible()) return;
        if (component instanceof javax.swing.JSlider slider
                && slider.isShowing()
                && slider.isEnabled()
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
    static List<java.awt.Component> parameterRows() {
        final List<java.awt.Component> found = new ArrayList<>();
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (window.isVisible()) collectParameterRows(window, found, 0);
        }
        return found;
    }

    static void collectParameterRows(
            final java.awt.Component component, final List<java.awt.Component> found, final int depth) {
        if (depth > CANVAS_SCAN_DEPTH || !component.isVisible()) return;
        final String className = component.getClass().getName();
        if (component.isShowing()
                && className.contains(".palette.parameter")
                && !(component instanceof javax.swing.AbstractButton)
                && !(component instanceof javax.swing.text.JTextComponent)
                && component.getWidth() >= 48
                && component.getWidth() <= 400
                && component.getHeight() >= 10
                && component.getHeight() <= 48
                && !found.contains(component)) {
            found.add(component);
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                collectParameterRows(child, found, depth + 1);
            }
        }
    }

    static final String[] PARAMETER_MARKERS = {"参数", "パラメータ", "arameter"};

    /**
     * The screen line a thumb drag for {@code slider} should follow: press at the thumb, drag
     * right by an eighth of the track.
     *
     * @return {@code [fromX, fromY, toX, toY]} in screen coordinates, or {@code null} when the
     *     slider has no room to move
     */
    static int[] sliderDragPlan(final javax.swing.JSlider slider) {
        if (!slider.isShowing()) return null;
        final int range = slider.getMaximum() - slider.getMinimum();
        if (range <= 0 || slider.getWidth() < 48) return null;
        final double fraction = (double) (slider.getValue() - slider.getMinimum()) / (double) range;
        // The track rarely spans the whole component; keep the press inside it.
        final int localX = Math.max(6, Math.min(slider.getWidth() - 6, (int) Math.round(fraction * slider.getWidth())));
        final int localY = slider.getHeight() / 2;
        final int delta = Math.max(6, slider.getWidth() / 8);
        if (localX + delta > slider.getWidth() - 2 && slider.getValue() == slider.getMaximum()) return null;
        final java.awt.Point from = new java.awt.Point(localX, localY);
        SwingUtilities.convertPointToScreen(from, slider);
        return new int[] {from.x, from.y, from.x + delta, from.y};
    }

    /**
     * The sliders inside the Parameter palette's dock columns — the palette's value rows
     * are CSlider widgets mirrored as {@code swingImpl.A} JSliders. Every selected
     * parameter tab's column is searched. Runs on the EDT.
     */
    static List<javax.swing.JSlider> parameterDockSliders() {
        final List<javax.swing.JSlider> found = new ArrayList<>();
        for (final javax.swing.AbstractButton tab : paletteTabs(PARAMETER_MARKERS, new StringBuilder(1))) {
            if (!tab.isSelected()) continue;
            java.awt.Container scope = tab.getParent();
            while (scope != null
                    && scope.getParent() != null
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
    static List<Object> parameterCWidgetRows() {
        final List<Object> found = new ArrayList<>();
        final List<Object> backup = new ArrayList<>();
        for (final javax.swing.AbstractButton tab : paletteTabs(PARAMETER_MARKERS, new StringBuilder(1))) {
            if (!tab.isSelected()) continue;
            java.awt.Container scope = tab.getParent();
            while (scope != null
                    && scope.getParent() != null
                    && scope.getParent().getWidth() <= 200) {
                scope = scope.getParent();
            }
            if (scope == null || !scope.isShowing()) continue;
            final java.awt.Point origin = scope.getLocationOnScreen();
            final java.awt.Rectangle dockRect =
                    new java.awt.Rectangle(origin.x, origin.y, scope.getWidth(), scope.getHeight());
            Object root = cwidgetOf(scope);
            if (root == null) root = cwidgetTop(cwidgetOf(tab));
            for (final Object widget : cwidgetTree(root)) {
                final String name = widget.getClass().getName();
                // The row's value control is a CSlidableFloat — a horizontal drag
                // across it scrubs the parameter. Other palette.parameter widgets
                // (the row surface, its value bar) qualify as weaker candidates.
                final int rank = name.endsWith("CSlidableFloat")
                        ? 0
                        : name.endsWith("CSlider") ? 1 : name.contains(".palette.parameter") ? 2 : -1;
                if (rank < 0) continue;
                final java.awt.Rectangle rect = cwidgetRect(widget);
                if (rect == null
                        || !rect.intersects(dockRect)
                        || rect.width < 24
                        || rect.height < 6
                        || rect.height > 60) continue;
                if (cwidgetShowing(widget)) {
                    (rank == 0 ? found : backup).add(widget);
                }
            }
        }
        found.addAll(backup);
        return found;
    }

    static boolean usableSdkHistory(final WindowsHistoryManagerValidationProbe.SdkHistorySnapshot snapshot) {
        return snapshot != null
                && "AVAILABLE".equals(snapshot.availability())
                && nonBlank(snapshot.documentBindingId())
                && nonBlank(snapshot.managerBindingId())
                && snapshot.entries() != null;
    }

    static boolean completeEntryIdentities(final WindowsHistoryManagerValidationProbe.SdkHistorySnapshot snapshot) {
        final Set<String> identities = new HashSet<>();
        for (WindowsHistoryManagerValidationProbe.SdkEntry entry : snapshot.entries()) {
            if (entry == null || !stableEntryId(entry.entryId()) || !identities.add(entry.entryId())) return false;
        }
        return true;
    }

    static boolean stableEntryId(final String value) {
        return nonBlank(value);
    }

    static String historySeen(
            final WindowsHistoryManagerValidationProbe.SdkHistorySnapshot before,
            final WindowsHistoryManagerValidationProbe.SdkHistorySnapshot after) {
        return "before=" + sdkHistorySeen(before) + ",after=" + sdkHistorySeen(after);
    }

    static String sdkHistorySeen(final WindowsHistoryManagerValidationProbe.SdkHistorySnapshot snapshot) {
        if (snapshot == null) return "null";
        return snapshot.availability() + ":generation=" + snapshot.generation() + ":position=" + snapshot.position()
                + ":total=" + snapshot.totalEntries();
    }

    static boolean stableTarget(final HistoryTarget target) {
        return target != null
                && nonBlank(target.type())
                && target.id()
                        .filter(WindowsHistoryNativeUiIngressVerdicts::nonBlank)
                        .isPresent();
    }

    static TargetKey targetKey(final HistoryTarget target) {
        return new TargetKey(target.type(), target.id().orElseThrow());
    }

    static EndpointIdentity endpointIdentity(final HistoryRelationChange.Endpoint endpoint) {
        return switch (endpoint.state()) {
            case ROOT ->
                endpoint.target().isEmpty() ? new EndpointIdentity(HistoryRelationChange.State.ROOT, null) : null;
            case TARGET ->
                endpoint.target()
                        .filter(WindowsHistoryNativeUiIngressParameterEvidence::stableTarget)
                        .map(WindowsHistoryNativeUiIngressParameterEvidence::targetKey)
                        .map(key -> new EndpointIdentity(HistoryRelationChange.State.TARGET, key))
                        .orElse(null);
            case UNKNOWN -> null;
        };
    }

    static boolean sameEndpoint(final EndpointIdentity left, final EndpointIdentity right) {
        if (left.state() != right.state()) return false;
        return left.state() == HistoryRelationChange.State.ROOT || Objects.equals(left.target(), right.target());
    }

    static WindowsHistoryManagerValidationProbe.SdkHistorySnapshot sdkHistory(
            final WindowsHistoryManagerValidationProbe.Snapshot snapshot) {
        return snapshot == null ? null : snapshot.sdkHistory();
    }

    static long countPhase(final List<Observed> events, final String phase) {
        return events.stream()
                .filter(event -> event != null && phase.equals(event.phase()))
                .count();
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

    static ParameterStateOutcome compareParameterState(
            final ParameterStateSnapshot before, final ParameterStateSnapshot after) {
        if (before == null || after == null || !before.available() || !after.available()) {
            return ParameterStateOutcome.UNAVAILABLE;
        }
        if (!before.modelId().equals(after.modelId())) return ParameterStateOutcome.MODEL_CHANGED;
        final Map<String, Float> beforeValues = parameterValueMap(before);
        final Map<String, Float> afterValues = parameterValueMap(after);
        if (beforeValues == null
                || afterValues == null
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

    static Set<String> changedParameterIds(final ParameterStateSnapshot before, final ParameterStateSnapshot after) {
        if (compareParameterState(before, after) != ParameterStateOutcome.CHANGED) return Set.of();
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

    static Map<String, Float> parameterValueMap(final ParameterStateSnapshot snapshot) {
        if (snapshot == null || !snapshot.available()) return null;
        final Map<String, Float> values = new HashMap<>();
        for (final ParameterValueSample sample : snapshot.values()) {
            if (sample == null || values.put(sample.parameterId(), sample.value()) != null) return null;
        }
        return values;
    }

    static ParameterHistoryAdmission parameterHistoryAdmission(
            final String beforeSignificant, final String afterSignificant) {
        if (beforeSignificant == null || afterSignificant == null) return ParameterHistoryAdmission.UNAVAILABLE;
        return beforeSignificant.equals(afterSignificant)
                ? ParameterHistoryAdmission.NOT_OBSERVED
                : ParameterHistoryAdmission.OBSERVED;
    }

    static List<ParameterLifecycleEvent> relevantParameterLifecycle(
            final List<ParameterLifecycleEvent> events, final Set<String> parameterIds) {
        if (events == null || events.isEmpty() || parameterIds == null || parameterIds.isEmpty()) return List.of();
        return events.stream()
                .filter(event -> event != null && parameterIds.contains(event.parameterId()))
                .toList();
    }

    static ParameterLifecycleStatus parameterLifecycleStatus(
            final List<ParameterLifecycleEvent> events, final Set<String> changedIds, final boolean valueChanged) {
        return parameterLifecycleStatus(events, null, null, changedIds, valueChanged, true);
    }

    static ParameterLifecycleStatus parameterLifecycleStatus(
            final List<ParameterLifecycleEvent> events,
            final ParameterStateSnapshot before,
            final ParameterStateSnapshot after,
            final Set<String> changedIds,
            final boolean valueChanged) {
        return parameterLifecycleStatus(events, before, after, changedIds, valueChanged, true);
    }

    static ParameterLifecycleStatus parameterLifecycleStatus(
            final List<ParameterLifecycleEvent> events,
            final ParameterStateSnapshot before,
            final ParameterStateSnapshot after,
            final Set<String> changedIds,
            final boolean valueChanged,
            final boolean lifecycleWindowComplete) {
        if (!lifecycleWindowComplete) return ParameterLifecycleStatus.INCOMPLETE;
        if (events == null || events.isEmpty()) return ParameterLifecycleStatus.MISSING;
        if (changedIds == null || changedIds.isEmpty()) return ParameterLifecycleStatus.OBSERVED_UNRELATED;
        if (!valueChanged) return ParameterLifecycleStatus.INCOMPLETE;
        if (before == null
                || after == null
                || !before.available()
                || !after.available()
                || !before.modelId().equals(after.modelId())) {
            return ParameterLifecycleStatus.UNAVAILABLE;
        }
        final Map<String, Float> beforeValues = parameterValueMap(before);
        final Map<String, Float> afterValues = parameterValueMap(after);
        final Set<String> actualChangedIds = changedParameterIds(before, after);
        if (beforeValues == null || afterValues == null || !actualChangedIds.equals(changedIds)) {
            return ParameterLifecycleStatus.UNAVAILABLE;
        }
        if (!strictLifecycleSequence(events)) return ParameterLifecycleStatus.INCOMPLETE;
        final List<ParameterLifecycleEvent> relevant = relevantParameterLifecycle(events, changedIds);
        if (relevant.isEmpty()) return ParameterLifecycleStatus.OBSERVED_UNRELATED;
        for (final String changedId : changedIds) {
            final Float beforeValue = beforeValues.get(changedId);
            final Float afterValue = afterValues.get(changedId);
            if (beforeValue == null || afterValue == null) return ParameterLifecycleStatus.UNAVAILABLE;
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
            final boolean valueChanged) {
        return assessParameterLifecycle(events, before, after, changedIds, valueChanged, true);
    }

    static ParameterLifecycleAssessment assessParameterLifecycle(
            final List<ParameterLifecycleEvent> events,
            final ParameterStateSnapshot before,
            final ParameterStateSnapshot after,
            final Set<String> changedIds,
            final boolean valueChanged,
            final boolean lifecycleWindowComplete) {
        return new ParameterLifecycleAssessment(
                parameterLifecycleStatus(events, before, after, changedIds, valueChanged, lifecycleWindowComplete),
                ParameterModelCorrelation.UNAVAILABLE);
    }

    static boolean strictLifecycleSequence(final List<ParameterLifecycleEvent> events) {
        long previous = Long.MIN_VALUE;
        for (final ParameterLifecycleEvent event : events) {
            if (event == null || event.sequence() <= previous) return false;
            previous = event.sequence();
        }
        return true;
    }

    static boolean validParameterLifecycle(
            final List<ParameterLifecycleEvent> events, final float beforeValue, final float afterValue) {
        if (events == null || events.isEmpty() || Float.compare(beforeValue, afterValue) == 0) return false;
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
                    if (open
                            || !finite(event.oldValue())
                            || !finite(event.newValue())
                            || !same(event.oldValue(), current)) return false;
                    open = true;
                    segmentChanged = false;
                    segmentNeutral = same(event.oldValue(), event.newValue()) && same(event.newValue(), current);
                }
                case "on" -> {
                    if (!open
                            || !finite(event.oldValue())
                            || !finite(event.newValue())
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
                    if (!open
                            || (!segmentChanged && !segmentNeutral)
                            || !finite(event.newValue())
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

    /**
     * Applies the same final-readback decision used by the real settle loop to a bounded trace.
     * Unresolved actor outcomes are terminal, while every successful follow-up observation is a
     * new final candidate, including a readback that is unchanged from the original value.
     */
    static ParameterChangeObservation settleParameterObservations(
            final ParameterChangeObservation actorObservation,
            final List<ParameterChangeObservation> settleObservations) {
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

    static String parameterStateJson(final String step, final String phase, final ParameterStateSnapshot snapshot) {
        return "{\"type\":\"parameter-state\",\"step\":\"" + json(step) + "\",\"phase\":\"" + json(phase)
                + "\",\"availability\":\"" + snapshot.availability().code()
                + "\",\"modelId\":\"" + json(snapshot.modelId()) + "\",\"reason\":\"" + json(snapshot.reason())
                + "\",\"parameters\":["
                + snapshot.values().stream()
                        .map(ParameterValueSample::json)
                        .reduce((left, right) -> left + "," + right)
                        .orElse("")
                + "]}\n";
    }

    static String parameterEvidenceJson(
            final String step,
            final ParameterStateSnapshot before,
            final ParameterStateSnapshot after,
            final ParameterStateOutcome valueStatus,
            final ParameterHistoryAdmission historyAdmission,
            final ParameterLifecycleAssessment lifecycleAssessment,
            final Set<String> changedIds,
            final List<ParameterLifecycleEvent> lifecycleEvents) {
        final Set<String> relevantIds = changedIds == null ? Set.of() : changedIds;
        return "{\"type\":\"parameter-evidence\",\"step\":\"" + json(step)
                + "\",\"modelIdBefore\":\"" + json(modelId(before)) + "\",\"modelIdAfter\":\"" + json(modelId(after))
                + "\",\"valueStatus\":\"" + valueStatus.code() + "\",\"historyAdmission\":\"" + historyAdmission.code()
                + "\",\"lifecycle\":\"" + lifecycleAssessment.status().code() + "\",\"modelCorrelation\":\""
                + lifecycleAssessment.modelCorrelation().code() + "\",\"changedParameterIds\":["
                + relevantIds.stream()
                        .map(WindowsHistoryNativeUiIngressVerdicts::quoted)
                        .reduce((left, right) -> left + "," + right)
                        .orElse("")
                + "],\"callbacks\":["
                + (lifecycleEvents == null
                        ? ""
                        : lifecycleEvents.stream()
                                .map(event -> event.json(relevantIds))
                                .reduce((left, right) -> left + "," + right)
                                .orElse(""))
                + "]}\n";
    }

    enum ParameterStateAvailability {
        AVAILABLE("available"),
        UNAVAILABLE("unavailable");

        final String code;

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

        final String code;

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

        final String code;

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

        final String code;

        ParameterLifecycleStatus(final String code) {
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    enum ParameterModelCorrelation {
        UNAVAILABLE("unavailable");

        final String code;

        ParameterModelCorrelation(final String code) {
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    record ParameterLifecycleAssessment(ParameterLifecycleStatus status, ParameterModelCorrelation modelCorrelation) {
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
            if (!Float.isFinite(value)) throw new IllegalArgumentException("parameter value must be finite");
        }

        String json() {
            return "{\"id\":\"" + WindowsHistoryNativeUiIngressVerdicts.json(parameterId) + "\",\"value\":"
                    + Float.toString(value) + "}";
        }
    }

    record ParameterStateSnapshot(
            ParameterStateAvailability availability, String modelId, List<ParameterValueSample> values, String reason) {
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

        static ParameterStateSnapshot available(final String modelId, final List<ParameterValueSample> values) {
            return new ParameterStateSnapshot(ParameterStateAvailability.AVAILABLE, modelId, values, "");
        }

        static ParameterStateSnapshot unavailable(final String reason) {
            return new ParameterStateSnapshot(
                    ParameterStateAvailability.UNAVAILABLE,
                    "",
                    List.of(),
                    reason == null || reason.isBlank() ? "unavailable" : reason);
        }

        boolean available() {
            return availability == ParameterStateAvailability.AVAILABLE;
        }
    }

    record ParameterChangeObservation(ParameterStateOutcome outcome, ParameterStateSnapshot after, String reason) {
        ParameterChangeObservation {
            outcome = Objects.requireNonNull(outcome, "outcome");
            reason = reason == null ? "" : reason;
        }
    }

    record ParameterLifecycleEvent(
            long sequence, String phase, String parameterId, Float oldValue, Float newValue, String thread) {
        ParameterLifecycleEvent {
            phase = boundedText(phase, MAX_PARAMETER_PHASE_LENGTH);
            parameterId = boundedText(parameterId, MAX_PARAMETER_ID_LENGTH);
            oldValue = finiteValue(oldValue);
            newValue = finiteValue(newValue);
            thread = boundedText(thread, MAX_PARAMETER_THREAD_LENGTH);
        }

        String json(final Set<String> relatedIds) {
            final boolean related = relatedIds != null && relatedIds.contains(parameterId);
            return "{\"sequence\":" + sequence + ",\"phase\":\"" + WindowsHistoryNativeUiIngressVerdicts.json(phase)
                    + "\",\"parameterId\":\"" + WindowsHistoryNativeUiIngressVerdicts.json(parameterId)
                    + "\",\"oldValue\":" + numberOrNull(oldValue) + ",\"newValue\":" + numberOrNull(newValue)
                    + ",\"thread\":\"" + WindowsHistoryNativeUiIngressVerdicts.json(thread)
                    + "\",\"related\":" + related + "}";
        }

        static String numberOrNull(final Float value) {
            return value == null ? "null" : Float.toString(value);
        }
    }
}
