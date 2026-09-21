package dev.turboism.exportsettings;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
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
    public static final String BEHAVIOR_CAPTURE_FAILED_KEY =
        "protected-export.behavior-capture-failed";
    public static final String BEHAVIOR_MISMATCH_KEY =
        "protected-export.behavior-mismatch";
    public static final String EXPORT_CANCELLED_KEY = "protected-export.export-cancelled";
    public static final String EXPORT_FAILED_KEY = "protected-export.export-failed";
    public static final String REVOKED_KEY = "protected-export.revoked";
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
        SOURCE_BEHAVIOR_CAPTURED,
        FLATTENED,
        OBFUSCATED,
        BEHAVIOR_CAPTURED,
        BEHAVIOR_COMPARED,
        EXPORT_DRIVEN,
        STAGED,
        VALIDATED,
        RESTORED,
        PUBLISHED,
        CANCELLED,
        FAILED
    }

    /**
     * Terminal report for one armed session.
     *
     * <p>{@code originalRestored} is only {@code true} when the post-session check
     * verified the original document active again with every snapshotted invariant
     * intact; {@code cleanupErrors} lists every cleanup step that failed (empty means
     * task-owned state is verifiably gone). A terminal {@code PUBLISHED},
     * {@code FAILED} or {@code CANCELLED} phase never implies either by itself.</p>
     */
    public record Report(
        long sessionId,
        Phase reached,
        boolean published,
        String failureKey,
        List<Path> publishedFiles,
        String failureDetail,
        boolean originalRestored,
        List<String> cleanupErrors
    ) {
        /** Whether task-owned state was verifiably removed without error. */
        public boolean cleanedUp() {
            return cleanupErrors.isEmpty();
        }
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
            captureOriginalBehavior(session);
            flatten(session);
            obfuscate(session);
            captureBehavior(session);
            compareBehavior(session);
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
            // Every terminal path — published, failed, cancelled — runs the same
            // teardown: put the original document back, verify its invariants,
            // retire the copy and task-owned files, and record what happened.
            session.cleanupErrors = restoreSession(session);
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
                throw new SessionRejection(PREFLIGHT_FAILED_KEY, "original-dirty");
            }
            // The full census resolves on the original source before any copy exists.
            try {
                session.plan = ProtectedExportDeformerPlan.plan(host, session.modelSource)
                    .leafToRootGuids();
            } catch (ProtectedExportDeformerPlan.ProtectedExportPlanRejection rejection) {
                throw new SessionRejection(PREFLIGHT_FAILED_KEY, rejection.getMessage());
            }
            if (host.usesExtendedInterpolation(session.modelSource)) {
                throw new SessionRejection(PREFLIGHT_FAILED_KEY, "extended-interpolation");
            }
            // Identity census on the authoring source before any copy exists —
            // the bound copy must reproduce it exactly.
            session.originalCensus = censusModel(session.modelSource, PREFLIGHT_FAILED_KEY);
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
            final Object instance = host.modelSourceCurrentInstance(source);
            if (instance == null) {
                throw new SessionRejection(BIND_FAILED_KEY);
            }
            session.copyModelInstance = instance;
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
        // Pre-mutation census on the bound copy: every later phase and the staged
        // validation compare against this snapshot, so a mutation that drifted
        // part/parameter/mesh identity — or a copy that never matched the source —
        // rejects instead of publishing.
        session.censusBefore = onEdt(() -> {
            final ModelCensus census = censusModel(copySource, BIND_FAILED_KEY);
            if (!census.equals(session.originalCensus)) {
                throw new SessionRejection(BIND_FAILED_KEY);
            }
            return census;
        });
        session.expectedParameters = parameterExpectations(session.censusBefore);
        session.expectedPartIds = partIdSet(session.censusBefore);
        session.phase = Phase.COPY_BOUND;
    }

    private void flatten(final Session session) throws Exception {
        for (String guid : session.plan) {
            final boolean applied = onEdt(() -> {
                // The apply command mutates whatever the selector and active
                // document hold, and every selection-mutating call can reenter
                // host code — so the whole context is re-read and re-verified
                // after each of them, never trusted across a callback.
                FlattenContext context = requireFlattenContext(session, null);
                final Object source = resolveDeformer(context.liveSource(), guid);
                if (source == null) {
                    return Boolean.FALSE;
                }
                host.clearSelection(context.selector());
                context = requireFlattenContext(session, context);
                host.selectSource(context.selector(), source);
                context = requireFlattenContext(session, context);
                // Exactly one selected deformer, the very source object resolved
                // for this plan step — a switched document or a foreign
                // selection entry rejects before any apply can run.
                final List<?> selected = host.selectedDeformers(context.selector());
                context = requireFlattenContext(session, context);
                if (selected.size() != 1 || selected.get(0) != source
                    || !guid.equals(host.deformerGuid(selected.get(0)))
                    || host.selectedCount(context.selector()) != 1) {
                    throw new SessionRejection(FLATTEN_FAILED_KEY);
                }
                // A deformer with no keyform bindings contributes a constant
                // deformation that the host's delete-and-reflect command does
                // NOT preserve: it bakes keyforms only at bound parameter keys,
                // so an empty binding list silently drops the deformation.
                // Bake that constant into each child ArtMesh's base and keyform
                // positions through the deformer's own local-to-canvas transform
                // before the apply deletes it.
                if (host.keyformBindings(source).isEmpty()) {
                    bakeConstantDeformation(session, source);
                }
                host.applyDeformerToParameters(context.editMode());
                return resolveDeformer(context.liveSource(), guid) == null
                    ? Boolean.TRUE : Boolean.FALSE;
            });
            if (!Boolean.TRUE.equals(applied)) {
                throw new SessionRejection(FLATTEN_FAILED_KEY);
            }
        }
        final boolean clean = onEdt(() -> {
            requireLiveCopy(session);
            for (Object deformer : host.allDeformers(requireCopyModelSource(session))) {
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
     * Bakes an unbound deformer's constant deformation into every ArtMesh child.
     * Child positions are authored in the deformer's local space; once the
     * deformer is deleted they are interpreted in the surviving parent's local
     * space, so each authored position is first mapped to its evaluated canvas
     * position through the deformer's own local-to-canvas transform and then
     * re-expressed in the parent's local space through the parent's
     * canvas-to-local transform (identity when the deformer is root-level).
     * Rewriting both the base positions and every keyform's positions keeps the
     * rendered shape unchanged under whatever ancestors remain. Runs on the
     * copy's live model instance; called only inside the flatten EDT block after
     * the deformer's selection identity was verified.
     */
    private void bakeConstantDeformation(final Session session, final Object source) {
        final Object instance = session.copyModelInstance;
        final Object forward =
            host.deformerLocalToCanvasTransform(instance, source);
        if (forward == null) {
            throw new SessionRejection(FLATTEN_FAILED_KEY);
        }
        final Object parentInverse =
            host.deformerParentCanvasToLocalTransform(instance, source);
        for (Object child : host.deformerChildren(source)) {
            if (!host.isArtMeshSource(child)) {
                // A surviving deformer child cannot absorb a position bake;
                // leaf-to-root order should have deleted it first — fail closed.
                throw new SessionRejection(FLATTEN_FAILED_KEY);
            }
            final float[] base = host.artMeshSourcePositions(child);
            if (base == null) {
                throw new SessionRejection(FLATTEN_FAILED_KEY);
            }
            host.setArtMeshSourcePositions(
                child, bakePositions(forward, parentInverse, base));
            for (Object keyform : host.artMeshSourceKeyforms(child)) {
                final float[] positions = host.artMeshFormPositions(keyform);
                if (positions == null) {
                    throw new SessionRejection(FLATTEN_FAILED_KEY);
                }
                host.setArtMeshFormPositions(
                    keyform, bakePositions(forward, parentInverse, positions));
            }
        }
        host.evaluateModelInstance(instance);
    }

    private float[] bakePositions(
        final Object forward,
        final Object parentInverse,
        final float[] positions
    ) {
        final float[] canvas = host.transformPositions(forward, positions);
        return parentInverse == null
            ? canvas : host.transformPositions(parentInverse, canvas);
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
                return ProtectedExportObfuscationPlan.plan(
                    host, requireCopyModelSource(session, OBFUSCATE_FAILED_KEY));
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
                final Object mesh = resolveArtMesh(
                    requireCopyModelSource(session, OBFUSCATE_FAILED_KEY), guid);
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
            final Object liveSource = requireCopyModelSource(
                session, OBFUSCATE_FAILED_KEY);
            for (Object mesh : host.allArtMeshes(liveSource)) {
                final ProtectedExportObfuscationPlan.Target target =
                    plan.byGuid().get(host.objectGuid(mesh));
                if (target == null
                    || !target.name().equals(host.objectLocalName(mesh))
                    || !target.idToken().equals(host.drawableIdString(mesh))) {
                    return Boolean.FALSE;
                }
            }
            // Post-mutation census: parts and parameters must be byte-identical
            // to the bound snapshot (flatten may only drop deformer memberships);
            // every ArtMesh must carry exactly its planned obfuscated identity.
            verifyPostMutationCensus(session, censusModel(liveSource, OBFUSCATE_FAILED_KEY), plan);
            return Boolean.TRUE;
        });
        if (!Boolean.TRUE.equals(consistent)) {
            throw new SessionRejection(OBFUSCATE_FAILED_KEY);
        }
        session.expectedDrawableIds = plan.idTokens();
        session.obfuscationPlan = plan;
        session.phase = Phase.OBFUSCATED;
    }

    /**
     * Pre-flatten behavior baseline: on the freshly bound copy — before any
     * deformer apply or rename — replay the deterministic parameter-sample
     * sequence and record evaluated ArtMesh positions keyed by the authored
     * source GUID. This is the authored-behavior half of the oracle; without it
     * a flatten that corrupted geometry would only prove the exporter
     * faithfully reproduces a damaged copy.
     */
    private void captureOriginalBehavior(final Session session) throws Exception {
        final Map<String, String> guidKeys = new LinkedHashMap<>();
        for (String guid : session.censusBefore.artMeshes().keySet()) {
            guidKeys.put(guid, guid);
        }
        session.originalBehavior = captureBehaviorSnapshot(session, guidKeys);
        session.phase = Phase.SOURCE_BEHAVIOR_CAPTURED;
    }

    /**
     * Behavior-oracle capture: on the disposable copy, post-flatten and
     * post-obfuscation, replay a deterministic parameter-sample sequence through
     * the host's own model evaluation and record evaluated ArtMesh positions per
     * drawable token. Validation replays the identical sequence on the staged
     * .moc3 through the owned Core runtime — this is the sampled-behavior
     * comparison, not a structural-equality proxy.
     */
    private void captureBehavior(final Session session) throws Exception {
        final ProtectedExportObfuscationPlan.Plan plan = session.obfuscationPlan;
        if (plan == null) {
            throw new SessionRejection(BEHAVIOR_CAPTURE_FAILED_KEY);
        }
        final Map<String, String> tokenKeys = new LinkedHashMap<>();
        for (Map.Entry<String, ProtectedExportObfuscationPlan.Target> entry
                : plan.byGuid().entrySet()) {
            tokenKeys.put(entry.getKey(), entry.getValue().idToken());
        }
        session.behavior = captureBehaviorSnapshot(session, tokenKeys);
        session.phase = Phase.BEHAVIOR_CAPTURED;
    }

    /**
     * Rejects publication when flatten/obfuscation changed authored behavior:
     * the pre-flatten GUID-keyed snapshot is compared against the post-mutation
     * token-keyed snapshot over the obfuscation plan mapping. A flatten that
     * scaled or shifted geometry — even when the exporter would faithfully
     * reproduce it — is a pre-export rejection, not a validated output.
     */
    private void compareBehavior(final Session session) {
        final Map<String, String> guidToToken = new LinkedHashMap<>();
        for (Map.Entry<String, ProtectedExportObfuscationPlan.Target> entry
                : session.obfuscationPlan.byGuid().entrySet()) {
            guidToToken.put(entry.getKey(), entry.getValue().idToken());
        }
        final String drift = ProtectedExportStaging.behaviorDrift(
            session.originalBehavior, session.behavior, guidToToken);
        if (drift != null) {
            throw new SessionRejection(BEHAVIOR_MISMATCH_KEY, drift);
        }
        session.phase = Phase.BEHAVIOR_COMPARED;
    }

    /**
     * Shared sampled-behavior capture on the copy's live instance: set every
     * parameter to its contract default, evaluate, record the baseline frame,
     * then replay each sample (value → evaluate → frame → back to default).
     * Every touched parameter is restored to the value it held before capture —
     * on success and on failure — so sampling can never contaminate the export
     * that follows. {@code guidToKey} maps authored source GUID to the frame
     * key (the GUID itself pre-flatten, the drawable-ID token post-obfuscation).
     */
    private ProtectedExportStaging.BehaviorSnapshot captureBehaviorSnapshot(
        final Session session,
        final Map<String, String> guidToKey
    ) throws Exception {
        final List<ProtectedExportStaging.BehaviorSample> samples =
            behaviorSamples(session.expectedParameters);
        final ProtectedExportStaging.BehaviorSnapshot snapshot = onEdt(() -> {
            requireGeneration(session, session.hostGeneration);
            requireLiveCopy(session, BEHAVIOR_CAPTURE_FAILED_KEY);
            final Object source =
                requireCopyModelSource(session, BEHAVIOR_CAPTURE_FAILED_KEY);
            final Object instance = session.copyModelInstance;
            if (instance == null) {
                throw new SessionRejection(BEHAVIOR_CAPTURE_FAILED_KEY);
            }
            final Map<String, ProtectedExportStaging.ParameterExpectation>
                expected = session.expectedParameters;
            // Record the pre-capture state of every parameter we will touch so
            // it can be restored exactly — defaults are the sampling baseline,
            // not necessarily the live state.
            final Map<String, Object> params = new LinkedHashMap<>();
            final Map<String, Float> priorValues = new LinkedHashMap<>();
            for (var expectation : expected.values()) {
                final Object parameter =
                    requireLiveParameter(session, source, expectation.id());
                params.put(expectation.id(), parameter);
                priorValues.put(expectation.id(),
                    host.parameterInstanceValue(parameter));
            }
            try {
                for (Map.Entry<String, Object> entry : params.entrySet()) {
                    host.setParameterInstanceValue(entry.getValue(),
                        expected.get(entry.getKey()).defaultValue());
                }
                host.evaluateModelInstance(instance);
                final Map<String, float[]> baseline =
                    captureFrame(instance, guidToKey);
                final List<Map<String, float[]>> frames =
                    new ArrayList<>(samples.size());
                for (var sample : samples) {
                    host.setParameterInstanceValue(
                        params.get(sample.parameterId()), sample.value());
                    host.evaluateModelInstance(instance);
                    frames.add(captureFrame(instance, guidToKey));
                    // Isolate the next sample: return this parameter to default.
                    host.setParameterInstanceValue(
                        params.get(sample.parameterId()),
                        expected.get(sample.parameterId()).defaultValue());
                }
                return new ProtectedExportStaging.BehaviorSnapshot(
                    samples, baseline, frames);
            } finally {
                for (Map.Entry<String, Object> entry : params.entrySet()) {
                    host.setParameterInstanceValue(
                        entry.getValue(), priorValues.get(entry.getKey()));
                }
                host.evaluateModelInstance(instance);
            }
        });
        if (snapshot == null) {
            throw new SessionRejection(BEHAVIOR_CAPTURE_FAILED_KEY);
        }
        return snapshot;
    }

    /**
     * Deterministic sample sequence from the parameter contract: parameters in
     * ID order, each contributing its minimum, maximum, and bound key positions
     * (the default is covered by the baseline frame). Capped so a pathological
     * model cannot turn validation into an unbounded sampling run.
     */
    private List<ProtectedExportStaging.BehaviorSample> behaviorSamples(
        final Map<String, ProtectedExportStaging.ParameterExpectation> expected
    ) {
        final int maxSamples = 64;
        final List<ProtectedExportStaging.BehaviorSample> samples =
            new ArrayList<>();
        final List<String> ids = new ArrayList<>(expected.keySet());
        Collections.sort(ids);
        for (String id : ids) {
            final var expectation = expected.get(id);
            final Set<Float> points = new TreeSet<>();
            points.add(expectation.minimumValue());
            points.add(expectation.maximumValue());
            points.addAll(expectation.keys());
            for (float point : points) {
                if (!Float.isFinite(point)) {
                    continue;
                }
                if (samples.size() >= maxSamples) {
                    return List.copyOf(samples);
                }
                samples.add(
                    new ProtectedExportStaging.BehaviorSample(id, point));
            }
        }
        return List.copyOf(samples);
    }

    /** Resolves a live parameter instance on the copy's source by ID. */
    private Object requireLiveParameter(
        final Session session,
        final Object modelSource,
        final String parameterId
    ) {
        for (Object parameter : host.liveParameters(modelSource)) {
            if (parameterId.equals(host.parameterInstanceId(parameter))) {
                return parameter;
            }
        }
        throw new SessionRejection(BEHAVIOR_CAPTURE_FAILED_KEY);
    }

    /**
     * Evaluated positions of every mapped ArtMesh on the copy's live model
     * instance. {@code guidToKey} decides which authored meshes are captured
     * and under which key (source GUID pre-flatten, drawable token
     * post-obfuscation). The instance ArtMesh list is re-read on every call
     * because host evaluation may rebuild instance objects; a mapped mesh that
     * cannot be resolved or read fails the capture.
     */
    private Map<String, float[]> captureFrame(
        final Object modelInstance,
        final Map<String, String> guidToKey
    ) {
        final Map<String, float[]> frame = new LinkedHashMap<>();
        for (Object mesh : host.modelInstanceArtMeshes(modelInstance)) {
            final Object meshSource = host.artMeshInstanceSource(mesh);
            final String guid = meshSource == null
                ? null : host.objectGuid(meshSource);
            final String key = guid == null ? null : guidToKey.get(guid);
            if (key == null) {
                continue;
            }
            final float[] positions = host.evaluatedArtMeshPositions(mesh);
            if (positions == null || positions.length == 0
                || (positions.length & 1) != 0) {
                throw new SessionRejection(BEHAVIOR_CAPTURE_FAILED_KEY,
                    "invalid-positions:" + key);
            }
            for (float position : positions) {
                if (!Float.isFinite(position)) {
                    throw new SessionRejection(BEHAVIOR_CAPTURE_FAILED_KEY,
                        "non-finite-positions:" + key);
                }
            }
            frame.put(key, positions);
        }
        // Full plan coverage: a planned mesh absent from the live evaluation
        // is a capture failure, not a silently narrower comparison surface.
        for (String key : guidToKey.values()) {
            if (!frame.containsKey(key)) {
                throw new SessionRejection(BEHAVIOR_CAPTURE_FAILED_KEY,
                    "uncaptured-drawable:" + key);
            }
        }
        return Map.copyOf(frame);
    }

    private void driveExport(final Session session) throws Exception {
        final Object callback = host.newExportCompletionProxy((file, paths) -> {
            // Late or foreign completions must not revive a session: only an
            // armed, still-admitted session inside its own export window may
            // record staged output.
            if (armed.get() != session || !exportWindow.get() || closed.get()
                || session.aborted.get()
                || hostGeneration.getAsLong() != session.hostGeneration
                || !optionBindingLive.getAsBoolean()) {
                return;
            }
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
                    // Revocation while the task sat queued — or while the native
                    // modal flow ran — must not proceed as a live session.
                    requireExportLive(session);
                    final Object driver = host.exportDriver();
                    if (driver == null) {
                        throw new SessionRejection(EXPORT_FAILED_KEY);
                    }
                    host.invokeNativeExport(
                        driver, session.copyModelSource, host.mainFrame(),
                        callback);
                    requireExportLive(session);
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
            // Revocation during the native modal flow (plugin unload, host
            // replacement, document loss) must never proceed to validation.
            requireSessionAdmittedOnEdt(session, true);
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
            requireExportLive(session);
            requireSessionAdmittedOnEdt(session, true);
            if (session.stagedPick == null || session.stagedPaths.isEmpty()) {
                throw new SessionRejection(EXPORT_FAILED_KEY);
            }
            session.phase = Phase.STAGED;
        } finally {
            exportWindow.set(false);
        }
    }

    private void validate(final Session session) throws Exception {
        requireSessionAdmittedOnEdt(session, true);
        final ProtectedExportStaging.Validation validation = staging.validate(
            session.stagedPick, session.stagedPaths, session.expectedDrawableIds,
            session.expectedParameters, session.expectedPartIds,
            session.behavior);
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
        requireSessionAdmittedOnEdt(session, true);
        restoreOriginalDocument(session);
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

    /**
     * Reactivates the original document through the native open path and requires
     * the rebound document to be the very same instance — a reopened lookalike
     * means the original was closed underneath the session and fails closed.
     */
    private void restoreOriginalDocument(final Session session) throws Exception {
        restoreOriginalDocument(session, false);
    }

    private void restoreOriginalDocument(
        final Session session,
        final boolean cleanupContext
    ) throws Exception {
        onEdt(() -> {
            host.openFile(session.sourceFile);
            return null;
        });
        if (awaitBound(session, session.sourceFile, cleanupContext) != session.document) {
            throw new SessionRejection(RESTORE_FAILED_KEY);
        }
    }

    private void publish(final Session session) throws Exception {
        // The last revocation barrier: the destination is touched only while the
        // session is still armed, admitted and bound to the live original
        // document — re-verified on the EDT immediately beforehand.
        onEdt(() -> {
            requireSessionAdmittedOnEdt(session, false);
            if (host.currentDocument() != session.document) {
                throw new SessionRejection(REVOKED_KEY);
            }
            return null;
        });
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
            || !optionBindingLive.getAsBoolean()
            || closed.get() || session.aborted.get()) {
            throw new SessionRejection(NOT_ADMITTED_KEY);
        }
    }

    /**
     * Cancellation precedes revocation in the report: an aborted or
     * chooser-cancelled session reports CANCELLED, everything else revoked.
     */
    private void requireExportLive(final Session session) {
        if (session.aborted.get() || session.chooserCancelled.get()) {
            throw new SessionRejection(EXPORT_CANCELLED_KEY);
        }
        requireSessionAdmitted(session);
    }

    /**
     * Worker-side revocation barrier for the export tail: the session must still
     * be the armed session of a live orchestrator, on the same host generation
     * with a live option binding, and neither cancelled nor chooser-cancelled.
     * Any break throws {@link #REVOKED_KEY} — the report names the revocation
     * instead of a generic failure.
     */
    private void requireSessionAdmitted(final Session session) {
        if (closed.get() || armed.get() != session
            || hostGeneration.getAsLong() != session.hostGeneration
            || !optionBindingLive.getAsBoolean()
            || session.aborted.get() || session.chooserCancelled.get()) {
            throw new SessionRejection(REVOKED_KEY);
        }
    }

    /**
     * Full revocation barrier marshalled onto the EDT: the worker-side state
     * plus live document identity. While the copy may be open
     * ({@code copyExpectedOpen}) both documents must still be in the project;
     * afterwards only the original's presence is required — the active-document
     * check belongs to the caller.
     */
    private void requireSessionAdmittedOnEdt(
        final Session session,
        final boolean copyExpectedOpen
    ) throws Exception {
        onEdt(() -> {
            requireSessionAdmitted(session);
            if (!host.projectContains(session.document)
                || (copyExpectedOpen && session.copyDocument != null
                    && !host.projectContains(session.copyDocument))) {
                throw new SessionRejection(REVOKED_KEY);
            }
            return null;
        });
    }

    private void requireLiveDocument(final Session session) {
        if (host.currentDocument() != session.document
            || !host.projectContains(session.document)) {
            throw new SessionRejection(NOT_ADMITTED_KEY);
        }
    }

    /**
     * The complete flatten-apply context: bound live model source, the document's
     * main selector and the document's main edit mode — re-resolved from the
     * document each call.
     */
    private record FlattenContext(Object liveSource, Object selector, Object editMode) {
    }

    /**
     * Re-reads and re-verifies the whole flatten context on the EDT: binding
     * liveness and host generation, the copy as the active in-project document,
     * its bound model source and live instance, and the modeling-main selector
     * and edit mode. When {@code expected} is given, the freshly resolved
     * selector and edit mode must be the same objects — a swapped selector would
     * make the apply command act on a selection this session never made.
     */
    private FlattenContext requireFlattenContext(
        final Session session,
        final FlattenContext expected
    ) {
        requireGeneration(session, session.hostGeneration);
        requireLiveCopy(session);
        final Object liveSource = requireCopyModelSource(session);
        final Object selector = host.documentSelector(session.copyDocument);
        final Object editMode = host.documentMainEditMode(session.copyDocument);
        if (!host.isMainSelector(selector) || !host.isMainEditMode(editMode)
            || (expected != null
                && (selector != expected.selector()
                    || editMode != expected.editMode()
                    || liveSource != expected.liveSource()))) {
            throw new SessionRejection(FLATTEN_FAILED_KEY);
        }
        return new FlattenContext(liveSource, selector, editMode);
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

    /**
     * The live model source of the bound copy document — re-read from the document
     * (not the cached reference) and required to be the same source object carrying
     * the same live model instance captured at bind.
     */
    private Object requireCopyModelSource(final Session session) {
        return requireCopyModelSource(session, FLATTEN_FAILED_KEY);
    }

    private Object requireCopyModelSource(
        final Session session,
        final String failureKey
    ) {
        final Object liveSource = host.documentModelSource(session.copyDocument);
        if (liveSource == null || liveSource != session.copyModelSource
            || host.modelSourceCurrentInstance(liveSource) != session.copyModelInstance) {
            throw new SessionRejection(failureKey);
        }
        return liveSource;
    }

    private Object resolveDeformer(final Object modelSource, final String guid) {
        for (Object deformer : host.allDeformers(modelSource)) {
            if (guid.equals(host.deformerGuid(deformer))) {
                return deformer;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Identity census — captured before mutation, enforced after
    // ------------------------------------------------------------------

    /** Part identity: stable GUID keys the map; value holds serialized identity. */
    private record PartIdentity(
        String id,
        String name,
        List<String> childGuids
    ) {
    }

    /** Parameter contract: evaluable range/default/repeat plus baked key union. */
    private record ParameterIdentity(
        float min,
        float max,
        float defaultValue,
        Boolean repeat,
        List<Float> keys
    ) {
    }

    /** ArtMesh identity: serialized name and drawable ID under the stable GUID. */
    private record ArtMeshIdentity(String name, String drawableId) {
    }

    /**
     * Identity snapshot of one model source: parts keyed by stable GUID, parameters
     * keyed by ID, ArtMeshes keyed by stable GUID.
     */
    private record ModelCensus(
        Map<String, PartIdentity> parts,
        Map<String, ParameterIdentity> parameters,
        Map<String, ArtMeshIdentity> artMeshes
    ) {
    }

    /**
     * Snapshots part, parameter and ArtMesh identities of {@code modelSource}.
     * Parameter keys are the union of key positions across every object's keyform
     * bindings for that parameter — the evaluable surface flatten must preserve.
     * Any unreadable identity fails closed with {@code failureKey}.
     */
    private ModelCensus censusModel(final Object modelSource, final String failureKey) {
        final Object root = host.rootPart(modelSource);
        final Map<String, PartIdentity> parts = new LinkedHashMap<>();
        for (Object part : host.allParts(modelSource)) {
            if (part == null || part == root) {
                continue;
            }
            if (!host.isPartSource(part)) {
                throw new SessionRejection(failureKey);
            }
            final String guid = host.objectGuid(part);
            final String id = host.objectIdString(part);
            if (guid == null || guid.isBlank() || id == null || id.isBlank()) {
                throw new SessionRejection(failureKey);
            }
            if (parts.put(guid, new PartIdentity(
                id, host.objectLocalName(part),
                List.copyOf(host.partChildGuids(part)))) != null) {
                throw new SessionRejection(failureKey);
            }
        }
        final Map<String, java.util.TreeSet<Float>> keyUnion = new LinkedHashMap<>();
        for (Object object : host.allObjects(modelSource)) {
            for (Object binding : host.keyformBindings(object)) {
                final String parameterId = host.keyformBindingParameterId(binding);
                if (parameterId == null || parameterId.isBlank()) {
                    continue;
                }
                keyUnion.computeIfAbsent(parameterId, id -> new java.util.TreeSet<>())
                    .addAll(host.keyformBindingKeys(binding));
            }
        }
        final Map<String, ParameterIdentity> parameters = new LinkedHashMap<>();
        for (Object parameter : host.allParameters(modelSource)) {
            final String id = host.parameterSourceIdString(parameter);
            final Float min = host.parameterSourceMinValue(parameter);
            final Float max = host.parameterSourceMaxValue(parameter);
            final Float def = host.parameterSourceDefaultValue(parameter);
            if (id == null || id.isBlank() || min == null || max == null
                || def == null) {
                throw new SessionRejection(failureKey);
            }
            final java.util.TreeSet<Float> keys = keyUnion.get(id);
            final ParameterIdentity identity = new ParameterIdentity(
                min, max, def, host.parameterSourceRepeat(parameter),
                keys == null ? List.of() : List.copyOf(keys));
            final ParameterIdentity prior = parameters.put(id, identity);
            if (prior != null && !prior.equals(identity)) {
                // Duplicate parameter IDs with diverging contracts are ambiguous.
                throw new SessionRejection(failureKey);
            }
        }
        final Map<String, ArtMeshIdentity> artMeshes = new LinkedHashMap<>();
        for (Object mesh : host.allArtMeshes(modelSource)) {
            final String guid = host.objectGuid(mesh);
            final String drawableId = host.drawableIdString(mesh);
            if (guid == null || guid.isBlank() || drawableId == null) {
                throw new SessionRejection(failureKey);
            }
            if (artMeshes.put(guid, new ArtMeshIdentity(
                host.objectLocalName(mesh), drawableId)) != null) {
                throw new SessionRejection(failureKey);
            }
        }
        return new ModelCensus(
            Map.copyOf(parts), Map.copyOf(parameters), Map.copyOf(artMeshes));
    }

    /** Expected staged parameter contracts from the pre-mutation census. */
    private Map<String, ProtectedExportStaging.ParameterExpectation>
            parameterExpectations(final ModelCensus census) {
        final Map<String, ProtectedExportStaging.ParameterExpectation> expectations =
            new LinkedHashMap<>();
        for (Map.Entry<String, ParameterIdentity> entry : census.parameters().entrySet()) {
            final ParameterIdentity identity = entry.getValue();
            expectations.put(entry.getKey(),
                new ProtectedExportStaging.ParameterExpectation(
                    entry.getKey(), identity.min(), identity.max(),
                    identity.defaultValue(), identity.repeat(), identity.keys()));
        }
        return Map.copyOf(expectations);
    }

    /** Serialized part ID set from the census (synthetic root excluded). */
    private Set<String> partIdSet(final ModelCensus census) {
        final Set<String> ids = new LinkedHashSet<>();
        for (PartIdentity part : census.parts().values()) {
            ids.add(part.id());
        }
        return Set.copyOf(ids);
    }

    /**
     * Post-mutation census enforcement: parts and parameters must equal the bound
     * snapshot — flatten may only remove planned deformer GUIDs from part
     * membership; ArtMeshes must carry exactly their planned obfuscated identity.
     */
    private void verifyPostMutationCensus(
        final Session session,
        final ModelCensus after,
        final ProtectedExportObfuscationPlan.Plan plan
    ) {
        final ModelCensus before = session.censusBefore;
        if (before == null) {
            throw new SessionRejection(OBFUSCATE_FAILED_KEY);
        }
        if (!before.parameters().equals(after.parameters())) {
            throw new SessionRejection(OBFUSCATE_FAILED_KEY, "parameter-identity-drift");
        }
        if (!before.parts().keySet().equals(after.parts().keySet())) {
            throw new SessionRejection(OBFUSCATE_FAILED_KEY, "part-identity-drift");
        }
        final Set<String> flattened = new LinkedHashSet<>(session.plan);
        for (Map.Entry<String, PartIdentity> entry : before.parts().entrySet()) {
            final PartIdentity prior = entry.getValue();
            final PartIdentity current = after.parts().get(entry.getKey());
            if (!prior.id().equals(current.id())
                || !Objects.equals(prior.name(), current.name())) {
                throw new SessionRejection(OBFUSCATE_FAILED_KEY, "part-identity-drift");
            }
            final List<String> priorChildren = new ArrayList<>(prior.childGuids());
            priorChildren.removeAll(flattened);
            if (!priorChildren.equals(current.childGuids())) {
                throw new SessionRejection(OBFUSCATE_FAILED_KEY, "part-hierarchy-drift");
            }
        }
        if (!before.artMeshes().keySet().equals(after.artMeshes().keySet())) {
            throw new SessionRejection(OBFUSCATE_FAILED_KEY, "artmesh-identity-drift");
        }
        for (Map.Entry<String, ArtMeshIdentity> entry : after.artMeshes().entrySet()) {
            final ProtectedExportObfuscationPlan.Target target =
                plan.byGuid().get(entry.getKey());
            final ArtMeshIdentity current = entry.getValue();
            if (target == null || !target.name().equals(current.name())
                || !target.idToken().equals(current.drawableId())) {
                throw new SessionRejection(OBFUSCATE_FAILED_KEY, "artmesh-identity-drift");
            }
        }
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
     * {@code cleanupContext} waits ignore the abort flag and keep sleeping through
     * interrupts — session teardown must still finish restoring the original.
     */
    private Object awaitBound(final Session session, final File file) throws Exception {
        return awaitBound(session, file, false);
    }

    private Object awaitBound(
        final Session session,
        final File file,
        final boolean cleanupContext
    ) throws Exception {
        final long deadline = System.nanoTime()
            + TimeUnit.MILLISECONDS.toNanos(bindTimeoutMillis);
        while (System.nanoTime() < deadline) {
            if (!cleanupContext && session.aborted.get()) {
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
            if (cleanupContext) {
                sleepDuringCleanup(50L);
            } else {
                Thread.sleep(50L);
            }
        }
        return null;
    }

    /**
     * Interrupt-tolerant sleep for teardown paths: an interrupted worker must still
     * finish cleanup, so the interrupt is remembered and restored afterwards
     * instead of abandoning the wait.
     */
    private static void sleepDuringCleanup(final long millis) {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        boolean interrupted = false;
        for (;;) {
            final long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                break;
            }
            try {
                Thread.sleep(TimeUnit.NANOSECONDS.toMillis(remaining) + 1L);
                break;
            } catch (InterruptedException wake) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Closes the dirty copy through the proven mark-saved + native-close recipe.
     * A bind timeout can fire while the native open still lands afterwards, so a
     * missing {@code copyDocument} falls back to scanning the project's open
     * documents for one backed by the copy file.
     */
    private void closeCopy(final Session session) throws Exception {
        closeCopy(session, false);
    }

    private void closeCopy(final Session session, final boolean cleanupContext)
            throws Exception {
        Object copy = session.copyDocument;
        if (copy == null && session.copyFile != null) {
            copy = onEdt(() -> {
                for (Object document : host.projectDocuments()) {
                    if (session.copyFile.equals(host.documentFile(document))) {
                        return document;
                    }
                }
                return null;
            });
            if (copy != null) {
                session.copyDocument = copy;
            }
        }
        if (copy == null) {
            return;
        }
        final Object target = copy;
        onEdt(() -> {
            if (!host.projectContains(target)) {
                return null;
            }
            host.markDocumentSaved(target);
            final Object content = host.documentFileContent(target);
            if (content != null) {
                host.closeFileContent(content);
            }
            return null;
        });
        final long deadline = System.nanoTime()
            + TimeUnit.MILLISECONDS.toNanos(bindTimeoutMillis);
        while (System.nanoTime() < deadline) {
            final boolean detached = onEdt(() -> !host.projectContains(target));
            if (detached) {
                break;
            }
            if (cleanupContext) {
                sleepDuringCleanup(50L);
            } else {
                Thread.sleep(50L);
            }
        }
        if (onEdt(() -> host.projectContains(target))) {
            throw new SessionRejection(RESTORE_FAILED_KEY);
        }
        if (session.copyFile != null && session.copyFile.isFile()
            && !host.releaseFileHandleFor(session.copyFile)) {
            throw new SessionRejection(RESTORE_FAILED_KEY);
        }
        if (session.copyFile != null) {
            ProtectedExportStaging.deleteIfExists(session.copyFile.toPath());
            if (session.copyFile.isFile()) {
                throw new SessionRejection(RESTORE_FAILED_KEY);
            }
        }
    }

    /**
     * Terminal teardown shared by every exit path — success, failure and
     * cancellation. Reactivates the original document when a copy may have been
     * opened, closes the copy (including one that bound after a bind timeout),
     * removes task-owned files, and records every step that failed instead of
     * swallowing it. Idempotent: a retry after a partial pass completes whatever
     * is left.
     */
    private List<String> restoreSession(final Session session) {
        session.teardownLock.lock();
        try {
            return restoreSessionLocked(session);
        } finally {
            session.teardownLock.unlock();
        }
    }

    /**
     * Bounded variant for {@link #runTeardownFallback}: a worker wedged inside an
     * EDT call would otherwise park {@code close()} forever on the teardown
     * monitor. A failed acquisition surfaces as a cleanup error instead.
     */
    private List<String> restoreSessionBounded(
        final Session session,
        final long timeoutMillis
    ) {
        boolean acquired = false;
        try {
            acquired = session.teardownLock.tryLock(
                timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (!acquired) {
            return List.of(
                "teardown-busy: worker still holds session teardown");
        }
        try {
            return restoreSessionLocked(session);
        } finally {
            session.teardownLock.unlock();
        }
    }

    private List<String> restoreSessionLocked(final Session session) {
            final List<String> errors = new ArrayList<>();
            if (session.copyFile != null) {
                try {
                    restoreOriginalDocument(session, true);
                } catch (Throwable failure) {
                    errors.add("restore-original: " + describe(failure));
                }
            }
            try {
                closeCopy(session, true);
            } catch (Throwable failure) {
                errors.add("close-copy: " + describe(failure));
            }
            try {
                ProtectedExportStaging.deleteRecursively(session.stagingDir);
            } catch (Throwable failure) {
                errors.add("staging: " + describe(failure));
            }
            session.originalRestored = verifyRestored(session, errors);
            if (session.copyFile != null && session.copyFile.isFile()) {
                errors.add("copy-file remains: " + session.copyFile);
            }
            if (session.stagingDir != null && Files.isDirectory(session.stagingDir)) {
                errors.add("staging remains: " + session.stagingDir);
            }
            return List.copyOf(errors);
    }

    /**
     * Verified end-state of the authoring session: the original document is the
     * active document again and every snapshotted invariant still holds. A session
     * that never reached preflight has no snapshot — then only document identity
     * is verifiable.
     */
    private boolean verifyRestored(final Session session, final List<String> errors) {
        try {
            return Boolean.TRUE.equals(onEdt(() -> {
                if (host.currentDocument() != session.document
                    || !host.projectContains(session.document)) {
                    return false;
                }
                if (session.sourceSha256 == null) {
                    return true;
                }
                try {
                    return verifyInvariants(session);
                } catch (IOException failure) {
                    return false;
                }
            }));
        } catch (Throwable failure) {
            errors.add("verify-restored: " + describe(failure));
            return false;
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
        if (session == null) {
            return;
        }
        session.aborted.set(true);
        try {
            // The worker owns teardown in its finally; give it a bounded drain so
            // callers do not race task-owned files still being deleted.
            if (!worker.awaitTermination(10L, TimeUnit.SECONDS)) {
                // Worker never finished — a queued-but-never-started session or a
                // wedged EDT call. Run the same idempotent teardown here.
                runTeardownFallback(session);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            runTeardownFallback(session);
        }
    }

    /**
     * Teardown when the worker never delivered: cancel the report, run the shared
     * idempotent restore/cleanup on this thread, then deliver. Safe to enter while
     * the worker is still unwinding — {@link #restoreSession} serializes on the
     * session monitor and every step tolerates a second pass.
     */
    private void runTeardownFallback(final Session session) {
        if (session.reportDelivered.get()) {
            return;
        }
        if (session.pendingReport == null) {
            session.report(Phase.CANCELLED, false, EXPORT_CANCELLED_KEY);
        }
        session.cleanupErrors = restoreSessionBounded(session, 10_000L);
        session.deliverReport();
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
        final AtomicBoolean reportDelivered = new AtomicBoolean();
        final java.util.concurrent.locks.ReentrantLock teardownLock =
            new java.util.concurrent.locks.ReentrantLock();
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
        volatile Object copyModelInstance;
        volatile ModelCensus originalCensus;
        volatile ModelCensus censusBefore;
        volatile File realPick;
        volatile File stagedPick;
        volatile List<String> stagedPaths = List.of();
        volatile List<Path> stagedFiles = List.of();
        volatile Set<String> expectedDrawableIds = Set.of();
        volatile ProtectedExportObfuscationPlan.Plan obfuscationPlan;
        volatile ProtectedExportStaging.BehaviorSnapshot originalBehavior;
        volatile ProtectedExportStaging.BehaviorSnapshot behavior;
        volatile Map<String, ProtectedExportStaging.ParameterExpectation>
            expectedParameters = Map.of();
        volatile Set<String> expectedPartIds = Set.of();
        volatile List<Path> publishedFiles = List.of();
        volatile boolean originalRestored;
        volatile List<String> cleanupErrors = List.of();
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
            // The terminal report is delivered only after teardown: restoration
            // and cleanup outcomes are attached at delivery time so a published,
            // failed or cancelled report always carries verified cleanup state.
            pendingReport = new Report(id, reached, published, failureKey,
                publishedFiles, failureDetail, false, List.of());
        }

        void deliverReport() {
            final Report pending = pendingReport;
            if (pending == null) {
                return;
            }
            pendingReport = null;
            final Report report = new Report(pending.sessionId(), pending.reached(),
                pending.published(), pending.failureKey(), pending.publishedFiles(),
                pending.failureDetail(), originalRestored, cleanupErrors);
            reportDelivered.set(true);
            try {
                reporter.accept(report);
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
