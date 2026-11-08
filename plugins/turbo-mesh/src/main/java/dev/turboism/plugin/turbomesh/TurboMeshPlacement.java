package dev.turboism.plugin.turbomesh;

import dev.turboism.plugin.turbomesh.domain.PlacementSettings;
import dev.turboism.plugin.turbomesh.domain.TurboMeshPlanner;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.task.PluginTaskKind;
import dev.turboism.sdk.task.PluginTaskPriority;
import dev.turboism.sdk.task.PluginTaskRequest;
import dev.turboism.sdk.task.TaskId;
import dev.turboism.sdk.task.TaskSubmission;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.context.ContextMenuSelection;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs one custom re-placement on the ArtMesh carried by a context-menu selection (or, failing
 * that, asks the user to select one). The heavy lifting is {@link TurboMeshPlanner}; this class
 * owns host interaction: model lookup, the undoable {@link Drawable#replaceGeometry} write and
 * status notifications.
 *
 * <p>Model reads and writes are host-thread-bound, so {@code geometry()} and
 * {@code replaceGeometry()} execute inside {@code runOnUiThread} turns. The planner itself is pure
 * compute and runs on the task scheduler when available so large meshes do not stall the editor
 * UI; when the scheduler is unavailable the plan is computed inline on the UI turn instead.
 */
final class TurboMeshPlacement {

    private static final String NOTIFY_ID = "turbo-mesh.placement";
    private static final AtomicLong PLAN_SEQUENCE = new AtomicLong();

    private final PluginContext context;

    TurboMeshPlacement(final PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    void generate(final dev.turboism.sdk.action.ActionRegistry.ActionContext actionContext) {
        final ArtMeshId target = resolveTarget(actionContext);
        if (target == null) {
            notify("WARNING", text("turbo-mesh.status.no-selection"));
            return;
        }
        context.uiScheduler().runOnUiThread(() -> refineTarget(target));
    }

    void apply(final dev.turboism.sdk.action.ActionRegistry.ActionContext actionContext) {
        final ArtMeshId target = resolveTarget(actionContext);
        if (target == null) {
            notify("WARNING", text("turbo-mesh.status.no-selection"));
            return;
        }
        context.uiScheduler().runOnUiThread(() -> readSource(target));
    }

    private ArtMeshId resolveTarget(final dev.turboism.sdk.action.ActionRegistry.ActionContext actionContext) {
        final ArtMeshId fromMenu = actionContext
                .contextMenuSelection()
                .flatMap(TurboMeshPlacement::firstArtMesh)
                .map(ArtMeshId::new)
                .orElse(null);
        if (fromMenu != null) {
            return fromMenu;
        }
        try {
            final java.util.List<ArtMeshId> selected =
                    context.selectionQuery().currentSelection().selectedArtMeshIds();
            if (selected.size() == 1) {
                return selected.get(0);
            }
        } catch (Exception ignored) {
            // SelectionQueryService unavailable or throws
        }
        return null;
    }

    private void refineTarget(final ArtMeshId target) {
        final Drawable drawable = findDrawable(target);
        if (drawable == null) {
            return;
        }
        final ArtMeshGeometry source;
        try {
            source = drawable.geometry();
        } catch (UnsupportedOperationException e) {
            notify("WARNING", text("turbo-mesh.status.geometry-unavailable"));
            return;
        }
        final dev.turboism.plugin.turbomesh.domain.refine.IncrementalMeshRefiner.RefinementOptions options =
                dev.turboism.plugin.turbomesh.domain.refine.IncrementalMeshRefiner.RefinementOptions.defaults();
        final dev.turboism.plugin.turbomesh.domain.refine.IncrementalMeshRefiner.RefinementResult result =
                dev.turboism.plugin.turbomesh.domain.refine.IncrementalMeshRefiner.refine(source, null, options);
        if (result.addedPoints() == 0) {
            notify("INFO", text("turbo-mesh.status.already-refined"));
            return;
        }
        try {
            drawable.replaceGeometry(result.geometry());
        } catch (UnsupportedOperationException e) {
            notify("WARNING", text("turbo-mesh.status.write-unavailable"));
            return;
        } catch (RuntimeException e) {
            notify("ERROR", text("turbo-mesh.status.write-failed") + ": " + e.getMessage());
            return;
        }
        final int beforeV = result.originalVertexCount();
        final int afterV = beforeV + result.addedPoints();
        final int beforeT = source.triangleIndices().size() / 3;
        final int afterT = beforeT + result.addedTriangles();
        notify(
                "INFO",
                text("turbo-mesh.status.refined")
                        + " — "
                        + beforeV + " -> " + afterV + " pts, "
                        + beforeT + " -> " + afterT + " tris");
    }

    /** UI turn: resolve the drawable and snapshot its geometry for off-thread planning. */
    private void readSource(final ArtMeshId target) {
        final Drawable drawable = findDrawable(target);
        if (drawable == null) {
            return;
        }
        final ArtMeshGeometry source;
        try {
            source = drawable.geometry();
        } catch (UnsupportedOperationException e) {
            notify("WARNING", text("turbo-mesh.status.geometry-unavailable"));
            return;
        }
        final dev.turboism.sdk.cubism.model.TexturePixels texture =
                drawable.texturePixels().orElse(null);
        final boolean keyforms = hasParameterKeyforms(drawable);
        final PlacementSettings settings = PlacementSettings.fromSystemProperties();

        final Runnable workflow = () -> runInteractiveWorkflow(target, source, texture, settings, keyforms);

        if (context.tasks().isAvailable()) {
            final TaskSubmission submission = context.tasks()
                    .submit(new PluginTaskRequest(
                            new TaskId("turbo-mesh-plan-" + PLAN_SEQUENCE.incrementAndGet()),
                            PluginTaskKind.COMPUTE,
                            PluginTaskPriority.NORMAL,
                            token -> {
                                token.checkCanceled();
                                workflow.run();
                            }));
            if (submission.accepted()) {
                return;
            }
        }
        workflow.run();
    }

    /** Background worker: runs dialogs and heavy computation without holding the uiScheduler. */
    private void runInteractiveWorkflow(
            final ArtMeshId target,
            final ArtMeshGeometry source,
            final dev.turboism.sdk.cubism.model.TexturePixels texture,
            final PlacementSettings settings,
            final boolean hasKeyforms) {
        final UiHostCapabilityService ui = context.services().find(UiHostCapabilityService.class).orElse(null);
        final boolean interactive = confirmMode() != ConfirmMode.NEVER;
        String keyformWarning = "";
        dev.turboism.plugin.turbomesh.domain.features.FeatureOptions options =
                dev.turboism.plugin.turbomesh.domain.features.FeatureOptions.fromSystemProperties();

        if (interactive) {
            if (ui == null) {
                if (hasKeyforms) {
                    notify("WARNING", text("turbo-mesh.status.blocked-no-dialog"));
                    return;
                }
                notify("INFO", text("turbo-mesh.status.dialog-unavailable"));
            } else {
                java.util.Optional<String> chosen = java.util.Optional.empty();
                boolean dialogRejected = false;
                try {
                    chosen = ui.choose(modeDialog());
                } catch (RuntimeException dialogFailure) {
                    context.logger().warn("turbo-mesh mode dialog unavailable: " + dialogFailure);
                    dialogRejected = true;
                }
                if (dialogRejected) {
                    if (hasKeyforms) {
                        notify("WARNING", text("turbo-mesh.status.blocked-no-dialog"));
                        return;
                    }
                    notify("INFO", text("turbo-mesh.status.dialog-unavailable"));
                } else if (chosen.isEmpty()) {
                    notify("INFO", text("turbo-mesh.status.cancelled"));
                    return;
                } else {
                    final dev.turboism.plugin.turbomesh.domain.features.DensityPreset preset =
                            dev.turboism.plugin.turbomesh.domain.features.DensityPreset.valueOf(chosen.get());
                    options = dev.turboism.plugin.turbomesh.domain.features.FeatureOptions.forPreset(preset);
                    keyformWarning = hasKeyforms ? text("turbo-mesh.dialog.keyform-warning") : "";
                }
            }
        }

        final TurboMeshPlanner.PlacementPlan plan;
        try {
            plan = planFor(source, texture, settings, options);
        } catch (TurboMeshPlanner.PlacementException failure) {
            context.uiScheduler()
                    .runOnUiThread(
                            () -> notify("ERROR", text("turbo-mesh.status.failed") + ": " + failure.getMessage()));
            return;
        }

        // Preview confirmation dialog
        if (interactive && ui != null) {
            final String sourceSummary =
                    source.uvs().size() + "/" + source.triangleIndices().size() / 3;
            if (!confirmPlacement(ui, sourceSummary, keyformWarning, plan)) {
                return;
            }
        }

        context.uiScheduler().runOnUiThread(() -> commitPlanDirect(target, plan));
    }

    /** UI turn: write the planned geometry atomically. */
    private void commitPlanDirect(final ArtMeshId target, final TurboMeshPlanner.PlacementPlan plan) {
        final Drawable drawable = findDrawable(target);
        if (drawable == null) {
            return;
        }
        try {
            drawable.replaceGeometry(plan.geometry());
        } catch (UnsupportedOperationException e) {
            notify("WARNING", text("turbo-mesh.status.write-unavailable"));
            return;
        } catch (RuntimeException e) {
            notify("ERROR", text("turbo-mesh.status.write-failed") + ": " + e.getMessage());
            return;
        }
        final TurboMeshPlanner.Stats stats = plan.stats();
        notify(
                "INFO",
                text("turbo-mesh.status.applied")
                        + " — "
                        + stats.boundaryPoints() + "/" + stats.interiorPoints()
                        + " pts, " + stats.regionCount() + " regions, "
                        + stats.separatorEdges() + " separator edges, "
                        + stats.triangleCount() + " tris");
    }

    private enum ConfirmMode {
        DIALOG,
        NEVER
    }

    private static ConfirmMode confirmMode() {
        final String raw = System.getProperty("turboMesh.confirm", "dialog").trim();
        return "never".equalsIgnoreCase(raw) ? ConfirmMode.NEVER : ConfirmMode.DIALOG;
    }

    private static boolean hasParameterKeyforms(final Drawable drawable) {
        try {
            return !drawable.getParameterBindings().isEmpty();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private dev.turboism.sdk.ui.ChoiceDialogRequest modeDialog() {
        final java.util.List<dev.turboism.sdk.ui.ChoiceDialogOption> options = new java.util.ArrayList<>();
        for (final dev.turboism.plugin.turbomesh.domain.features.DensityPreset preset :
                dev.turboism.plugin.turbomesh.domain.features.DensityPreset.values()) {
            options.add(new dev.turboism.sdk.ui.ChoiceDialogOption(
                    preset.name(), text("turbo-mesh.preset." + preset.name()), "", true));
        }
        return new dev.turboism.sdk.ui.ChoiceDialogRequest(
                "turbo-mesh.mode",
                text("turbo-mesh.dialog.mode-title"),
                text("turbo-mesh.dialog.mode-notice"),
                options,
                java.util.Optional.of(dev.turboism.plugin.turbomesh.domain.features.DensityPreset.AUTO_BLEND.name()),
                text("turbo-mesh.dialog.mode-accept"),
                text("turbo-mesh.dialog.mode-cancel"),
                java.util.List.of(),
                java.util.Optional.empty(),
                "");
    }

    private TurboMeshPlanner.PlacementPlan planFor(
            final ArtMeshGeometry source,
            final dev.turboism.sdk.cubism.model.TexturePixels texture,
            final PlacementSettings settings,
            final dev.turboism.plugin.turbomesh.domain.features.FeatureOptions options)
            throws TurboMeshPlanner.PlacementException {
        if (texture != null) {
            try {
                double u0 = Double.MAX_VALUE;
                double v0 = Double.MAX_VALUE;
                double u1 = -Double.MAX_VALUE;
                double v1 = -Double.MAX_VALUE;
                for (final dev.turboism.sdk.cubism.model.Point2 uv : source.uvs()) {
                    u0 = Math.min(u0, uv.x());
                    v0 = Math.min(v0, uv.y());
                    u1 = Math.max(u1, uv.x());
                    v1 = Math.max(v1, uv.y());
                }
                final double pad = settings.interiorMarginUv() + settings.maxBoundarySpacingUv();
                final dev.turboism.plugin.turbomesh.domain.DomainMask mask =
                        dev.turboism.plugin.turbomesh.domain.DomainMask.fromPixels(
                                texture.argb(),
                                texture.width(),
                                texture.height(),
                                texture.uvToPixel(),
                                u0 - pad,
                                v0 - pad,
                                u1 + pad,
                                v1 + pad,
                                settings.rasterMaxDim(),
                                alphaThreshold());
                context.logger().info("turbo-mesh planning domain: texture alpha mask");
                return TurboMeshPlanner.plan(source, mask, settings, options);
            } catch (IllegalArgumentException | IllegalStateException invalid) {
                context.logger()
                        .info("turbo-mesh planning domain: texture mask rejected (" + invalid + "), geometry fallback");
            }
        } else {
            context.logger().info("turbo-mesh planning domain: no texture pixels, geometry fallback");
        }
        return TurboMeshPlanner.plan(source, settings, options);
    }

    private static int alphaThreshold() {
        try {
            return Math.max(
                    1,
                    Math.min(
                            255,
                            Integer.parseInt(System.getProperty("turboMesh.alphaThreshold", "8")
                                    .trim())));
        } catch (RuntimeException ignored) {
            return 8;
        }
    }

    /** UI turn: re-resolve the drawable, confirm the preview, and write the plan atomically. */
    private void commitPlan(
            final ArtMeshId target,
            final String sourceSummary,
            final String keyformWarning,
            final TurboMeshPlanner.PlacementPlan plan) {
        final Drawable drawable = findDrawable(target);
        if (drawable == null) {
            return;
        }
        if (confirmMode() != ConfirmMode.NEVER) {
            final UiHostCapabilityService ui = context.services().find(UiHostCapabilityService.class).orElse(null);
            if (ui != null) {
                if (!confirmPlacement(ui, sourceSummary, keyformWarning, plan)) {
                    return;
                }
            }
        }
        try {
            drawable.replaceGeometry(plan.geometry());
        } catch (UnsupportedOperationException e) {
            notify("WARNING", text("turbo-mesh.status.write-unavailable"));
            return;
        } catch (RuntimeException e) {
            notify("ERROR", text("turbo-mesh.status.write-failed") + ": " + e.getMessage());
            return;
        }
        final TurboMeshPlanner.Stats stats = plan.stats();
        notify(
                "INFO",
                text("turbo-mesh.status.applied")
                        + " — "
                        + stats.boundaryPoints() + "/" + stats.interiorPoints()
                        + " pts, " + stats.regionCount() + " regions, "
                        + stats.separatorEdges() + " separator edges, "
                        + stats.triangleCount() + " tris");
    }

    /**
     * Confirmation gate: shows the before/after preview using a runtime-rendered choice dialog
     * and waits for the user. Returns whether the placement may proceed. Dialog denial degrades
     * to the no-dialog contract: risky meshes fail closed, plain meshes proceed as in 0.1.0.
     */
    private boolean confirmPlacement(
            final UiHostCapabilityService ui,
            final String sourceSummary,
            final String keyformWarning,
            final TurboMeshPlanner.PlacementPlan plan) {
        final TurboMeshPlanner.Stats stats = plan.stats();
        final String body = text("turbo-mesh.dialog.before") + " " + sourceSummary + "\n"
                + text("turbo-mesh.dialog.after") + " "
                + (stats.boundaryPoints() + stats.interiorPoints()) + " / "
                + stats.triangleCount() + "\n"
                + text("turbo-mesh.dialog.preset-label") + " "
                + text("turbo-mesh.preset." + stats.presetName())
                + (keyformWarning.isEmpty() ? "" : "\n" + keyformWarning);
        final java.util.List<dev.turboism.sdk.ui.ChoiceDialogOption> options = java.util.List.of(
                new dev.turboism.sdk.ui.ChoiceDialogOption("APPLY", text("turbo-mesh.dialog.confirm-apply"), "", true),
                new dev.turboism.sdk.ui.ChoiceDialogOption(
                        "CANCEL", text("turbo-mesh.dialog.confirm-cancel"), "", true));
        final dev.turboism.sdk.ui.ChoiceDialogRequest request = new dev.turboism.sdk.ui.ChoiceDialogRequest(
                "turbo-mesh.confirm",
                text("turbo-mesh.dialog.confirm-title"),
                body,
                options,
                java.util.Optional.of("APPLY"),
                text("turbo-mesh.dialog.mode-accept"),
                text("turbo-mesh.dialog.mode-cancel"),
                java.util.List.of(),
                java.util.Optional.empty(),
                "");
        try {
            final java.util.Optional<String> decision = ui.choose(request);
            if (decision.isPresent() && "APPLY".equals(decision.get())) {
                return true;
            }
            notify("INFO", text("turbo-mesh.status.cancelled"));
            return false;
        } catch (RuntimeException dialogFailure) {
            context.logger().warn("turbo-mesh confirm dialog unavailable: " + dialogFailure);
            if (!keyformWarning.isEmpty()) {
                notify("WARNING", text("turbo-mesh.status.blocked-no-dialog"));
                return false;
            }
            return true;
        }
    }

    private Drawable findDrawable(final ArtMeshId target) {
        try {
            return context.cubism().model().active().drawables().find(target);
        } catch (IllegalStateException | java.util.NoSuchElementException e) {
            notify("WARNING", text("turbo-mesh.status.mesh-not-found"));
            return null;
        }
    }

    private void notify(final String severity, final String message) {
        context.services()
                .find(UiHostCapabilityService.class)
                .ifPresent(ui -> ui.notifyStatus(new StatusNotification(NOTIFY_ID, severity, message)));
    }

    private String text(final String key) {
        return context.localization().text(key);
    }

    private static java.util.Optional<String> firstArtMesh(final ContextMenuSelection selection) {
        return selection.items().stream()
                .filter(item -> item.kind() == ContextMenuRegistry.ObjectKind.ART_MESH)
                .map(ContextMenuSelection.Item::id)
                .findFirst();
    }
}
