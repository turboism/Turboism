package dev.turboism.plugin.boundingboxwarpmirror;

import dev.turboism.plugin.boundingboxwarpmirror.mirror.MirrorOperationRunner;
import dev.turboism.plugin.boundingboxwarpmirror.mirror.MirrorTargets;
import dev.turboism.plugin.boundingboxwarpmirror.ui.MirrorDirectionDialog;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.mirror.WarpMirrorBlockerCode;
import dev.turboism.sdk.cubism.mirror.WarpMirrorService;
import dev.turboism.sdk.cubism.model.WarpDeformer;
import dev.turboism.sdk.cubism.service.query.ModelHierarchy;
import dev.turboism.sdk.cubism.service.query.SelectionSummary;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.TurboismPlugin;
import dev.turboism.sdk.task.PluginTaskKind;
import dev.turboism.sdk.task.PluginTaskPriority;
import dev.turboism.sdk.task.PluginTaskRequest;
import dev.turboism.sdk.task.TaskId;
import dev.turboism.sdk.ui.BoundingBoxOverlayButton;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ports the legacy BoundingBox overlay mirror workflow: one overlay button opens a
 * direction chooser, and confirming applies a one-shot whole-object mirror to every
 * selected Warp Deformer through {@link WarpMirrorService}.
 *
 * <p>This plugin complements {@code warp-deformer-alt-symmetry} — the interactive Alt
 * drag mirror stays untouched; this entry performs an explicit whole-grid operation.
 * Descendant preservation, fail-closed blockers, and the single native Undo entry are
 * all enforced inside the runtime service.</p>
 */
public final class BoundingBoxWarpMirrorPlugin implements TurboismPlugin {

    private static final String OVERLAY_BUTTON_ID = "boundingbox-warp-mirror.mirror";
    private static final int OVERLAY_BUTTON_ORDER = 100;
    private static final String ICON_RESOURCE = "META-INF/turboism/icons/mirror.png";
    private static final String ICON_HOVER_RESOURCE = "META-INF/turboism/icons/mirror_hover.png";

    private final AtomicLong applySequence = new AtomicLong();
    private final MirrorOperationRunner runner = new MirrorOperationRunner();

    private PluginContext context;
    private PluginLogger logger;

    @Override
    public void init(final PluginContext pluginContext) {
        this.context = pluginContext;
        this.logger = pluginContext.logger();
    }

    @Override
    public void enable() {
        try {
            context.disposableScope().register(context.uiHost().contributeBoundingBoxOverlayButton(
                new BoundingBoxOverlayButton(
                    OVERLAY_BUTTON_ID,
                    context.localization().text("overlay.tooltip"),
                    new BoundingBoxOverlayButton.IconVariants(
                        ICON_RESOURCE,
                        java.util.Optional.of(ICON_HOVER_RESOURCE),
                        // Legacy convention: the pressed state shows the same
                        // icon as hover (single brighter variant for both).
                        java.util.Optional.of(ICON_HOVER_RESOURCE),
                        java.util.Optional.empty()),
                    OVERLAY_BUTTON_ORDER,
                    this::onOverlayClick
                )
            ));
            logger.info("BoundingBox warp mirror overlay button contributed");
        } catch (RuntimeException | Error unsupported) {
            logger.warn("BoundingBox overlay contribution unavailable: "
                + unsupported.getClass().getSimpleName());
        }
    }

    @Override
    public void disable() {
        // The overlay registration is bound to disposableScope; disable unregisters it.
    }

    private void onOverlayClick() {
        SwingUtilities.invokeLater(this::openMirrorDialog);
    }

    private void openMirrorDialog() {
        final PluginLocalization localization = context.localization();
        final List<DeformerId> targets;
        try {
            targets = resolveWarpTargets();
        } catch (dev.turboism.sdk.cubism.CubismServiceException | RuntimeException failure) {
            logger.warn("Warp mirror target resolution failed: " + failure.getMessage());
            showMessage(localization.text("dialog.noTarget"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (targets.isEmpty()) {
            showMessage(localization.text("dialog.noTarget"), JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        final MirrorDirectionDialog.Choice choice =
            new MirrorDirectionDialog(localization).showDialog().orElse(null);
        if (choice == null) {
            return;
        }
        submitApply(targets, choice);
    }

    private List<DeformerId> resolveWarpTargets()
        throws dev.turboism.sdk.cubism.CubismServiceException {
        final SelectionSummary selection = context.selectionQuery().currentSelection();
        final Set<DeformerId> warpIds = new HashSet<>();
        for (WarpDeformer warp : context.cubism().model().active().warpDeformers().all()) {
            warpIds.add(warp.id());
        }
        if (warpIds.isEmpty()) {
            return List.of();
        }
        final List<DeformerId> selectedWarps = selection.selectedModelObjectIds().stream()
            .map(id -> new DeformerId(id.value()))
            .filter(warpIds::contains)
            .toList();
        if (selectedWarps.isEmpty()) {
            return List.of();
        }
        final ModelHierarchy hierarchy =
            context.modelHierarchyQuery().currentHierarchy().orElse(null);
        return MirrorTargets.resolveWarpTargets(selectedWarps, warpIds, hierarchy);
    }

    private void submitApply(
        final List<DeformerId> targets,
        final MirrorDirectionDialog.Choice choice
    ) {
        final long sequence = applySequence.incrementAndGet();
        context.tasks().submit(new PluginTaskRequest(
            new TaskId("boundingbox-warp-mirror-apply-" + sequence),
            PluginTaskKind.COMPUTE,
            PluginTaskPriority.NORMAL,
            token -> {
                token.checkCanceled();
                final MirrorOperationRunner.Summary summary = runner.apply(
                    context.cubism().warpMirror(),
                    targets,
                    choice.direction(),
                    choice.preserveDescendants()
                );
                token.checkCanceled();
                report(summary);
            }
        ));
    }

    private void report(final MirrorOperationRunner.Summary summary) {
        logger.info("Warp mirror finished: applied=" + summary.applied()
            + ", unchanged=" + summary.noChange() + ", failed=" + summary.failures().size());
        if (!summary.hasFailures()) {
            return;
        }
        final PluginLocalization localization = context.localization();
        final StringBuilder message = new StringBuilder();
        for (MirrorOperationRunner.Failure failure : summary.failures()) {
            final String reason = failure.blockerCodes().stream()
                .findFirst()
                .map(code -> blockerText(localization, code))
                .orElseGet(() -> localization.text("result.blocker.write-failed"));
            message.append(localization.format(
                "result.failure.line", failure.targetId(), reason)).append('\n');
        }
        final int severity = summary.recoveryFailed()
            ? JOptionPane.ERROR_MESSAGE
            : JOptionPane.WARNING_MESSAGE;
        SwingUtilities.invokeLater(() -> showMessage(message.toString(), severity));
    }

    private String blockerText(
        final PluginLocalization localization,
        final WarpMirrorBlockerCode code
    ) {
        final String key = "result.blocker." + code.name();
        return localization.contains(key) ? localization.text(key) : code.name();
    }

    private void showMessage(final String message, final int severity) {
        JOptionPane.showMessageDialog(
            null, message, context.localization().text("dialog.title"), severity);
    }
}
