package dev.turboism.tests.plugin;

import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressCanvasActor.ConfirmationCheck;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressCanvasActor.ConfirmationPair;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressCanvasActor.checkConfirmations;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressParameterEvidence.ParameterStateSnapshot;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressParameterEvidence.completeEntryIdentities;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressParameterEvidence.countPhase;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressParameterEvidence.endpointIdentity;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressParameterEvidence.historySeen;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressParameterEvidence.sameEndpoint;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressParameterEvidence.stableEntryId;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressParameterEvidence.stableTarget;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressParameterEvidence.targetKey;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressParameterEvidence.usableSdkHistory;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressPartActors.ActorPart;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressPartActors.MAX_PART_GESTURE_PATH_COMPONENTS;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressPartActors.MAX_PART_GESTURE_TEXT_LENGTH;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressPartActors.PartCandidate;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressPartActors.PartPairLayout;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressPartActors.PartRowLocation;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressPartActors.partSurfaceJson;

import dev.turboism.sdk.cubism.history.HistoryChange;
import dev.turboism.sdk.cubism.history.HistoryEntryDetail;
import dev.turboism.sdk.cubism.history.HistoryRelationChange;
import dev.turboism.sdk.cubism.history.HistoryTarget;
import java.awt.Insets;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.TreePath;

/**
 * Step verdicts, evidence serialisation, and the shared text helpers.
 *
 * <p>Each operator step is judged only on what the runtime actually published for it. The verdict
 * codes are the review vocabulary, so they stay stable and explicit.</p>
 */
final class WindowsHistoryNativeUiIngressVerdicts {
    static final long MAX_EVIDENCE_BYTES = 2_097_152L;
    static final long TERMINAL_RESERVE_BYTES = 4_096L;
    static final int MAX_RECORDED_EVENTS = 512;

    static final long POLL_MILLIS = 250L;

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

    /**
     * How often a pending step re-announces its instruction.
     *
     * <p>The step window opens when the instruction is written, and a human who is not at the
     * desk yet reads nothing. Re-announcing inside the same window means a late operator sees the
     * current instruction instead of losing the step; it costs no extra time because the deadline
     * does not move.</p>
     */
    static final long REMINDER_MILLIS = 90_000L;

    /** How long an edit step waits for a multi-entry action to finish committing. */
    static final long ACTION_SETTLE_MILLIS = 2_000L;

    /**
     * How long a navigation step waits.
     *
     * <p>Deliberately short: a native Undo or Redo is one atomic step, and a long settle lets the
     * operator's next keystroke land inside this step's window, which is how the previous three
     * runs misattributed the redo.</p>
     */
    static final long NAVIGATION_SETTLE_MILLIS = 300L;

    /**
     * How long one sample of the host may take.
     *
     * <p>A sample runs on the Editor thread. If that thread has stopped pumping events — because
     * the Editor is closing, or a modal dialog is up — the wait has to end anyway, so the probe
     * can write its verdicts and its summary instead of hanging with no terminal line.</p>
     */
    static final long SAMPLE_TIMEOUT_MILLIS = 15_000L;

    static final long AWAIT_DOCUMENT_MILLIS = 240_000L;
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
    static final long STEP_TIMEOUT_MILLIS = Long.parseLong(
            System.getProperty("turboism.history.nativeUi.stepTimeoutMillis", AUTOMATE ? "60000" : "420000"));

