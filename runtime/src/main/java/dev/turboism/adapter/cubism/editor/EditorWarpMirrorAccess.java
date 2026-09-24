package dev.turboism.adapter.cubism.editor;

import dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator;
import dev.turboism.adapter.cubism.editor.transaction.EditorRefreshRequirement;
import dev.turboism.adapter.cubism.editor.transaction.EditorUndoContribution;
import dev.turboism.adapter.cubism.warp.WarpMirrorPairing;
import dev.turboism.adapter.cubism.warp.WarpMirrorRuntimeService;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.mirror.WarpMirrorBlocker;
import dev.turboism.sdk.cubism.mirror.WarpMirrorBlockerCode;
import dev.turboism.sdk.cubism.mirror.WarpMirrorDirection;
import dev.turboism.sdk.cubism.mirror.WarpMirrorRequest;
import dev.turboism.sdk.cubism.mirror.WarpMirrorResult;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionResult;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Editor-side port for whole-object Warp Deformer mirroring (BoundingBox overlay workflow).
 *
 * <p>One {@link #apply(WarpMirrorRequest)} call captures the live state, computes the
 * mirrored grid, prepares descendant compensation, and commits everything inside a single
 * {@link EditorAuthoringTransactionCoordinator} transaction so the parent write and every
 * child compensation share one native Undo entry. When {@code preserveDescendants} is set,
 * every descendant's evaluated canvas geometry is snapshotted, compensated through the
 * parent Warp instance's own canvas↔local transforms (the host's exact evaluation
 * inverse, quad or bezier), and re-verified after the write; any residual
 * above {@value #MAX_RESIDUAL} px fails the transaction and rolls back.</p>
 */
final class EditorWarpMirrorAccess implements WarpMirrorRuntimeService.Port {

    /** Maximum tolerated canvas-space residual for preserved descendant geometry. */
    private static final double MAX_RESIDUAL = 0.01;
    /** Near-equality epsilon matching the legacy warp mirror already-synced check. */
    private static final float ALREADY_SYNCED_EPSILON = 0.0001f;
    /**
     * Rotation-direction probe used by the host evaluation for a Warp parent:
     * the evaluated angle is {@code stored.angle + angle((0,-0.1) → warped secant)},
     * sampled in the parent Warp's local (0..1) lattice units.
     */
    private static final float ROTATION_PROBE_DIR_X = 0f;
    private static final float ROTATION_PROBE_DIR_Y = -0.1f;
    private static final String LABEL = "Turboism: Warp Mirror";
    private static final System.Logger LOGGER =
        System.getLogger(EditorWarpMirrorAccess.class.getName());

    private final VerifiedMemberResolver resolver;
    private final EditorObjectReadAccess objects;
    private final Supplier<NativeBinding> current;
    private final EditorAuthoringTransactionCoordinator coordinator;
    private final Supplier<EditorAuthoringTransactionCoordinator.Binding> transactionBinding;
    private volatile String lastResidualDetails = "";

    EditorWarpMirrorAccess(
        final VerifiedMemberResolver resolver,
        final EditorObjectReadAccess objects,
        final Supplier<NativeBinding> current,
        final EditorAuthoringTransactionCoordinator coordinator,
        final Supplier<EditorAuthoringTransactionCoordinator.Binding> transactionBinding
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.objects = Objects.requireNonNull(objects, "objects");
        this.current = Objects.requireNonNull(current, "current");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.transactionBinding = Objects.requireNonNull(transactionBinding, "transactionBinding");
    }

    @Override
    public WarpMirrorResult apply(final WarpMirrorRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            final NativeBinding binding = current.get();
            final Capture capture = capture(binding, request);
            final WarpMirrorPairing.Result pairing = mirrorProposal(capture, request.direction());
            if (pairing == null) {
                return blockedResult(WarpMirrorBlockerCode.NO_PAIR,
                    "No source control point could be paired across the mirror axis.");
            }
            if (nearlyEqual(capture.rootOriginalPositions(), pairing.positions())) {
                return WarpMirrorResult.noChange();
            }
            final List<PreparedCompensation> compensations = request.preserveDescendants()
                ? prepareCompensations(capture)
                : List.of();
            return commit(capture, pairing, compensations);
        } catch (BlockedException blocked) {
            return WarpMirrorResult.blocked(List.of(blocked.blocker()));
        }
    }

    /** Throws typed blockers for a degenerate proposal, or returns the pairing result. */
    private WarpMirrorPairing.Result mirrorProposal(
        final Capture capture,
        final WarpMirrorDirection direction
    ) {
        final float[] positions = capture.rootOriginalPositions();
        final int pointColumns = capture.inputColumns() + 1;
        final int pointRows = capture.inputRows() + 1;
        if (pointColumns < 2 || pointRows < 2
            || positions.length != pointColumns * pointRows * 2) {
            throw blocked(WarpMirrorBlockerCode.DEGENERATE_GEOMETRY,
                "The Warp grid does not match its declared divisions.");
        }
        for (float value : positions) {
            if (!Float.isFinite(value)) {
                throw blocked(WarpMirrorBlockerCode.DEGENERATE_GEOMETRY,
                    "The Warp grid contains non-finite control points.");
            }
        }
        return WarpMirrorPairing.mirror(positions, pointColumns, pointRows, direction);
    }

    private Capture capture(
        final NativeBinding binding,
        final WarpMirrorRequest request
    ) {
        final List<EditorObjectReadAccess.NativeObjectRef> all = objects.nativeObjects(
            binding.identity(), binding.source(), binding.model()
        );
        final EditorObjectReadAccess.NativeObjectRef root = all.stream()
            .filter(value -> value.id().equals(request.target().value()))
            .findFirst()
            .orElseThrow(() -> blocked(WarpMirrorBlockerCode.TARGET_MISSING,
                "The selected Warp no longer exists."));
        if (!root.kind().equals("WARP")) {
            throw blocked(WarpMirrorBlockerCode.TARGET_MISSING,
                "The selected object is not a Warp deformer.");
        }
        if (root.locked()) {
            throw blocked(WarpMirrorBlockerCode.TARGET_LOCKED, "The selected Warp is locked.");
        }
        final Object rootForm = currentForm(root);
        requireStoredForm(root, rootForm);
        final boolean defaultLocked = defaultKeyformLocked(binding.source());
        requireSelectedDefaultUnlocked(root, rootForm, defaultLocked);

        final int rows = number(resolver.invoke("cubism.editor-model.warp-source.row", root.source()));
        final int columns = number(resolver.invoke("cubism.editor-model.warp-source.col", root.source()));
        final float[] rootPositions = positions(rootForm, "WARP");
        if (rootPositions.length != (columns + 1) * (rows + 1) * 2) {
            throw blocked(WarpMirrorBlockerCode.DEGENERATE_GEOMETRY,
                "The Warp grid does not match its declared divisions.");
        }

        final List<EditorObjectReadAccess.NativeObjectRef> descendants = descendants(all, root.id());
        ancestors(all, root);
        final ArrayList<EvaluatedState> evaluated = new ArrayList<>();
        final ArrayList<DirectCompensation> direct = new ArrayList<>();
        if (request.preserveDescendants()) {
            if (!list(resolver.invoke("cubism.editor-model.model-source.all-glues", binding.source()))
                .isEmpty()) {
                throw blocked(WarpMirrorBlockerCode.GLUE_PRESENT,
                    "Descendant preservation is unavailable while Glue objects are present.");
            }
        }
        for (EditorObjectReadAccess.NativeObjectRef descendant : descendants) {
            if (request.preserveDescendants() && descendant.locked()) {
                throw blocked(WarpMirrorBlockerCode.DESCENDANT_LOCKED,
                    "A required descendant is locked: " + descendant.id());
            }
            final Object liveCalculated;
            final EvaluatedState snapshot;
            try {
                liveCalculated = calculatedForm(descendant.instance());
                snapshot = new EvaluatedState(
                    descendant, evaluationState(descendant, liveCalculated));
            } catch (RuntimeException failure) {
                if (request.preserveDescendants()) {
                    throw failure;
                }
                LOGGER.log(System.Logger.Level.WARNING,
                    "Warp mirror follow observation unavailable: id=" + descendant.id()
                        + ", kind=" + descendant.kind() + ", cause=" + failure.getMessage());
                continue;
            }
            evaluated.add(snapshot);
            if (request.preserveDescendants()
                && Objects.equals(descendant.targetDeformerId(), root.id())) {
                final Object selected = currentForm(descendant);
                requireStoredForm(descendant, selected);
                requireSelectedDefaultUnlocked(descendant, selected, defaultLocked);
                direct.add(new DirectCompensation(
                    descendant,
                    FormState.read(resolver, descendant.kind(), liveCalculated),
                    selected,
                    FormState.read(resolver, descendant.kind(), selected),
                    snapshot.evaluation()
                ));
            }
        }
        requireDistinctSelectedForms(rootForm, direct);
        return new Capture(
            binding,
            root,
            rootForm,
            rootPositions,
            rows,
            columns,
            List.copyOf(descendants),
            List.copyOf(evaluated),
            List.copyOf(direct),
            writable(root, direct)
        );
    }

    /**
     * Pre-write half of descendant preservation. For ArtMesh/Warp children it
     * inverse-maps the captured canvas geometry into the parent's local lattice using
     * the parent's own {@code transformCanvasToLocal} — the exact inverse of the host
     * evaluation (which internally selects the quad or bezier grid implementation).
     * For Rotation children it records the direction-probe angle the host evaluation
     * applies at the stored origin, so the in-transaction solve can restore the exact
     * evaluated angle without a similarity approximation. The matching post-write
     * solve runs inside the transaction; storing only baselines here keeps every
     * checked input genuinely pre-write.
     */
    private List<PreparedCompensation> prepareCompensations(final Capture capture) {
        if (capture.directCompensations().isEmpty()) return List.of();
        final Object targetInstance = capture.root().instance();
        final ArrayList<PreparedCompensation> prepared = new ArrayList<>();
        for (DirectCompensation direct : capture.directCompensations()) {
            try {
                final float[] baseline = direct.object().kind().equals("ROTATION")
                    ? new float[] { rotationProbeDelta(targetInstance,
                        direct.originalSelectedState().values()[1],
                        direct.originalSelectedState().values()[2]) }
                    : inverseAndVerify(targetInstance, direct.oldEvaluation().values());
                prepared.add(new PreparedCompensation(direct, baseline));
            } catch (BlockedException blocked) {
                throw blocked;
            } catch (RuntimeException failure) {
                logPreflightFailure("representation/id=" + direct.object().id()
                    + "/kind=" + direct.object().kind(), failure);
                final StringBuilder frames = new StringBuilder();
                final StackTraceElement[] trace = failure.getStackTrace();
                for (int index = 0; index < Math.min(trace.length, 4); index++) {
                    frames.append(index == 0 ? "" : " <- ").append(trace[index]);
                }
                throw blocked(WarpMirrorBlockerCode.UNSOLVABLE_COMPENSATION, direct.object().id(),
                    "Descendant " + direct.object().id() + " (" + direct.object().kind()
                        + ") cannot exactly represent the preserved geometry: "
                        + failure.getClass().getName() + " at " + frames + ": "
                        + failure.getMessage());
            }
        }
        return List.copyOf(prepared);
    }

    private WarpMirrorResult commit(
        final Capture capture,
        final WarpMirrorPairing.Result pairing,
        final List<PreparedCompensation> compensations
    ) {
        final MutationState mutation = new MutationState();
        final EditorAuthoringTransactionCoordinator.Binding authoring;
        try {
            authoring = transactionBinding.get();
        } catch (RuntimeException unavailable) {
            throw blocked(WarpMirrorBlockerCode.UNAVAILABLE,
                "The Editor authoring binding is unavailable.");
        }
        if (!capture.binding().identity().equals(authoring.modelIdentity())
            || capture.binding().generation() != authoring.modelGeneration()) {
            throw blocked(WarpMirrorBlockerCode.STALE_TARGET,
                "The active Editor authoring binding changed.");
        }
        final AuthoringTransactionResult<MutationState> transaction = coordinator.execute(
            authoring,
            AuthoringTransactionOptions.of(LABEL),
            () -> {
                coordinator.mutate(
                    authoring, contribution(capture, pairing.positions(), compensations, mutation));
                return mutation;
            }
        );
        return switch (transaction.outcome()) {
            case COMMITTED -> WarpMirrorResult.applied(
                pairing.pairedCount(), mutation.compensatedCount);
            case NO_CHANGE -> WarpMirrorResult.noChange();
            case ROLLED_BACK -> WarpMirrorResult.blocked(List.of(new WarpMirrorBlocker(
                mutation.failureCode, mutation.failureReason)));
            case RECOVERY_FAILED -> WarpMirrorResult.recoveryFailed(
                "Mirror transaction failed and rollback could not be verified: "
                    + transaction.diagnosticId().orElse("unknown"));
            case REJECTED_STALE, REJECTED_SCOPE, UNAVAILABLE -> WarpMirrorResult.blocked(List.of(
                new WarpMirrorBlocker(WarpMirrorBlockerCode.STALE_TARGET,
                    "The authoring transaction was rejected: "
                        + transaction.diagnosticId().orElse("unknown"))));
        };
    }

    private EditorUndoContribution contribution(
        final Capture before,
        final float[] proposed,
        final List<PreparedCompensation> compensations,
        final MutationState state
    ) {
        return new EditorUndoContribution(
            "turboism.cubism.warp-mirror.write",
            before.binding().identity() + ":warp:" + before.root().id(),
            LABEL,
            (edit, label) -> admitUndo(edit, label, before.writableObjects()),
            () -> mutate(before, proposed, compensations, state),
            () -> state.applied,
            () -> restore(before),
            () -> restored(before),
            EnumSet.of(
                EditorRefreshRequirement.MODEL_INSTANCES,
                EditorRefreshRequirement.DEFORMER_PALETTE,
                EditorRefreshRequirement.CANVAS,
                EditorRefreshRequirement.MARK_DIRTY
            )
        );
    }

    private void mutate(
        final Capture before,
        final float[] proposed,
        final List<PreparedCompensation> compensations,
        final MutationState state
    ) {
        try {
            writePositions(before.rootForm(), proposed);
            updateInstances(before.binding().source());
            for (PreparedCompensation compensation : compensations) {
                final DirectCompensation direct = compensation.direct();
                final FormState solved = solveCompensation(before, compensation);
                solved.write(resolver, direct.selectedForm());
                final FormState actual = FormState.read(
                    resolver, direct.object().kind(), direct.selectedForm());
                if (!solved.same(actual)) {
                    throw new IllegalStateException("live descendant Form readback mismatch: expected="
                        + solved.diagnostic() + ", actual=" + actual.diagnostic());
                }
            }
            if (!compensations.isEmpty()) {
                updateInstances(before.binding().source());
                state.maximumResidual = residual(before);
                if (!Double.isFinite(state.maximumResidual)
                    || state.maximumResidual > MAX_RESIDUAL) {
                    throw new UnsolvableCompensationException(
                        "Descendant compensation residual exceeds tolerance: residual="
                            + state.maximumResidual
                            + (lastResidualDetails.isEmpty()
                                ? "" : " [" + lastResidualDetails + "]"));
                }
                state.compensatedCount = compensations.size();
            } else if (!before.evaluatedDescendants().isEmpty()) {
                observeDescendantFollow(before);
            }
            state.applied = samePositions(before.rootForm(), proposed);
            if (!state.applied) {
                throw new IllegalStateException("Warp Form readback did not match the proposal.");
            }
        } catch (UnsolvableCompensationException unsolvable) {
            state.failureCode = WarpMirrorBlockerCode.UNSOLVABLE_COMPENSATION;
            state.failureReason = unsolvable.getMessage();
            throw unsolvable;
        } catch (RuntimeException failure) {
            state.failureCode = WarpMirrorBlockerCode.WRITE_FAILED;
            state.failureReason = "A host write failed inside the mirror transaction: "
                + failure.getMessage();
            throw failure;
        }
    }

    /**
     * In-transaction half of descendant preservation. Runs after the parent grid write
     * and instance refresh, so {@code transformCanvasToLocal} on the parent already
     * reflects the mirrored lattice — the solve is the host evaluation's own inverse.
     * The candidate state is validated against a detached Form copy before the live
     * write, keeping a failed solve on the rollback path.
     */
    private FormState solveCompensation(
        final Capture before,
        final PreparedCompensation compensation
    ) {
        final DirectCompensation direct = compensation.direct();
        final boolean rotation = direct.object().kind().equals("ROTATION");
        final float[] local = rotation
            ? inverseAndVerify(before.root().instance(), new float[] {
                direct.oldEvaluation().values()[0], direct.oldEvaluation().values()[1] })
            : inverseAndVerify(before.root().instance(), direct.oldEvaluation().values());
        final FormState state = rotation
            ? rotationCompensation(direct, compensation.baseline(), local,
                rotationProbeDelta(before.root().instance(), local[0], local[1]))
            : FormState.fromEvaluation(direct.object().kind(), local, compensation.baseline(),
                direct.originalSelectedState());
        final Object detached = detachedCopy(direct.selectedForm());
        if (detached == null || detached == direct.selectedForm()) {
            throw new IllegalStateException("descendant Form copy aliases live state");
        }
        state.write(resolver, detached);
        final FormState readback = FormState.read(resolver, direct.object().kind(), detached);
        if (!state.same(readback)) {
            throw new IllegalStateException("detached descendant Form readback mismatch: expected="
                + state.diagnostic() + ", actual=" + readback.diagnostic());
        }
        return state;
    }

    /**
     * Exact Rotation-descendant compensation matching the host evaluation model:
     * the evaluated frame is {@code (warped origin, stored.angle + probeDelta,
     * stored.scale, stored.reflects)} — scale and reflection pass through verbatim,
     * so preservation only needs the inverse-mapped origin plus an angle correction
     * equal to the change in the direction-probe delta between the old and new
     * parent lattice. {@code baseline[0]} holds the pre-write probe delta.
     */
    private static FormState rotationCompensation(
        final DirectCompensation direct,
        final float[] baseline,
        final float[] localOrigin,
        final float probeDeltaNew
    ) {
        final FormState stored = direct.originalSelectedState();
        return new FormState("ROTATION", new float[] {
            stored.values()[0] + baseline[0] - probeDeltaNew,
            localOrigin[0], localOrigin[1], stored.values()[3]
        }, stored.reflectX(), stored.reflectY());
    }

    /**
     * Replicates the host's rotation direction probe under a Warp parent: the signed
     * angle in degrees from the local probe direction (0,-0.1) to the warped image
     * of that direction at {@code (originX, originY)}, sampled through the live
     * evaluation transform. Degenerate zero-length images retry on the opposite
     * side and at shrinking probe scales, mirroring the native fallback loop.
     */
    private float rotationProbeDelta(
        final Object warpInstance,
        final float originX,
        final float originY
    ) {
        float probeScale = 1.0f;
        for (int attempt = 0; attempt < 10; attempt++) {
            final float[] secant = probeSecant(warpInstance, originX, originY, probeScale, 1.0f);
            if (secant != null) {
                return probeAngleDegrees(secant);
            }
            final float[] negative = probeSecant(warpInstance, originX, originY, probeScale, -1.0f);
            if (negative != null) {
                return probeAngleDegrees(new float[] { -negative[0], -negative[1] });
            }
            probeScale *= 0.1f;
        }
        throw blocked(WarpMirrorBlockerCode.UNSOLVABLE_COMPENSATION,
            "The Rotation direction probe is degenerate under the parent Warp.");
    }

    private float[] probeSecant(
        final Object warpInstance,
        final float originX,
        final float originY,
        final float probeScale,
        final float side
    ) {
        final float[] input = new float[] {
            originX, originY,
            originX + side * probeScale * ROTATION_PROBE_DIR_X,
            originY + side * probeScale * ROTATION_PROBE_DIR_Y
        };
        final float[] canvas = new float[input.length];
        resolver.invoke("cubism.editor-model.deformer.transform-local-to-canvas",
            warpInstance, input, canvas,
            Integer.valueOf(input.length / 2), Integer.valueOf(0), Integer.valueOf(2));
        final float dx = canvas[2] - canvas[0];
        final float dy = canvas[3] - canvas[1];
        if (!Float.isFinite(dx) || !Float.isFinite(dy)) {
            throw blocked(WarpMirrorBlockerCode.UNSOLVABLE_COMPENSATION,
                "The Rotation direction probe produced a non-finite direction.");
        }
        return (dx == 0f && dy == 0f) ? null : new float[] { dx, dy };
    }

    /** Signed angle in degrees from the probe direction to its warped image. */
    private static float probeAngleDegrees(final float[] secant) {
        final double cross = (double) ROTATION_PROBE_DIR_X * secant[1]
            - (double) ROTATION_PROBE_DIR_Y * secant[0];
        final double dot = (double) ROTATION_PROBE_DIR_X * secant[0]
            + (double) ROTATION_PROBE_DIR_Y * secant[1];
        return (float) Math.toDegrees(Math.atan2(cross, dot));
    }

    private void restore(final Capture before) {
        writePositions(before.rootForm(), before.rootOriginalPositions());
        for (DirectCompensation child : before.directCompensations()) {
            child.originalSelectedState().write(resolver, child.selectedForm());
        }
        updateInstances(before.binding().source());
    }

    private boolean restored(final Capture before) {
        if (!samePositions(before.rootForm(), before.rootOriginalPositions())) return false;
        for (DirectCompensation child : before.directCompensations()) {
            if (!child.originalSelectedState().same(resolver, child.selectedForm())) return false;
        }
        return before.directCompensations().isEmpty()
            || residual(before) <= MAX_RESIDUAL;
    }

    private void admitUndo(
        final Object edit,
        final String label,
        final List<EditorObjectReadAccess.NativeObjectRef> writable
    ) {
        final Set<Object> admitted = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (EditorObjectReadAccess.NativeObjectRef object : writable) {
            if (!admitted.add(object.source())) continue;
            final Object handler = resolver.invoke(
                "cubism.editor-model.parameter-controllable-source.handler", object.source());
            final Object undo = resolver.invoke(
                "cubism.editor-model.parameter-controllable-handler.create-undo-for-keyform-edit",
                handler, label);
            final Object accepted = resolver.invoke(
                "cubism.editor-model.undo.add", edit, undo, Boolean.TRUE);
            if (!Boolean.TRUE.equals(accepted)) {
                throw new IllegalStateException("Cubism rejected a Warp mirror Form Undo snapshot.");
            }
        }
    }

    private Object currentForm(final EditorObjectReadAccess.NativeObjectRef object) {
        final Object form = resolver.invoke(
            object.kind().equals("ART_MESH")
                ? "cubism.editor-model.art-mesh.current-keyform"
                : "cubism.editor-model.deformer.current-keyform",
            object.instance());
        if (form == null) {
            throw blocked(WarpMirrorBlockerCode.UNAVAILABLE,
                "The current keyform of " + object.id() + " is unavailable.");
        }
        return form;
    }

    /**
     * Fail-closed stored-keyform check: the form currently being edited must be one of the
     * object's own stored forms. An interpolated/temporary edit context is rejected rather
     * than written or auto-keyed.
     */
    private void requireStoredForm(
        final EditorObjectReadAccess.NativeObjectRef object,
        final Object form
    ) {
        if (sourceForms(object).stream().noneMatch(stored -> stored == form)) {
            throw blocked(WarpMirrorBlockerCode.NON_STORED_KEYFORM,
                "The current form of " + object.id() + " is not a stored keyform "
                    + "(interpolated edit context).");
        }
    }

    private void requireSelectedDefaultUnlocked(
        final EditorObjectReadAccess.NativeObjectRef object,
        final Object selected,
        final boolean defaultLocked
    ) {
        if (defaultLocked && selected == resolver.invoke(
            "cubism.editor-model.parameter-controllable-source.default-key-form", object.source())) {
            throw blocked(WarpMirrorBlockerCode.TARGET_LOCKED,
                "The current Form of " + object.id() + " aliases the locked default keyform.");
        }
    }

    private void requireDistinctSelectedForms(
        final Object rootForm,
        final List<DirectCompensation> direct
    ) {
        final IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        if (seen.put(rootForm, Boolean.TRUE) != null) {
            throw blocked(WarpMirrorBlockerCode.UNAVAILABLE,
                "Writable objects share the same selected native Form.");
        }
        for (DirectCompensation compensation : direct) {
            if (seen.put(compensation.selectedForm(), Boolean.TRUE) != null) {
                throw blocked(WarpMirrorBlockerCode.UNAVAILABLE,
                    "Writable objects share the same selected native Form.");
            }
        }
    }

    private boolean defaultKeyformLocked(final Object modelSource) {
        final Object value = resolver.invoke(
            "cubism.editor-model.model-source.default-keyform-locked", modelSource);
        if (!(value instanceof Boolean locked)) {
            throw new IllegalStateException("Editor default-keyform lock state is unavailable.");
        }
        return locked;
    }

    private List<?> sourceForms(final EditorObjectReadAccess.NativeObjectRef object) {
        return list(resolver.invoke(switch (object.kind()) {
            case "ART_MESH" -> "cubism.editor-model.art-mesh-source.keyforms";
            case "WARP" -> "cubism.editor-model.warp-source.keyforms";
            case "ROTATION" -> "cubism.editor-model.rotation-source.keyforms";
            default -> throw new IllegalStateException("Unsupported Editor object kind");
        }, object.source()));
    }

    private Object calculatedForm(final Object instance) {
        final Object form = resolver.invoke(
            "cubism.editor-model.parameter-controllable.calculated-form", instance);
        if (form == null) throw new IllegalStateException("Calculated Form is unavailable.");
        return form;
    }

    private Evaluation evaluationState(
        final EditorObjectReadAccess.NativeObjectRef object,
        final Object calculated
    ) {
        if (object.kind().equals("ROTATION")) {
            return new Evaluation(rotationReferenceCanvas(object.instance()));
        }
        return new Evaluation(positions(calculated, object.kind()));
    }

    private float[] rotationReferenceCanvas(final Object rotation) {
        final float[] local = new float[] {0f, 0f, 100f, 0f, 0f, 100f};
        final float[] canvas = new float[local.length];
        resolver.invoke("cubism.editor-model.deformer.transform-local-to-canvas",
            rotation, local, canvas, local.length / 2, 0, 2);
        return canvas;
    }

    private double residual(final Capture before) {
        updateInstances(before.binding().source());
        double maximum = 0.0;
        final StringBuilder details = new StringBuilder();
        for (EvaluatedState value : before.evaluatedDescendants()) {
            final float[] expected = value.evaluation().values();
            final float[] after = evaluationState(
                value.object(), calculatedForm(value.object().instance())).values();
            if (expected.length != after.length) {
                LOGGER.log(System.Logger.Level.ERROR,
                    "Warp mirror native readback length mismatch: id=" + value.object().id()
                        + ", kind=" + value.object().kind());
                return Double.POSITIVE_INFINITY;
            }
            double objectResidual = 0.0;
            int worstIndex = -1;
            double worstDx = 0.0;
            double worstDy = 0.0;
            for (int index = 0; index < expected.length; index += 2) {
                final double dx = (double) expected[index] - after[index];
                final double dy = (double) expected[index + 1] - after[index + 1];
                final double distance = Math.hypot(dx, dy);
                if (distance > objectResidual) {
                    objectResidual = distance;
                    worstIndex = index / 2;
                    worstDx = dx;
                    worstDy = dy;
                }
            }
            maximum = Math.max(maximum, objectResidual);
            if (objectResidual > MAX_RESIDUAL) {
                LOGGER.log(System.Logger.Level.ERROR,
                    "Warp mirror native readback residual: id=" + value.object().id()
                        + ", kind=" + value.object().kind() + ", residual=" + objectResidual
                        + ", worstPoint=" + worstIndex
                        + ", dx=" + worstDx + ", dy=" + worstDy);
                if (details.length() < 400) {
                    if (details.length() > 0) details.append("; ");
                    details.append(value.object().kind()).append(':')
                        .append(value.object().id()).append('=')
                        .append(String.format(java.util.Locale.ROOT, "%.4f", objectResidual))
                        .append("@p").append(worstIndex)
                        .append(String.format(java.util.Locale.ROOT,
                            "(dx=%.4f,dy=%.4f)", worstDx, worstDy));
                }
            }
        }
        lastResidualDetails = details.toString();
        return maximum;
    }

    /**
     * No-preservation observation: re-evaluates every captured descendant after the
     * parent mirror and logs how far its canvas geometry moved. Movement is expected
     * here — preservation is off — so the measurement is evidence for the validation
     * probe and never fails the transaction.
     */
    private void observeDescendantFollow(final Capture before) {
        updateInstances(before.binding().source());
        double maximum = 0.0;
        int compared = 0;
        for (EvaluatedState value : before.evaluatedDescendants()) {
            final float[] expected = value.evaluation().values();
            final float[] after;
            try {
                after = evaluationState(
                    value.object(), calculatedForm(value.object().instance())).values();
            } catch (RuntimeException failure) {
                LOGGER.log(System.Logger.Level.WARNING,
                    "Warp mirror follow readback failed: id=" + value.object().id()
                        + ", kind=" + value.object().kind()
                        + ", cause=" + failure.getMessage());
                continue;
            }
            if (expected.length != after.length) {
                LOGGER.log(System.Logger.Level.WARNING,
                    "Warp mirror follow readback length mismatch: id=" + value.object().id()
                        + ", kind=" + value.object().kind());
                continue;
            }
            double objectDelta = 0.0;
            for (int index = 0; index < expected.length; index += 2) {
                objectDelta = Math.max(objectDelta, Math.hypot(
                    (double) after[index] - expected[index],
                    (double) after[index + 1] - expected[index + 1]));
            }
            compared++;
            maximum = Math.max(maximum, objectDelta);
            LOGGER.log(System.Logger.Level.INFO,
                "Warp mirror descendant follow: id=" + value.object().id()
                    + ", kind=" + value.object().kind()
                    + ", delta=" + objectDelta);
        }
        LOGGER.log(System.Logger.Level.INFO,
            "Warp mirror descendant follow summary: target=" + before.root().id()
                + ", compared=" + compared + ", maxDelta=" + maximum);
    }

    /**
     * Inverse-maps canvas positions into the parent Warp's local lattice through the
     * Warp instance's own {@code transformCanvasToLocal} — the exact inverse of the
     * evaluation pipeline (the host internally selects the quad {@code CGridTransform}
     * or the bezier {@code jp.noids} grid transform). The result is verified by a
     * forward {@code transformLocalToCanvas} round trip through the same live
     * evaluation, so an unreliable inverse is rejected rather than written.
     */
    private float[] inverseAndVerify(
        final Object targetInstance,
        final float[] canvas
    ) {
        final float[] input = canvas.clone();
        final float[] local = new float[input.length];
        resolver.invoke("cubism.editor-model.deformer.transform-canvas-to-local",
            targetInstance, input, local,
            Integer.valueOf(input.length / 2), Integer.valueOf(0), Integer.valueOf(2));
        for (float coordinate : local) {
            if (!Float.isFinite(coordinate)) {
                throw blocked(WarpMirrorBlockerCode.UNSOLVABLE_COMPENSATION,
                    "The native canvas-to-local transform produced a non-finite local point.");
            }
        }
        final float[] roundTrip = new float[input.length];
        resolver.invoke("cubism.editor-model.deformer.transform-local-to-canvas",
            targetInstance, local.clone(), roundTrip,
            Integer.valueOf(input.length / 2), Integer.valueOf(0), Integer.valueOf(2));
        for (int index = 0; index < input.length; index += 2) {
            if (Math.hypot(roundTrip[index] - input[index],
                roundTrip[index + 1] - input[index + 1]) > MAX_RESIDUAL) {
                throw blocked(WarpMirrorBlockerCode.UNSOLVABLE_COMPENSATION,
                    "The certified native Warp inverse exceeds the preservation tolerance.");
            }
        }
        return local;
    }

    private List<EditorObjectReadAccess.NativeObjectRef> ancestors(
        final List<EditorObjectReadAccess.NativeObjectRef> all,
        final EditorObjectReadAccess.NativeObjectRef root
    ) {
        final ArrayList<EditorObjectReadAccess.NativeObjectRef> result = new ArrayList<>();
        String targetId = root.targetDeformerId();
        while (targetId != null && !targetId.isBlank()) {
            final String expectedId = targetId;
            final EditorObjectReadAccess.NativeObjectRef ancestor = all.stream()
                .filter(value -> value.id().equals(expectedId))
                .findFirst()
                .orElseThrow(() -> blocked(WarpMirrorBlockerCode.UNAVAILABLE,
                    "The Warp parent-deformer context could not be resolved."));
            if (!ancestor.kind().equals("WARP") && !ancestor.kind().equals("ROTATION")) {
                throw blocked(WarpMirrorBlockerCode.UNAVAILABLE,
                    "The Warp parent context contains an unsupported object kind.");
            }
            if (result.contains(ancestor)) {
                throw blocked(WarpMirrorBlockerCode.UNAVAILABLE,
                    "The Warp parent-deformer context is cyclic.");
            }
            result.add(ancestor);
            targetId = ancestor.targetDeformerId();
        }
        return List.copyOf(result);
    }

    private static List<EditorObjectReadAccess.NativeObjectRef> descendants(
        final List<EditorObjectReadAccess.NativeObjectRef> all,
        final String rootId
    ) {
        final ArrayList<EditorObjectReadAccess.NativeObjectRef> result = new ArrayList<>();
        boolean changed;
        do {
            changed = false;
            for (EditorObjectReadAccess.NativeObjectRef object : all) {
                if (result.contains(object) || object.id().equals(rootId)) continue;
                final boolean child = Objects.equals(object.targetDeformerId(), rootId)
                    || result.stream().anyMatch(parent ->
                        Objects.equals(object.targetDeformerId(), parent.id()));
                if (child) {
                    result.add(object);
                    changed = true;
                }
            }
        } while (changed);
        return List.copyOf(result);
    }

    private static List<EditorObjectReadAccess.NativeObjectRef> writable(
        final EditorObjectReadAccess.NativeObjectRef root,
        final List<DirectCompensation> direct
    ) {
        final ArrayList<EditorObjectReadAccess.NativeObjectRef> values = new ArrayList<>();
        values.add(root);
        direct.forEach(value -> values.add(value.object()));
        return List.copyOf(values);
    }

    private float[] positions(final Object form, final String kind) {
        final String alias = kind.equals("ART_MESH")
            ? "cubism.editor-model.art-mesh-form.positions"
            : "cubism.editor-model.warp-form.positions";
        return ((float[]) resolver.invoke(alias, form)).clone();
    }

    private void writePositions(final Object form, final float[] positions) {
        resolver.invoke("cubism.editor-model.warp-form.set-positions", form, positions.clone());
    }

    private boolean samePositions(final Object form, final float[] expected) {
        return sameBits(positions(form, "WARP"), expected);
    }

    private void updateInstances(final Object source) {
        resolver.invoke("cubism.editor-model.model-source.update-instances", source);
    }

    private Object detachedCopy(final Object value) {
        return resolver.invokeStatic("cubism.editor-model.copy-helper.copy",
            value, null, Integer.valueOf(1), null);
    }

    private static boolean nearlyEqual(final float[] left, final float[] right) {
        if (left.length != right.length) return false;
        for (int index = 0; index < left.length; index++) {
            if (Math.abs(left[index] - right[index]) > ALREADY_SYNCED_EPSILON) return false;
        }
        return true;
    }

    private static boolean sameBits(final float[] left, final float[] right) {
        if (left.length != right.length) return false;
        for (int index = 0; index < left.length; index++) {
            if (Float.floatToRawIntBits(left[index]) != Float.floatToRawIntBits(right[index])) {
                return false;
            }
        }
        return true;
    }

    private static int number(final Object value) {
        return ((Number) value).intValue();
    }

    private static List<?> list(final Object value) {
        if (value instanceof List<?> list) return list;
        if (value instanceof Iterable<?> iterable) {
            final ArrayList<Object> result = new ArrayList<>();
            iterable.forEach(result::add);
            return result;
        }
        throw new IllegalStateException("Expected Editor list value.");
    }

    private static void logPreflightFailure(final String step, final RuntimeException failure) {
        LOGGER.log(System.Logger.Level.ERROR,
            "Warp mirror preservation preflight failed at internal step " + step, failure);
    }

    private static WarpMirrorResult blockedResult(
        final WarpMirrorBlockerCode code,
        final String reason
    ) {
        return WarpMirrorResult.blocked(List.of(new WarpMirrorBlocker(code, reason)));
    }

    private static BlockedException blocked(
        final WarpMirrorBlockerCode code,
        final String reason
    ) {
        return new BlockedException(new WarpMirrorBlocker(code, reason));
    }

    private static BlockedException blocked(
        final WarpMirrorBlockerCode code,
        final String target,
        final String reason
    ) {
        return new BlockedException(new WarpMirrorBlocker(code, target + ": " + reason));
    }

    /** Typed pre-write rejection mapped to {@link WarpMirrorResult#blocked}. */
    static final class BlockedException extends RuntimeException {
        private final WarpMirrorBlocker blocker;

        BlockedException(final WarpMirrorBlocker blocker) {
            super(Objects.requireNonNull(blocker, "blocker").reason());
            this.blocker = blocker;
        }

        WarpMirrorBlocker blocker() {
            return blocker;
        }
    }

    /** Marks a post-write preservation failure so rollback maps to UNSOLVABLE_COMPENSATION. */
    private static final class UnsolvableCompensationException extends IllegalStateException {
        UnsolvableCompensationException(final String message) {
            super(message);
        }
    }

    record NativeBinding(String identity, long generation, Object source, Object model) {
        NativeBinding {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(model, "model");
        }
    }

    private record Capture(
        NativeBinding binding,
        EditorObjectReadAccess.NativeObjectRef root,
        Object rootForm,
        float[] rootOriginalPositions,
        int inputRows,
        int inputColumns,
        List<EditorObjectReadAccess.NativeObjectRef> descendants,
        List<EvaluatedState> evaluatedDescendants,
        List<DirectCompensation> directCompensations,
        List<EditorObjectReadAccess.NativeObjectRef> writableObjects
    ) { }

    private record DirectCompensation(
        EditorObjectReadAccess.NativeObjectRef object,
        FormState oldCalculatedState,
        Object selectedForm,
        FormState originalSelectedState,
        Evaluation oldEvaluation
    ) { }

    /** Pre-write baseline local solve plus the direct descendant it belongs to. */
    private record PreparedCompensation(DirectCompensation direct, float[] baseline) {
        PreparedCompensation {
            baseline = baseline.clone();
        }
    }

    private record EvaluatedState(
        EditorObjectReadAccess.NativeObjectRef object,
        Evaluation evaluation
    ) { }

    private record Evaluation(float[] values) {
        Evaluation {
            values = values.clone();
        }
    }

    private static final class MutationState {
        boolean applied;
        int compensatedCount;
        double maximumResidual;
        WarpMirrorBlockerCode failureCode = WarpMirrorBlockerCode.WRITE_FAILED;
        String failureReason = "The mirror transaction was rolled back.";
    }

    /**
     * Writable state of one native Form: flat positions for ArtMesh/Warp, or the
     * similarity tuple (angle, origin, scale, reflections) for Rotation deformers.
     */
    private record FormState(String kind, float[] values, boolean reflectX, boolean reflectY) {
        FormState {
            values = values.clone();
        }

        static FormState read(final VerifiedMemberResolver resolver, final String kind, final Object form) {
            return switch (kind) {
                case "ART_MESH" -> new FormState(kind,
                    ((float[]) resolver.invoke("cubism.editor-model.art-mesh-form.positions", form)).clone(),
                    false, false);
                case "WARP" -> new FormState(kind,
                    ((float[]) resolver.invoke("cubism.editor-model.warp-form.positions", form)).clone(),
                    false, false);
                case "ROTATION" -> new FormState(kind, new float[] {
                    ((Number) resolver.invoke("cubism.editor-model.rotation-form.angle", form)).floatValue(),
                    ((Number) resolver.invoke("cubism.editor-model.rotation-form.origin-x", form)).floatValue(),
                    ((Number) resolver.invoke("cubism.editor-model.rotation-form.origin-y", form)).floatValue(),
                    ((Number) resolver.invoke("cubism.editor-model.rotation-form.scale", form)).floatValue()
                }, Boolean.TRUE.equals(resolver.invoke("cubism.editor-model.rotation-form.reflect-x", form)),
                    Boolean.TRUE.equals(resolver.invoke("cubism.editor-model.rotation-form.reflect-y", form)));
                default -> throw new IllegalStateException("Unsupported Form kind: " + kind);
            };
        }

        static FormState fromEvaluation(
            final String kind,
            final float[] evaluation,
            final float[] baseline,
            final FormState original
        ) {
            if (kind.equals("ART_MESH") || kind.equals("WARP")) {
                return new FormState(kind, evaluation, false, false);
            }
            throw new IllegalStateException("Unsupported descendant evaluation representation: " + kind);
        }

        void write(final VerifiedMemberResolver resolver, final Object form) {
            switch (kind) {
                case "ART_MESH" -> resolver.invoke(
                    "cubism.editor-model.art-mesh-form.set-positions", form, values.clone());
                case "WARP" -> resolver.invoke(
                    "cubism.editor-model.warp-form.set-positions", form, values.clone());
                case "ROTATION" -> {
                    resolver.invoke("cubism.editor-model.rotation-form.set-angle", form, values[0]);
                    resolver.invoke("cubism.editor-model.rotation-form.set-origin-x", form, values[1]);
                    resolver.invoke("cubism.editor-model.rotation-form.set-origin-y", form, values[2]);
                    resolver.invoke("cubism.editor-model.rotation-form.set-scale", form, values[3]);
                    resolver.invoke("cubism.editor-model.rotation-form.set-reflect-x", form, reflectX);
                    resolver.invoke("cubism.editor-model.rotation-form.set-reflect-y", form, reflectY);
                }
                default -> throw new IllegalStateException("Unsupported Form kind: " + kind);
            }
        }

        boolean same(final VerifiedMemberResolver resolver, final Object form) {
            return same(read(resolver, kind, form));
        }

        boolean same(final FormState other) {
            if (reflectX != other.reflectX || reflectY != other.reflectY
                || values.length != other.values.length) {
                return false;
            }
            if (!kind.equals("ROTATION")) {
                return sameBits(values, other.values);
            }
            // The host may normalize a written Rotation angle; ±360 wraps are
            // evaluation-equivalent, everything else must match bit-for-bit.
            double wrapped = (values[0] - other.values[0]) % 360.0;
            if (wrapped > 180.0) wrapped -= 360.0;
            if (wrapped < -180.0) wrapped += 360.0;
            if (Math.abs(wrapped) > 0.001) return false;
            for (int index = 1; index < values.length; index++) {
                if (Float.floatToRawIntBits(values[index])
                    != Float.floatToRawIntBits(other.values[index])) {
                    return false;
                }
            }
            return true;
        }

        String diagnostic() {
            return "kind=" + kind + ",values=" + java.util.Arrays.toString(values)
                + ",reflectX=" + reflectX + ",reflectY=" + reflectY;
        }
    }
}
