package dev.turboism.plugin.deformerbatchrename;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.CubismPlugin;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOutcome;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.ChoiceDialogOption;
import dev.turboism.sdk.ui.ChoiceDialogRequest;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** One confirmed rename batch participates in one native Undo transaction. */
public final class DeformerBatchRenamePlugin implements CubismPlugin {
    public static final String ACTION_ID = "deformer.rename.preview";
    private static final String OPTION_BASE_MINIMAL = "minimal-common";
    private static final String OPTION_BASE_PART = "part-name";
    private PluginContext context;
    private volatile boolean enabled;
    private volatile Registration dialog;
    private volatile long generation;
    /** Naming base used by the last preview, so a repeat run starts where the operator left off. */
    private volatile RenamePlanner.BaseNameMode baseNameMode = RenamePlanner.BaseNameMode.MINIMAL_COMMON;

    @Override
    public void init(PluginContext context) {
        this.context = context;
    }

    @Override
    public void enable() {
        enabled = true;
        generation++;
        context.disposableScope().register(context.actions().register(ACTION_ID, new ActionRegistry.Action() {
            @Override
            public String id() {
                return ACTION_ID;
            }

            @Override
            public String label() {
                return text("action.rename");
            }

            @Override
            public Consumer<ActionRegistry.ActionContext> handler() {
                return ignored -> context.uiScheduler().runOnUiThread(() -> open());
            }
        }));
        context.disposableScope()
                .register(context.menus()
                        .contribute(
                                MenuRegistry.MenuContribution.of("Turboism/" + text("action.rename"), ACTION_ID, 140)));
    }

    @Override
    public void disable() {
        enabled = false;
        generation++;
        var current = dialog;
        dialog = null;
        if (current != null) current.close();
    }

    @Override
    public void shutdown() {
        disable();
    }

    private String text(String key) {
        return context.localization().text(key);
    }

    private void notifyStatus(String key, String severity) {
        context.services()
                .require(UiHostCapabilityService.class)
                .notifyStatus(new StatusNotification(ACTION_ID, severity, text(key)));
    }

    private void open() {
        if (!enabled || dialog != null) return;
        try {
            CubismModel model;
            try {
                model = context.cubism().model().active();
            } catch (IllegalStateException missing) {
                notifyStatus("status.noModel", "INFO");
                return;
            }
            var modelId = model.id();
            var mode = chooseBaseNameMode();
            if (mode == null) return;
            baseNameMode = mode;
            var rows = RenamePlanner.preview(model, mode);
            if (rows.isEmpty()) {
                notifyStatus("status.empty", "INFO");
                return;
            }
            long current = generation;
            dialog = context.services()
                    .require(UiHostCapabilityService.class)
                    .openSelectionTableDialog(RenameDialog.request(context.localization(), rows), (accepted, ids) -> {
                        if (!enabled || current != generation) return;
                        var handle = dialog;
                        dialog = null;
                        if (handle != null) handle.close();
                        if (accepted && !ids.isEmpty()) apply(modelId, rows, ids, mode);
                    });
        } catch (RuntimeException failure) {
            context.logger().warn("Deformer rename failed: " + failure);
            notifyStatus("status.failed", "ERROR");
        }
    }

    /**
     * Asks which name the batch derives from, before the preview is built.
     *
     * <p>The preview dialog is a static table, so the choice has to precede it; asking first keeps
     * one confirmable list instead of a preview that silently changes under the operator.</p>
     *
     * @return the chosen mode, or {@code null} when the operator cancelled
     */
    private RenamePlanner.BaseNameMode chooseBaseNameMode() {
        final ChoiceDialogRequest request = new ChoiceDialogRequest(
                "deformer.rename.base",
                text("base.title"),
                text("base.notice"),
                List.of(
                        new ChoiceDialogOption(
                                OPTION_BASE_MINIMAL, text("base.minimal"), text("base.minimal.detail"), true),
                        new ChoiceDialogOption(OPTION_BASE_PART, text("base.part"), text("base.part.detail"), true)),
                Optional.of(
                        baseNameMode == RenamePlanner.BaseNameMode.PART_NAME ? OPTION_BASE_PART : OPTION_BASE_MINIMAL),
                text("base.accept"),
                text("base.cancel"));
        final Optional<String> chosen =
                context.services().require(UiHostCapabilityService.class).choose(request);
        if (chosen.isEmpty()) return null;
        return OPTION_BASE_PART.equals(chosen.get())
                ? RenamePlanner.BaseNameMode.PART_NAME
                : RenamePlanner.BaseNameMode.MINIMAL_COMMON;
    }

    private void apply(
            ModelId modelId, List<RenamePlanner.Row> rows, List<String> ids, RenamePlanner.BaseNameMode mode) {
        try {
            var selected = new HashSet<>(ids);
            var result = context.cubism()
                    .authoringTransactions()
                    .execute(AuthoringTransactionOptions.of(text("action.rename")), () -> {
                        CubismModel active = context.cubism().model().active();
                        if (!active.id().equals(modelId) || !rows.equals(RenamePlanner.preview(active, mode)))
                            throw new IllegalStateException("Rename preview is stale");
                        for (var row : rows)
                            if (row.changed() && selected.contains(row.id().value()))
                                active.deformers().find(row.id()).setName(row.target());
                        return selected.size();
                    });
            boolean success = result.outcome() == AuthoringTransactionOutcome.COMMITTED
                    || result.outcome() == AuthoringTransactionOutcome.NO_CHANGE;
            if (!success) context.logger().warn("Deformer rename: " + result.outcome() + " " + result.diagnosticId());
            notifyStatus(success ? "status.done" : "status.failed", success ? "INFO" : "ERROR");
        } catch (RuntimeException failure) {
            context.logger().warn("Deformer rename failed: " + failure);
            notifyStatus("status.failed", "ERROR");
        }
    }
}
