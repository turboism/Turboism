package dev.turboism.exportsettings;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Armed-session orchestrator for protected export (approved F+S1 design).
 *
 * <p>Threading model:</p>
 * <ul>
 *   <li>{@link #requestExport} runs on the EDT inside the outer dialog's decide gate. It
 *       only admits or refuses: capture the dialog-bound model source, arm a session,
 *       and hand off to the orchestrator worker. The outer decide vetoes either way.</li>
 *   <li>The worker thread performs every phase, marshalling each host-object access onto
 *   the EDT through {@link EdtDispatcher}. Open/close and the inner export are driven on
 *   the EDT; the worker polls for native completion.</li>
 *   <li>During the bounded re-drive window the native settings dialog shows no
 *       contributed option (suppression), the native chooser pick is redirected to
 *       task-owned staging, and the worker callback delivers the staged output
 *       inventory.</li>
 * </ul>
 *
 * <p>Every failure rejects the run, restores the original session when bound, and
 * removes task-owned files. The user's real destination is never touched until staged
 * output validates and the original session verifies restored.</p>
 */
public final class ProtectedExportOrchestrator implements AutoCloseable {

    /** Bounded failure identities reported per run. */
    public static final String NOT_ADMITTED_KEY = "protected-export.not-admitted";
    public static final String PREFLIGHT_FAILED_KEY = "protected-export.preflight-failed";
    public static final String BIND_FAILED_KEY = "protected-export.bind-failed";
    public static final String FLATTEN_FAILED_KEY = "protected-export.flatten-failed";
    public static final String OBFUSCATE_FAILED_KEY = "protected-export.obfuscation-failed";
    public static final String EXPORT_CANCELLED_KEY = "protected-export.export-cancelled";
    public static final String EXPORT_FAILED_KEY = "protected-export.export-failed";
    public static final String EXPORT_TIMEOUT_KEY = "protected-export.export-timeout";
    public static final String VALIDATION_FAILED_KEY = "protected-export.validation-failed";
    public static final String RESTORE_FAILED_KEY = "protected-export.restore-failed";
    public static final String PUBLISH_FAILED_KEY = "protected-export.publish-failed";

    /** EDT marshalling seam; production wraps SwingUtilities, tests run inline. */
    public interface EdtDispatcher {
        <T> T call(Callable<T> action) throws Exception;

        /** Posts {@code task} to the EDT without blocking the caller. */
        void submit(Runnable task);
    }

    /** Session-phase trace for diagnostics and the fault-injection matrix. */
    public enum Phase {
        ARMED,
        PREFLIGHTED,
        COPY_BOUND,
        FLATTENED,
        OBFUSCATED,
        EXPORT_DRIVEN,
        STAGED,
        VALIDATED,
        RESTORED,
        PUBLISHED,
        CANCELLED,
        FAILED
    }

    /** Terminal report for one armed session. */
    public record Report(
        long sessionId,
        Phase reached,
        boolean published,
        String failureKey,
        List<Path> publishedFiles,
        String failureDetail
    ) {
    }

    private final ProtectedExportHostOperations host;
    private final ProtectedExportStaging staging;
    private final Path stagingRoot;
    private final String orchestratedPluginId;
    private final String orchestratedOptionId;
    private final BooleanSupplier redirectSeamInstalled;
    private final BooleanSupplier optionBindingLive;
    private final LongSupplier hostGeneration;
    private final EdtDispatcher edt;
    private final Consumer<Report> reporter;
    private final long bindTimeoutMillis;
    private final long exportCallbackTimeoutMillis;
    private final ExecutorService worker;
    private final AtomicLong sessionIds = new AtomicLong();
    private final AtomicReference<Session> armed = new AtomicReference<>();
    /** Hot only while our own native re-drive executes on the EDT. */
    private final AtomicBoolean exportWindow = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public ProtectedExportOrchestrator(
        final ProtectedExportHostOperations host,
        final ProtectedExportStaging staging,
        final Path stagingRoot,
        final String orchestratedPluginId,
        final String orchestratedOptionId,
        final BooleanSupplier redirectSeamInstalled,
        final BooleanSupplier optionBindingLive,
        final LongSupplier hostGeneration,
        final EdtDispatcher edt,
        final Consumer<Report> reporter,
        final long bindTimeoutMillis,
        final long exportCallbackTimeoutMillis
    ) {
        this.host = Objects.requireNonNull(host, "host");
        this.staging = Objects.requireNonNull(staging, "staging");
        this.stagingRoot = Objects.requireNonNull(stagingRoot, "stagingRoot");
        this.orchestratedPluginId = requireText(orchestratedPluginId, "orchestratedPluginId");
        this.orchestratedOptionId = requireText(orchestratedOptionId, "orchestratedOptionId");
        this.redirectSeamInstalled =
            Objects.requireNonNull(redirectSeamInstalled, "redirectSeamInstalled");
        this.optionBindingLive =
            Objects.requireNonNull(optionBindingLive, "optionBindingLive");
        this.hostGeneration = Objects.requireNonNull(hostGeneration, "hostGeneration");
        this.edt = Objects.requireNonNull(edt, "edt");
        this.reporter = Objects.requireNonNull(reporter, "reporter");
        this.bindTimeoutMillis = bindTimeoutMillis;
        this.exportCallbackTimeoutMillis = exportCallbackTimeoutMillis;
        final ThreadFactory daemon = task -> {
            final Thread thread = new Thread(task, "turboism-protected-export");
            thread.setDaemon(true);
            return thread;
        };
        this.worker = Executors.newSingleThreadExecutor(daemon);
    }

    /** Whether the authority should route this contribution's decision through us. */
    public boolean isOrchestrationOption(final String pluginId, final String optionId) {
        return orchestratedPluginId.equals(pluginId) && orchestratedOptionId.equals(optionId);
    }

    /**
     * Admits and arms a protected-export session for the confirmed outer dialog.
     *
     * <p>Runs on the EDT inside decide. Capture is limited to identity-bound host reads;
     * all heavy preflight executes on the orchestrator worker after the modal unwinds.
     * The outer export is vetoed regardless of the return value.</p>
     *
     * @return {@code true} only when a session armed and will run
     */
    public boolean requestExport(final Object dialogOwner) {
        if (closed.get() || !redirectSeamInstalled.getAsBoolean()
            || dialogOwner == null || !host.isExportDialog(dialogOwner)) {
            return false;
        }
        final Object modelSource;
        final Object document;
        final File sourceFile;
        try {
            modelSource = host.dialogModelSource(dialogOwner);
            document = modelSource == null ? null : host.modelSourceDocument(modelSource);
            sourceFile = document == null ? null : host.documentFile(document);
        } catch (Throwable failure) {
            return false;
        }
        if (modelSource == null || document == null || sourceFile == null
            || !host.isModelingDocument(document)
            || host.currentDocument() != document
            || !host.projectContains(document)) {
            return false;
        }
        final Session session = new Session(
            sessionIds.incrementAndGet(), dialogOwner, document, modelSource, sourceFile,
            hostGeneration.getAsLong());
        if (!armed.compareAndSet(null, session)) {
            return false;
        }
        worker.execute(() -> run(session));
        return true;
    }

    /**
     * Whether a dialog attach must present no contributed options. True for the whole
     * armed session: the inner re-drive dialog stays byte-identical to a native
     * unchecked run, and an interleaved native export cannot acquire the option.
     */
    public boolean suppressDialogOptions(final Object dialogOwner) {
        final Session session = armed.get();
        if (session == null || dialogOwner == null) {
            return false;
        }
        if (exportWindow.get() && session.innerDialogOwner.compareAndSet(null, dialogOwner)) {
            session.innerDialogSeen.set(true);
        }
        return true;
    }

    /** A dialog cancelled mid-window aborts the session's export phase. */
    public void dialogCancelled(final Object dialogOwner) {
        final Session session = armed.get();
        if (session == null || dialogOwner == null) {
            return;
        }
        final Object inner = session.innerDialogOwner.get();
        if (inner != null && inner == dialogOwner) {
            session.aborted.set(true);
        }
    }

    /**
     * Chooser-result redirect. Outside the re-drive window every pick passes through —
     * including a concurrent native export — so only our own inner export can stage.
     */
    public Object redirectPickedFile(final Object picked) {
        final Session session = armed.get();
        if (session == null || !exportWindow.get()) {
            return picked;
        }
        if (picked == null) {
            session.chooserCancelled.set(true);
            return null;
        }
        if (!(picked instanceof File realPick)) {
            return null;
        }
        session.realPick = realPick;
        session.redirectFired.set(true);
        return session.stagingPick(realPick);
    }

    private void run(final Session session) {
        try {
            preflight(session);
            bindCopy(session);
            flatten(session);
            obfuscate(session);
            driveExport(session);
            awaitCompletion(session);
            validate(session);
            restore(session);
            publish(session);
            session.report(Phase.PUBLISHED, true, null);
        } catch (SessionRejection rejection) {
            session.fail(rejection.failureKey, rejection.detail);
        } catch (Throwable failure) {
            session.fail("protected-export.internal-failure",
                session.phase + " " + describe(failure));
        } finally {
            session.cleanup();
            armed.compareAndSet(session, null);
            session.deliverReport();
        }
    }

    // ------------------------------------------------------------------
    // Phases — every host access marshals to the EDT through edt.call
    // ------------------------------------------------------------------

    private void preflight(final Session session) throws Exception {
        final long generation = session.hostGeneration;
        onEdt(() -> {
            requireGeneration(session, generation);
            requireLiveDocument(session);
            snapshotInvariants(session);
            // A dirty original means the file copy cannot represent the live document;
            // the protected output would silently diverge from what the user sees.
            if (session.originalModified) {
                throw new SessionRejection(PREFLIGHT_FAILED_KEY);
            }
            // The full census resolves on the original source before any copy exists.
            try {
                session.plan = ProtectedExportDeformerPlan.plan(host, session.modelSource)
                    .leafToRootGuids();
            } catch (ProtectedExportDeformerPlan.ProtectedExportPlanRejection rejection) {
                throw new SessionRejection(PREFLIGHT_FAILED_KEY);
            }
            if (host.usesExtendedInterpolation(session.modelSource)) {
                throw new SessionRejection(PREFLIGHT_FAILED_KEY);
            }
            return null;
        });
        try {
            session.sourceSha256 = sha256(session.sourceFile.toPath());
        } catch (IOException failure) {
            throw new SessionRejection(PREFLIGHT_FAILED_KEY);
        }
        session.phase = Phase.PREFLIGHTED;
    }

    private void bindCopy(final Session session) throws Exception {
        final Path stagingDir;
        try {
            Files.createDirectories(stagingRoot);
            stagingDir = Files.createTempDirectory(
                stagingRoot, "protected-export-" + session.id + "-");
            session.stagingDir = stagingDir;
            session.copyFile = stagingDir.resolve(session.sourceFile.getName()).toFile();
            Files.copy(session.sourceFile.toPath(), session.copyFile.toPath());
        } catch (IOException failure) {
            throw new SessionRejection(BIND_FAILED_KEY);
        }
        onEdt(() -> {
            host.openFile(session.copyFile);
            return null;
        });
        final Object bound = awaitBound(session, session.copyFile);
        if (bound == null || bound == session.document) {
            throw new SessionRejection(BIND_FAILED_KEY);
        }
        final Object copySource = onEdt(() -> {
            // The copy is active now; the original only needs to survive in the project.
            if (!host.projectContains(session.document)) {
                throw new SessionRejection(BIND_FAILED_KEY);
            }
            final Object source = host.documentModelSource(bound);
            if (source == null || source == session.modelSource) {
                throw new SessionRejection(BIND_FAILED_KEY);
            }
            return source;
        });
        session.copyDocument = bound;
        session.copyModelSource = copySource;
        // Binding confirmation: the copy census must reproduce the plan exactly.
        final List<String> copyOrder;
        try {
            copyOrder = onEdt(() ->
                ProtectedExportDeformerPlan.plan(host, copySource).leafToRootGuids());
        } catch (ProtectedExportDeformerPlan.ProtectedExportPlanRejection rejection) {
            throw new SessionRejection(BIND_FAILED_KEY);
        }
        if (!session.plan.equals(copyOrder)) {
            throw new SessionRejection(BIND_FAILED_KEY);
        }
        session.phase = Phase.COPY_BOUND;
    }

    private void flatten(final Session session) throws Exception {
        for (String guid : session.plan) {
            final boolean applied = onEdt(() -> {
                requireGeneration(session, session.hostGeneration);
                final Object source = resolveDeformer(session.copyModelSource, guid);
                if (source == null) {
                    return Boolean.FALSE;
                }
                final Object selector = host.documentSelector(session.copyDocument);
                final Object editMode = host.documentMainEditMode(session.copyDocument);
                if (!host.isMainSelector(selector) || !host.isMainEditMode(editMode)) {
                    throw new SessionRejection(FLATTEN_FAILED_KEY);
                }
                requireLiveCopy(session);
                host.clearSelection(selector);
                host.selectSource(selector, source);
                if (host.selectedDeformers(selector).isEmpty()) {
                    return Boolean.FALSE;
                }
                host.applyDeformerToParameters(editMode);
                return resolveDeformer(session.copyModelSource, guid) == null
                    ? Boolean.TRUE : Boolean.FALSE;
            });
            if (!Boolean.TRUE.equals(applied)) {
                throw new SessionRejection(FLATTEN_FAILED_KEY);
            }
        }
        final boolean clean = onEdt(() -> {
            for (Object deformer : host.allDeformers(session.copyModelSource)) {
                if (host.isWarpDeformer(deformer) || host.isRotationDeformer(deformer)) {
                    return Boolean.FALSE;
                }
            }
            return Boolean.TRUE;
        });
        if (!clean) {
            throw new SessionRejection(FLATTEN_FAILED_KEY);
        }
        session.phase = Phase.FLATTENED;
    }

    /**
     * Rewrites every ArtMesh name and drawable ID on the disposable copy to the
     * deterministic GUID-derived token — never on the authoring document. Each write
     * re-resolves the target by GUID and re-reads the result; a post-pass census then
     * requires every ArtMesh to carry exactly its planned identity.
     */
    private void obfuscate(final Session session) throws Exception {
        final ProtectedExportObfuscationPlan.Plan plan;
        try {
            plan = onEdt(() -> {
                requireGeneration(session, session.hostGeneration);
                requireLiveCopy(session, OBFUSCATE_FAILED_KEY);
                session.expectedParameterIds =
                    parameterIdentitySet(host.allParameters(session.copyModelSource));
                session.expectedPartIds =
                    identitySet(host.allParts(session.copyModelSource));
                return ProtectedExportObfuscationPlan.plan(
                    host, session.copyModelSource);
            });
        } catch (ProtectedExportDeformerPlan.ProtectedExportPlanRejection rejection) {
            throw new SessionRejection(OBFUSCATE_FAILED_KEY);
        }
        for (Map.Entry<String, ProtectedExportObfuscationPlan.Target> entry
                : plan.byGuid().entrySet()) {
            final String guid = entry.getKey();
            final ProtectedExportObfuscationPlan.Target target = entry.getValue();
            final boolean applied = onEdt(() -> {
                requireGeneration(session, session.hostGeneration);
                requireLiveCopy(session, OBFUSCATE_FAILED_KEY);
                final Object mesh = resolveArtMesh(session.copyModelSource, guid);
                if (mesh == null || !host.isArtMeshSource(mesh)) {
                    return Boolean.FALSE;
                }
                host.setObjectLocalName(mesh, target.name());
                host.setDrawableId(mesh, target.idToken());
                return target.name().equals(host.objectLocalName(mesh))
                    && target.idToken().equals(host.drawableIdString(mesh));
            });
            if (!Boolean.TRUE.equals(applied)) {
                throw new SessionRejection(OBFUSCATE_FAILED_KEY);
            }
        }
        final boolean consistent = onEdt(() -> {
            requireGeneration(session, session.hostGeneration);
            requireLiveCopy(session, OBFUSCATE_FAILED_KEY);
            for (Object mesh : host.allArtMeshes(session.copyModelSource)) {
                final ProtectedExportObfuscationPlan.Target target =
                    plan.byGuid().get(host.objectGuid(mesh));
                if (target == null
                    || !target.name().equals(host.objectLocalName(mesh))
                    || !target.idToken().equals(host.drawableIdString(mesh))) {
                    return Boolean.FALSE;
                }
            }
            return Boolean.TRUE;
        });
        if (!Boolean.TRUE.equals(consistent)) {
            throw new SessionRejection(OBFUSCATE_FAILED_KEY);
        }
        session.expectedDrawableIds = plan.idTokens();
        session.phase = Phase.OBFUSCATED;
    }

    private void driveExport(final Session session) throws Exception {
        final Object callback = host.newExportCompletionProxy((file, paths) -> {
            session.stagedPick = file;
            session.stagedPaths = paths == null ? List.of() : List.copyOf(paths);
            session.completion.countDown();
        });
        exportWindow.set(true);
        try {
            // Posted, not waited on: al.a runs the entire modal export flow on
            // the EDT (inner settings dialog, warnings, chooser, write and any
            // trailing message prompts). Blocking the worker in invokeAndWait
            // here would park the session past every timeout in
            // awaitCompletion — one un-dismissed native prompt wedged it
            // forever (observed on exact host run queue-e324da1a).
            edt.submit(() -> {
                try {
                    final Object driver = host.exportDriver();
                    if (driver == null) {
                        throw new SessionRejection(EXPORT_FAILED_KEY);
                    }
                    host.invokeNativeExport(
                        driver, session.copyModelSource, host.mainFrame(),
                        callback);
                    session.exportDone.complete(null);
                } catch (Throwable failure) {
                    session.exportDone.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException failure) {
            exportWindow.set(false);
            throw failure;
        }
        session.phase = Phase.EXPORT_DRIVEN;
    }

    private void awaitCompletion(final Session session) throws Exception {
        try {
            if (session.aborted.get() || session.chooserCancelled.get()) {
                throw new SessionRejection(EXPORT_CANCELLED_KEY);
            }
            // Bound the whole modal export flow, not just the write callback:
            // al.a only returns after every prompt it raised was dismissed. One
            // shared deadline covers the EDT flow and the completion callback.
            final long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(exportCallbackTimeoutMillis);
            try {
                session.exportDone.get(
                    exportCallbackTimeoutMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException timeout) {
                throw new SessionRejection(EXPORT_TIMEOUT_KEY);
            } catch (ExecutionException wrapped) {
                final Throwable cause = wrapped.getCause();
                if (cause instanceof SessionRejection rejection) {
                    throw rejection;
                }
                throw new SessionRejection(EXPORT_FAILED_KEY);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new SessionRejection(EXPORT_FAILED_KEY);
            }
            // Cancellation can arrive mid-flow (chooser cancel posts null);
            // re-check after the bounded wait so it reports CANCELLED.
            if (session.aborted.get() || session.chooserCancelled.get()) {
                throw new SessionRejection(EXPORT_CANCELLED_KEY);
            }
            // A native pre-check veto (e.g. empty texture atlases) returns from
            // al.a without ever opening the settings dialog or the chooser.
            if (!session.innerDialogSeen.get() || !session.redirectFired.get()) {
                throw new SessionRejection(EXPORT_FAILED_KEY);
            }
            final long remaining = TimeUnit.NANOSECONDS.toMillis(
                deadline - System.nanoTime());
            if (remaining <= 0 || !session.completion.await(
                remaining, TimeUnit.MILLISECONDS)) {
                throw new SessionRejection(EXPORT_TIMEOUT_KEY);
            }
            if (session.stagedPick == null || session.stagedPaths.isEmpty()) {
                throw new SessionRejection(EXPORT_FAILED_KEY);
            }
            session.phase = Phase.STAGED;
        } finally {
            exportWindow.set(false);
        }
    }

    private void validate(final Session session) {
        final ProtectedExportStaging.Validation validation = staging.validate(
            session.stagedPick, session.stagedPaths, session.expectedDrawableIds,
            session.expectedParameterIds, session.expectedPartIds);
        if (!validation.valid()) {
            throw new SessionRejection(
                VALIDATION_FAILED_KEY + ":" + validation.failureKey(),
                validation.failureDetail());
        }
        session.stagedFiles = validation.stagedFiles();
        session.phase = Phase.VALIDATED;
    }

    private void restore(final Session session) throws Exception {
        // Restore the original as the SAME live document and verify every session
        // invariant before any output can be published.
        onEdt(() -> {
            host.openFile(session.sourceFile);
            return null;
        });
        final Object restored = awaitBound(session, session.sourceFile);
        if (restored != session.document) {
            throw new SessionRejection(RESTORE_FAILED_KEY);
        }
        final boolean intact = onEdt(() -> {
            try {
                return verifyInvariants(session) ? Boolean.TRUE : Boolean.FALSE;
            } catch (IOException failure) {
                return Boolean.FALSE;
            }
        });
        if (!Boolean.TRUE.equals(intact)) {
            throw new SessionRejection(RESTORE_FAILED_KEY);
        }
        closeCopy(session);
        session.phase = Phase.RESTORED;
    }

    private void publish(final Session session) throws Exception {
        try {
            session.publishedFiles =
                staging.publish(session.stagedPick, session.stagedFiles, session.realPick);
        } catch (IOException failure) {
            throw new SessionRejection(PUBLISH_FAILED_KEY);
        }
        session.phase = Phase.PUBLISHED;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void requireGeneration(final Session session, final long expected) {
        if (hostGeneration.getAsLong() != expected
            || !optionBindingLive.getAsBoolean()) {
            throw new SessionRejection(NOT_ADMITTED_KEY);
        }
    }

    private void requireLiveDocument(final Session session) {
        if (host.currentDocument() != session.document
            || !host.projectContains(session.document)) {
            throw new SessionRejection(NOT_ADMITTED_KEY);
        }
    }

    private void requireLiveCopy(final Session session) {
        requireLiveCopy(session, FLATTEN_FAILED_KEY);
    }

    private void requireLiveCopy(final Session session, final String failureKey) {
        if (host.currentDocument() != session.copyDocument
            || !host.projectContains(session.copyDocument)
            || !host.projectContains(session.document)) {
            throw new SessionRejection(failureKey);
        }
    }

    private Object resolveDeformer(final Object modelSource, final String guid) {
        for (Object deformer : host.allDeformers(modelSource)) {
            if (guid.equals(host.deformerGuid(deformer))) {
                return deformer;
            }
        }
        return null;
    }

    /** Exact ID set of a host census; a single unreadable identity fails closed. */
    private Set<String> identitySet(final List<?> sources) {
        final Set<String> ids = new LinkedHashSet<>();
        for (Object source : sources) {
            final String id = host.objectIdString(source);
            if (id == null || id.isBlank()) {
                throw new SessionRejection(OBFUSCATE_FAILED_KEY);
            }
            ids.add(id);
        }
        return Set.copyOf(ids);
    }

    /**
     * Exact ID set of the parameter-source census. Parameter sources are not
     * parameter-controllable, so their IDs come through the dedicated accessor.
     */
    private Set<String> parameterIdentitySet(final List<?> sources) {
        final Set<String> ids = new LinkedHashSet<>();
        for (Object source : sources) {
            final String id = host.parameterSourceIdString(source);
            if (id == null || id.isBlank()) {
                throw new SessionRejection(OBFUSCATE_FAILED_KEY);
            }
            ids.add(id);
        }
        return Set.copyOf(ids);
    }

    private Object resolveArtMesh(final Object modelSource, final String guid) {
        for (Object mesh : host.allArtMeshes(modelSource)) {
            if (guid.equals(host.objectGuid(mesh))) {
                return mesh;
            }
        }
        return null;
    }

    /**
     * Polls until the active document is a modeling document backed by {@code file}.
     * Native open dispatches asynchronously, so this waits on the worker thread.
     */
    private Object awaitBound(final Session session, final File file) throws Exception {
        final long deadline = System.nanoTime()
            + TimeUnit.MILLISECONDS.toNanos(bindTimeoutMillis);
        while (System.nanoTime() < deadline) {
            if (session.aborted.get()) {
                return null;
            }
            final Object document = onEdt(() -> {
                final Object current = host.currentDocument();
                return current != null && host.isModelingDocument(current)
                    && file.equals(host.documentFile(current)) ? current : null;
            });
            if (document != null) {
                return document;
            }
            Thread.sleep(50L);
        }
        return null;
    }

    /** Closes the dirty copy through the proven mark-saved + native-close recipe. */
    private void closeCopy(final Session session) throws Exception {
        if (session.copyDocument == null) {
            return;
        }
        onEdt(() -> {
            host.markDocumentSaved(session.copyDocument);
            final Object content = host.documentFileContent(session.copyDocument);
            if (content != null) {
                host.closeFileContent(content);
            }
            return null;
        });
        final long deadline = System.nanoTime()
            + TimeUnit.MILLISECONDS.toNanos(bindTimeoutMillis);
        while (System.nanoTime() < deadline) {
            final boolean detached = onEdt(() ->
                !host.projectContains(session.copyDocument));
            if (detached) {
                break;
            }
            Thread.sleep(50L);
        }
        if (onEdt(() -> host.projectContains(session.copyDocument))) {
            throw new SessionRejection(RESTORE_FAILED_KEY);
        }
        if (session.copyFile != null && session.copyFile.isFile()
            && !host.releaseFileHandleFor(session.copyFile)) {
            throw new SessionRejection(RESTORE_FAILED_KEY);
        }
        if (session.copyFile != null) {
            Files.deleteIfExists(session.copyFile.toPath());
            if (session.copyFile.isFile()) {
                throw new SessionRejection(RESTORE_FAILED_KEY);
            }
        }
    }

    /** Session-invariant comparison: original file sha, dirty flag, undo, selection. */
    private boolean verifyInvariants(final Session session) throws IOException {
        final Object content = host.documentFileContent(session.document);
        final boolean modified = content != null && host.fileContentModified(content);
        final Object undo = host.documentUndoManager(session.document);
        final String undoSignature = undoPositionSignature(undo);
        final String selectionSignature = selectionSignature(session.document);
        if (session.originalModified != modified
            || !Objects.equals(session.originalUndoSignature, undoSignature)
            || !Objects.equals(session.originalSelectionSignature, selectionSignature)) {
            return false;
        }
        return sha256(session.sourceFile.toPath()).equals(session.sourceSha256);
    }

    private void snapshotInvariants(final Session session) throws IOException {
        final Object content = host.documentFileContent(session.document);
        session.originalModified =
            content != null && host.fileContentModified(content);
        session.originalUndoSignature =
            undoPositionSignature(host.documentUndoManager(session.document));
        session.originalSelectionSignature = selectionSignature(session.document);
    }

    private String undoPositionSignature(final Object undoManager) {
        if (undoManager == null) {
            return "none";
        }
        return host.undoPosition(undoManager) + ":"
            + host.undoEditCount(undoManager) + ":"
            + host.undoCanUndo(undoManager);
    }

    private String selectionSignature(final Object document) {
        final Object selector = host.documentSelector(document);
        if (selector == null) {
            return "none";
        }
        final StringBuilder signature = new StringBuilder()
            .append(host.selectedCount(selector)).append(':');
        for (Object item : host.selectedDeformers(selector)) {
            signature.append(System.identityHashCode(item)).append(',');
        }
        return signature.toString();
    }

    /**
     * Marshals work onto the EDT and unwraps the container exception so a
     * {@link SessionRejection} raised inside keeps its bounded failure identity.
     */
    private <T> T onEdt(final Callable<T> action) throws Exception {
        try {
            return edt.call(action);
        } catch (java.lang.reflect.InvocationTargetException
                 | java.util.concurrent.ExecutionException wrapped) {
            final Throwable cause = wrapped.getCause();
            if (cause instanceof SessionRejection rejection) {
                throw rejection;
            }
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw wrapped;
        }
    }

    private static String sha256(final Path file) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        worker.shutdownNow();
        final Session session = armed.getAndSet(null);
        if (session != null) {
            session.aborted.set(true);
            session.cleanup();
        }
        try {
            // Bounded drain: callers (and test teardown) must not race task-owned
            // files the worker may still be deleting.
            worker.awaitTermination(10L, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /** Mutable per-run session state; only the worker thread mutates after arming. */
    private final class Session {
        final long id;
        final Object dialogOwner;
        final Object document;
        final Object modelSource;
        final File sourceFile;
        final long hostGeneration;
        final CountDownLatch completion = new CountDownLatch(1);
        final java.util.concurrent.CompletableFuture<Void> exportDone =
            new java.util.concurrent.CompletableFuture<>();
        final AtomicBoolean innerDialogSeen = new AtomicBoolean();
        final AtomicBoolean aborted = new AtomicBoolean();
        final AtomicBoolean chooserCancelled = new AtomicBoolean();
        final AtomicBoolean redirectFired = new AtomicBoolean();
        final AtomicReference<Object> innerDialogOwner = new AtomicReference<>();
        final AtomicBoolean cleaned = new AtomicBoolean();
        volatile Phase phase = Phase.ARMED;
        volatile List<String> plan = List.of();
        volatile String sourceSha256;
        volatile boolean originalModified;
        volatile String originalUndoSignature;
        volatile String originalSelectionSignature;
        volatile Path stagingDir;
        volatile File copyFile;
        volatile Object copyDocument;
        volatile Object copyModelSource;
        volatile File realPick;
        volatile File stagedPick;
        volatile List<String> stagedPaths = List.of();
        volatile List<Path> stagedFiles = List.of();
        volatile Set<String> expectedDrawableIds = Set.of();
        volatile Set<String> expectedParameterIds = Set.of();
        volatile Set<String> expectedPartIds = Set.of();
        volatile List<Path> publishedFiles = List.of();
        volatile Report pendingReport;

        Session(
            final long id,
            final Object dialogOwner,
            final Object document,
            final Object modelSource,
            final File sourceFile,
            final long hostGeneration
        ) {
            this.id = id;
            this.dialogOwner = dialogOwner;
            this.document = document;
            this.modelSource = modelSource;
            this.sourceFile = sourceFile;
            this.hostGeneration = hostGeneration;
        }

        File stagingPick(final File realPick) {
            final Path dir = stagingDir == null ? stagingRoot : stagingDir;
            return dir.resolve(realPick.getName()).toFile();
        }

        void report(final Phase reached, final boolean published,
            final String failureKey
        ) {
            report(reached, published, failureKey, null);
        }

        void report(final Phase reached, final boolean published,
            final String failureKey, final String failureDetail
        ) {
            phase = reached;
            // The terminal report is delivered after cleanup: a published or
            // failed report always means task-owned state is already gone.
            pendingReport = new Report(id, reached, published, failureKey,
                publishedFiles, failureDetail);
        }

        void deliverReport() {
            final Report pending = pendingReport;
            if (pending == null) {
                return;
            }
            pendingReport = null;
            try {
                reporter.accept(pending);
            } catch (Throwable ignored) {
                // Reporting must never disturb session teardown.
            }
        }

        void fail(final String failureKey) {
            fail(failureKey, null);
        }

        void fail(final String failureKey, final String failureDetail) {
            report(EXPORT_CANCELLED_KEY.equals(failureKey) ? Phase.CANCELLED : Phase.FAILED,
                false, failureKey, failureDetail);
        }

        /** Best-effort removal of task-owned state; failures are only recorded. */
        void cleanup() {
            if (!cleaned.compareAndSet(false, true)) {
                return;
            }
            try {
                if (copyDocument != null) {
                    final boolean stillBound = onEdt(() ->
                        host.projectContains(copyDocument));
                    if (stillBound) {
                        onEdt(() -> {
                            host.markDocumentSaved(copyDocument);
                            final Object content = host.documentFileContent(copyDocument);
                            if (content != null) {
                                host.closeFileContent(content);
                            }
                            return null;
                        });
                    }
                }
            } catch (Throwable ignored) {
                // Cleanup failure must not mask the session's recorded outcome.
            }
            try {
                if (copyFile != null && copyFile.isFile()) {
                    host.releaseFileHandleFor(copyFile);
                    Files.deleteIfExists(copyFile.toPath());
                }
            } catch (Throwable ignored) {
            }
            try {
                ProtectedExportStaging.deleteRecursively(stagingDir);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Bounded rejection carrying a stable failure identity. */
    private static final class SessionRejection extends RuntimeException {
        final String failureKey;
        final String detail;

        SessionRejection(final String failureKey) {
            this(failureKey, null);
        }

        SessionRejection(final String failureKey, final String detail) {
            super(failureKey);
            this.failureKey = failureKey;
            this.detail = detail;
        }
    }

    /**
     * Bounded one-line description of an unexpected failure for the terminal
     * report — class plus a truncated message, never a stack trace.
     */
    private static String describe(final Throwable failure) {
        final String message = failure.getMessage();
        final String text = failure.getClass().getName()
            + (message == null ? "" : ": " + message.replaceAll("\\s+", " ").strip());
        return text.length() > 200 ? text.substring(0, 200) : text;
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
