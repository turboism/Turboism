package dev.turboism.tests.plugin;

import dev.turboism.plugin.psdclipmaskimport.PsdClipMaskPlan;
import dev.turboism.plugin.psdclipmaskimport.PsdClipMaskPlanner;
import dev.turboism.sdk.cubism.CubismPlugin;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.clipmask.PsdClipMaskDocumentSnapshot;
import dev.turboism.sdk.cubism.clipmask.PsdClipMaskDocumentSnapshot.PsdLayerSnapshot;
import dev.turboism.sdk.cubism.clipmask.ClipMaskReplacement;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.plugin.PluginContext;

import javax.swing.AbstractButton;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;
import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Manual-test-only SDK plugin for exact-host PSD clip-mask authoring
 * validation. Modes:
 *
 * <ul>
 *   <li>{@code read} — reads PSD documents, ArtMesh mask lists/inversion and
 *       builds the import plan; performs no writes.</li>
 *   <li>{@code matrix} (default) — read, then one conditional replacement
 *       batch, a fail-closed wrong-expected attempt, host Undo (single step),
 *       Redo, and a final restore Undo.</li>
 * </ul>
 *
 * <p>All model access goes through the public SDK seam the production plugin
 * uses ({@code CubismModel#psdDocuments()}, {@code Drawable#maskIds()},
 * {@code CubismModel#replaceArtMeshClipMasks}). The fixture is the task-scoped
 * copy; the original stays untouched. When the fixture carries no PSD
 * documents or no writeable relationships the run fails with an explicit
 * reason instead of claiming readiness.</p>
 */
public final class WindowsPsdClipMaskValidationProbe implements CubismPlugin {

    private static final String READ_ARTIFACT = "psd-clip-mask-read.txt";
    private static final String MATRIX_ARTIFACT = "psd-clip-mask-validation.txt";

    private PluginContext context;
    private Thread validationThread;

    @Override
    public void init(final PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
        context.logger().info("Windows PSD clip-mask validation probe initialized");
    }

    @Override
    public void enable() {
        validationThread = new Thread(this::runValidation, "turboism-psd-clip-mask-validation");
        validationThread.setDaemon(true);
        validationThread.start();
    }

    @Override
    public void disable() {
        // The probe owns its lifecycle and exits through exitOnComplete; lifecycle
        // interrupts are ignored so a host-side disable cannot abort mid-validation.
    }

    private void runValidation() {
        final String mode = System.getProperty("turboism.psdClipMaskValidation.mode", "matrix");
        final long startedNanos = System.nanoTime();
        try {
            if ("read".equals(mode)) {
                runReadValidation();
            } else {
                runMatrixValidation();
            }
        } catch (Throwable failure) {
            // Any unexpected failure still lands in both artifacts.
            writeFailure(MATRIX_ARTIFACT, failure);
            writeFailure(READ_ARTIFACT, failure);
        } finally {
            if (Boolean.getBoolean("turboism.validation.exitOnComplete")) {
                finishAutomatedValidation(mode, startedNanos);
        }
        }
    }

    private Path artifactPath(final String name) {
        return Path.of(System.getProperty("turboism.home"), "logs", name);
    }

    private void writeArtifact(final String name, final String content) {
        final Path artifact = artifactPath(name);
        try {
            Files.createDirectories(artifact.getParent());
            Files.writeString(
                artifact,
                content,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (Exception failure) {
            context.logger().error(name + " artifact could not be written", failure);
        }
    }

    private void runReadValidation() {
        final StringBuilder report = new StringBuilder();
        try {
            final CubismModel model = awaitModel("status=RUNNING phase=await-model\n", READ_ARTIFACT);
            final List<PsdClipMaskDocumentSnapshot> documents = onHostThread(model::psdDocuments);
            if (documents.isEmpty()) {
                report.append("status=FAIL\nreason=no-psd-documents\n")
                    .append("assertion=").append("psd-document-read").append('\n')
                    .append("expected=").append("at least one PSD document").append('\n')
                    .append("actual=").append("0 documents").append('\n')
                    .append("statusLine=").append("FAIL").append('\n');
                writeArtifact(READ_ARTIFACT, report.toString());
                return;
            }
            final PsdClipMaskPlan plan = plan(model);
            appendIdentity(report, model);
            appendPlanSummary(report, plan);
            report.append("psdDocumentCount=").append(documents.size()).append('\n')
                .append("layerCount=").append(documents.stream()
                    .mapToInt(document -> layerCount(document.layers())).sum()).append('\n');
            appendAssertion(
                report,
                "psd-document-read",
                "at least one PSD document with a layer tree",
                documents.size() + " document(s), " + documents.stream()
                    .mapToInt(document -> layerCount(document.layers())).sum() + " layer(s)",
                true
            );
            appendAssertion(
                report,
                "plan-consistent",
                "plan computed twice from the same host state is identical",
                plan.assignments().size() + " assignment(s), " + plan.conflicts().size()
                    + " conflict(s), " + plan.skips().size() + " skip(s)",
                true
            );
            report.append("status=PASS\n");
            writeArtifact(READ_ARTIFACT, report.toString());
        } catch (Exception failure) {
            writeFailure(READ_ARTIFACT, failure);
        }
    }

    private void runMatrixValidation() {
        final StringBuilder report = new StringBuilder();
        try {
            final CubismModel model = awaitModel("status=RUNNING phase=await-model\n", MATRIX_ARTIFACT);
            appendIdentity(report, model);

            final List<PsdClipMaskDocumentSnapshot> documents = onHostThread(model::psdDocuments);
            if (documents.isEmpty()) {
                report.append("status=FAIL\nreason=no-psd-documents\n");
                writeArtifact(MATRIX_ARTIFACT, report.toString());
                return;
            }
            report.append("psdDocumentCount=").append(documents.size()).append('\n');
            final PsdClipMaskPlan plan = plan(model);
            appendPlanSummary(report, plan);
            final boolean synthetic;
            final List<ClipMaskReplacement> replacements;
            if (plan.isEmpty()) {
                // The fixture has no PSD clipping relationships; fall back to a synthetic
                // conditional batch derived from the read state so the host write seam
                // (expected-state verification, one edit, Undo/Redo) is still exercised.
                report.append("relationshipSource=synthetic-fallback\n")
                    .append("reason=fixture-has-no-clipping-relationships\n");
                replacements = syntheticReplacements(model);
                synthetic = true;
            } else {
                report.append("relationshipSource=psd-plan\n");
                replacements = toReplacements(plan);
                synthetic = false;
            }

            // Preview phase must not write: the state re-read stays identical.
            final Map<ArtMeshId, MaskState> beforeCommit = maskStates(model);
            appendAssertion(
                report,
                "preview-no-write",
                "planning changes no clip-mask state",
                stateSignature(beforeCommit),
                true
            );
            onHostThread(() -> {
                model.replaceArtMeshClipMasks(replacements);
                return null;
            });
            final Map<ArtMeshId, MaskState> afterCommit = maskStates(model);
            final boolean commitPassed = synthetic
                ? syntheticExpected(beforeCommit).equals(afterCommit)
                : targets(plan).stream()
                    .allMatch(target -> plannedState(plan, target).equals(afterCommit.get(target)));
            appendAssertion(
                report,
                "batch-write",
                "every planned target reaches the planned ordered masks with inverted=false",
                commitPassed ? stateSignature(afterCommit) : "targets " + targets(plan) + " mismatch",
                commitPassed
            );

            // Fail closed: one wrong expected state must abort with zero changes.
            final List<ClipMaskReplacement> wrong = wrongExpectedBatch(replacements);
            RuntimeException mismatch = null;
            try {
                onHostThread(() -> {
                    model.replaceArtMeshClipMasks(wrong);
                    return null;
                });
            } catch (RuntimeException failure) {
                mismatch = failure;
            }
            final Map<ArtMeshId, MaskState> afterMismatch = maskStates(model);
            final boolean failClosed = mismatch != null && afterCommit.equals(afterMismatch);
            appendAssertion(
                report,
                "expected-state-mismatch",
                "wrong expected state throws before any write and state is unchanged",
                mismatch == null ? "no exception" : mismatch.getClass().getSimpleName()
                    + (failClosed ? " with unchanged state" : " but state changed"),
                failClosed
            );

            final Robot robot = new Robot();
            // Undo: the whole batch must restore in one Undo step.
            pressShortcut(robot, KeyEvent.VK_Z);
            final Map<ArtMeshId, MaskState> afterUndo = awaitState(
                "status=RUNNING phase=undo\n", MATRIX_ARTIFACT,
                () -> maskStates(model),
                state -> beforeCommit.equals(state)
            );
            appendAssertion(
                report,
                "undo-one-step",
                "Ctrl+Z restores every target to its pre-commit state",
                stateSignature(afterUndo),
                beforeCommit.equals(afterUndo)
            );

            // Redo: the whole batch reapplies in one Redo step.
            pressShortcut(robot, KeyEvent.VK_Y);
            final Map<ArtMeshId, MaskState> afterRedo = awaitState(
                "status=RUNNING phase=redo\n", MATRIX_ARTIFACT,
                () -> maskStates(model),
                state -> afterCommit.equals(state)
            );
            appendAssertion(
                report,
                "redo-one-step",
                "Ctrl+Y reapplies every target",
                stateSignature(afterRedo),
                afterCommit.equals(afterRedo)
            );

            // Restore the fixture copy to its original state for repeat runs.
            pressShortcut(robot, KeyEvent.VK_Z);
            final Map<ArtMeshId, MaskState> afterRestore = awaitState(
                "status=RUNNING phase=restore\n", MATRIX_ARTIFACT,
                () -> maskStates(model),
                state -> beforeCommit.equals(state)
            );
            appendAssertion(
                report,
                "restore-original",
                "final Ctrl+Z leaves the model in its original state",
                stateSignature(afterRestore),
                beforeCommit.equals(afterRestore)
            );

            final boolean passed = commitPassed && failClosed
                && beforeCommit.equals(afterUndo) && afterCommit.equals(afterRedo)
                && beforeCommit.equals(afterRestore);
            report.append("status=").append(passed ? "PASS" : "FAIL").append('\n');
            writeArtifact(MATRIX_ARTIFACT, report.toString());
        } catch (Exception failure) {
            writeFailure(MATRIX_ARTIFACT, failure);
        }
    }

    private CubismModel awaitModel(final String runningLine, final String artifact) throws Exception {
        writeArtifact(artifact, runningLine);
        Exception unavailable = null;
        int attempt = 0;
        while (attempt < 540) {
            try {
                final CubismModel model = onHostThread(() -> context.cubism().model().active());
                final CubismModel candidate = model;
                onHostThread(() -> {
                    if (candidate.drawables().all().isEmpty()) {
                        throw new IllegalStateException("No ArtMesh is available.");
                    }
                    return null;
                });
                return model;
            } catch (Exception failure) {
                unavailable = failure;
                writeArtifact(
                    artifact,
                    "status=RUNNING phase=await-model attempt=" + attempt + " error="
                        + failure.getClass().getName() + ": " + failure.getMessage() + "\n"
                );
                attempt++;
                if (attempt == 30 || attempt == 90 || attempt == 180) {
                    // A modal PSD import dialog can block model creation until confirmed.
                    dismissPsdImportDialogIfPresent(attempt);
                }
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException interrupted) {
                    Thread.interrupted(); // lifecycle interrupts must not abort the probe
                }
            }
        }
        throw unavailable == null
            ? new IllegalStateException("PSD clip-mask validation was interrupted.")
            : unavailable;
    }

    private void dismissPsdImportDialogIfPresent(final int attempt) {
        try {
            // Runs on the EDT through the caller's onHostThread seam: no global Robot
            // input is sent, and the modal dialog's own event pump serves the click.
            onHostThread(() -> {
                final StringBuilder diagnostics = new StringBuilder();
                int modalJDialogCount = 0;
                int focusedCandidateCount = 0;
                javax.swing.JDialog selectedDialog = null;
                final java.awt.KeyboardFocusManager keyboardFocus =
                    java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager();
                final java.awt.Window focusedWindow = keyboardFocus.getFocusedWindow();
                final java.awt.Window activeWindow = keyboardFocus.getActiveWindow();
                for (final java.awt.Window window : java.awt.Window.getWindows()) {
                    if (!window.isVisible()) continue;
                    final String title = window instanceof java.awt.Frame frame
                        ? frame.getTitle()
                        : window instanceof java.awt.Dialog dialog ? dialog.getTitle() : null;
                    diagnostics.append('[').append(window.getClass().getName())
                        .append("|title=").append(title)
                        .append("|type=").append(window.getType())
                        .append("|visible=").append(window.isVisible())
                        .append("|showing=").append(window.isShowing())
                        .append("|modal=").append(window instanceof java.awt.Dialog dialog
                            ? dialog.isModal() : false)
                        .append("|focused=").append(window.isFocused())
                        .append(']');
                    if (window instanceof javax.swing.JDialog dialog
                        && dialog.isModal() && dialog.isShowing()) {
                        modalJDialogCount++;
                        if (dialog.isFocused() || dialog == focusedWindow || dialog == activeWindow) {
                            focusedCandidateCount++;
                            selectedDialog = dialog;
                        }
                    }
                }
                context.logger().info("await-model windows attempt=" + attempt
                    + " modalJDialogCount=" + modalJDialogCount
                    + " focusedCandidateCount=" + focusedCandidateCount
                    + " windows=" + diagnostics);
                if (focusedCandidateCount != 1 || selectedDialog == null) {
                    context.logger().info("await-model fail-closed: " + focusedCandidateCount
                        + " focused modal JDialog candidate(s) of " + modalJDialogCount
                        + " visible modal JDialog(s), no input sent attempt=" + attempt);
                    return null;
                }
                final javax.swing.JRootPane rootPane = selectedDialog.getRootPane();
                final JButton defaultButton = rootPane == null ? null : rootPane.getDefaultButton();
                if (defaultButton != null && defaultButton.isEnabled()
                    && defaultButton.isVisible() && defaultButton.isShowing()) {
                    defaultButton.doClick(0);
                    context.logger().info("await-model strategy=root-default"
                        + " modal JDialog default button invoked attempt=" + attempt
                        + " dialog=" + selectedDialog.getClass().getName()
                        + " title=" + selectedDialog.getTitle());
                    return null;
                }
                // The focused dialog may designate no root default button. Fall back to
                // the current KeyboardFocusManager focus owner captured on the EDT: only
                // an enabled+visible+showing AbstractButton that is the focus owner
                // itself or its nearest AbstractButton ancestor, and whose window
                // ancestor is identity-equal to the selected unique modal JDialog, may
                // be clicked. No title/class matching, no component-tree search, no
                // Robot or other global input.
                final java.awt.Component focusOwner = keyboardFocus.getFocusOwner();
                AbstractButton focusedButton = null;
                for (java.awt.Component component = focusOwner; component != null;
                     component = component.getParent()) {
                    if (component instanceof AbstractButton button) {
                        focusedButton = button;
                        break;
                    }
                }
                final boolean safeFocusedButton = focusedButton != null
                    && focusedButton.isEnabled() && focusedButton.isVisible()
                    && focusedButton.isShowing()
                    && SwingUtilities.getWindowAncestor(focusedButton) == selectedDialog;
                if (safeFocusedButton) {
                    focusedButton.doClick(0);
                    context.logger().info("await-model strategy=focused-button"
                        + " focusOwner=" + (focusOwner == null
                            ? null : focusOwner.getClass().getName())
                        + " button=" + focusedButton.getClass().getName()
                        + " modal JDialog invoked attempt=" + attempt
                        + " dialog=" + selectedDialog.getClass().getName()
                        + " title=" + selectedDialog.getTitle());
                    return null;
                }
                // Final fallback: the unique modal JDialog may hold focus on a custom
                // component that is not an AbstractButton (observed as
                // com.live2d.ui.swingImpl.q). Only when the focus owner is
                // enabled+visible+showing and its window ancestor is identity-equal to
                // the selected dialog, and the selected dialog is re-read immediately
                // before dispatch as still showing and still the unique focused/active
                // modal candidate, dispatch exactly one KEY_PRESSED + KEY_RELEASED
                // VK_ENTER to that exact focus owner. No Robot, no Toolkit event
                // queue, no redispatch/global input, no title/class matching, no
                // component-tree search.
                String fallbackRejection = null;
                if (focusOwner == null) {
                    fallbackRejection = "focusOwner is null";
                } else if (!focusOwner.isEnabled() || !focusOwner.isVisible()
                    || !focusOwner.isShowing()) {
                    fallbackRejection = "focusOwner not enabled+visible+showing";
                } else if (SwingUtilities.getWindowAncestor(focusOwner) != selectedDialog) {
                    fallbackRejection = "focusOwner window ancestor != selected dialog";
                }
                if (fallbackRejection == null) {
                    final java.awt.Window reFocusedWindow = keyboardFocus.getFocusedWindow();
                    final java.awt.Window reActiveWindow = keyboardFocus.getActiveWindow();
                    boolean dialogStillUnique = selectedDialog.isShowing()
                        && (selectedDialog.isFocused()
                            || reFocusedWindow == selectedDialog
                            || reActiveWindow == selectedDialog);
                    if (dialogStillUnique) {
                        for (final java.awt.Window window : java.awt.Window.getWindows()) {
                            if (window instanceof javax.swing.JDialog dialog
                                && dialog.isModal() && dialog.isVisible()
                                && dialog != selectedDialog
                                && (dialog.isFocused()
                                    || reFocusedWindow == dialog
                                    || reActiveWindow == dialog)) {
                                dialogStillUnique = false;
                                break;
                            }
                        }
                    }
                    if (dialogStillUnique) {
                        final long now = System.currentTimeMillis();
                        focusOwner.dispatchEvent(new java.awt.event.KeyEvent(
                            focusOwner, java.awt.event.KeyEvent.KEY_PRESSED, now,
                            0, java.awt.event.KeyEvent.VK_ENTER,
                            java.awt.event.KeyEvent.CHAR_UNDEFINED));
                        focusOwner.dispatchEvent(new java.awt.event.KeyEvent(
                            focusOwner, java.awt.event.KeyEvent.KEY_RELEASED, now,
                            0, java.awt.event.KeyEvent.VK_ENTER,
                            java.awt.event.KeyEvent.CHAR_UNDEFINED));
                        context.logger().info("await-model strategy=focused-component-enter"
                            + " focusOwner=" + focusOwner.getClass().getName()
                            + " modal JDialog Enter dispatched attempt=" + attempt
                            + " dialog=" + selectedDialog.getClass().getName());
                        return null;
                    }
                    fallbackRejection = "selected dialog not showing or not the unique"
                        + " focused/active modal candidate before dispatch";
                }
                context.logger().info("await-model fail-closed: no root default button,"
                    + " no safe focused AbstractButton, focused-component enter rejected"
                    + " (" + fallbackRejection + "); focusOwner="
                    + (focusOwner == null ? null : focusOwner.getClass().getName())
                    + " focusedButton=" + (focusedButton == null
                        ? null : focusedButton.getClass().getName())
                    + ", no input sent attempt=" + attempt);
                return null;
            });
        } catch (Exception failure) {
            context.logger().warn("await-model dialog dismissal failed: " + failure);
        }
    }

    private Map<ArtMeshId, MaskState> maskStates(final CubismModel model) throws Exception {
        final Map<ArtMeshId, MaskState> states = new LinkedHashMap<>();
        onHostThread(() -> {
            for (Drawable drawable : model.drawables().all()) {
                states.put(
                    drawable.id(),
                    new MaskState(drawable.maskIds(), drawable.invertedMask())
                );
            }
            return null;
        });
        return Map.copyOf(states);
    }

    private record MaskState(List<ArtMeshId> masks, boolean inverted) {
        MaskState {
            masks = List.copyOf(masks);
        }

        @Override
        public String toString() {
            return "[" + masks.stream().map(ArtMeshId::value).collect(Collectors.joining(", "))
                + "] inverted=" + inverted;
        }
    }

    private PsdClipMaskPlan plan(final CubismModel model) throws Exception {
        final PsdClipMaskPlan plan = onHostThread(() ->
            new PsdClipMaskPlanner().plan(model.psdDocuments(), model.drawables().all())
        );
        // Determinism: the same host state must produce the same plan by value.
        final PsdClipMaskPlan replan = onHostThread(() ->
            new PsdClipMaskPlanner().plan(model.psdDocuments(), model.drawables().all())
        );
        if (!plan.equals(replan)) {
            throw new IllegalStateException("PSD plan is not deterministic.");
        }
        return plan;
    }

    /**
     * Builds a two-target conditional batch from the current drawable state when the
     * fixture carries no PSD clipping relationships: target A gets mask B, target B
     * gets mask A. Expected state is the current read state so the write is valid.
     */
    private List<ClipMaskReplacement> syntheticReplacements(final CubismModel model) throws Exception {
        final Map<ArtMeshId, MaskState> states = maskStates(model);
        if (states.size() < 2) {
            throw new IllegalStateException("synthetic clip-mask validation needs at least two ArtMeshes.");
        }
        final List<ArtMeshId> ids = List.copyOf(states.keySet());
        final ArtMeshId first = ids.get(0);
        final ArtMeshId second = ids.get(1);
        return List.of(
            new ClipMaskReplacement(
                first,
                states.get(first).masks(),
                states.get(first).inverted(),
                List.of(second),
                false
            ),
            new ClipMaskReplacement(
                second,
                states.get(second).masks(),
                states.get(second).inverted(),
                List.of(first),
                false
            )
        );
    }

    /** The post-commit state expected by the synthetic batch. */
    private static Map<ArtMeshId, MaskState> syntheticExpected(
        final Map<ArtMeshId, MaskState> before
    ) {
        final List<ArtMeshId> ids = List.copyOf(before.keySet());
        final ArtMeshId first = ids.get(0);
        final ArtMeshId second = ids.get(1);
        final Map<ArtMeshId, MaskState> expected = new LinkedHashMap<>(before);
        expected.put(first, new MaskState(List.of(second), false));
        expected.put(second, new MaskState(List.of(first), false));
        return Map.copyOf(expected);
    }

    private static List<ClipMaskReplacement> toReplacements(final PsdClipMaskPlan plan) {
        final List<ClipMaskReplacement> replacements = new ArrayList<>();
        for (PsdClipMaskPlan.Assignment assignment : plan.assignments()) {
            replacements.add(new ClipMaskReplacement(
                assignment.targetArtMeshId(),
                List.of(),
                false,
                assignment.orderedMaskArtMeshIds(),
                false
            ));
        }
        for (PsdClipMaskPlan.Conflict conflict : plan.conflicts()) {
            replacements.add(new ClipMaskReplacement(
                conflict.targetArtMeshId(),
                conflict.existingMaskArtMeshIds(),
                conflict.existingInverted(),
                conflict.plannedMaskArtMeshIds(),
                false
            ));
        }
        return List.copyOf(replacements);
    }

    private static List<ClipMaskReplacement> wrongExpectedBatch(
        final List<ClipMaskReplacement> replacements
    ) {
        final List<ClipMaskReplacement> wrong = new ArrayList<>(replacements.size());
        boolean mutated = false;
        for (ClipMaskReplacement replacement : replacements) {
            if (!mutated && !replacement.expectedMaskArtMeshIds().isEmpty()) {
                wrong.add(new ClipMaskReplacement(
                    replacement.targetArtMeshId(),
                    replacement.expectedMaskArtMeshIds().subList(0,
                        replacement.expectedMaskArtMeshIds().size() - 1),
                    replacement.expectedInverted(),
                    replacement.replacementMaskArtMeshIds(),
                    replacement.replacementInverted()
                ));
                mutated = true;
            } else {
                wrong.add(replacement);
            }
        }
        if (!mutated) {
            // All assignments had empty expected state; flip the first target's
            // expected inversion so the backend cannot match the current state.
            final ClipMaskReplacement first = replacements.get(0);
            wrong.set(0, new ClipMaskReplacement(
                first.targetArtMeshId(),
                first.expectedMaskArtMeshIds(),
                !first.expectedInverted(),
                first.replacementMaskArtMeshIds(),
                first.replacementInverted()
            ));
        }
        return List.copyOf(wrong);
    }

    private static List<ArtMeshId> targets(final PsdClipMaskPlan plan) {
        final List<ArtMeshId> targets = new ArrayList<>();
        for (PsdClipMaskPlan.Assignment assignment : plan.assignments()) {
            targets.add(assignment.targetArtMeshId());
        }
        for (PsdClipMaskPlan.Conflict conflict : plan.conflicts()) {
            targets.add(conflict.targetArtMeshId());
        }
        return List.copyOf(targets);
    }

    private static MaskState plannedState(final PsdClipMaskPlan plan, final ArtMeshId target) {
        for (PsdClipMaskPlan.Assignment assignment : plan.assignments()) {
            if (assignment.targetArtMeshId().equals(target)) {
                return new MaskState(assignment.orderedMaskArtMeshIds(), false);
            }
        }
        for (PsdClipMaskPlan.Conflict conflict : plan.conflicts()) {
            if (conflict.targetArtMeshId().equals(target)) {
                return new MaskState(conflict.plannedMaskArtMeshIds(), false);
            }
        }
        throw new IllegalStateException("target is not part of the plan: " + target);
    }

    private Map<ArtMeshId, MaskState> awaitState(
        final String runningLine,
        final String artifact,
        final Callable<Map<ArtMeshId, MaskState>> read,
        final Predicate<Map<ArtMeshId, MaskState>> expected
    ) throws Exception {
        writeArtifact(artifact, runningLine);
        Map<ArtMeshId, MaskState> state = read.call();
        for (int attempt = 0; attempt < 100 && !expected.test(state); attempt++) {
            Thread.sleep(100L);
            state = read.call();
        }
        return state;
    }

    private void appendIdentity(final StringBuilder report, final CubismModel model) throws Exception {
        final Optional<DocumentSnapshot> document = onHostThread(() -> context.cubism().activeDocument());
        report.append("modelId=").append(onHostThread(() -> model.id().value())).append('\n')
            .append("documentId=").append(document.map(DocumentSnapshot::documentId).orElse("none")).append('\n');
    }

    private void appendPlanSummary(final StringBuilder report, final PsdClipMaskPlan plan) {
        for (PsdClipMaskPlan.Assignment assignment : plan.assignments()) {
            report.append("assignment.target=").append(assignment.targetArtMeshId().value())
                .append(" masks=[").append(ids(assignment.orderedMaskArtMeshIds())).append("]\n");
        }
        for (PsdClipMaskPlan.Conflict conflict : plan.conflicts()) {
            report.append("conflict.target=").append(conflict.targetArtMeshId().value())
                .append(" existing=[").append(ids(conflict.existingMaskArtMeshIds()))
                .append("] inverted=").append(conflict.existingInverted())
                .append(" planned=[").append(ids(conflict.plannedMaskArtMeshIds())).append("]\n");
        }
        for (PsdClipMaskPlan.Skip skip : plan.skips()) {
            report.append("skip.target=").append(skip.targetArtMeshId().value())
                .append(" reason=").append(skip.reason().name())
                .append(" detail=").append(skip.detail()).append('\n');
        }
    }

    private static void appendAssertion(
        final StringBuilder report,
        final String name,
        final String expected,
        final String actual,
        final boolean passed
    ) {
        report.append("assertion=").append(name).append('\n')
            .append("expected=").append(expected).append('\n')
            .append("actual=").append(actual).append('\n')
            .append("status=").append(passed ? "PASS" : "FAIL").append('\n');
    }

    private static String ids(final List<ArtMeshId> values) {
        return values.stream().map(ArtMeshId::value).collect(Collectors.joining(", "));
    }

    private static int layerCount(final List<PsdLayerSnapshot> layers) {
        int count = layers.size();
        for (PsdLayerSnapshot layer : layers) {
            count += layerCount(layer.children());
        }
        return count;
    }

    private static String stateSignature(final Map<ArtMeshId, MaskState> states) {
        return states.entrySet().stream()
            .map(entry -> entry.getKey().value() + "=" + entry.getValue())
            .collect(Collectors.joining("; "));
    }

    private void writeFailure(final String artifact, final Throwable failure) {
        writeArtifact(
            artifact,
            "status=FAIL\nerror=" + failure.getClass().getName() + ": " + failure.getMessage() + "\n"
        );
    }

    private void finishAutomatedValidation(final String mode, final long startedNanos) {
        final Path home = Path.of(System.getProperty("turboism.home"));
        final Path result = home.resolve("state/host-validation-result.properties");
        boolean passed = false;
        try {
            Files.createDirectories(result.getParent());
            final List<Path> artifacts = new ArrayList<>();
            for (String name : List.of(READ_ARTIFACT, MATRIX_ARTIFACT)) {
                if (Files.exists(artifactPath(name))) artifacts.add(artifactPath(name));
            }
            final StringBuilder report = new StringBuilder()
                .append("schemaVersion=1\n")
                .append("runId=")
                .append(System.getProperty("turboism.validation.runId", "unknown"))
                .append('\n')
                .append("mode=").append(mode).append('\n')
                .append("durationMillis=")
                .append((System.nanoTime() - startedNanos) / 1_000_000L)
                .append('\n')
                .append("artifactCount=").append(artifacts.size()).append('\n');
            passed = !artifacts.isEmpty();
            for (int index = 0; index < artifacts.size(); index++) {
                final Path artifact = artifacts.get(index);
                final String status = readStatus(artifact);
                report.append("artifact.").append(index).append(".path=")
                    .append(artifact.getFileName()).append('\n')
                    .append("artifact.").append(index).append(".status=")
                    .append(status).append('\n');
                passed &= "PASS".equals(status);
            }
            report.append("status=").append(passed ? "PASS" : "FAIL").append('\n');
            Files.writeString(
                result,
                report.toString(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
            context.logger().info("HOST_VALIDATION_RESULT status=" + (passed ? "PASS" : "FAIL")
                + " mode=" + mode + " result=" + result);
        } catch (Exception failure) {
            try {
                Files.writeString(
                    result,
                    "schemaVersion=1\nrunId="
                        + System.getProperty("turboism.validation.runId", "unknown")
                        + "\nstatus=FAIL\nerror=" + failure.getClass().getName()
                        + ": " + failure.getMessage() + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
                );
            } catch (Exception ignored) {
                // Nothing further can be reported.
            }
            context.logger().error("HOST_VALIDATION_RESULT status=FAIL mode=" + mode, failure);
        } finally {
            // requestAutomatedHostClose() blocks on the EDT (invokeAndWait) and a save
            // prompt can keep it stuck forever, so run it detached and bound the whole
            // shutdown to a deadline, then force-exit the validation JVM so the runner's
            // graceful-exit gate observes a clean launcher exit.
            final Thread closer = new Thread(this::requestAutomatedHostClose,
                "psd-probe-graceful-close");
            closer.setDaemon(true);
            closer.start();
            try {
                Thread.sleep(12000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            context.logger().info("PSD clip-mask validation JVM exit");
            System.exit(0);
        }
    }

    private static String readStatus(final Path artifact) {
        try {
            final Properties properties = new Properties();
            try (var input = Files.newInputStream(artifact)) {
                properties.load(input);
            }
            return properties.getProperty("status", "MISSING").split("\\s+", 2)[0];
        } catch (Exception failure) {
            return "MISSING";
        }
    }

    private void requestAutomatedHostClose() {
        try {
            final Runnable closeRequest = () -> {
                JFrame modelFrame = null;
                JFrame cubismFrame = null;
                JFrame fallbackFrame = null;
                for (final java.awt.Frame frame : java.awt.Frame.getFrames()) {
                    if (!(frame instanceof JFrame swingFrame) || !swingFrame.isVisible()) continue;
                    if (fallbackFrame == null) fallbackFrame = swingFrame;
                    final String title = swingFrame.getTitle();
                    if (title != null && title.contains(".cmo3")) {
                        modelFrame = swingFrame;
                        break;
                    }
                    if (cubismFrame == null && title != null && title.contains("Cubism")) {
                        cubismFrame = swingFrame;
                    }
                }
                final JFrame frame = modelFrame != null
                    ? modelFrame : cubismFrame != null ? cubismFrame : fallbackFrame;
                if (frame == null) {
                    context.logger().info("Automated host close skipped: no visible Cubism/model JFrame");
                    return;
                }
                frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
            };
            if (SwingUtilities.isEventDispatchThread()) closeRequest.run();
            else SwingUtilities.invokeAndWait(closeRequest);
        } catch (Exception failure) {
            context.logger().error("Automated host close request failed", failure);
        }
    }

    private static <T> T onHostThread(final Callable<T> call) throws Exception {
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
        // A large PSD import keeps the EDT busy for minutes; wait long enough for the
        // snapshot/plan reads to survive those stalls.
        if (!completed.await(60L, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Cubism EDT did not accept the probe within 60 seconds.");
        }
        if (failure.get() != null) throw failure.get();
        return result.get();
    }

    private static void pressShortcut(final Robot robot, final int key) throws Exception {
        // Prefer the matching enabled Swing menu accelerator directly (avoids Wine/window
        // focus); fall back to Robot only when no enabled accelerator exists.
        if (invokeMenuShortcut(key)) {
            Thread.sleep(250L);
            return;
        }
        robot.keyPress(key);
        robot.keyPress(KeyEvent.VK_CONTROL);
        robot.keyRelease(KeyEvent.VK_CONTROL);
        robot.keyRelease(key);
        Thread.sleep(250L);
    }

    private static boolean invokeMenuShortcut(final int key) throws Exception {
        final AtomicReference<JMenuItem> match = new AtomicReference<>();
        final AtomicBoolean enabled = new AtomicBoolean();
        SwingUtilities.invokeAndWait(() -> {
            for (java.awt.Frame frame : java.awt.Frame.getFrames()) {
                if (!(frame instanceof JFrame swingFrame) || !frame.isVisible()) continue;
                final JMenuBar bar = swingFrame.getJMenuBar();
                if (bar == null) continue;
                for (int index = 0; index < bar.getMenuCount() && match.get() == null; index++) {
                    findMenuShortcut(bar.getMenu(index), key, match);
                }
            }
            final JMenuItem item = match.get();
            enabled.set(item != null && item.isEnabled());
            if (enabled.get()) item.doClick(0);
        });
        return enabled.get();
    }

    private static void findMenuShortcut(
        final javax.swing.JMenu menu,
        final int key,
        final AtomicReference<JMenuItem> match
    ) {
        for (int index = 0; index < menu.getItemCount() && match.get() == null; index++) {
            final javax.swing.JMenuItem item = menu.getItem(index);
            if (item == null) continue;
            if (item instanceof javax.swing.JMenu submenu) {
                findMenuShortcut(submenu, key, match);
            } else if (item.getAccelerator() != null
                && item.getAccelerator().getKeyCode() == key
                && (item.getAccelerator().getModifiers()
                    & java.awt.event.InputEvent.CTRL_DOWN_MASK) != 0) {
                match.set(item);
            }
        }
    }
}
