package dev.turboism.shell;

import dev.turboism.core.FrameworkBuildInfo;
import dev.turboism.distribution.PreparedPluginJar;
import dev.turboism.internal.core.CoreFrameworkUpdates;
import dev.turboism.internal.core.CorePluginManagement;
import dev.turboism.internal.core.CoreUpdateService;
import dev.turboism.pluginmanagement.CoordinatedPluginUpdateService;
import dev.turboism.pluginmanagement.CoordinatedPluginUpdateService.PluginUpdateRow;
import dev.turboism.pluginmanagement.PluginCatalogClient;
import dev.turboism.pluginmanagement.PluginUpdateGate;
import dev.turboism.pluginmanagement.RuntimePluginManagementService;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.Registration;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.LongSupplier;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.Timer;
import javax.swing.table.AbstractTableModel;

/** Modeless UI. Mutable view state is EDT-owned; disk/network/leases belong to the serial worker. */
final class FrameworkUpdatesWindow implements AutoCloseable {
    enum PlanState {
        IDLE,
        PLANNING,
        PLANNED,
        PLAN_FAILED
    }

    enum BatchState {
        IDLE,
        DOWNLOADING,
        PREPARED,
        COMMITTING,
        DOWNLOAD_FAILED,
        CANCELLED
    }

    private final PluginLocalization i18n;
    private final CoreUpdateService discovery;
    private final CoreFrameworkUpdates updates;
    private final CorePluginManagement plugins;
    private final CoordinatedPluginUpdateService coordinatedService;
    private final Function<String, List<PluginUpdateRow>> planner;
    private final Function<PluginUpdateRow, String> notesLoader;
    private final ExecutorService notesWorker = Executors.newSingleThreadExecutor(r -> {
        final Thread thread = new Thread(r, "turboism-update-notes");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<PluginUpdateRow, CompletableFuture<String>> notesCache = new HashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        final Thread thread = new Thread(r, "turboism-framework-updates-window");
        thread.setDaemon(true);
        return thread;
    });
    private final List<Registration> subscriptions = new ArrayList<>();
    private final JLabel version = new JLabel();
    private final JLabel phase = new JLabel();
    private final JLabel elapsed = new JLabel();
    private final JEditorPane releaseNotes = new JEditorPane();
    private final JTextArea detail = new JTextArea();
    private final JProgressBar progress = new JProgressBar(0, 100);
    private final JButton cancel;
    private final JButton install;
    private final JButton check;
    private final JCheckBox includePlugins;
    private final PluginSelectionTableModel pluginTableModel;
    private final JTable pluginTable;
    private final JPanel pluginPanel = new JPanel(new BorderLayout(4, 4));
    private final JLabel emptyList = new JLabel();
    private final JLabel notesTitle = new JLabel();
    private final JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
    private JDialog dialog;
    private boolean subscribed;
    private boolean closed;
    private PlanState planState = PlanState.IDLE;
    private BatchState batchState = BatchState.IDLE;
    private long planGeneration;
    private String plannedVersion = "";
    private String persistentDetailMessage = "";
    private DownloadJob activeJob;
    private final LongSupplier nanoTime;
    private final Timer elapsedTimer = new Timer(250, event -> renderElapsed());
    private long downloadStartedNanos;
    private long elapsedNanos;
    private boolean timing;
    private String displayedNotes;
    private boolean plannedFrameworkAvailable;
    private boolean pluginQueued;
    private long notesGeneration;
    private long completedPluginBytes;
    private long completedPluginTotalBytes;

    /** Worker-owned resources, published to EDT only after preparation completes. Never reused by another batch. */
    private static final class DownloadJob {
        final String version;
        final boolean framework;
        final PluginCatalogClient.DownloadControl control = new PluginCatalogClient.DownloadControl();
        List<PreparedPluginJar> prepared = List.of();
        PluginUpdateGate.Lease lease;
        volatile long pluginBytes;
        volatile long pluginTotalBytes;

        DownloadJob(final String version, final boolean framework) {
            this.version = version;
            this.framework = framework;
        }
    }

    FrameworkUpdatesWindow(final PluginLocalization i18n, final CoreUpdateService discovery) {
        this(i18n, discovery, null);
    }

    FrameworkUpdatesWindow(
            final PluginLocalization i18n, final CoreUpdateService discovery, final CorePluginManagement plugins) {
        this(i18n, discovery, plugins, null, null);
    }