    /**
     * Operator steps in order.
     *
     * <p>The first six are the native action families whose ingress is being reviewed, split so
     * that each one produces a shape a decoder can be judged against: a whole-object move
     * translates every point by the same vector, while a mesh deformation does not. The last two
     * are a native Undo and Redo of whatever the operator left on the stack.</p>
     */
    static final List<Step> STEPS = List.of(
            new Step(
                    "parts-tree-drag",
                    "ACTION",
                    "Drag one Part onto a different Part in the Parts tree, then release."),
            new Step(
                    "deformer-assign",
                    "ACTION",
                    "Assign a different Deformer target to one Part or ArtMesh and confirm it: the"
                            + " Deformers palette must show the new target. Selecting the object alone is not"
                            + " an assignment and does not count."),
            new Step(
                    "canvas-move",
                    "ACTION",
                    "Move ONE model object as a whole on the canvas with the mouse, then release. Do not"
                            + " edit its vertices or mesh points."),
            new Step(
                    "canvas-deform",
                    "ACTION",
                    "Deform ONE model object instead of moving it: drag a single mesh point (or use the"
                            + " mesh edit tool) so its shape changes, then release."),
            new Step(
                    "native-parameter",
                    "ACTION",
                    "Change one Parameter value in the native Parameter palette only (drag its slider or"
                            + " type a value), then release. Do not move any object."),
            new Step(
                    "native-color",
                    "ACTION",
                    "Change only the multiply colour (正片叠底色) of one drawable in the native"
                            + " palette, then confirm. Do not move the object and do not edit its mesh."),
            new Step("native-undo", "UNDO", "Press the native Undo shortcut once (Ctrl+Z)."),
            new Step("native-redo", "REDO", "Press the native Redo shortcut once (Ctrl+Y)."));

    static String boundedText(final String value, final int limit) {
        return value == null || value.length() > limit ? "" : value;
    }

    static boolean samePathNodeIdentity(final TreePath expected, final TreePath actual) {
        if (expected == null || actual == null || expected.getPathCount() != actual.getPathCount()) return false;
        for (int index = 0; index < expected.getPathCount(); index++) {
            if (expected.getPathComponent(index) != actual.getPathComponent(index)) return false;
        }
        return true;
    }

    /** Java-level pointer observation; it is evidence, not an independent OS truth source. */
    static Point readMouseInfoPointer() {
        final PointerInfo pointerInfo = MouseInfo.getPointerInfo();
        if (pointerInfo == null) return null;
        final Point location = pointerInfo.getLocation();
        return location == null ? null : new Point(location);
    }

    static String parentText(final Optional<String> parent) {
        return parent == null ? "<unavailable>" : parent.orElse("<none>");
    }

    static Optional<String> parentOf(final ActorPart part) {
        return part == null ? Optional.empty() : part.parentId();
    }

    static boolean uniqueName(final ActorPart part, final Map<String, Integer> nameCounts) {
        return part.name() != null && !part.name().isBlank() && nameCounts.getOrDefault(part.name(), 0) == 1;
    }

    static String treeLabel(final JTree tree, final Object node) {
        if (tree == null || node == null) return "";
        try {
            return boundedText(
                    tree.convertValueToText(node, false, false, false, 0, false), MAX_PART_GESTURE_TEXT_LENGTH);
        } catch (RuntimeException failure) {
            return boundedText(String.valueOf(node), MAX_PART_GESTURE_TEXT_LENGTH);
        }
    }

    static String treePathLabel(final JTree tree, final TreePath path) {
        if (tree == null || path == null) return "";
        final StringBuilder label = new StringBuilder();
        final int count = Math.min(path.getPathCount(), MAX_PART_GESTURE_PATH_COMPONENTS);
        for (int index = 0; index < count; index++) {
            if (index > 0) label.append('/');
            label.append(treeLabel(tree, path.getPathComponent(index)));
        }
        return boundedText(label.toString(), MAX_PART_GESTURE_TEXT_LENGTH);
    }

    static void expandAncestors(final javax.swing.JTree tree, final javax.swing.tree.TreePath path) {
        javax.swing.tree.TreePath parent = path == null ? null : path.getParentPath();
        while (parent != null) {
            tree.expandPath(parent);
            parent = parent.getParentPath();
        }
    }

