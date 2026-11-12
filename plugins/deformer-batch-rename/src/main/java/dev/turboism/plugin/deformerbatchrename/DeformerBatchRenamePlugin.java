package dev.turboism.plugin.deformerbatchrename;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.CubismPlugin;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.DeformerRename;
import dev.turboism.sdk.cubism.model.ModelNamingIndex;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOutcome;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;

/**
 * One confirmed rename batch participates in one native Undo transaction.
 *
 * <p>The naming read runs off the UI thread behind the shared progress window, because the Editor
 * validates every collection it reports and a large model takes long enough to freeze the Editor.
 * Both base-name previews are derived from that one read, so the footer switch never reads the
 * model again.</p>
 */
public final class DeformerBatchRenamePlugin implements CubismPlugin {
    public static final String ACTION_ID = "deformer.rename.preview";

    private PluginContext context;
    private RenameReadRunner runner;
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
        runner = new RenameReadRunner(this::readIndex);
        // The scope closes the runner so a disable that skips the explicit close still releases
        // the worker thread and the progress window.
        context.disposableScope().register(runner);
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
                return ignored -> context.uiScheduler().runOnUiThread(DeformerBatchRenamePlugin.this::open);
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
        final Registration current = dialog;
        dialog = null;
        if (current != null) current.close();
        final RenameReadRunner active = runner;
        runner = null;
        if (active != null) active.close();
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

    /** Reads the active model's naming index. Runs on the read worker, never on the UI thread. */
    private ModelNamingIndex readIndex() {
        return context.cubism().model().active().namingIndex();
    }

    private void open() {
        if (!enabled || dialog != null) return;
        final RenameReadRunner active = runner;
        if (active == null) return;
        final ModelId modelId;
        try {
            modelId = context.cubism().model().active().id();
        } catch (IllegalStateException missing) {
            notifyStatus("status.noModel", "INFO");
            return;
        }
        final long current = generation;
        active.requestRead(new RenameReadProgress(context.localization()), new RenameReadRunner.ResultSink() {
            @Override
            public void ready(final ModelNamingIndex index) {
                context.uiScheduler().runOnUiThread(() -> showPreview(current, modelId, index));
            }

            @Override
            public void failed(final Throwable failure) {
                context.uiScheduler().runOnUiThread(() -> reportReadFailure(current, failure));
            }
        });
    }

    private void reportReadFailure(final long expectedGeneration, final Throwable failure) {
        if (!enabled || expectedGeneration != generation) return;
        if (failure instanceof IllegalStateException) {
            notifyStatus("status.noModel", "INFO");
            return;
        }
        context.logger().warn("Deformer rename read failed: " + failure);
        notifyStatus("status.failed", "ERROR");
    }

    private void showPreview(final long expectedGeneration, final ModelId modelId, final ModelNamingIndex index) {
        if (!enabled || expectedGeneration != generation || dialog != null) return;
        if (index.deformers().isEmpty()) {
            notifyStatus("status.empty", "INFO");
            return;
        }
        try {
            final RenamePlanner.Preview preview = RenamePlanner.preview(index);
            final RenameBaseNameFooter baseNames =
                    new RenameBaseNameFooter(context.localization(), preview, baseNameMode);
            dialog = context.services()
                    .require(UiHostCapabilityService.class)
                    .openSelectionTableDialog(
                            RenameDialog.request(context.localization(), preview.rows(baseNameMode)),
                            baseNames,
                            (accepted, ids) -> {
                                if (!enabled || expectedGeneration != generation) return;
                                final Registration handle = dialog;
                                dialog = null;
                                if (handle != null) handle.close();
                                baseNameMode = baseNames.mode();
                                if (accepted && !ids.isEmpty()) {
                                    apply(modelId, preview.index(), baseNames.rows(), ids, baseNames.mode());
                                }
                            });
        } catch (RuntimeException failure) {
            context.logger().warn("Deformer rename failed: " + failure);
            notifyStatus("status.failed", "ERROR");
        }
    }

    private void apply(
            final ModelId modelId,
            final ModelNamingIndex index,
            final List<RenamePlanner.Row> rows,
            final List<String> ids,
            final RenamePlanner.BaseNameMode mode) {
        try {
            final var selected = new HashSet<>(ids);
            final var result = context.cubism()
                    .authoringTransactions()
                    .execute(AuthoringTransactionOptions.of(text("action.rename")), () -> {
                        final CubismModel active = context.cubism().model().active();
                        if (!active.id().equals(modelId)) throw new IllegalStateException("Rename preview is stale");
                        // Re-derive from a fresh read: a model edited after the preview fails closed
                        // instead of renaming a name the operator never saw.
                        final ModelNamingIndex current = active.namingIndex();
                        if (!current.equals(index)
                                || !RenamePlanner.rows(current, mode).equals(rows)) {
                            throw new IllegalStateException("Rename preview is stale");
                        }
                        // One conditional batch: the host checks every expected name, then applies
                        // all renames inside one edit and one palette refresh.
                        final List<DeformerRename> batch = rows.stream()
                                .filter(row -> row.changed()
                                        && selected.contains(row.id().value()))
                                .map(row -> new DeformerRename(row.id(), row.original(), row.target()))
                                .toList();
                        active.renameDeformers(batch);
                        return batch.size();
                    });
            final boolean success = result.outcome() == AuthoringTransactionOutcome.COMMITTED
                    || result.outcome() == AuthoringTransactionOutcome.NO_CHANGE;
            if (!success) {
                context.logger().warn("Deformer rename: " + result.outcome() + " " + result.diagnosticId());
            }
            notifyStatus(success ? "status.done" : "status.failed", success ? "INFO" : "ERROR");
        } catch (RuntimeException failure) {
            context.logger().warn("Deformer rename failed: " + failure);
            notifyStatus("status.failed", "ERROR");
        }
    }
}
