package dev.turboism.plugin.externalpsdedit;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ModelImageId;
import dev.turboism.sdk.cubism.id.RawImageId;
import dev.turboism.sdk.cubism.model.ArtMeshTextureInputs;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.ModelImageRelation;
import dev.turboism.sdk.cubism.model.ModelTextures;
import dev.turboism.sdk.cubism.model.TextureInputBinding;
import dev.turboism.sdk.cubism.model.TextureRelationsSnapshot;
import dev.turboism.sdk.cubism.model.TextureSourceQuery;
import dev.turboism.sdk.cubism.model.TextureSourcesSnapshot;
import dev.turboism.sdk.cubism.model.TextureSourcesSnapshot.ModelImageSource;
import dev.turboism.sdk.cubism.psd.PsdEditFile;
import dev.turboism.sdk.cubism.psd.PsdExportResult;
import dev.turboism.sdk.cubism.psd.PsdFileOperationResult;
import dev.turboism.sdk.cubism.psd.PsdFileRevision;
import dev.turboism.sdk.cubism.psd.PsdReplaceResult;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.CancellationToken;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.task.FixedDelayTaskRequest;
import dev.turboism.sdk.task.PluginTaskKind;
import dev.turboism.sdk.task.PluginTaskPriority;
import dev.turboism.sdk.task.PluginTaskRequest;
import dev.turboism.sdk.task.TaskHandle;
import dev.turboism.sdk.task.TaskId;
import dev.turboism.sdk.task.TaskOutcome;
import dev.turboism.sdk.task.TaskOutcomeStatus;
import dev.turboism.sdk.task.TaskSubmission;
import dev.turboism.sdk.ui.DialogRequest;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.context.ContextMenuSelection;
import dev.turboism.sdk.ui.window.TurboismWindowFactory;
import java.awt.BorderLayout;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/**
 * Owns external PSD edit sessions for one plugin instance. One session exists per stable
 * document/model/generation/model-image anchor; its current raw image is re-resolved from the
 * live relation graph before every replacement. A session owns the runtime-issued
 * {@link PsdEditFile}, subscribes to stable saves before the file is opened in the default
 * application, and converts each save revision into a Cubism native explicit-target replacement.
 *
 * <p>Sessions are bound to the document/model binding captured when their context menu was
 * built ({@link ContextMenuSelection#documentId()}). Any generation, document, or model change
 * detected at action time or on a save event invalidates the session instead of writing into a
 * different binding.</p>
 */
final class ExternalPsdEditSessionManager {

    private final PluginContext context;
    private final PluginLocalization localization;
    private final Map<SessionKey, Session> sessions = new LinkedHashMap<>();
    private final AtomicLong taskSequence = new AtomicLong();
    private final AtomicInteger pendingExportOperations = new AtomicInteger();
    private long lifecycleEpoch;
    private long refreshAttemptedEpoch = -1L;
    private long refreshHandleEpoch = -1L;
    private TaskHandle refreshHandle;
    private volatile boolean stopped;
    private volatile JDialog exportProgressDialog;

    ExternalPsdEditSessionManager(final PluginContext context, final PluginLocalization localization) {
        this.context = Objects.requireNonNull(context, "context");
        this.localization = Objects.requireNonNull(localization, "localization");
    }

    /** Entry point for the ArtMesh context-menu action. */
    void openFromContextMenu(final ActionRegistry.ActionContext actionContext) {
        Objects.requireNonNull(actionContext, "actionContext");
        ContextMenuSelection selection;
        try {
            selection = actionContext.contextMenuSelection().orElse(null);
        } catch (RuntimeException unavailable) {
            selection = null;
        }
        if (selection == null) {
            notifyStatus("external-psd-edit.error.no-selection", "ERROR", text("external-psd-edit.error.no-selection"));
            return;
        }
        final LinkedHashSet<ArtMeshId> artMeshes = new LinkedHashSet<>();
        final List<String> skipped = new ArrayList<>();
        for (final ContextMenuSelection.Item item : selection.items()) {
            if (item.kind() == ContextMenuRegistry.ObjectKind.ART_MESH
                    && !item.id().isBlank()) {
                artMeshes.add(new ArtMeshId(item.id()));
            } else {
                skipped.add(item.kind() + ":" + item.id());
            }
        }
        if (artMeshes.isEmpty()) {
            notifyStatus("external-psd-edit.error.unsupported", "ERROR", text("external-psd-edit.error.unsupported"));
            return;
        }

        notifyStatus("external-psd-edit.status.preparing", "INFO", text("external-psd-edit.status.preparing"));
        final CubismModel model;
        final TextureSourcesSnapshot relations;
        final ModelId modelId;
        String preparationStage = "active-model";
        try {
            model = context.cubism().model().active();
            preparationStage = "model-textures";
            final ModelTextures textures = model.textures();
            preparationStage = "texture-relations";
            relations = textures.sources(sourceQuery(artMeshes));
            preparationStage = "model-id";
            modelId = model.id();
        } catch (RuntimeException unavailable) {
            // Version/capability gates, permissions and stale bindings can all reject here.
            // Keep the original cause: the localized status alone cannot distinguish them.
            context.logger().error("External PSD model preparation failed; stage=" + preparationStage, unavailable);
            notifyStatus(
                    "external-psd-edit.error.model-unavailable",
                    "ERROR",
                    text("external-psd-edit.error.model-unavailable"));
            return;
        }
        if (!relations.isAvailable()) {
            notifyStatus(
                    "external-psd-edit.error.relations-unavailable",
                    "ERROR",
                    text("external-psd-edit.error.relations-unavailable"));
            return;
        }
        if (!selection.documentId().equals(relations.binding())) {
            notifyStatus(
                    "external-psd-edit.error.document-changed",
                    "ERROR",
                    text("external-psd-edit.error.document-changed"));
            return;
        }

        final Map<RawImageId, TargetSeed> targets = new LinkedHashMap<>();
        final Map<SessionKey, RawImageId> targetAnchors = new LinkedHashMap<>();
        final List<String> unresolved = new ArrayList<>(skipped);
        boolean targetCollision = false;
        for (final ArtMeshId artMesh : artMeshes) {
            final Optional<RawImageId> raw = currentRawImage(relations, artMesh);
            if (raw.isPresent()) {
                final Optional<TargetSeed> target = targetSeed(relations, modelId, raw.orElseThrow());
                if (target.isEmpty()) {
                    unresolved.add(artMesh.value());
                    continue;
                }
                final TargetSeed resolvedTarget = target.orElseThrow();
                targets.putIfAbsent(resolvedTarget.rawImageId(), resolvedTarget);
                final RawImageId priorRaw =
                        targetAnchors.putIfAbsent(resolvedTarget.key(), resolvedTarget.rawImageId());
                if (priorRaw != null && !priorRaw.equals(resolvedTarget.rawImageId())) {
                    targetCollision = true;
                }
            } else {
                unresolved.add(artMesh.value());
            }
        }
        if (!unresolved.isEmpty()) {
            notifyStatus(
                    "external-psd-edit.warn.unresolved",
                    "WARNING",
                    format("external-psd-edit.warn.unresolved", unresolved.size(), String.join(", ", unresolved)));
        }
        if (targets.isEmpty() || targetCollision) {
            notifyStatus("external-psd-edit.error.unresolved", "ERROR", text("external-psd-edit.error.unresolved"));
            return;
        }

        final String binding = relations.binding();
        final long generation = relations.generation();
        final List<Session> reopenSessions = new ArrayList<>();
        final List<Session> inFlightSessions = new ArrayList<>();
        final List<Session> staleSessions = new ArrayList<>();
        final List<TargetSeed> fresh = new ArrayList<>();
        final Map<RawImageId, Session> currentSessionsByRaw = new LinkedHashMap<>();
        boolean currentSessionCollision = false;
        synchronized (sessions) {
            if (stopped) {
                return;
            }
            for (final Session existing : sessions.values()) {
                if (!existing.matchesContext(binding, modelId, generation)) {
                    staleSessions.add(existing);
                    continue;
                }
                if (!existing.isLive()) {
                    continue;
                }
                final Optional<RawImageId> current = uniqueRawForModelImages(relations, existing.key.modelImageIds());
                if (current.isEmpty()) {
                    staleSessions.add(existing);
                    continue;
                }
                if (currentSessionsByRaw.putIfAbsent(current.orElseThrow(), existing) != null) {
                    currentSessionCollision = true;
                }
            }
            for (final TargetSeed target : targets.values()) {
                final Session existing = currentSessionsByRaw.get(target.rawImageId());
                if (existing == null || !existing.isLive()) {
                    fresh.add(target);
                } else if (existing.isReopenable()) {
                    reopenSessions.add(existing);
                } else {
                    inFlightSessions.add(existing);
                }
            }
        }
        for (final Session stale : staleSessions) {
            invalidate(stale, "document or model binding changed");
        }
        if (currentSessionCollision) {
            notifyStatus("external-psd-edit.error.unresolved", "ERROR", text("external-psd-edit.error.unresolved"));
            return;
        }
        if (reopenSessions.isEmpty() && fresh.isEmpty()) {
            notifyStatus(
                    "external-psd-edit.status.already-open", "INFO", text("external-psd-edit.status.already-open"));
            return;
        }
        final int openCount = reopenSessions.size() + fresh.size();
        if (openCount > 1 && !confirmMultiple(openCount)) {
            return;
        }
        for (final Session inFlight : inFlightSessions) {
            notifyStatus(
                    "external-psd-edit.status.already-open", "INFO", text("external-psd-edit.status.already-open"));
        }
        for (final Session existing : reopenSessions) {
            notifyStatus(
                    "external-psd-edit.status.already-open", "INFO", text("external-psd-edit.status.already-open"));
            reopenSession(existing);
        }
        openFreshWithProgress(model.textures(), fresh);
    }