    /** The host CWidget registered for a mirror component, via {@code com.live2d.ui.k.a}. */
    static Object widgetOf(final javax.swing.JMenuItem jmi, final StringBuilder diag) {
        try {
            final Class<?> registry =
                    Class.forName("com.live2d.ui.k", true, jmi.getClass().getClassLoader());
            return registry.getMethod("a", java.awt.Component.class).invoke(null, jmi);
        } catch (final Exception e) {
            diag.append("registry-threw:").append(e.getClass().getSimpleName()).append(';');
            return null;
        }
    }

    static boolean containsAny(final String text, final String[] markers) {
        for (final String marker : markers) {
            if (text.contains(marker)) return true;
        }
        return false;
    }

    /** A plausible replacement for a colour-editor field's current text, or null. */
    static String replacementValue(final String previous) {
        if (previous == null) return null;
        final String value = previous.trim();
        if (value.matches("#?[0-9a-fA-F]{6}")) return value.contains("33") ? "2244CC" : "CC4433";
        if (value.matches("\\d{1,3}")) {
            final int channel = Integer.parseInt(value);
            return String.valueOf((channel + 96) % 256);
        }
        if (value.matches("\\d+\\.\\d+")) return value.startsWith("0.5") ? "0.7" : "0.5";
        return null;
    }