    FrameworkUpdatesWindow(
            final PluginLocalization i18n,
            final CoreUpdateService discovery,
            final CorePluginManagement plugins,
            final CoordinatedPluginUpdateService coordinator) {
        this(i18n, discovery, plugins, coordinator, null);
    }

    FrameworkUpdatesWindow(
            final PluginLocalization i18n,
            final CoreUpdateService discovery,
            final CorePluginManagement plugins,
            final CoordinatedPluginUpdateService coordinator,
            final Function<String, List<PluginUpdateRow>> customPlanner) {
        this(i18n, discovery, plugins, coordinator, customPlanner, System::nanoTime);
    }

    FrameworkUpdatesWindow(
            final PluginLocalization i18n,
            final CoreUpdateService discovery,
            final CorePluginManagement plugins,
            final CoordinatedPluginUpdateService coordinator,
            final Function<String, List<PluginUpdateRow>> customPlanner,
            final LongSupplier nanoTime) {
        this(i18n, discovery, plugins, coordinator, customPlanner, nanoTime, null);
    }

    FrameworkUpdatesWindow(
            final PluginLocalization i18n,
            final CoreUpdateService discovery,
            final CorePluginManagement plugins,
            final CoordinatedPluginUpdateService coordinator,
            final Function<String, List<PluginUpdateRow>> customPlanner,
            final LongSupplier nanoTime,
            final Function<PluginUpdateRow, String> customNotesLoader) {
        this.i18n = i18n;
        this.nanoTime = nanoTime;
        this.discovery = discovery;
        this.updates = discovery.frameworkUpdates();
        this.plugins = plugins;
        pluginTableModel = new PluginSelectionTableModel();
        pluginTable = new JTable(pluginTableModel);
        notesLoader = customNotesLoader != null
                ? customNotesLoader
                : row -> {
                    if (row.plannedUpdate().isEmpty()) return "";
                    try {
                        return new PluginCatalogClient()
                                .fetchReleaseNotes(row.plannedUpdate().get(), i18n.locale());
                    } catch (java.io.IOException failure) {
                        throw new java.util.concurrent.CompletionException(failure);
                    }
                };
        if (coordinator != null) {
            coordinatedService = coordinator;
        } else if (plugins instanceof RuntimePluginManagementService runtime) {
            coordinatedService = new CoordinatedPluginUpdateService(
                    runtime.homeDirectory(),
                    new PluginCatalogClient(),
                    runtime.pendingOperations(),
                    FrameworkBuildInfo.current().channel(),
                    runtime.cubismVersionSupplier(),
                    FrameworkBuildInfo.current().apiVersion());
        } else {
            coordinatedService = null;
        }
        planner = customPlanner != null
                ? customPlanner
                : target -> {
                    if (plugins instanceof RuntimePluginManagementService runtime && coordinatedService != null) {
                        final var catalog = runtime.marketService()
                                .loadCatalog(false)
                                .toCompletableFuture()
                                .join();
                        return coordinatedService.planCoordinatedUpdates(
                                catalog, runtime.plugins(), Optional.of(FrameworkBuildInfo.parseApiVersion(target)));
                    }
                    if (coordinatedService != null) throw new IllegalStateException("Plugin catalog unavailable");
                    return List.of();
                };
        cancel = new JButton(text("updates.install.cancel"));
        install = new JButton(text("updates.install.start"));
        check = new JButton(text("updates.check"));
        includePlugins = new JCheckBox(text("updates.plugins.include"), true);
        includePlugins.setVisible(coordinatedService != null);
        includePlugins.addActionListener(e -> {
            if (batchState == BatchState.COMMITTING) return;
            invalidateBatch();
            ++planGeneration;
            planState = PlanState.IDLE;
            persistentDetailMessage = "";
            render();
        });
        detail.setEditable(false);
        detail.setLineWrap(true);
        detail.setWrapStyleWord(true);
        detail.setRows(3);
        releaseNotes.setContentType("text/html");
        releaseNotes.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true);
        releaseNotes.setEditable(false);
        releaseNotes.addPropertyChangeListener("UI", event -> {
            if (displayedNotes != null) CoreDialogs.onEdt(this::renderNotes);
        });
        progress.setStringPainted(true);
        pluginTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        pluginTable.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) renderNotes();
        });
        pluginTable.getColumnModel().getColumn(0).setMaxWidth(60);
        pluginTable.getColumnModel().getColumn(1).setPreferredWidth(180);
        pluginTable.setRowHeight(
                Math.max(pluginTable.getRowHeight(), pluginTable.getFont().getSize() + 12));
        final JScrollPane tableScroll = new JScrollPane(pluginTable);
        tableScroll.setPreferredSize(new Dimension(540, 340));
        pluginPanel.add(new JLabel(text("updates.plugins.header")), BorderLayout.NORTH);
        pluginPanel.add(tableScroll, BorderLayout.CENTER);
        pluginPanel.add(emptyList, BorderLayout.SOUTH);
        pluginPanel.setMinimumSize(new Dimension(400, 160));
        final JPanel notesPanel = new JPanel(new BorderLayout(4, 4));
        notesPanel.add(notesTitle, BorderLayout.NORTH);
        notesPanel.add(new JScrollPane(releaseNotes), BorderLayout.CENTER);
        notesPanel.setMinimumSize(new Dimension(280, 160));
        split.setLeftComponent(pluginPanel);
        split.setRightComponent(notesPanel);
        split.setResizeWeight(0.55);
        split.setDividerLocation(550);
        split.setBorder(null);
        check.addActionListener(e -> {
            if (batchState == BatchState.COMMITTING) return;
            invalidateBatch();
            ++planGeneration;
            planState = PlanState.IDLE;
            notesCache.clear();
            persistentDetailMessage = "";
            discovery.checkManual().whenComplete((result, failure) -> CoreDialogs.onEdt(this::render));
            render();
        });
        cancel.addActionListener(e -> cancelCoordinatedDownload());
        install.addActionListener(e -> startCoordinatedInstall());
        render();
    }

    void show() {
        if (closed) return;
        if (!subscribed) {
            subscribed = true;
            subscriptions.add(discovery.subscribe(s -> CoreDialogs.onEdt(this::render)));
            subscriptions.add(updates.subscribe(s -> CoreDialogs.onEdt(this::render)));
        }
        if (dialog == null) {
            dialog = CoreDialogs.create(text("updates.install.title"), 1040, 640);
            if (dialog != null) {
                final JPanel content = new JPanel(new BorderLayout(8, 8));
                content.setBorder(javax.swing.BorderFactory.createEmptyBorder(12, 12, 12, 12));
                final JPanel heading = new JPanel(new java.awt.GridLayout(0, 1, 4, 4));
                heading.add(version);
                heading.add(phase);
                heading.add(progress);
                heading.add(elapsed);
                heading.add(includePlugins);
                content.add(heading, BorderLayout.NORTH);
                final JPanel center = new JPanel(new BorderLayout(8, 8));
                center.add(split, BorderLayout.CENTER);
                center.add(new JScrollPane(detail), BorderLayout.SOUTH);
                content.add(center, BorderLayout.CENTER);
                final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING));
                for (JButton button : List.of(check, cancel, install)) buttons.add(button);
                content.add(buttons, BorderLayout.SOUTH);
                dialog.setContentPane(content);
                dialog.setMinimumSize(new Dimension(900, 520));
            }
        }
        render();
        if (dialog != null) CoreDialogs.show(dialog);
    }

    void render() {
        if (closed) return;
        final var offered = discovery.snapshot();
        final var state = updates.snapshot();
        final boolean committing = batchState == BatchState.COMMITTING;
        final boolean queued = pluginQueued || state.phase() == CoreFrameworkUpdates.Phase.APPLY_PENDING;
        final boolean frameworkAvailable = offered.status() == CoreUpdateService.Status.UPDATE_AVAILABLE
                && state.phase() != CoreFrameworkUpdates.Phase.UNAVAILABLE;
        pluginTableModel.setFrameworkVersion(
                frameworkAvailable ? offered.availableVersion().orElse("") : "");
        final String target = frameworkAvailable ? offered.availableVersion().orElseThrow() : currentApiVersion();
        if (!committing
                && !queued
                && offered.status() != CoreUpdateService.Status.CHECKING
                && (frameworkAvailable || coordinatedService != null)) {
            if (!target.equals(plannedVersion)
                    || frameworkAvailable != plannedFrameworkAvailable
                    || planState == PlanState.IDLE) {
                plannedFrameworkAvailable = frameworkAvailable;
                startPlanning(target);
            }
        }
        final boolean busy = batchState == BatchState.DOWNLOADING;
        final boolean planned = planState == PlanState.PLANNED;
        includePlugins.setEnabled(!busy && !committing && !queued);
        final String displayTarget = queued || state.phase() == CoreFrameworkUpdates.Phase.STAGED
                ? state.version()
                : offered.availableVersion().orElse("");
        version.setText(offered.localVersion() + (displayTarget.isEmpty() ? "" : " → " + displayTarget));
        final String phaseName = queued
                ? "apply_pending"
                : committing
                        ? "committing"
                        : planState == PlanState.PLAN_FAILED || batchState == BatchState.DOWNLOAD_FAILED
                                ? "failed"
                                : batchState == BatchState.CANCELLED
                                        ? "cancelled"
                                        : busy
                                                ? "downloading"
                                                : batchState == BatchState.PREPARED
                                                        ? "staged"
                                                        : !frameworkAvailable && coordinatedService != null
                                                                ? "idle"
                                                                : state.phase()
                                                                        .name()
                                                                        .toLowerCase(java.util.Locale.ROOT);
        phase.setText(text("updates.install.state." + phaseName));
        final boolean frameworkProgress =
                activeJob == null ? pluginTableModel.frameworkSelected() : activeJob.framework;
        final long totalBytes = (frameworkProgress ? state.totalBytes() : 0)
                + (activeJob == null ? completedPluginTotalBytes : activeJob.pluginTotalBytes);
        final long receivedBytes = (frameworkProgress ? state.verifiedBytes() : 0)
                + (activeJob == null ? completedPluginBytes : activeJob.pluginBytes);
        progress.setIndeterminate(busy && totalBytes == 0);
        progress.setValue(totalBytes == 0 ? 0 : (int) Math.min(100, 100.0 * receivedBytes / totalBytes));
        progress.setString(text("updates.install.progress", megabytes(receivedBytes), megabytes(totalBytes)));
        renderElapsed();
        renderNotes();
        emptyList.setText(
                planState == PlanState.PLANNING
                        ? text("updates.plugins.planning")
                        : pluginTableModel.getRowCount() == 0 && planState == PlanState.PLANNED
                                ? text("updates.list.empty")
                                : "");
        final boolean selectedFramework = pluginTableModel.frameworkSelected();
        final int selected = pluginTableModel.selectedCount();
        install.setEnabled(planned && !busy && !committing && !queued && (selectedFramework || selected > 0));
        cancel.setEnabled(busy);
        check.setEnabled(!busy
                && !committing
                && !queued
                && planState != PlanState.PLANNING
                && offered.status() != CoreUpdateService.Status.CHECKING);
        detail.setText(
                !persistentDetailMessage.isBlank()
                        ? persistentDetailMessage
                        : text(
                                        state.phase() == CoreFrameworkUpdates.Phase.UNAVAILABLE
                                                        && coordinatedService == null
                                                ? "updates.install.unsupported"
                                                : "updates.install.no-shutdown")
                                + (state.detail().isBlank() ? "" : "\n\n" + state.detail()));
    }

    private void startPlanning(final String target) {
        invalidateBatch();
        plannedVersion = target;
        planState = PlanState.PLANNING;
        persistentDetailMessage = text("updates.plugins.planning");
        pluginTableModel.setRows(List.of());
        final long generation = ++planGeneration;
        if (!includePlugins.isSelected()) {
            planState = PlanState.PLANNED;
            persistentDetailMessage = "";
            return;
        }
        worker.execute(() -> {
            try {
                final List<PluginUpdateRow> rows = List.copyOf(planner.apply(target));
                CoreDialogs.onEdt(() -> {
                    if (closed || generation != planGeneration) return;
                    planState = PlanState.PLANNED;
                    persistentDetailMessage = "";
                    pluginTableModel.setRows(rows);
                    render();
                });
            } catch (Exception failure) {
                CoreDialogs.onEdt(() -> {
                    if (closed || generation != planGeneration) return;
                    planState = PlanState.PLAN_FAILED;
                    persistentDetailMessage = text("updates.plugins.plan_failed", failure.getMessage());
                    render(); // Failed generation stays failed until explicit Check or a different target.
                });
            }
        });
    }

    private void renderNotes() {
        if (closed) return;
        final long generation = ++notesGeneration;
        final int index = pluginTable.getSelectedRow();
        if (index < 0 || index >= pluginTableModel.getRowCount()) {
            notesTitle.setText(text("updates.install.notes"));
            showNotes(text("updates.install.notes.select"));
            return;
        }
        notesTitle.setText(text("updates.install.notes") + " · " + pluginTableModel.getValueAt(index, 1));
        if (pluginTableModel.isFramework(index)) {
            showNotes(discovery.snapshot().notesFor(i18n.locale()));
            return;
        }
        final PluginUpdateRow row = pluginTableModel.pluginAt(index).withSelected(false);
        final var future = notesCache.computeIfAbsent(
                row, key -> CompletableFuture.supplyAsync(() -> notesLoader.apply(key), notesWorker));
        if (!future.isDone()) showNotes(text("updates.install.notes.loading"));
        future.whenComplete((notes, failure) -> CoreDialogs.onEdt(() -> {
            if (closed || generation != notesGeneration) return;
            showNotes(failure == null ? notes : text("updates.install.notes.failed"));
        }));
    }

    private void showNotes(final String notes) {
        final String shown = notes == null || notes.isBlank() ? text("updates.install.notes.empty") : notes;
        final String themed =
                PluginReadmeRenderer.render(shown, releaseNotes.getForeground(), releaseNotes.getBackground());
        if (themed.equals(displayedNotes)) return;
        displayedNotes = themed;
        releaseNotes.setText(themed);
        releaseNotes.setCaretPosition(0);
    }

    private CompletableFuture<Void> startCoordinatedInstall() {
        if (closed || !install.isEnabled()) return CompletableFuture.completedFuture(null);
        if (canCommitPrepared()) return commitCoordinatedInstall();
        return startCoordinatedDownload()
                .thenCompose(result -> result == BatchState.PREPARED
                        ? commitCoordinatedInstall()
                        : CompletableFuture.completedFuture(null));
    }

    private boolean canCommitPrepared() {
        final var state = updates.snapshot();
        final int selected = pluginTableModel.selectedCount();
        final boolean verified = selected == 0
                || (batchState == BatchState.PREPARED
                        && activeJob != null
                        && (!activeJob.framework || activeJob.version.equals(state.version()))
                        && activeJob.prepared.size() == selected);
        return planState == PlanState.PLANNED
                && batchState != BatchState.DOWNLOADING
                && batchState != BatchState.COMMITTING
                && !pluginQueued
                && (pluginTableModel.frameworkSelected()
                        ? state.phase() == CoreFrameworkUpdates.Phase.STAGED && plannedVersion.equals(state.version())
                        : selected > 0 && batchState == BatchState.PREPARED)
                && verified;
    }

    CompletableFuture<BatchState> startCoordinatedDownload() {
        if (closed
                || !install.isEnabled()
                || planState != PlanState.PLANNED
                || batchState == BatchState.COMMITTING
                || batchState == BatchState.DOWNLOADING) return CompletableFuture.completedFuture(batchState);
        invalidateBatch();
        final boolean frameworkSelected = pluginTableModel.frameworkSelected();
        final DownloadJob job =
                new DownloadJob(frameworkSelected ? plannedVersion : currentApiVersion(), frameworkSelected);
        activeJob = job;
        final List<PluginUpdateRow> rows = List.copyOf(pluginTableModel.rows);
        final int selected = pluginTableModel.selectedCount();
        final CompletableFuture<BatchState> future = new CompletableFuture<>();
        batchState = BatchState.DOWNLOADING;
        elapsedNanos = 0;
        downloadStartedNanos = nanoTime.getAsLong();
        timing = true;
        elapsedTimer.start();
        persistentDetailMessage = "";
        render();
        worker.execute(() -> {
            String error = null;
            try {
                acquireLease(job);
                if (job.control.isCancelled()) throw new java.util.concurrent.CancellationException();
                final var framework = job.framework ? updates.download().toCompletableFuture() : null;
                if (coordinatedService != null) {
                    final var result = coordinatedService.downloadAndPrepare(
                            rows, Optional.of(FrameworkBuildInfo.parseApiVersion(job.version)), job.control, value -> {
                                job.pluginBytes = value.receivedBytes();
                                job.pluginTotalBytes = value.totalBytes();
                                CoreDialogs.onEdt(() -> {
                                    if (!closed && activeJob == job) render();
                                });
                            });
                    if (!result.succeeded()) throw new IllegalStateException(result.message());
                    job.prepared = List.copyOf(result.prepared());
                    if (job.prepared.size() != selected) throw new IllegalStateException("Incomplete plugin batch");
                }
                final var verified = framework == null ? null : framework.join();
                if (job.control.isCancelled()) throw new java.util.concurrent.CancellationException();
                if (verified != null
                        && (verified.phase() != CoreFrameworkUpdates.Phase.STAGED
                                || !job.version.equals(verified.version()))) {
                    throw new IllegalStateException(
                            verified.detail().isBlank() ? "Framework download not ready" : verified.detail());
                }
            } catch (Exception failure) {
                error = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
                if (job.framework) updates.cancel();
                cleanup(job);
            }
            final String failure = error;
            CoreDialogs.onEdt(() -> {
                if (closed || activeJob != job) {
                    future.complete(BatchState.CANCELLED);
                    return; // Invalidating this job already scheduled its worker cleanup.
                }
                stopTiming();
                if (job.control.isCancelled()) {
                    batchState = BatchState.CANCELLED;
                    persistentDetailMessage = text("updates.install.state.cancelled");
                } else if (failure != null) {
                    batchState = BatchState.DOWNLOAD_FAILED;
                    persistentDetailMessage = text("updates.plugins.download_failed", failure) + "\n\n"
                            + text("updates.plugins.strict_policy");
                } else {
                    batchState = BatchState.PREPARED;
                    persistentDetailMessage = text("updates.plugins.ready");
                }
                render();
                future.complete(batchState);
            });
        });
        return future;
    }

    void cancelCoordinatedDownload() {
        if (closed || !cancel.isEnabled()) return;
        invalidateBatch();
        updates.cancel();
        batchState = BatchState.CANCELLED;
        persistentDetailMessage = text("updates.install.state.cancelled");
        render();
    }

    CompletableFuture<Void> commitCoordinatedInstall() {
        if (closed || !canCommitPrepared()) return CompletableFuture.completedFuture(null);
        final boolean frameworkSelected = pluginTableModel.frameworkSelected();
        final String certifiedVersion = frameworkSelected ? updates.snapshot().version() : currentApiVersion();
        final DownloadJob job = activeJob != null ? activeJob : new DownloadJob(certifiedVersion, frameworkSelected);
        final List<PreparedPluginJar> batch = List.copyOf(job.prepared);
        batchState = BatchState.COMMITTING;
        render();
        final CompletableFuture<Void> future = new CompletableFuture<>();
        worker.execute(() -> {
            boolean pluginCommitSucceeded = false;
            boolean frameworkAttempted = false;
            String message;
            try {
                acquireLease(job);
                final var current = updates.snapshot();
                if (job.framework
                        && (current.phase() != CoreFrameworkUpdates.Phase.STAGED
                                || !certifiedVersion.equals(current.version())))
                    throw new IllegalStateException("Framework plan changed");
                if (coordinatedService != null) {
                    final var result = coordinatedService.commitStagedBatch(
                            batch,
                            FrameworkBuildInfo.parseApiVersion(certifiedVersion),
                            plugins == null ? List.of() : List.copyOf(plugins.plugins()));
                    if (!result.succeeded()) throw new IllegalStateException(result.message());
                }
                pluginCommitSucceeded = !batch.isEmpty();
                if (job.framework) {
                    frameworkAttempted = true;
                    final var queued =
                            updates.installOnExit().toCompletableFuture().join();
                    if (queued.phase() != CoreFrameworkUpdates.Phase.APPLY_PENDING)
                        throw new IllegalStateException(queued.detail());
                }
                message = text(
                                job.framework ? "updates.plugins.staged_count" : "updates.plugins.only_staged_count",
                                batch.size())
                        + "\n\n" + text("updates.install.no-shutdown");
            } catch (Exception failure) {
                message = pluginCommitSucceeded
                        ? text("updates.plugins.partial_failure", failure.getMessage())
                        : frameworkAttempted
                                ? text("updates.install.state.failed") + ": " + failure.getMessage()
                                : text("updates.plugins.conflict_pending", failure.getMessage());
            } finally {
                cleanup(job); // Journalled packages are retained. No global cross-journal transaction is claimed.
            }
            final String resultMessage = message;
            final boolean pluginsOnlyCommitted = pluginCommitSucceeded && !job.framework;
            CoreDialogs.onEdt(() -> {
                pluginQueued = pluginsOnlyCommitted;
                completedPluginBytes = job.pluginBytes;
                completedPluginTotalBytes = job.pluginTotalBytes;
                activeJob = null;
                batchState = BatchState.IDLE;
                persistentDetailMessage = resultMessage;
                render();
                future.complete(null);
            });
        });
        return future;
    }

    private void acquireLease(final DownloadJob job) {
        if (coordinatedService != null && job.lease == null) {
            job.lease = PluginUpdateGate.acquireLease(coordinatedService.home())
                    .orElseThrow(() -> new IllegalStateException("Another plugin update batch owns the installation"));
        }
    }

    /** Only called on the worker; may wait for auto hot-apply, never blocks the EDT. */
    private void cleanup(final DownloadJob job) {
        try {
            if (coordinatedService != null) coordinatedService.discardUncommitted(job.prepared);
        } finally {
            if (job.lease != null) {
                job.lease.close();
                job.lease = null;
            }
        }
    }

    private void invalidateBatch() {
        stopTiming();
        completedPluginBytes = 0;
        completedPluginTotalBytes = 0;
        if (batchState == BatchState.DOWNLOADING) updates.cancel();
        if (activeJob != null) {
            final DownloadJob old = activeJob;
            activeJob = null;
            old.control.cancel();
            worker.execute(() -> cleanup(old));
        }
        batchState = BatchState.IDLE;
    }

    private String megabytes(final long bytes) {
        return String.format(i18n.locale(), "%.2f", bytes / 1_000_000.0);
    }

    private static String currentApiVersion() {
        return FrameworkBuildInfo.current().apiVersion().toString();
    }

    private void renderElapsed() {
        final long nanos = timing ? Math.max(0, nanoTime.getAsLong() - downloadStartedNanos) : elapsedNanos;
        final long seconds = java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(nanos);
        final String duration = seconds < 3600
                ? String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
                : String.format(
                        java.util.Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
        elapsed.setText(text("updates.install.elapsed", duration));
    }

    private void stopTiming() {
        if (timing) elapsedNanos = Math.max(0, nanoTime.getAsLong() - downloadStartedNanos);
        timing = false;
        elapsedTimer.stop();
        renderElapsed();
    }

    JProgressBar progressBar() {
        return progress;
    }

    JLabel elapsedLabel() {
        return elapsed;
    }

    JEditorPane releaseNotesPane() {
        return releaseNotes;
    }

    JButton cancelButton() {
        return cancel;
    }

    JButton installButton() {
        return install;
    }

    JButton checkButton() {
        return check;
    }

    JCheckBox includePluginsCheckbox() {
        return includePlugins;
    }

    JTable pluginTable() {
        return pluginTable;
    }

    JTextArea detailArea() {
        return detail;
    }

    PluginSelectionTableModel tableModel() {
        return pluginTableModel;
    }

    String currentDetailText() {
        return detail.getText();
    }

    BatchState currentBatchState() {
        return batchState;
    }

    PlanState currentPlanState() {
        return planState;
    }

    CompletableFuture<BatchState> triggerDownload() {
        return startCoordinatedDownload();
    }

    void triggerCancel() {
        cancelCoordinatedDownload();
    }

    CompletableFuture<Void> triggerInstall() {
        return startCoordinatedInstall();
    }

    private String text(final String key, final Object... args) {
        return i18n.format(key, args);
    }

    private String text(final String key) {
        return i18n.text(key);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        stopTiming();
        ++planGeneration;
        if (batchState != BatchState.COMMITTING) {
            invalidateBatch();
            updates.cancel();
        }
        subscriptions.forEach(Registration::close);
        subscriptions.clear();
        // Let an already-consented commit and queued cleanup finish; closing this view never cancels a queued install.
        worker.shutdown();
        notesWorker.shutdownNow();
        if (dialog != null) dialog.dispose();
    }

    final class PluginSelectionTableModel extends AbstractTableModel {
        private final String[] columns = {"select", "name", "current", "target", "status"};
        List<PluginUpdateRow> rows = List.of();
        private List<PluginUpdateRow> visibleRows = List.of();
        private String frameworkVersion = "";
        private boolean frameworkChecked = true;

        boolean isFramework(final int index) {
            return !frameworkVersion.isEmpty() && index == 0;
        }

        boolean frameworkSelected() {
            return !frameworkVersion.isEmpty() && frameworkChecked;
        }

        PluginUpdateRow pluginAt(final int index) {
            return visibleRows.get(index - (frameworkVersion.isEmpty() ? 0 : 1));
        }

        private String selectionKey() {
            final int selected = pluginTable.getSelectedRow();
            if (selected < 0 || selected >= getRowCount()) return "";
            return isFramework(selected)
                    ? CorePluginManagement.CORE_PLUGIN_ID
                    : pluginAt(selected).pluginId();
        }

        private void refresh(final String selectedKey) {
            fireTableDataChanged();
            for (int row = 0; row < getRowCount(); row++) {
                final String key = isFramework(row)
                        ? CorePluginManagement.CORE_PLUGIN_ID
                        : pluginAt(row).pluginId();
                if (key.equals(selectedKey)) {
                    pluginTable.setRowSelectionInterval(row, row);
                    return;
                }
            }
            if (getRowCount() > 0) pluginTable.setRowSelectionInterval(0, 0);
            else renderNotes();
        }

        void setFrameworkVersion(final String value) {
            if (Objects.equals(frameworkVersion, value)) return;
            final String selectedKey = selectionKey();
            frameworkVersion = value;
            frameworkChecked = true;
            refresh(selectedKey);
        }

        void setRows(final List<PluginUpdateRow> value) {
            final String selectedKey = selectionKey();
            rows = List.copyOf(value);
            visibleRows = rows.stream()
                    .filter(row -> !row.targetVersion().isBlank()
                            && !row.targetVersion().equals(row.installedVersion())
                            && row.status() == CoordinatedPluginUpdateService.CompatibilityStatus.DUAL_COMPATIBLE)
                    .toList();
            refresh(selectedKey);
        }

        int selectedCount() {
            return (int) rows.stream().filter(PluginUpdateRow::selected).count();
        }

        @Override
        public int getRowCount() {
            return visibleRows.size() + (frameworkVersion.isEmpty() ? 0 : 1);
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(final int col) {
            return text("updates.plugins.col." + columns[col]);
        }

        @Override
        public Class<?> getColumnClass(final int col) {
            return col == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(final int row, final int col) {
            return col == 0
                    && row >= 0
                    && row < getRowCount()
                    && planState == PlanState.PLANNED
                    && batchState != BatchState.DOWNLOADING
                    && batchState != BatchState.COMMITTING
                    && !pluginQueued
                    && updates.snapshot().phase() != CoreFrameworkUpdates.Phase.APPLY_PENDING
                    && (isFramework(row)
                            || pluginAt(row).status()
                                    == CoordinatedPluginUpdateService.CompatibilityStatus.DUAL_COMPATIBLE);
        }

        @Override
        public Object getValueAt(final int index, final int col) {
            if (isFramework(index)) {
                return switch (col) {
                    case 0 -> frameworkChecked;
                    case 1 -> text("updates.list.framework");
                    case 2 -> discovery.snapshot().localVersion();
                    case 3 -> frameworkVersion;
                    case 4 -> text("updates.list.available");
                    default -> "";
                };
            }
            final var row = pluginAt(index);
            return switch (col) {
                case 0 -> row.selected();
                case 1 -> row.pluginName();
                case 2 -> row.installedVersion();
                case 3 -> row.targetVersion().isEmpty() ? "-" : row.targetVersion();
                case 4 -> text("updates.plugins.status." + row.status().name().toLowerCase(java.util.Locale.ROOT));
                default -> "";
            };
        }

        @Override
        public void setValueAt(final Object value, final int index, final int col) {
            if (!isCellEditable(index, col) || !(value instanceof Boolean selected)) return;
            if (isFramework(index)) {
                if (frameworkChecked == selected) return;
                invalidateBatch();
                frameworkChecked = selected;
                fireTableCellUpdated(index, col);
                persistentDetailMessage = text("updates.plugins.selection_changed");
                render();
                return;
            }
            if (coordinatedService == null) return;
            final var updated =
                    coordinatedService.toggleSelection(rows, pluginAt(index).pluginId(), selected);
            if (updated.equals(rows)) return;
            invalidateBatch();
            setRows(updated);
            persistentDetailMessage = text("updates.plugins.selection_changed");
            render();
        }
    }
}
