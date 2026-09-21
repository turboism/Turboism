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
import dev.turboism.sdk.cubism.psd.PsdEditFile;
import dev.turboism.sdk.cubism.psd.PsdExportResult;
import dev.turboism.sdk.cubism.psd.PsdFileOperationResult;
import dev.turboism.sdk.cubism.psd.PsdFileRevision;
import dev.turboism.sdk.cubism.psd.PsdReplaceResult;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.task.PluginTaskKind;
import dev.turboism.sdk.task.PluginTaskPriority;
import dev.turboism.sdk.task.PluginTaskRequest;
import dev.turboism.sdk.task.TaskId;
import dev.turboism.sdk.ui.DialogRequest;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.context.ContextMenuSelection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

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
    private volatile boolean stopped;

    ExternalPsdEditSessionManager(
        final PluginContext context,
        final PluginLocalization localization
    ) {
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
            notifyStatus("external-psd-edit.error.no-selection", "ERROR",
                text("external-psd-edit.error.no-selection"));
            return;
        }
        final LinkedHashSet<ArtMeshId> artMeshes = new LinkedHashSet<>();
        final List<String> skipped = new ArrayList<>();
        for (final ContextMenuSelection.Item item : selection.items()) {
            if (item.kind() == ContextMenuRegistry.ObjectKind.ART_MESH && !item.id().isBlank()) {
                artMeshes.add(new ArtMeshId(item.id()));
            } else {
                skipped.add(item.kind() + ":" + item.id());
            }
        }
        if (artMeshes.isEmpty()) {
            notifyStatus("external-psd-edit.error.unsupported", "ERROR",
                text("external-psd-edit.error.unsupported"));
            return;
        }

        final CubismModel model;
        final TextureRelationsSnapshot relations;
        final ModelId modelId;
        try {
            model = context.cubism().model().active();
            relations = model.textures().relations();
            modelId = model.id();
        } catch (RuntimeException unavailable) {
            notifyStatus("external-psd-edit.error.model-unavailable", "ERROR",
                text("external-psd-edit.error.model-unavailable"));
            return;
        }
        if (!relations.isAvailable()) {
            notifyStatus("external-psd-edit.error.relations-unavailable", "ERROR",
                text("external-psd-edit.error.relations-unavailable"));
            return;
        }
        if (!selection.documentId().equals(relations.binding())) {
            notifyStatus("external-psd-edit.error.document-changed", "ERROR",
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
                final Optional<TargetSeed> target = targetSeed(
                    relations, modelId, raw.orElseThrow());
                if (target.isEmpty()) {
                    unresolved.add(artMesh.value());
                    continue;
                }
                final TargetSeed resolvedTarget = target.orElseThrow();
                targets.putIfAbsent(resolvedTarget.rawImageId(), resolvedTarget);
                final RawImageId priorRaw = targetAnchors.putIfAbsent(
                    resolvedTarget.key(), resolvedTarget.rawImageId());
                if (priorRaw != null && !priorRaw.equals(resolvedTarget.rawImageId())) {
                    targetCollision = true;
                }
            } else {
                unresolved.add(artMesh.value());
            }
        }
        if (!unresolved.isEmpty()) {
            notifyStatus("external-psd-edit.warn.unresolved", "WARNING", format(
                "external-psd-edit.warn.unresolved",
                unresolved.size(),
                String.join(", ", unresolved)
            ));
        }
        if (targets.isEmpty() || targetCollision) {
            notifyStatus("external-psd-edit.error.unresolved", "ERROR",
                text("external-psd-edit.error.unresolved"));
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
                final Optional<RawImageId> current = uniqueRawForModelImages(
                    relations, existing.key.modelImageIds());
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
            notifyStatus("external-psd-edit.error.unresolved", "ERROR",
                text("external-psd-edit.error.unresolved"));
            return;
        }
        if (reopenSessions.isEmpty() && fresh.isEmpty()) {
            notifyStatus("external-psd-edit.status.already-open", "INFO",
                text("external-psd-edit.status.already-open"));
            return;
        }
        final int openCount = reopenSessions.size() + fresh.size();
        if (openCount > 1 && !confirmMultiple(openCount)) {
            return;
        }
        for (final Session inFlight : inFlightSessions) {
            notifyStatus("external-psd-edit.status.already-open", "INFO",
                text("external-psd-edit.status.already-open"));
        }
        for (final Session existing : reopenSessions) {
            notifyStatus("external-psd-edit.status.already-open", "INFO",
                text("external-psd-edit.status.already-open"));
            reopenSession(existing);
        }
        for (final TargetSeed target : fresh) {
            try {
                beginSession(model.textures(), target);
            } catch (RuntimeException exportRefused) {
                notifyStatus("external-psd-edit.error.session-failed", "ERROR", format(
                    "external-psd-edit.error.export-failed", target.rawImageId().value()));
            }
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
            final TextureRelationsSnapshot relations = model.textures().relations();
            if (relations.isAvailable()) {
                currentBinding = relations.binding();
                currentModel = model.id();
                currentGeneration = relations.generation();
            }
        } catch (RuntimeException unavailable) {
            // No live binding to compare against: every session is suspect.
        }
        for (final Session session : snapshot) {
            if (currentBinding == null || currentModel == null
                || !session.matchesContext(currentBinding, currentModel, currentGeneration)) {
                invalidate(session, "model closed");
            }
        }
    }

    /** Stops every session: unsubscribes saves and stops each file without deleting it. */
    void stopAll(final String reason) {
        final List<Session> snapshot;
        synchronized (sessions) {
            stopped = true;
            snapshot = new ArrayList<>(sessions.values());
            sessions.clear();
        }
        for (final Session session : snapshot) {
            session.stop();
        }
        if (!snapshot.isEmpty()) {
            context.logger().info("ExternalPsdEditPlugin stopped " + snapshot.size()
                + " session(s): " + reason);
        }
    }

    int liveSessionCount() {
        synchronized (sessions) {
            return (int) sessions.values().stream().filter(Session::isLive).count();
        }
    }

    private void beginSession(
        final ModelTextures textures,
        final TargetSeed target
    ) {
        final Session session = new Session(target);
        synchronized (sessions) {
            if (stopped) {
                return;
            }
            sessions.put(target.key(), session);
        }
        textures.exportRawImagePsd(target.rawImageId()).whenComplete((result, failure) ->
            onExportComplete(session, result, failure));
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
            file.openInDefaultApplication().whenComplete((result, failure) ->
                onReopenComplete(session, result, failure));
        } catch (RuntimeException failure) {
            onReopenComplete(session, null, failure);
        }
    }

    private void onReopenComplete(
        final Session session,
        final PsdFileOperationResult result,
        final Throwable failure
    ) {
        synchronized (sessions) {
            if (stopped || sessions.get(session.key) != session) {
                return;
            }
        }
        if (failure != null || result == null
            || result.status() != PsdFileOperationResult.Status.OPENED) {
            notifyStatus("external-psd-edit.error.open-failed", "ERROR", format(
                "external-psd-edit.error.open-failed", session.rawImageId().value()));
        }
    }

    private void onExportComplete(
        final Session session,
        final PsdExportResult result,
        final Throwable failure
    ) {
        if (failure != null) {
            failSession(session, format(
                "external-psd-edit.error.export-failed", session.rawImageId().value()));
            return;
        }
        if (result == null || result.status() != PsdExportResult.Status.EXPORTED) {
            failSession(session, format(
                "external-psd-edit.error.export-failed",
                    session.rawImageId().value()
                    + (result == null ? "" : " (" + result.status() + ")")));
            return;
        }
        synchronized (session) {
            if (!session.isLive()) {
                stopFileQuietly(result.file().orElse(null));
                return;
            }
            session.file = result.file().orElseThrow();
            try {
                session.saveRegistration =
                    session.file.observeSaves(revision -> onSave(session, revision));
            } catch (RuntimeException subscribeFailure) {
                failSession(session, format(
                    "external-psd-edit.error.export-failed",
                    session.rawImageId().value() + " (subscribe failed)"));
                return;
            }
            session.state = State.OPENING;
        }
        session.file.openInDefaultApplication().whenComplete((openResult, openFailure) ->
            onOpenComplete(session, openResult, openFailure));
    }

    private void onOpenComplete(
        final Session session,
        final PsdFileOperationResult result,
        final Throwable failure
    ) {
        synchronized (session) {
            if (!session.isLive()) {
                return;
            }
        }
        if (failure != null || result == null
            || result.status() != PsdFileOperationResult.Status.OPENED) {
            failSession(session, format(
                "external-psd-edit.error.open-failed", session.rawImageId().value()));
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
        notifyStatus("external-psd-edit.status.editing", "INFO",
            format("external-psd-edit.status.editing", session.rawImageId().value()));
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
            dispatch = true;
        }
        if (dispatch) {
            submitRevision(session, revision);
        }
    }

    private void submitRevision(final Session session, final PsdFileRevision revision) {
        submit("external-psd-edit.import." + taskSequence.incrementAndGet(), () ->
            importSave(session, revision));
    }

    private void importSave(final Session session, final PsdFileRevision revision) {
        synchronized (session) {
            if (!session.acceptsSaves() || !session.isInFlight(revision)) {
                return;
            }
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
        try {
            target.model().textures().replaceRawImagePsd(target.rawImageId(), file, revision)
                .whenComplete((result, failure) -> onReplaceComplete(
                    session, revision, target.rawImageId(), result, failure));
        } catch (RuntimeException failure) {
            onReplaceComplete(session, revision, target.rawImageId(), null, failure);
        }
    }

    private void onReplaceComplete(
        final Session session,
        final PsdFileRevision revision,
        final RawImageId requestedRaw,
        final PsdReplaceResult result,
        final Throwable failure
    ) {
        synchronized (session) {
            if (!session.isLive() || !session.isInFlight(revision)) {
                return;
            }
        }
        if (failure != null || result == null) {
            finishFailedReplacement(session, revision,
                failure == null ? "no result" : failure.getMessage(), true);
            return;
        }
        switch (result.status()) {
            case APPLIED -> finishAppliedReplacement(session, revision, requestedRaw, result);
            case PARTIAL_FAILURE -> finishFailedReplacement(
                session, revision, result.diagnostic(), true);
            case FAILED -> finishFailedReplacement(
                session, revision, result.diagnostic(), false);
            case STALE_TARGET, REJECTED, UNAVAILABLE ->
                invalidate(session, "replace " + result.status() + ": " + result.diagnostic());
        }
    }

    private void finishAppliedReplacement(
        final Session session,
        final PsdFileRevision revision,
        final RawImageId requestedRaw,
        final PsdReplaceResult result
    ) {
        if (!requestedRaw.equals(result.before())) {
            pauseAfterUnverifiedReplacement(session, revision,
                "native before identity changed from " + requestedRaw.value());
            return;
        }
        if (result.consumedRevision().isEmpty()
            || !Objects.equals(result.consumedRevision().orElseThrow(), revision)) {
            pauseAfterUnverifiedReplacement(session, revision,
                "native consumed revision does not match the in-flight revision");
            return;
        }
        final TargetResolution resolution = resolveTarget(session);
        if (resolution.target().isEmpty()) {
            invalidate(session, "post-replace relation unavailable: " + resolution.reason());
            return;
        }
        final ResolvedTarget target = resolution.target().orElseThrow();
        final RawImageId observedAfter = result.after().orElse(null);
        if (observedAfter == null || !observedAfter.equals(target.rawImageId())
            || target.relations().rawImage(observedAfter).isEmpty()
            || (result.relations().isPresent()
                && !suppliedRelationsMatch(session, result.relations().orElseThrow(), observedAfter))) {
            pauseAfterUnverifiedReplacement(session, revision,
                "native after does not match the fresh model-image relation");
            return;
        }

        final PsdFileRevision pending;
        synchronized (session) {
            if (!session.isLive() || !session.isInFlight(revision)) {
                return;
            }
            session.currentRawImageId = target.rawImageId();
            session.lastConsumedRevision = revision;
            session.inFlightRevision = null;
            pending = session.pendingRevision;
            session.pendingRevision = null;
            if (pending != null && session.acceptsSaves()) {
                session.inFlightRevision = pending;
            }
        }
        notifyStatus("external-psd-edit.status.applied", "INFO",
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
        final Session session,
        final PsdFileRevision revision,
        final String diagnostic,
        final boolean pause
    ) {
        final PsdFileRevision pending;
        synchronized (session) {
            if (!session.isLive() || !session.isInFlight(revision)) {
                return;
            }
            session.inFlightRevision = null;
            pending = pause ? null : session.pendingRevision;
            session.pendingRevision = null;
            if (pause) {
                session.state = State.PAUSED;
            } else if (pending != null && session.acceptsSaves()) {
                session.inFlightRevision = pending;
            }
        }
        if (pause) {
            notifyStatus("external-psd-edit.status.paused-partial", "ERROR", format(
                "external-psd-edit.status.paused-partial",
                session.rawImageId().value(), diagnostic));
        } else {
            notifyStatus("external-psd-edit.error.replace-failed", "ERROR", format(
                "external-psd-edit.error.replace-failed",
                session.rawImageId().value() + " (" + diagnostic + ")"));
        }
        if (pending != null) {
            submitRevision(session, pending);
        }
    }

    private void pauseAfterUnverifiedReplacement(
        final Session session,
        final PsdFileRevision revision,
        final String reason
    ) {
        synchronized (session) {
            if (!session.isLive() || !session.isInFlight(revision)) {
                return;
            }
            session.inFlightRevision = null;
            session.pendingRevision = null;
            session.state = State.PAUSED;
        }
        notifyStatus("external-psd-edit.status.paused-partial", "ERROR", format(
            "external-psd-edit.status.paused-partial",
            session.rawImageId().value(), reason));
    }

    private boolean suppliedRelationsMatch(
        final Session session,
        final TextureRelationsSnapshot relations,
        final RawImageId observedAfter
    ) {
        if (!relations.isAvailable() || !session.matchesRelationContext(
            relations.binding(), relations.generation())) {
            return false;
        }
        return uniqueRawForModelImages(relations, session.key.modelImageIds())
            .map(observedAfter::equals)
            .orElse(false);
    }

    /** Stops and removes a session whose binding or target is no longer trustworthy. */
    private void invalidate(final Session session, final String reason) {
        final boolean removed;
        synchronized (sessions) {
            removed = sessions.remove(session.key, session);
        }
        session.stop();
        if (removed) {
            context.logger().info("ExternalPsdEditPlugin session invalidated for "
                + session.rawImageId().value() + ": " + reason);
            notifyStatus("external-psd-edit.warn.session-invalidated", "WARNING", format(
                "external-psd-edit.warn.session-invalidated", session.rawImageId().value()));
        }
    }

    private void failSession(final Session session, final String message) {
        synchronized (sessions) {
            sessions.remove(session.key, session);
        }
        session.stop();
        notifyStatus("external-psd-edit.error.session-failed", "ERROR", message);
    }

    private boolean confirmMultiple(final int count) {
        try {
            return context.uiHost().confirmDialog(new DialogRequest(
                "external-psd-edit.confirm.open-multiple",
                text("external-psd-edit.confirm.open-multiple.title"),
                format("external-psd-edit.confirm.open-multiple.body", count)
            ));
        } catch (RuntimeException unavailable) {
            notifyStatus("external-psd-edit.error.confirmation-unavailable", "ERROR",
                text("external-psd-edit.error.confirmation-unavailable"));
            return false;
        }
    }

    private void submit(final String id, final Runnable work) {
        try {
            context.tasks().submit(new PluginTaskRequest(
                new TaskId(id),
                PluginTaskKind.COMPUTE,
                PluginTaskPriority.NORMAL,
                cancellation -> work.run()
            ));
        } catch (RuntimeException schedulerUnavailable) {
            work.run();
        }
    }

    private TargetResolution resolveTarget(final Session session) {
        try {
            final CubismModel model = context.cubism().model().active();
            final TextureRelationsSnapshot relations = model.textures().relations();
            if (!relations.isAvailable()) {
                return TargetResolution.unavailable("relations unavailable");
            }
            if (!session.matchesContext(relations.binding(), model.id(), relations.generation())) {
                return TargetResolution.unavailable("document, model, or generation changed");
            }
            final Optional<RawImageId> raw = uniqueRawForModelImages(
                relations, session.key.modelImageIds());
            if (raw.isEmpty()) {
                return TargetResolution.unavailable("model-image relation is missing or ambiguous");
            }
            if (hasLiveSessionCollision(session, relations, raw.orElseThrow())) {
                return TargetResolution.unavailable(
                    "multiple live sessions resolve the same current raw image");
            }
            return TargetResolution.available(new ResolvedTarget(
                model, relations, raw.orElseThrow()));
        } catch (RuntimeException unavailable) {
            return TargetResolution.unavailable("model unavailable");
        }
    }

    private boolean hasLiveSessionCollision(
        final Session session,
        final TextureRelationsSnapshot relations,
        final RawImageId raw
    ) {
        synchronized (sessions) {
            for (final Session other : sessions.values()) {
                if (other == session || !other.isLive()
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
        final TextureRelationsSnapshot relations,
        final ModelId modelId,
        final RawImageId raw
    ) {
        return sessionKeyForRaw(relations, modelId, raw)
            .map(key -> new TargetSeed(key, raw));
    }

    private Optional<SessionKey> sessionKeyForRaw(
        final TextureRelationsSnapshot relations,
        final ModelId modelId,
        final RawImageId raw
    ) {
        if (relations.rawImages().stream().filter(value -> value.id().equals(raw)).count() != 1) {
            return Optional.empty();
        }
        final List<ModelImageId> imageIds = relations.modelImages().stream()
            .filter(value -> value.currentRawImageId().filter(raw::equals).isPresent())
            .map(ModelImageRelation::id)
            .toList();
        if (imageIds.isEmpty() || Set.copyOf(imageIds).size() != imageIds.size()) {
            return Optional.empty();
        }
        return Optional.of(new SessionKey(
            relations.binding(), modelId, relations.generation(), Set.copyOf(imageIds)));
    }

    private Optional<RawImageId> uniqueRawForModelImages(
        final TextureRelationsSnapshot relations,
        final Set<ModelImageId> imageIds
    ) {
        if (imageIds.isEmpty()) {
            return Optional.empty();
        }
        final Set<RawImageId> candidates = new LinkedHashSet<>();
        for (final ModelImageId imageId : imageIds) {
            final List<ModelImageRelation> matches = relations.modelImages().stream()
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
        return relations.rawImages().stream().filter(value -> value.id().equals(raw)).count() == 1
            ? Optional.of(raw)
            : Optional.empty();
    }

    private Optional<RawImageId> currentRawImage(
        final TextureRelationsSnapshot relations,
        final ArtMeshId artMesh
    ) {
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
        if (index.isEmpty() || index.getAsInt() < 0 || index.getAsInt() >= matched.inputs().size()) {
            return Optional.empty();
        }
        final TextureInputBinding input = matched.inputs().get(index.getAsInt());
        if (input.kind() != TextureInputBinding.Kind.MODEL_IMAGE
            || input.modelImageId().isEmpty()) {
            return Optional.empty();
        }
        final ModelImageId modelImageId = input.modelImageId().orElseThrow();
        final List<ModelImageRelation> imageMatches = relations.modelImages().stream()
            .filter(value -> value.id().equals(modelImageId))
            .toList();
        if (imageMatches.size() != 1) {
            return Optional.empty();
        }
        return imageMatches.get(0).currentRawImageId();
    }

    private void stopFileQuietly(final PsdEditFile file) {
        if (file == null) {
            return;
        }
        try {
            file.stop();
        } catch (RuntimeException ignored) {
            // best effort: the session is already gone
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

    private record SessionKey(
        String binding,
        ModelId modelId,
        long generation,
        Set<ModelImageId> modelImageIds
    ) {
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

    private record ResolvedTarget(
        CubismModel model,
        TextureRelationsSnapshot relations,
        RawImageId rawImageId
    ) {
    }

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
        private PsdFileRevision pendingRevision;
        private PsdFileRevision lastConsumedRevision;

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

        private synchronized boolean isInFlight(final PsdFileRevision revision) {
            return Objects.equals(inFlightRevision, revision);
        }

        private synchronized boolean isConsumed(final PsdFileRevision revision) {
            return Objects.equals(lastConsumedRevision, revision);
        }

        private synchronized RawImageId rawImageId() {
            return currentRawImageId;
        }

        private boolean matchesContext(
            final String binding,
            final ModelId modelId,
            final long generation
        ) {
            return key.binding().equals(binding)
                && key.modelId().equals(modelId)
                && key.generation() == generation;
        }

        private boolean matchesRelationContext(final String binding, final long generation) {
            return key.binding().equals(binding) && key.generation() == generation;
        }

        private synchronized PsdEditFile reopenableFile() {
            return isReopenable() ? file : null;
        }

        private synchronized void stop() {
            if (state == State.STOPPED) {
                return;
            }
            state = State.STOPPED;
            inFlightRevision = null;
            pendingRevision = null;
            final Registration registration = saveRegistration;
            saveRegistration = null;
            if (registration != null) {
                try {
                    registration.close();
                } catch (RuntimeException ignored) {
                    // best effort: the handle stop below still runs
                }
            }
            final PsdEditFile editFile = file;
            file = null;
            if (editFile != null) {
                try {
                    editFile.stop();
                } catch (RuntimeException ignored) {
                    // best effort: temporary file is retained by contract
                }
            }
        }
    }
}