    /** Types one ASCII hex character through the Robot. */
    static void typeChar(final java.awt.Robot robot, final char digit) throws Exception {
        final int key =
                switch (digit) {
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

    static List<java.awt.Component> concat(
            final List<java.awt.Component> first, final List<java.awt.Component> second) {
        final List<java.awt.Component> all = new ArrayList<>(first.size() + second.size());
        all.addAll(first);
        all.addAll(second);
        return all;
    }

    /**
     * Runs one callable on the host event thread with a bound.
     *
     * <p>Same discipline as {@link #sample()}: the wait has to end even when the host thread has
     * stopped pumping, because actor discovery must never park the worker past the step
     * deadline.</p>
     */
    static <T> T onEdt(final java.util.concurrent.Callable<T> call) throws Exception {
        return WindowsProbeHarness.onEdt(call, SAMPLE_TIMEOUT_MILLIS);
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
                .filter(event -> event != null && ("on".equals(event.phase()) || "after".equals(event.phase())))
                .count();
        final String seen = "before=" + before + ",confirmed=" + confirmed + ",events=" + safeEvents.size();
        if (step == null || !supportedStepKind(step.kind())) return new Verdict(false, "unknown-step-kind", seen);
        if ("ACTION".equals(step.kind())) {
            if (before == 0) return new Verdict(false, "no-before-event", seen);
        } else if (before != 0) {
            return new Verdict(false, "navigation-was-announced-as-an-edit", seen);
        }
        final ConfirmationCheck confirmations = checkConfirmations(safeEvents);
        if (!confirmations.valid()) return new Verdict(false, confirmations.code(), seen);
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
        if (confirmations.pairs().isEmpty()) return new Verdict(false, "navigation-not-attributed", seen);
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
            final WindowsHistoryManagerValidationProbe.SdkHistorySnapshot after) {
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
        if (candidates.isEmpty()) return new Verdict(false, "no-new-entry", seen + ",candidates=0");

        final List<PartCandidate> assessments = candidates.stream()
                .map(WindowsHistoryNativeUiIngressVerdicts::assessPartCandidate)
                .toList();
        final List<PartCandidate> relationCandidates =
                assessments.stream().filter(PartCandidate::hasRelation).toList();
        final String candidateSeen =
                seen + ",candidates=" + candidates.size() + ",relationCandidates=" + relationCandidates.size();
        if (relationCandidates.isEmpty()) return new Verdict(false, "no-proven-membership-change", candidateSeen);
        if (relationCandidates.size() > 1) {
            final boolean allPartial = relationCandidates.stream()
                    .allMatch(candidate -> "partial-relation".equals(candidateFailureCode(candidate)));
            return new Verdict(false, allPartial ? "partial-relation" : "ambiguous-candidate", candidateSeen);
        }

        final PartCandidate candidate = relationCandidates.get(0);
        final String candidateFailure = candidateFailureCode(candidate);
        if (!"proven-membership-change".equals(candidateFailure)) {
            return new Verdict(false, candidateFailure, candidateSeen);
        }
        // A label-only/unstructured entry can be a selection side effect. Any additional typed
        // facts are evidence that the delta is not one unambiguous membership edit.
        if (assessments.stream().filter(other -> other != candidate).anyMatch(PartCandidate::structured)) {
            return new Verdict(false, "ambiguous-candidate", candidateSeen);
        }
        return new Verdict(true, "proven-membership-change", candidateSeen);
    }

    static String historyGateFailure(
            final WindowsHistoryManagerValidationProbe.SdkHistorySnapshot before,
            final WindowsHistoryManagerValidationProbe.SdkHistorySnapshot after) {
        if (!usableSdkHistory(before) || !usableSdkHistory(after)) return "history-unavailable";
        if (!sameNonBlank(before.documentBindingId(), after.documentBindingId())
                || !sameNonBlank(before.managerBindingId(), after.managerBindingId())
                || before.generation() != after.generation()) {
            return "binding-changed";
        }
        if (truncatedSdkHistory(before) || truncatedSdkHistory(after)) return "history-truncated";
        if (!completeEntryIdentities(before) || !completeEntryIdentities(after)) return "history-unavailable";
        return null;
    }

    static boolean truncatedSdkHistory(final WindowsHistoryManagerValidationProbe.SdkHistorySnapshot snapshot) {
        return snapshot.totalEntries() != snapshot.entries().size();
    }

    static boolean sameNonBlank(final String left, final String right) {
        return nonBlank(left) && Objects.equals(left, right);
    }

    static boolean nonBlank(final String value) {
        return value != null && !value.isBlank();
    }

    static PartCandidate assessPartCandidate(final WindowsHistoryManagerValidationProbe.SdkEntry entry) {
        final HistoryEntryDetail detail = entry.detail();
        if (detail == null) return new PartCandidate(entry.entryId(), true, true, false, Set.of(), List.of(), true);
        final DetailWalk walk = walkDetail(detail, 0, new int[] {0});
        return new PartCandidate(
                entry.entryId(),
                walk.structured(),
                walk.complete(),
                walk.unstableTarget(),
                walk.targetKeys(),
                walk.relations(),
                walk.hasNonRelationChange());
    }

    static String candidateFailureCode(final PartCandidate candidate) {
        if (!candidate.hasRelation()) return "no-proven-membership-change";
        if (candidate.hasNonRelationChange()) return "ambiguous-candidate";
        if (!candidate.complete()) return "partial-relation";
        if (candidate.relations().size() != 1
                || candidate.unstableTarget()
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

    static DetailWalk walkDetail(final HistoryEntryDetail detail, final int depth, final int[] nodes) {
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
        return new DetailWalk(complete, structured, unstableTarget, targetKeys, relations, hasNonRelationChange);
    }

    static RelationObservation relationObservation(final HistoryEntryDetail detail, final HistoryChange change) {
        final HistoryRelationChange relation = change.relation().orElseThrow();
        if (relation.kind() != HistoryRelationChange.Kind.PART_MEMBERSHIP) {
            return new RelationObservation(RelationStatus.NON_MEMBERSHIP);
        }
        if (change.targetIndex().isEmpty()) return new RelationObservation(RelationStatus.INVALID_TARGET_INDEX);
        final int targetIndex = change.targetIndex().orElse(-1);
        if (targetIndex < 0 || targetIndex >= detail.targets().size()) {
            return new RelationObservation(RelationStatus.INVALID_TARGET_INDEX);
        }
        if (!stableTarget(detail.targets().get(targetIndex))) return new RelationObservation(RelationStatus.PARTIAL);
        final EndpointIdentity before = endpointIdentity(relation.before());
        final EndpointIdentity after = endpointIdentity(relation.after());
        if (before == null || after == null) return new RelationObservation(RelationStatus.PARTIAL);
        if (sameEndpoint(before, after)) return new RelationObservation(RelationStatus.SAME_ENDPOINT);
        return new RelationObservation(RelationStatus.COMPLETE);
    }

    enum RelationStatus {
        COMPLETE,
        PARTIAL,
        INVALID_TARGET_INDEX,
        SAME_ENDPOINT,
        NON_MEMBERSHIP
    }

    record TargetKey(String type, String id) {}

    record EndpointIdentity(HistoryRelationChange.State state, TargetKey target) {}

    record RelationObservation(RelationStatus status) {}

    record DetailWalk(
            boolean complete,
            boolean structured,
            boolean unstableTarget,
            Set<TargetKey> targetKeys,
            List<RelationObservation> relations,
            boolean hasNonRelationChange) {
        DetailWalk {
            targetKeys = Set.copyOf(targetKeys);
            relations = List.copyOf(relations);
        }

        static DetailWalk incomplete() {
            return new DetailWalk(false, true, true, Set.of(), List.of(), true);
        }
    }

    static boolean supportedStepKind(final String kind) {
        return "ACTION".equals(kind) || "UNDO".equals(kind) || "REDO".equals(kind);
    }

    static boolean blank(final String value) {
        return value == null || value.isBlank();
    }

    static boolean finite(final Float value) {
        return value != null && Float.isFinite(value);
    }

    static boolean same(final Float left, final float right) {
        return finite(left) && Float.compare(left, right) == 0;
    }

    static String modelId(final ParameterStateSnapshot snapshot) {
        return snapshot == null ? "" : snapshot.modelId();
    }

    static long position(final WindowsHistoryManagerValidationProbe.Snapshot snapshot) {
        return snapshot == null ? -1L : snapshot.current().position();
    }

    static void write(final Path artifact, final String value, final boolean terminal) throws Exception {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        final long existing = Files.exists(artifact) ? Files.size(artifact) : 0L;
        final long reserve = terminal ? 0L : TERMINAL_RESERVE_BYTES;
        if (bytes.length > MAX_EVIDENCE_BYTES || existing + bytes.length > MAX_EVIDENCE_BYTES - reserve) {
            throw new IllegalStateException("Native UI ingress evidence budget exhausted");
        }
        Files.writeString(
                artifact, value, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * {@return the terminal result line the host runner matches}
     *
     * <p>The runner compares one whole line, so nothing else may share it.</p>
     */
    static String summaryLine(final boolean passed) {
        return passed ? "{\"type\":\"summary\",\"status\":\"PASS\"}\n" : "{\"type\":\"summary\",\"status\":\"FAIL\"}\n";
    }

    static String quoted(final String value) {
        return WindowsProbeHarness.quoted(value);
    }

    static String json(final String value) {
        return WindowsProbeHarness.json(value);
    }

    static String json(final String value, final int maxCodePoints) {
        return WindowsProbeHarness.json(value, maxCodePoints);
    }

    static String layoutJson(
            final PartPairLayout layout, final String intendedSourceId, final String intendedTargetId) {
        return "{\"type\":\"part-gesture\",\"phase\":\"layout\",\"threadEDT\":" + SwingUtilities.isEventDispatchThread()
                + ",\"thread\":\"" + json(Thread.currentThread().getName()) + "\",\"tableIdentityHash\":"
                + identityHash(layout.table())
                + ",\"treeIdentityHash\":" + identityHash(layout.tree()) + ",\"tableModelIdentityHash\":"
                + identityHash(layout.tableModel())
                + ",\"treeModelIdentityHash\":" + identityHash(layout.treeModel()) + ",\"tableShowing\":"
                + layout.tableShowing()
                + ",\"intendedSourceId\":\"" + json(intendedSourceId) + "\",\"intendedTargetId\":\""
                + json(intendedTargetId)
                + "\",\"surface\":" + partSurfaceJson(layout.table())
                + ",\"source\":" + rowJson(layout.source(), layout.tree())
                + ",\"target\":" + rowJson(layout.target(), layout.tree()) + "}\n";
    }

    static String insetsJson(final Insets insets) {
        return insets == null
                ? "null"
                : "{\"top\":" + insets.top + ",\"left\":" + insets.left + ",\"bottom\":" + insets.bottom + ",\"right\":"
                        + insets.right + "}";
    }

    static String transformJson(final AffineTransform transform) {
        return transform == null
                ? "null"
                : "{\"scaleX\":" + transform.getScaleX() + ",\"scaleY\":" + transform.getScaleY()
                        + ",\"shearX\":" + transform.getShearX() + ",\"shearY\":" + transform.getShearY()
                        + ",\"translateX\":" + transform.getTranslateX() + ",\"translateY\":"
                        + transform.getTranslateY() + "}";
    }

    static String rowJson(final PartRowLocation row, final JTree tree) {
        if (row == null) return "null";
        return "{\"row\":" + row.row() + ",\"column\":" + row.column()
                + ",\"pathLabel\":\"" + json(treePathLabel(tree, row.path()))
                + "\",\"lastNodeLabel\":\"" + json(row.label())
                + "\",\"lastNodeIdentityHash\":" + identityHash(row.node())
                + ",\"local\":" + pointJson(row.localPoint()) + ",\"screen\":" + pointJson(row.screenPoint())
                + ",\"cell\":" + rectangleJson(row.cell()) + ",\"viewport\":" + rectangleJson(row.viewport()) + "}";
    }

    static String pointJson(final Point point) {
        return WindowsProbeHarness.pointJson(point);
    }

    static String rectangleJson(final Rectangle rectangle) {
        return WindowsProbeHarness.rectangleJson(rectangle);
    }

    static int identityHash(final Object value) {
        return WindowsProbeHarness.identityHash(value);
    }

    /** One operator instruction and the kind of native outcome it must produce. */
    record Step(String id, String kind, String instruction) {}

    /** One semantic event as the probe saw it. */
    record Observed(
            String phase,
            long sequence,
            String operation,
            String origin,
            String subjectId,
            String label,
            String thread,
            String observedAt) {
        String json(final String step) {
            return "{\"type\":\"semantic-event\",\"step\":\"" + WindowsHistoryNativeUiIngressVerdicts.json(step)
                    + "\",\"phase\":\"" + WindowsHistoryNativeUiIngressVerdicts.json(phase) + "\",\"sequence\":"
                    + sequence
                    + ",\"operation\":\"" + WindowsHistoryNativeUiIngressVerdicts.json(operation)
                    + "\",\"origin\":\"" + WindowsHistoryNativeUiIngressVerdicts.json(origin)
                    + "\",\"subjectId\":\"" + WindowsHistoryNativeUiIngressVerdicts.json(subjectId)
                    + "\",\"label\":\"" + WindowsHistoryNativeUiIngressVerdicts.json(label)
                    + "\",\"thread\":\"" + WindowsHistoryNativeUiIngressVerdicts.json(thread)
                    + "\",\"observedAt\":\"" + WindowsHistoryNativeUiIngressVerdicts.json(observedAt) + "\"}\n";
        }
    }

    /** One step's verdict. */
    record Verdict(boolean ok, String code, String detail) {
        String json(final String step) {
            return "{\"type\":\"check\",\"check\":\""
                    + WindowsHistoryNativeUiIngressVerdicts.json(step + "-" + code) + "\",\"ok\":" + ok
                    + ",\"detail\":\"" + WindowsHistoryNativeUiIngressVerdicts.json(detail) + "\"}\n";
        }
    }
}
