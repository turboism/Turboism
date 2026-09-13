package dev.turboism.plugin.externalpsdedit;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.id.ArtMeshId;
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
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns external PSD edit sessions for one plugin instance. One session exists per raw image;
 * a session owns the runtime-issued {@link PsdEditFile}, subscribes to stable saves before the
 * file is opened in the default application, and converts each save revision into a Cubism
 * native explicit-target replacement.
 *
 * <p>Sessions are bound to the document/model binding captured when their context menu was
 * built ({@link ContextMenuSelection#documentId()}). Any generation, document, or model change
 * detected at action time or on a save event invalidates the session instead of writing into a
 * different binding.</p>
 */
final class ExternalPsdEditSessionManager {

    private final PluginContext context;
    private final PluginLocalization localization;
    private final Map<RawImageId, Session> sessions = new LinkedHashMap<>();
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
        try {
            model = context.cubism().model().active();
            relations = model.textures().relations();
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

        final LinkedHashSet<RawImageId> targets = new LinkedHashSet<>();
        final List<String> unresolved = new ArrayList<>(skipped);
        for (final ArtMeshId artMesh : artMeshes) {
            final Optional<RawImageId> raw = currentRawImage(relations, artMesh);
            if (raw.isPresent()) {
                targets.add(raw.orElseThrow());
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
        if (targets.isEmpty()) {
            notifyStatus("external-psd-edit.error.unresolved", "ERROR",
                text("external-psd-edit.error.unresolved"));
            return;
        }

        final List<RawImageId> fresh = new ArrayList<>();
        synchronized (sessions) {
            if (stopped) {
                return;
            }
            for (final RawImageId target : targets) {
                final Session existing = sessions.get(target);
                if (existing == null || !existing.isLive()) {
                    fresh.add(target);
                }
            }
        }
        if (fresh.isEmpty()) {
            notifyStatus("external-psd-edit.status.already-open", "INFO",
                text("external-psd-edit.status.already-open"));
            return;
        }
        if (fresh.size() > 1 && !confirmMultiple(fresh.size())) {
            return;
        }
        final String binding = relations.binding();
        for (final RawImageId target : fresh) {
            try {
                beginSession(model.textures(), binding, target);
            } catch (RuntimeException exportRefused) {
                notifyStatus("external-psd-edit.error.session-failed", "ERROR", format(
                    "external-psd-edit.error.export-failed", target.value()));
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
        try {
            final CubismModel model = context.cubism().model().active();
            final TextureRelationsSnapshot relations = model.textures().relations();
            if (relations.isAvailable()) {
                currentBinding = relations.binding();
            }
        } catch (RuntimeException unavailable) {
            // No live binding to compare against: every session is suspect.
        }
        for (final Session session : snapshot) {
            if (currentBinding == null || !session.binding.equals(currentBinding)) {
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
        final String binding,
        final RawImageId target
    ) {
        final Session session = new Session(target, binding);
        synchronized (sessions) {
            if (stopped) {
                return;
            }
            sessions.put(target, session);
        }
        textures.exportRawImagePsd(target).whenComplete((result, failure) ->
            onExportComplete(session, result, failure));
    }

    private void onExportComplete(
        final Session session,
        final PsdExportResult result,
        final Throwable failure
    ) {
        if (failure != null) {
            failSession(session, format(
                "external-psd-edit.error.export-failed", session.rawImageId.value()));
            return;
        }
        if (result == null || result.status() != PsdExportResult.Status.EXPORTED) {
            failSession(session, format(
                "external-psd-edit.error.export-failed",
                session.rawImageId.value()
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
                    session.rawImageId.value() + " (subscribe failed)"));
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
                "external-psd-edit.error.open-failed", session.rawImageId.value()));
            return;
        }
        session.state = State.ACTIVE;
        notifyStatus("external-psd-edit.status.editing", "INFO",
            format("external-psd-edit.status.editing", session.rawImageId.value()));
    }

    private void onSave(final Session session, final PsdFileRevision revision) {
        synchronized (session) {
            if (session.state != State.ACTIVE || session.file == null) {
                return;
            }
        }
        submit("external-psd-edit.import." + taskSequence.incrementAndGet(), () ->
            importSave(session, revision));
    }

    private void importSave(final Session session, final PsdFileRevision revision) {
        synchronized (session) {
            if (session.state != State.ACTIVE || session.file == null) {
                return;
            }
        }
        final CubismModel model;
        final TextureRelationsSnapshot relations;
        try {
            model = context.cubism().model().active();
            relations = model.textures().relations();
        } catch (RuntimeException unavailable) {
            invalidate(session, "model unavailable");
            return;
        }
        if (!relations.isAvailable() || !session.binding.equals(relations.binding())) {
            invalidate(session, "document or model binding changed");
            return;
        }
        if (relations.rawImage(session.rawImageId).isEmpty()) {
            invalidate(session, "target raw image no longer exists");
            return;
        }
        final PsdEditFile file = session.file;
        model.textures().replaceRawImagePsd(session.rawImageId, file, revision)
            .whenComplete((result, failure) -> onReplaceComplete(session, result, failure));
    }

    private void onReplaceComplete(
        final Session session,
        final PsdReplaceResult result,
        final Throwable failure
    ) {
        synchronized (session) {
            if (!session.isLive()) {
                return;
            }
        }
        if (failure != null || result == null) {
            notifyStatus("external-psd-edit.error.replace-failed", "ERROR", format(
                "external-psd-edit.error.replace-failed", session.rawImageId.value()));
            return;
        }
        switch (result.status()) {
            case APPLIED -> notifyStatus(
                "external-psd-edit.status.applied", "INFO",
                format("external-psd-edit.status.applied", session.rawImageId.value()));
            case PARTIAL_FAILURE -> {
                session.state = State.PAUSED;
                notifyStatus("external-psd-edit.status.paused-partial", "ERROR",
                    format("external-psd-edit.status.paused-partial",
                        session.rawImageId.value(), result.diagnostic()));
            }
            case FAILED -> notifyStatus(
                "external-psd-edit.error.replace-failed", "ERROR", format(
                    "external-psd-edit.error.replace-failed",
                    session.rawImageId.value() + " (" + result.diagnostic() + ")"));
            case STALE_TARGET, REJECTED, UNAVAILABLE ->
                invalidate(session, "replace " + result.status() + ": " + result.diagnostic());
        }
    }

    /** Stops and removes a session whose binding or target is no longer trustworthy. */
    private void invalidate(final Session session, final String reason) {
        final boolean removed;
        synchronized (sessions) {
            removed = sessions.remove(session.rawImageId, session);
        }
        session.stop();
        if (removed) {
            context.logger().info("ExternalPsdEditPlugin session invalidated for "
                + session.rawImageId.value() + ": " + reason);
            notifyStatus("external-psd-edit.warn.session-invalidated", "WARNING", format(
                "external-psd-edit.warn.session-invalidated", session.rawImageId.value()));
        }
    }

    private void failSession(final Session session, final String message) {
        synchronized (sessions) {
            sessions.remove(session.rawImageId, session);
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
            return true;
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

    private Optional<RawImageId> currentRawImage(
        final TextureRelationsSnapshot relations,
        final ArtMeshId artMesh
    ) {
        for (final ArtMeshTextureInputs inputs : relations.artMeshInputs()) {
            if (!inputs.id().equals(artMesh)) {
                continue;
            }
            final OptionalInt index = inputs.currentInputIndex();
            if (index.isEmpty()) {
                return Optional.empty();
            }
            final TextureInputBinding input = inputs.inputs().get(index.getAsInt());
            if (input.kind() != TextureInputBinding.Kind.MODEL_IMAGE
                || input.modelImageId().isEmpty()) {
                return Optional.empty();
            }
            final ModelImageId modelImageId = input.modelImageId().orElseThrow();
            return relations.modelImage(modelImageId)
                .flatMap(ModelImageRelation::currentRawImageId);
        }
        return Optional.empty();
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

    private static final class Session {
        private final RawImageId rawImageId;
        private final String binding;
        private volatile State state = State.EXPORTING;
        private volatile PsdEditFile file;
        private volatile Registration saveRegistration;

        private Session(final RawImageId rawImageId, final String binding) {
            this.rawImageId = rawImageId;
            this.binding = binding;
        }

        private synchronized boolean isLive() {
            return state != State.STOPPED;
        }

        private synchronized void stop() {
            if (state == State.STOPPED) {
                return;
            }
            state = State.STOPPED;
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