    /**
     * Dispatches fresh PSD exports and shows a modal progress dialog while they run.
     *
     * <p>Native PSD serialization runs on a worker thread, matching Cubism's own background
     * export task; the modal keeps the document quiescent for that window so the save cannot
     * observe a half-applied edit, and keeps the editor visibly alive instead of freezing.
     * The dialog is dismissed once every dispatched export settles.</p>
     */
    private void openFreshWithProgress(final ModelTextures textures, final List<TargetSeed> fresh) {
        if (fresh.isEmpty()) {
            return;
        }
        final JDialog progress = GraphicsEnvironment.isHeadless() ? null : newExportProgressDialog(fresh.size());
        if (progress != null) {
            exportProgressDialog = progress;
            showExportProgress(progress);
        }
        for (final TargetSeed target : fresh) {
            try {
                beginSession(textures, target);
            } catch (RuntimeException exportRefused) {
                notifyStatus(
                        "external-psd-edit.error.session-failed",
                        "ERROR",
                        format(
                                "external-psd-edit.error.export-failed",
                                target.rawImageId().value()));
            }
        }
        if (pendingExportOperations.get() <= 0) {
            exportProgressDialog = null;
        }
    }

    /**
     * Shows the modal export dialog without blocking the caller. On the EDT the FIFO event
     * queue already orders this ahead of any export host-thread dispatch queued afterwards;
     * on other threads the caller waits until the window is actually modal so the document
     * stays quiescent before the first export can read it.
     */
    private void showExportProgress(final JDialog progress) {
        final CountDownLatch shown = new CountDownLatch(1);
        progress.addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(final WindowEvent event) {
                shown.countDown();
            }
        });
        SwingUtilities.invokeLater(() -> progress.setVisible(true));
        if (SwingUtilities.isEventDispatchThread()) {
            return;
        }
        try {
            if (!shown.await(30, TimeUnit.SECONDS)) {
                context.logger()
                        .warn(
                                "External PSD export progress dialog did not open; exports continue without its input block");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private JDialog newExportProgressDialog(final int count) {
        final JDialog dialog = new JDialog((Frame) null, text("external-psd-edit.progress.title"), true);
        TurboismWindowFactory.style(dialog);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.setResizable(false);
        final JPanel content = new JPanel(new BorderLayout(10, 10));
        content.setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));
        content.add(new JLabel(format("external-psd-edit.progress.body", count)), BorderLayout.NORTH);
        final JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        content.add(bar, BorderLayout.CENTER);
        dialog.setContentPane(content);
        dialog.pack();
        dialog.setLocationRelativeTo(null);
        return dialog;
    }

    private void onExportSettled() {
        if (pendingExportOperations.decrementAndGet() > 0) {
            return;
        }
        final JDialog dialog = exportProgressDialog;
        exportProgressDialog = null;
        if (dialog != null) {
            SwingUtilities.invokeLater(dialog::dispose);
        }
    }

    /** Allows new sessions after a disable/stop cycle; called from plugin enable. */
    void reopen() {
        synchronized (sessions) {
            stopped = false;
        }
    }

    /** Validates every live session binding after a model-close lifecycle event. */
    void onModelClosed() {
        final List<Session> snapshot;
        synchronized (sessions) {
            snapshot = new ArrayList<>(sessions.values());
        }
        if (snapshot.isEmpty()) {
            return;
        }
        String currentBinding = null;
        ModelId currentModel = null;
        long currentGeneration = -1L;
        try {
            final CubismModel model = context.cubism().model().active();
            final TextureSourcesSnapshot relations =
                    model.textures().sources(new TextureSourceQuery(Set.of(), Set.of()));
            if (relations.isAvailable()) {
                currentBinding = relations.binding();
                currentModel = model.id();
                currentGeneration = relations.generation();
            }
        } catch (RuntimeException unavailable) {
            // No live binding to compare against: every session is suspect.
        }
        for (final Session session : snapshot) {
            if (currentBinding == null
                    || currentModel == null
                    || !session.matchesContext(currentBinding, currentModel, currentGeneration)) {
                invalidate(session, "model closed");
            }
        }
    }

    /** Stops every session: unsubscribes saves and stops each file without deleting it. */
    void stopAll(final String reason) {
        final List<Session> snapshot;
        final TaskHandle refreshToClose;
        synchronized (sessions) {
            stopped = true;
            lifecycleEpoch++;
            refreshAttemptedEpoch = -1L;
            refreshHandleEpoch = -1L;
            refreshToClose = refreshHandle;
            refreshHandle = null;
            snapshot = new ArrayList<>(sessions.values());
            sessions.clear();
        }
        closeTaskHandle(refreshToClose);
        for (final Session session : snapshot) {
            session.stop();
        }
        if (!snapshot.isEmpty()) {
            context.logger().info("ExternalPsdEditPlugin stopped " + snapshot.size() + " session(s): " + reason);
        }
    }

    int liveSessionCount() {
        synchronized (sessions) {
            return (int) sessions.values().stream().filter(Session::isLive).count();
        }
    }

    private void beginSession(final ModelTextures textures, final TargetSeed target) {
        final Session session = new Session(target);
        synchronized (sessions) {
            if (stopped) {
                return;
            }
            sessions.put(target.key(), session);
        }
        ensureRefreshScheduled();
        notifyStatus(
                "external-psd-edit.status.exporting",
                "INFO",
                format("external-psd-edit.status.exporting", target.rawImageId().value()));
        try {
            final CompletionStage<PsdExportResult> export = textures.exportRawImagePsd(target.rawImageId());
            if (export == null) {
                throw new IllegalStateException("export returned no completion");
            }
            pendingExportOperations.incrementAndGet();
            export.whenComplete((result, failure) -> {
                try {
                    onExportComplete(session, result, failure);
                } finally {
                    onExportSettled();
                }
            });
        } catch (RuntimeException exportFailure) {
            failSession(
                    session,
                    format(
                            "external-psd-edit.error.export-failed",
                            target.rawImageId().value()));
        }
    }

    /**
     * Starts one read-only relation refresh for the current lifecycle epoch. Rejection is
     * reported and never replaced with an unscheduled direct run; a later enable/reopen creates
     * the next epoch and may try again.
     */
    private void ensureRefreshScheduled() {
        final long epoch;
        synchronized (sessions) {
            if (stopped
                    || !hasLiveSessionsLocked()
                    || refreshHandle != null
                    || refreshAttemptedEpoch == lifecycleEpoch) {
                return;
            }
            epoch = lifecycleEpoch;
            refreshAttemptedEpoch = epoch;
        }

        final TaskSubmission submission;
        try {
            submission = context.tasks()
                    .scheduleWithFixedDelay(new FixedDelayTaskRequest(
                            new TaskId("external-psd-edit.refresh." + epoch),
                            PluginTaskKind.COMPUTE,
                            PluginTaskPriority.NORMAL,
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(1),
                            cancellation -> refreshRelations(epoch, cancellation)));
        } catch (RuntimeException failure) {
            reportRefreshUnavailable(
                    epoch, "scheduler threw " + failure.getClass().getSimpleName());
            return;
        }
        if (submission == null || !submission.accepted()) {
            if (submission != null) {
                closeTaskHandle(submission.handle());
            }
            final String reason = submission == null
                    ? "scheduler returned no submission"
                    : "scheduler rejected " + submission.rejectionReason().orElse(null);
            reportRefreshUnavailable(epoch, reason);
            return;
        }

        final TaskHandle handle = submission.handle();
        boolean retained;
        synchronized (sessions) {
            retained = !stopped && lifecycleEpoch == epoch && hasLiveSessionsLocked() && refreshHandle == null;
            if (retained) {
                refreshHandle = handle;
                refreshHandleEpoch = epoch;
            }
        }
        if (!retained) {
            closeTaskHandle(handle);
            return;
        }
        try {
            handle.completion().whenComplete((outcome, failure) -> onRefreshTerminal(epoch, handle, outcome, failure));
        } catch (RuntimeException completionUnavailable) {
            synchronized (sessions) {
                if (refreshHandle == handle && refreshHandleEpoch == epoch) {
                    refreshHandle = null;
                    refreshHandleEpoch = -1L;
                }
            }
            closeTaskHandle(handle);
            reportRefreshUnavailable(epoch, "completion unavailable");
        }
    }

    private void onRefreshTerminal(
            final long epoch, final TaskHandle handle, final TaskOutcome outcome, final Throwable failure) {
        boolean current;
        synchronized (sessions) {
            current = refreshHandle == handle && refreshHandleEpoch == epoch;
            if (current) {
                refreshHandle = null;
                refreshHandleEpoch = -1L;
            }
        }
        if (!current) {
            return;
        }
        closeTaskHandle(handle);
        if (stopped || lifecycleEpoch != epoch) {
            return;
        }
        reportRefreshUnavailable(
                epoch,
                failure == null && outcome != null
                        ? "refresh task ended " + outcome.status()
                        : "refresh task ended unexpectedly");
    }

    private void reportRefreshUnavailable(final long epoch, final String reason) {
        synchronized (sessions) {
            if (stopped || lifecycleEpoch != epoch || !hasLiveSessionsLocked()) {
                return;
            }
        }
        notifyStatus(
                "external-psd-edit.error.refresh-unavailable",
                "ERROR",
                format("external-psd-edit.error.refresh-unavailable", reason));
    }

    private void refreshRelations(final long epoch, final CancellationToken cancellation) {
        if (cancellation.isCancellationRequested()) {
            return;
        }
        final List<Session> snapshot;
        final Map<Session, Long> sampledVersions = new LinkedHashMap<>();
        synchronized (sessions) {
            if (stopped || lifecycleEpoch != epoch) {
                return;
            }
            snapshot = sessions.values().stream()
                    .filter(Session::isRefreshCandidate)
                    .toList();
            for (final Session session : snapshot) {
                sampledVersions.put(session, session.transitionVersion());
            }
        }
        if (snapshot.isEmpty()) {
            stopRefreshIfNoLiveSessions();
            return;
        }

        final CubismModel model;
        final TextureSourcesSnapshot relations;
        try {
            model = context.cubism().model().active();
            relations = model.textures().sources(sourceQuery(Set.of()));
        } catch (RuntimeException unavailable) {
            for (final Session session : snapshot) {
                pauseForRelationRefresh(session, sampledVersions.get(session), "relation read unavailable");
            }
            return;
        }
        if (!relations.isAvailable()) {
            for (final Session session : snapshot) {
                pauseForRelationRefresh(session, sampledVersions.get(session), "relations unavailable");
            }
            return;
        }

        final Map<Session, RawImageId> resolved = new LinkedHashMap<>();
        final List<Session> stale = new ArrayList<>();
        final List<Session> unavailable = new ArrayList<>();
        for (final Session session : snapshot) {
            final long sampledVersion = sampledVersions.get(session);
            if (!session.isAtTransition(sampledVersion)) {
                continue;
            }
            if (session.hasNativeInFlight()) {
                continue;
            }
            if (!session.matchesContext(relations.binding(), model.id(), relations.generation())) {
                stale.add(session);
                continue;
            }
            final Optional<RawImageId> raw = uniqueRawForModelImages(relations, session.key.modelImageIds());
            if (raw.isEmpty()) {
                unavailable.add(session);
            } else {
                resolved.put(session, raw.orElseThrow());
            }
        }
        for (final Session session : stale) {
            final long sampledVersion = sampledVersions.get(session);
            invalidateIfAtTransition(session, sampledVersion, "relation refresh found a changed document or model");
        }
        for (final Session session : unavailable) {
            pauseForRelationRefresh(
                    session, sampledVersions.get(session), "model-image relation is missing or ambiguous");
        }

        final Map<RawImageId, List<Session>> byRaw = new LinkedHashMap<>();
        for (final Map.Entry<Session, RawImageId> entry : resolved.entrySet()) {
            byRaw.computeIfAbsent(entry.getValue(), ignored -> new ArrayList<>())
                    .add(entry.getKey());
        }
        for (final Map.Entry<RawImageId, List<Session>> entry : byRaw.entrySet()) {
            final List<Session> stable = entry.getValue().stream()
                    .filter(session -> session.isAtTransition(sampledVersions.get(session)))
                    .toList();
            if (stable.size() > 1) {
                for (final Session session : stable) {
                    pauseForRelationRefresh(
                            session,
                            sampledVersions.get(session),
                            "multiple live sessions resolve the same raw image "
                                    + entry.getKey().value());
                }
            }
        }
        for (final Map.Entry<RawImageId, List<Session>> entry : byRaw.entrySet()) {
            final List<Session> stable = entry.getValue().stream()
                    .filter(session -> session.isAtTransition(sampledVersions.get(session)))
                    .toList();
            if (stable.size() != 1) {
                continue;
            }
            final Session session = stable.get(0);
            session.recordObservedRaw(entry.getKey(), relations.revision(), sampledVersions.get(session));
        }
    }

    private void stopRefreshIfNoLiveSessions() {
        final TaskHandle handle;
        synchronized (sessions) {
            if (stopped || hasRefreshCandidatesLocked()) {
                return;
            }
            lifecycleEpoch++;
            refreshAttemptedEpoch = -1L;
            refreshHandleEpoch = -1L;
            handle = refreshHandle;
            refreshHandle = null;
        }
        closeTaskHandle(handle);
    }

    private boolean hasLiveSessionsLocked() {
        return sessions.values().stream().anyMatch(Session::isLive);
    }

    private boolean hasRefreshCandidatesLocked() {
        return sessions.values().stream().anyMatch(Session::isRefreshCandidate);
    }

    private void reopenSession(final Session session) {
        final PsdEditFile file;
        synchronized (sessions) {
            if (stopped || sessions.get(session.key) != session) {
                return;
            }
            file = session.reopenableFile();
        }
        if (file == null) {
            return;
        }
        try {
            file.openInDefaultApplication()
                    .whenComplete((result, failure) -> onReopenComplete(session, result, failure));
        } catch (RuntimeException failure) {
            onReopenComplete(session, null, failure);
        }
    }

    private void onReopenComplete(final Session session, final PsdFileOperationResult result, final Throwable failure) {
        synchronized (sessions) {
            if (stopped || sessions.get(session.key) != session) {
                return;
            }
        }
        if (failure != null || result == null || result.status() != PsdFileOperationResult.Status.OPENED) {
            notifyStatus(
                    "external-psd-edit.error.open-failed",
                    "ERROR",
                    format(
                            "external-psd-edit.error.open-failed",
                            session.rawImageId().value()));
        }
    }

    private void onExportComplete(final Session session, final PsdExportResult result, final Throwable failure) {
        if (failure != null) {
            failSession(
                    session,
                    format(
                            "external-psd-edit.error.export-failed",
                            session.rawImageId().value()));
            return;
        }
        if (result == null || result.status() != PsdExportResult.Status.EXPORTED) {
            failSession(
                    session,
                    format(
                            "external-psd-edit.error.export-failed",
                            session.rawImageId().value() + (result == null ? "" : " (" + result.status() + ")")));
            return;
        }
        final PsdEditFile exportedFile = result.file().orElse(null);
        if (exportedFile == null) {
            failSession(
                    session,
                    format(
                            "external-psd-edit.error.export-handle-unavailable",
                            session.rawImageId().value()));
            return;
        }
        final boolean retainedForSubscription;
        synchronized (session) {
            if (!session.isLive()) {
                retainedForSubscription = false;
            } else {
                session.file = exportedFile;
                retainedForSubscription = true;
            }
        }
        if (!retainedForSubscription) {
            stopFileQuietly(exportedFile);
            return;
        }

        final Registration registration;
        try {
            registration = exportedFile.observeSaves(revision -> onSave(session, revision));
            if (registration == null) {
                throw new IllegalStateException("save observation returned no registration");
            }
        } catch (RuntimeException subscribeFailure) {
            failSession(
                    session,
                    format(
                            "external-psd-edit.error.subscribe-failed",
                            session.rawImageId().value()));
            return;
        }

        final boolean retainedForOpen;
        synchronized (session) {
            retainedForOpen = session.isLive() && session.file == exportedFile && session.state == State.EXPORTING;
            if (retainedForOpen) {
                session.saveRegistration = registration;
                session.state = State.OPENING;
            }
        }
        if (!retainedForOpen) {
            closeRegistrationQuietly(registration);
            stopFileQuietly(exportedFile);
            return;
        }

        try {
            final CompletionStage<PsdFileOperationResult> open = exportedFile.openInDefaultApplication();
            if (open == null) {
                throw new IllegalStateException("open returned no completion");
            }
            open.whenComplete((openResult, openFailure) -> onOpenComplete(session, openResult, openFailure));
        } catch (RuntimeException openFailure) {
            failSession(
                    session,
                    format(
                            "external-psd-edit.error.open-failed",
                            session.rawImageId().value()));
        }
    }

    private void onOpenComplete(final Session session, final PsdFileOperationResult result, final Throwable failure) {
        synchronized (session) {
            if (!session.isLive()) {
                return;
            }
        }
        if (failure != null || result == null || result.status() != PsdFileOperationResult.Status.OPENED) {
            failSession(
                    session,
                    format(
                            "external-psd-edit.error.open-failed",
                            session.rawImageId().value()));
            return;
        }
        final boolean transitionedToActive;
        synchronized (session) {
            transitionedToActive = session.state == State.OPENING;
            if (transitionedToActive) {
                session.state = State.ACTIVE;
            }
        }
        if (!transitionedToActive) {
            return;
        }
        notifyStatus(
                "external-psd-edit.status.editing",
                "INFO",
                format("external-psd-edit.status.editing", session.rawImageId().value()));
        ensureRefreshScheduled();
    }

    private void onSave(final Session session, final PsdFileRevision revision) {
        boolean dispatch;
        synchronized (session) {
            if (!session.acceptsSaves() || session.isConsumed(revision)) {
                return;
            }
            if (session.inFlightRevision != null) {
                if (!Objects.equals(session.inFlightRevision, revision)
                        && !Objects.equals(session.pendingRevision, revision)) {
                    session.pendingRevision = revision;
                }
                return;
            }
            session.inFlightRevision = revision;
            session.inFlightTask = new RevisionTask(revision);
            session.transitionVersion++;
            dispatch = true;
        }
        if (dispatch) {
            submitRevision(session, revision);
        }
    }

    private void submitRevision(final Session session, final PsdFileRevision revision) {
        final RevisionTask task;
        synchronized (session) {
            if (!session.isLive() || !session.isInFlight(revision) || session.inFlightTask == null) {
                return;
            }
            task = session.inFlightTask;
        }
        final TaskSubmission submission;
        try {
            submission = context.tasks()
                    .submit(new PluginTaskRequest(
                            new TaskId("external-psd-edit.import." + taskSequence.incrementAndGet()),
                            PluginTaskKind.COMPUTE,
                            PluginTaskPriority.NORMAL,
                            cancellation -> importSave(session, revision, task, cancellation)));
        } catch (RuntimeException schedulerFailure) {
            failTaskBeforeNative(
                    session,
                    task,
                    "scheduler threw " + schedulerFailure.getClass().getSimpleName(),
                    true);
            return;
        }
        if (submission == null || !submission.accepted()) {
            if (submission != null) {
                closeTaskHandle(submission.handle());
            }
            failTaskBeforeNative(
                    session,
                    task,
                    submission == null
                            ? "scheduler returned no submission"
                            : "scheduler rejected "
                                    + submission.rejectionReason().orElse(null),
                    true);
            return;
        }

        final TaskHandle handle = submission.handle();
        final boolean current;
        synchronized (session) {
            current = session.isLive() && session.inFlightTask == task && session.isInFlight(revision);
        }
        if (!current) {
            closeTaskHandle(handle);
            return;
        }
        if (task.attachHandle(handle)) {
            closeTaskHandle(handle);
            return;
        }
        try {
            handle.completion().whenComplete((outcome, failure) -> onTaskTerminal(session, task, outcome, failure));
        } catch (RuntimeException completionUnavailable) {
            failTaskBeforeNative(session, task, "task completion unavailable", true);
            closeTaskHandle(handle);
        }
    }

    private void importSave(
            final Session session,
            final PsdFileRevision revision,
            final RevisionTask task,
            final CancellationToken cancellation) {
        synchronized (session) {
            if (!session.acceptsSaves() || !session.isInFlight(revision)) {
                return;
            }
        }
        if (cancellation.isCancellationRequested()) {
            return;
        }
        final TargetResolution resolution = resolveTarget(session);
        if (resolution.target().isEmpty()) {
            invalidate(session, resolution.reason());
            return;
        }
        final ResolvedTarget target = resolution.target().orElseThrow();
        final PsdEditFile file;
        synchronized (session) {
            if (!session.acceptsSaves() || !session.isInFlight(revision)) {
                return;
            }
            file = session.file;
        }
        if (cancellation.isCancellationRequested() || !task.beginNativeDispatch()) {
            return;
        }
        try {
            target.model()
                    .textures()
                    .replaceRawImagePsd(target.rawImageId(), file, revision)
                    .whenComplete((result, failure) ->
                            onReplaceComplete(session, revision, target.rawImageId(), result, failure));
        } catch (RuntimeException failure) {
            onReplaceComplete(session, revision, target.rawImageId(), null, failure);
        }
    }

    private void onTaskTerminal(
            final Session session, final RevisionTask task, final TaskOutcome outcome, final Throwable failure) {
        final boolean beforeNative = task.markTerminal();
        if (!beforeNative) {
            closeTaskHandle(task.handle());
            return;
        }
        final String diagnostic = failure == null && outcome != null
                ? "task ended " + outcome.status()
                : failure == null ? "task ended without an outcome" : failure.getMessage();
        final boolean ordinaryFailure = outcome != null && outcome.status() == TaskOutcomeStatus.FAILED;
        failTaskBeforeNative(session, task, diagnostic, !ordinaryFailure);
        closeTaskHandle(task.handle());
    }

    private void failTaskBeforeNative(
            final Session session, final RevisionTask task, final String diagnostic, final boolean pause) {
        final PsdFileRevision pending;
        if (task.nativeDispatched()) {
            return;
        }
        synchronized (session) {
            if (!session.isLive() || session.inFlightTask != task || !session.isInFlight(task.revision())) {
                return;
            }
            session.inFlightRevision = null;
            session.inFlightTask = null;
            session.transitionVersion++;
            pending = pause ? null : session.pendingRevision;
            session.pendingRevision = null;
            if (pause) {
                session.state = State.PAUSED;
            } else if (pending != null && session.acceptsSaves()) {
                session.inFlightRevision = pending;
                session.inFlightTask = new RevisionTask(pending);
            }
        }
        if (pause) {
            notifyStatus(
                    "external-psd-edit.status.paused-reason",
                    "ERROR",
                    format(
                            "external-psd-edit.status.paused-reason",
                            session.rawImageId().value(),
                            diagnostic));
        } else {
            notifyStatus(
                    "external-psd-edit.error.replace-failed",
                    "ERROR",
                    format(
                            "external-psd-edit.error.replace-failed",
                            session.rawImageId().value() + " (" + diagnostic + ")"));
        }
        if (pending != null) {
            submitRevision(session, pending);
        }
    }

    private void onReplaceComplete(
            final Session session,
            final PsdFileRevision revision,
            final RawImageId requestedRaw,
            final PsdReplaceResult result,
            final Throwable failure) {
        synchronized (session) {
            if (!session.isLive() || !session.isInFlight(revision)) {
                return;
            }
        }
        if (failure != null || result == null) {
            finishFailedReplacement(session, revision, failure == null ? "no result" : failure.getMessage(), true);
            return;
        }
        switch (result.status()) {
            case APPLIED -> finishAppliedReplacement(session, revision, requestedRaw, result);
            case PARTIAL_FAILURE -> finishFailedReplacement(session, revision, result.diagnostic(), true);
            case FAILED -> finishFailedReplacement(session, revision, result.diagnostic(), false);
            case STALE_TARGET, REJECTED, UNAVAILABLE ->
                invalidate(session, "replace " + result.status() + ": " + result.diagnostic());
        }
    }

    private void finishAppliedReplacement(
            final Session session,
            final PsdFileRevision revision,
            final RawImageId requestedRaw,
            final PsdReplaceResult result) {
        if (!requestedRaw.equals(result.before())) {
            pauseAfterUnverifiedReplacement(
                    session, revision, "native before identity changed from " + requestedRaw.value());
            return;
        }
        if (result.consumedRevision().isEmpty()
                || !Objects.equals(result.consumedRevision().orElseThrow(), revision)) {
            pauseAfterUnverifiedReplacement(
                    session, revision, "native consumed revision does not match the in-flight revision");
            return;
        }
        final TargetResolution resolution = resolveTarget(session);
        if (resolution.target().isEmpty()) {
            invalidate(session, "post-replace relation unavailable: " + resolution.reason());
            return;
        }
        final ResolvedTarget target = resolution.target().orElseThrow();
        final RawImageId observedAfter = result.after().orElse(null);
        if (observedAfter == null
                || !observedAfter.equals(target.rawImageId())
                || target.relations().rawImage(observedAfter).isEmpty()
                || (result.relations().isPresent()
                        && !suppliedRelationsMatch(session, result.relations().orElseThrow(), observedAfter))) {
            pauseAfterUnverifiedReplacement(
                    session, revision, "native after does not match the fresh model-image relation");
            return;
        }

        final PsdFileRevision pending;
        synchronized (session) {
            if (!session.isLive() || !session.isInFlight(revision)) {
                return;
            }
            session.currentRawImageId = target.rawImageId();
            session.observedRelationRevision = target.relations().revision();
            session.lastConsumedRevision = revision;
            session.inFlightRevision = null;
            session.inFlightTask = null;
            session.transitionVersion++;
            pending = session.pendingRevision;
            session.pendingRevision = null;
            if (pending != null && session.acceptsSaves()) {
                session.inFlightRevision = pending;
                session.inFlightTask = new RevisionTask(pending);
            }
        }
        notifyStatus(
                "external-psd-edit.status.applied",
                "INFO",
                format("external-psd-edit.status.applied", target.rawImageId().value()));
        if (pending != null) {
            synchronized (session) {
                if (!session.isInFlight(pending)) {
                    return;
                }
            }
            submitRevision(session, pending);
        }
    }

    private void finishFailedReplacement(
            final Session session, final PsdFileRevision revision, final String diagnostic, final boolean pause) {
        final PsdFileRevision pending;
        synchronized (session) {
            if (!session.isLive() || !session.isInFlight(revision)) {
                return;
            }
            session.inFlightRevision = null;
            session.inFlightTask = null;
            session.transitionVersion++;
            pending = pause ? null : session.pendingRevision;
            session.pendingRevision = null;
            if (pause) {
                session.state = State.PAUSED;
            } else if (pending != null && session.acceptsSaves()) {
                session.inFlightRevision = pending;
                session.inFlightTask = new RevisionTask(pending);
            }
        }
        if (pause) {
            notifyStatus(
                    "external-psd-edit.status.paused-partial",
                    "ERROR",
                    format(
                            "external-psd-edit.status.paused-partial",
                            session.rawImageId().value(),
                            diagnostic));
        } else {
            notifyStatus(
                    "external-psd-edit.error.replace-failed",
                    "ERROR",
                    format(
                            "external-psd-edit.error.replace-failed",
                            session.rawImageId().value() + " (" + diagnostic + ")"));
        }
        if (pending != null) {
            submitRevision(session, pending);
        }
    }

    private void pauseAfterUnverifiedReplacement(
            final Session session, final PsdFileRevision revision, final String reason) {
        synchronized (session) {
            if (!session.isLive() || !session.isInFlight(revision)) {
                return;
            }
            session.inFlightRevision = null;
            session.inFlightTask = null;
            session.pendingRevision = null;
            session.transitionVersion++;
            session.state = State.PAUSED;
        }
        notifyStatus(
                "external-psd-edit.status.paused-partial",
                "ERROR",
                format(
                        "external-psd-edit.status.paused-partial",
                        session.rawImageId().value(),
                        reason));
    }

    private boolean suppliedRelationsMatch(
            final Session session, final TextureRelationsSnapshot relations, final RawImageId observedAfter) {
        if (!relations.isAvailable() || !session.matchesRelationContext(relations.binding(), relations.generation())) {
            return false;
        }
        if (relations.rawImages().stream()
                        .filter(raw -> raw.id().equals(observedAfter))
                        .count()
                != 1) return false;
        for (final ModelImageId id : session.key.modelImageIds()) {
            final List<ModelImageRelation> matches = relations.modelImages().stream()
                    .filter(image -> image.id().equals(id))
                    .toList();
            if (matches.size() != 1
                    || !matches.get(0)
                            .currentRawImageId()
                            .filter(observedAfter::equals)
                            .isPresent()) {
                return false;
            }
        }
        return !session.key.modelImageIds().isEmpty();
    }

    /** Stops and removes a session whose binding or target is no longer trustworthy. */
    private void invalidate(final Session session, final String reason) {
        final boolean removed;
        synchronized (sessions) {
            removed = sessions.remove(session.key, session);
        }
        session.stop();
        if (removed) {
            context.logger()
                    .info("ExternalPsdEditPlugin session invalidated for "
                            + session.rawImageId().value() + ": " + reason);
            notifyStatus(
                    "external-psd-edit.warn.session-invalidated",
                    "WARNING",
                    format(
                            "external-psd-edit.warn.session-invalidated",
                            session.rawImageId().value()));
        }
        stopRefreshIfNoLiveSessions();
    }

    private void invalidateIfAtTransition(final Session session, final long expectedVersion, final String reason) {
        synchronized (session) {
            if (!session.isLive() || session.transitionVersion != expectedVersion || session.inFlightRevision != null) {
                return;
            }
            session.transitionVersion++;
        }
        invalidate(session, reason);
    }

    private void failSession(final Session session, final String message) {
        synchronized (sessions) {
            sessions.remove(session.key, session);
        }
        session.stop();
        notifyStatus("external-psd-edit.error.session-failed", "ERROR", message);
        stopRefreshIfNoLiveSessions();
    }

    private void pauseForRelationRefresh(final Session session, final long expectedVersion, final String reason) {
        final boolean paused;
        synchronized (session) {
            paused = session.isLive()
                    && session.transitionVersion == expectedVersion
                    && session.inFlightRevision == null
                    && session.state != State.PAUSED;
            if (paused) {
                session.pendingRevision = null;
                session.transitionVersion++;
                session.state = State.PAUSED;
            }
        }
        if (paused) {
            notifyStatus(
                    "external-psd-edit.status.paused-reason",
                    "ERROR",
                    format(
                            "external-psd-edit.status.paused-reason",
                            session.rawImageId().value(),
                            reason));
        }
    }

    private boolean confirmMultiple(final int count) {
        try {
            return context.uiHost()
                    .confirmDialog(new DialogRequest(
                            "external-psd-edit.confirm.open-multiple",
                            text("external-psd-edit.confirm.open-multiple.title"),
                            format("external-psd-edit.confirm.open-multiple.body", count)));
        } catch (RuntimeException unavailable) {
            notifyStatus(
                    "external-psd-edit.error.confirmation-unavailable",
                    "ERROR",
                    text("external-psd-edit.error.confirmation-unavailable"));
            return false;
        }
    }

    private TextureSourceQuery sourceQuery(final Set<ArtMeshId> selected) {
        final Set<ModelImageId> anchors = new LinkedHashSet<>();
        synchronized (sessions) {
            for (final Session session : sessions.values()) {
                if (session.isLive()) anchors.addAll(session.key.modelImageIds());
            }
        }
        return new TextureSourceQuery(selected, anchors);
    }

    private TargetResolution resolveTarget(final Session session) {
        try {
            final CubismModel model = context.cubism().model().active();
            final TextureSourcesSnapshot relations = model.textures().sources(sourceQuery(Set.of()));
            if (!relations.isAvailable()) {
                return TargetResolution.unavailable("relations unavailable");
            }
            if (!session.matchesContext(relations.binding(), model.id(), relations.generation())) {
                return TargetResolution.unavailable("document, model, or generation changed");
            }
            final Optional<RawImageId> raw = uniqueRawForModelImages(relations, session.key.modelImageIds());
            if (raw.isEmpty()) {
                return TargetResolution.unavailable("model-image relation is missing or ambiguous");
            }
            if (hasLiveSessionCollision(session, relations, raw.orElseThrow())) {
                return TargetResolution.unavailable("multiple live sessions resolve the same current raw image");
            }
            return TargetResolution.available(new ResolvedTarget(model, relations, raw.orElseThrow()));
        } catch (RuntimeException unavailable) {
            return TargetResolution.unavailable("model unavailable");
        }
    }

    private boolean hasLiveSessionCollision(
            final Session session, final TextureSourcesSnapshot relations, final RawImageId raw) {
        synchronized (sessions) {
            for (final Session other : sessions.values()) {
                if (other == session
                        || !other.isLive()
                        || !other.matchesRelationContext(relations.binding(), relations.generation())) {
                    continue;
                }
                if (uniqueRawForModelImages(relations, other.key.modelImageIds())
                        .filter(raw::equals)
                        .isPresent()) {
                    return true;
                }
            }
        }
        return false;
    }

    private Optional<TargetSeed> targetSeed(
            final TextureSourcesSnapshot relations, final ModelId modelId, final RawImageId raw) {
        return sessionKeyForRaw(relations, modelId, raw).map(key -> new TargetSeed(key, raw));
    }

    private Optional<SessionKey> sessionKeyForRaw(
            final TextureSourcesSnapshot relations, final ModelId modelId, final RawImageId raw) {
        if (relations.rawImages().stream()
                        .filter(value -> value.id().equals(raw))
                        .count()
                != 1) {
            return Optional.empty();
        }
        final List<ModelImageId> imageIds = relations.modelImages().stream()
                .filter(value -> value.currentRawImageId().filter(raw::equals).isPresent())
                .map(ModelImageSource::id)
                .toList();
        if (imageIds.isEmpty() || Set.copyOf(imageIds).size() != imageIds.size()) {
            return Optional.empty();
        }
        return Optional.of(new SessionKey(relations.binding(), modelId, relations.generation(), Set.copyOf(imageIds)));
    }

    private Optional<RawImageId> uniqueRawForModelImages(
            final TextureSourcesSnapshot relations, final Set<ModelImageId> imageIds) {
        if (imageIds.isEmpty()) {
            return Optional.empty();
        }
        final Set<RawImageId> candidates = new LinkedHashSet<>();
        for (final ModelImageId imageId : imageIds) {
            final List<ModelImageSource> matches = relations.modelImages().stream()
                    .filter(value -> value.id().equals(imageId))
                    .toList();
            if (matches.size() != 1) {
                return Optional.empty();
            }
            final Optional<RawImageId> current = matches.get(0).currentRawImageId();
            if (current.isEmpty()) {
                return Optional.empty();
            }
            candidates.add(current.orElseThrow());
        }
        if (candidates.size() != 1) {
            return Optional.empty();
        }
        final RawImageId raw = candidates.iterator().next();
        return relations.rawImages().stream()
                                .filter(value -> value.id().equals(raw))
                                .count()
                        == 1
                ? Optional.of(raw)
                : Optional.empty();
    }

    private Optional<RawImageId> currentRawImage(final TextureSourcesSnapshot relations, final ArtMeshId artMesh) {
        ArtMeshTextureInputs matched = null;
        for (final ArtMeshTextureInputs inputs : relations.artMeshInputs()) {
            if (!inputs.id().equals(artMesh)) {
                continue;
            }
            if (matched != null) {
                return Optional.empty();
            }
            matched = inputs;
        }
        if (matched == null) {
            return Optional.empty();
        }
        final OptionalInt index = matched.currentInputIndex();
        if (index.isEmpty()
                || index.getAsInt() < 0
                || index.getAsInt() >= matched.inputs().size()) {
            return Optional.empty();
        }
        final TextureInputBinding input = matched.inputs().get(index.getAsInt());
        if (!input.isResolved()) {
            return Optional.empty();
        }
        if (input.kind() == TextureInputBinding.Kind.MODEL_IMAGE) {
            return rawForModelImageInput(relations, input);
        }
        if (input.kind() != TextureInputBinding.Kind.ATLAS) {
            return Optional.empty();
        }
        // Atlas packing changes the rendering input, not necessarily the retained source.
        // Follow only explicit model-image links; never choose the first candidate or a name.
        RawImageId source = null;
        for (final TextureInputBinding candidate : matched.inputs()) {
            if (!candidate.isResolved()) {
                return Optional.empty();
            }
            if (candidate.kind() != TextureInputBinding.Kind.MODEL_IMAGE) {
                continue;
            }
            final Optional<RawImageId> raw = rawForModelImageInput(relations, candidate);
            if (raw.isEmpty() || (source != null && !source.equals(raw.orElseThrow()))) {
                return Optional.empty();
            }
            source = raw.orElseThrow();
        }
        return Optional.ofNullable(source);
    }

    private Optional<RawImageId> rawForModelImageInput(
            final TextureSourcesSnapshot relations, final TextureInputBinding input) {
        if (!input.isResolved()
                || input.kind() != TextureInputBinding.Kind.MODEL_IMAGE
                || input.modelImageId().isEmpty()) {
            return Optional.empty();
        }
        final ModelImageId modelImageId = input.modelImageId().orElseThrow();
        final List<ModelImageSource> imageMatches = relations.modelImages().stream()
                .filter(value -> value.id().equals(modelImageId))
                .toList();
        if (imageMatches.size() != 1) {
            return Optional.empty();
        }
        return imageMatches.get(0).currentRawImageId();
    }

    private static void stopFileQuietly(final PsdEditFile file) {
        if (file == null) {
            return;
        }
        try {
            file.stop();
        } catch (RuntimeException ignored) {
            // best effort: the session is already gone
        }
    }

    private static void closeRegistrationQuietly(final Registration registration) {
        if (registration == null) {
            return;
        }
        try {
            registration.close();
        } catch (RuntimeException ignored) {
            // A failed startup path must not retain a save callback.
        }
    }

    private static void closeTaskHandle(final TaskHandle handle) {
        if (handle == null) {
            return;
        }
        try {
            handle.close();
        } catch (RuntimeException ignored) {
            // A terminal task handle is already outside the plugin's work boundary.
        }
    }

    private void notifyStatus(final String id, final String severity, final String message) {
        try {
            context.uiHost().notifyStatus(new StatusNotification(id, severity, message));
        } catch (RuntimeException ignored) {
            context.logger().warn("status notification unavailable: " + message);
        }
    }

    private String text(final String key) {
        return localization.text(key);
    }

    private String format(final String key, final Object... arguments) {
        return localization.format(key, arguments);
    }

    private enum State {
        EXPORTING,
        OPENING,
        ACTIVE,
        PAUSED,
        STOPPED
    }

    private static final class RevisionTask {
        private final PsdFileRevision revision;
        private TaskHandle handle;
        private boolean nativeDispatched;
        private boolean terminal;

        private RevisionTask(final PsdFileRevision revision) {
            this.revision = Objects.requireNonNull(revision, "revision");
        }

        private synchronized boolean beginNativeDispatch() {
            if (terminal) {
                return false;
            }
            nativeDispatched = true;
            return true;
        }

        private synchronized boolean markTerminal() {
            if (terminal) {
                return false;
            }
            terminal = true;
            return !nativeDispatched;
        }

        private synchronized boolean attachHandle(final TaskHandle value) {
            handle = Objects.requireNonNull(value, "handle");
            return terminal;
        }

        private synchronized boolean nativeDispatched() {
            return nativeDispatched;
        }

        private PsdFileRevision revision() {
            return revision;
        }

        private synchronized TaskHandle handle() {
            return handle;
        }
    }

    private record SessionKey(String binding, ModelId modelId, long generation, Set<ModelImageId> modelImageIds) {
        private SessionKey {
            binding = Objects.requireNonNull(binding, "binding");
            modelId = Objects.requireNonNull(modelId, "modelId");
            modelImageIds = Set.copyOf(Objects.requireNonNull(modelImageIds, "modelImageIds"));
            if (modelImageIds.isEmpty()) {
                throw new IllegalArgumentException("modelImageIds must not be empty");
            }
        }
    }

    private record TargetSeed(SessionKey key, RawImageId rawImageId) {
        private TargetSeed {
            key = Objects.requireNonNull(key, "key");
            rawImageId = Objects.requireNonNull(rawImageId, "rawImageId");
        }
    }

    private record ResolvedTarget(CubismModel model, TextureSourcesSnapshot relations, RawImageId rawImageId) {}

    private record TargetResolution(Optional<ResolvedTarget> target, String reason) {
        private TargetResolution {
            target = Objects.requireNonNull(target, "target");
            reason = Objects.requireNonNull(reason, "reason");
        }

        private static TargetResolution available(final ResolvedTarget target) {
            return new TargetResolution(Optional.of(target), "");
        }

        private static TargetResolution unavailable(final String reason) {
            return new TargetResolution(Optional.empty(), reason);
        }
    }

    private static final class Session {
        private final SessionKey key;
        private RawImageId currentRawImageId;
        private volatile State state = State.EXPORTING;
        private volatile PsdEditFile file;
        private volatile Registration saveRegistration;
        private PsdFileRevision inFlightRevision;
        private RevisionTask inFlightTask;
        private PsdFileRevision pendingRevision;
        private PsdFileRevision lastConsumedRevision;
        private long observedRelationRevision = -1L;
        private long transitionVersion;

        private Session(final TargetSeed target) {
            this.key = target.key();
            this.currentRawImageId = target.rawImageId();
        }

        private synchronized boolean isLive() {
            return state != State.STOPPED;
        }

        private synchronized boolean isReopenable() {
            return (state == State.ACTIVE || state == State.PAUSED) && file != null;
        }

        private synchronized boolean acceptsSaves() {
            return (state == State.ACTIVE || state == State.OPENING) && file != null;
        }

        private synchronized boolean isRefreshCandidate() {
            return state == State.ACTIVE;
        }

        private synchronized boolean hasNativeInFlight() {
            return inFlightRevision != null;
        }

        private synchronized long transitionVersion() {
            return transitionVersion;
        }

        private synchronized boolean isAtTransition(final long expectedVersion) {
            return transitionVersion == expectedVersion;
        }

        private synchronized boolean recordObservedRaw(
                final RawImageId raw, final long relationRevision, final long expectedVersion) {
            if (state == State.STOPPED
                    || state == State.PAUSED
                    || inFlightRevision != null
                    || transitionVersion != expectedVersion) {
                return false;
            }
            currentRawImageId = Objects.requireNonNull(raw, "raw");
            observedRelationRevision = relationRevision;
            transitionVersion++;
            return true;
        }

        private synchronized boolean isInFlight(final PsdFileRevision revision) {
            return Objects.equals(inFlightRevision, revision);
        }

        private synchronized boolean isConsumed(final PsdFileRevision revision) {
            return Objects.equals(lastConsumedRevision, revision);
        }

        private synchronized RawImageId rawImageId() {
            return currentRawImageId;
        }

        private boolean matchesContext(final String binding, final ModelId modelId, final long generation) {
            return key.binding().equals(binding) && key.modelId().equals(modelId) && key.generation() == generation;
        }

        private boolean matchesRelationContext(final String binding, final long generation) {
            return key.binding().equals(binding) && key.generation() == generation;
        }

        private synchronized PsdEditFile reopenableFile() {
            return isReopenable() ? file : null;
        }

        private void stop() {
            final RevisionTask task;
            final Registration registration;
            final PsdEditFile editFile;
            synchronized (this) {
                if (state == State.STOPPED) {
                    return;
                }
                state = State.STOPPED;
                transitionVersion++;
                inFlightRevision = null;
                task = inFlightTask;
                inFlightTask = null;
                pendingRevision = null;
                registration = saveRegistration;
                saveRegistration = null;
                editFile = file;
                file = null;
            }
            closeRegistrationQuietly(registration);
            stopFileQuietly(editFile);
            if (task != null) {
                closeTaskHandle(task.handle());
            }
        }
    }
}
