package dev.turboism.adapter.host;

import dev.turboism.adapter.cubism.NativeLabelColorAuthoring;
import dev.turboism.adapter.cubism.NativeLabelColorTarget;
import dev.turboism.adapter.cubism.edit.RuntimeEditSessionProvider;
import dev.turboism.adapter.cubism.editor.transaction.RuntimeAuthoringTransactionProvider;
import dev.turboism.adapter.cubism.model.ModelObjectProviderUnavailableException;
import dev.turboism.adapter.cubism.model.RuntimeModelObjectCreateProvider;
import dev.turboism.adapter.cubism.warp.RuntimeWarpMirrorProvider;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.mirror.WarpMirrorBlocker;
import dev.turboism.sdk.cubism.mirror.WarpMirrorBlockerCode;
import dev.turboism.sdk.cubism.mirror.WarpMirrorRequest;
import dev.turboism.sdk.cubism.mirror.WarpMirrorResult;
import dev.turboism.sdk.cubism.mirror.WarpMirrorService;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.model.Deformer;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.ModelObjectCreateRequest;
import dev.turboism.sdk.cubism.model.ModelObjectReference;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionResult;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionService;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionWork;
import dev.turboism.sdk.ui.appearance.NativeLabelColor;
import dev.turboism.sdk.ui.appearance.NativeLabelColorState;
import dev.turboism.sdk.ui.appearance.model.DeformerAppearance;
import dev.turboism.sdk.ui.appearance.model.DrawableAppearance;
import dev.turboism.sdk.ui.appearance.model.ParameterAppearance;
import dev.turboism.sdk.ui.appearance.model.ParameterGroupAppearance;
import dev.turboism.sdk.ui.appearance.model.PartAppearance;
import java.util.Objects;
import java.util.function.Function;

/** Stable plugin-facing model access whose delegate follows one HostSession connection. */
final class DynamicCubismModelAccess
        implements CubismModelAccess,
                NativeLabelColorAuthoring,
                RuntimeModelObjectCreateProvider,
                RuntimeAuthoringTransactionProvider,
                RuntimeEditSessionProvider,
                RuntimeWarpMirrorProvider {

    private final Object callGate = new Object();
    private CubismModelAccess current = UnavailableCubismModelAccess.INSTANCE;
    private boolean acceptingCalls;
    private long generation;
    private int inFlight;
    private dev.turboism.ui.appearance.control.RuntimeModelAppearanceAccess appearanceAccess;

    void attachAppearanceAccess(
            final dev.turboism.ui.appearance.control.RuntimeModelAppearanceAccess appearanceAccess) {
        synchronized (callGate) {
            if (this.appearanceAccess != null || acceptingCalls || generation != 0) {
                throw new IllegalStateException("Model appearance access is already bound.");
            }
            this.appearanceAccess = Objects.requireNonNull(appearanceAccess, "appearanceAccess");
        }
    }

    long generation() {
        synchronized (callGate) {
            return generation;
        }
    }

    long modelGeneration() {
        synchronized (callGate) {
            if (current instanceof DynamicCubismModelAccess nested && nested != this) {
                return nested.generation();
            }
            return generation;
        }
    }

    private static long modelGeneration(final CubismModelAccess modelAccess, final long fallback) {
        return modelAccess instanceof DynamicCubismModelAccess nested ? nested.generation() : fallback;
    }

    /**
     * Live Editor object selection for the session snapshot seam. An inactive or
     * unauthorized connection reports the honest empty selection; a wired read whose
     * live-document read fails propagates instead of being masked.
     */
    dev.turboism.adapter.cubism.HostSnapshotSource.HostSelection currentHostSelection() {
        return withActiveLeaseOrFallback(dev.turboism.adapter.cubism.HostSnapshotSource.HostSelection::empty, lease -> {
            if ("selection-brush".equals(System.getProperty("turboism.meshEditValidation.mode"))) {
                dev.turboism.runtime.log.RuntimeDiagnostics.info(
                        "SelectionRead",
                        "generation=" + lease.generation() + " delegate="
                                + lease.modelAccess().getClass().getName()
                                + " readerAuthorized="
                                + (lease.modelAccess()
                                                instanceof
                                                dev.turboism.adapter.cubism.editor.EditorBackedCubismModelAccess editor
                                        && editor.selectionReadAuthorized()));
            }
            if (lease.modelAccess()
                            instanceof dev.turboism.adapter.cubism.editor.EditorBackedCubismModelAccess editorBacked
                    && editorBacked.selectionReadAuthorized()) {
                return editorBacked.readHostSelection();
            }
            return dev.turboism.adapter.cubism.HostSnapshotSource.HostSelection.empty();
        });
    }

    @Override
    public AuthoringTransactionService authoringTransactions(final String pluginId) {
        final String owner = Objects.requireNonNull(pluginId, "pluginId").strip();
        if (owner.isEmpty()) {
            throw new IllegalArgumentException("pluginId must not be blank");
        }
        return new AuthoringTransactionService() {
            @Override
            public <T> AuthoringTransactionResult<T> execute(
                    final AuthoringTransactionOptions options, final AuthoringTransactionWork<T> work) {
                final AuthoringTransactionOptions checkedOptions = Objects.requireNonNull(options, "options");
                final AuthoringTransactionWork<T> checkedWork = Objects.requireNonNull(work, "work");
                return withActiveLeaseOrFallback(
                        () -> AuthoringTransactionResult.unavailable("cubism.authoring.transactions.host-unavailable"),
                        lease -> {
                            if (!(lease.modelAccess() instanceof RuntimeAuthoringTransactionProvider provider)) {
                                return AuthoringTransactionResult.unavailable(
                                        "cubism.authoring.transactions.provider-unavailable");
                            }
                            return provider.authoringTransactions(owner).execute(checkedOptions, checkedWork);
                        });
            }
        };
    }

    @Override
    public dev.turboism.sdk.cubism.edit.EditSessionService editSessions(
            final String pluginId,
            final java.util.function.Supplier<java.util.Optional<dev.turboism.sdk.cubism.id.DocumentId>>
                    activeDocumentId) {
        final String owner = Objects.requireNonNull(pluginId, "pluginId").strip();
        if (owner.isEmpty()) {
            throw new IllegalArgumentException("pluginId must not be blank");
        }
        final java.util.function.Supplier<java.util.Optional<dev.turboism.sdk.cubism.id.DocumentId>> checkedDocument =
                Objects.requireNonNull(activeDocumentId, "activeDocumentId");
        return new dev.turboism.sdk.cubism.edit.EditSessionService() {
            @Override
            public boolean isEditApproved(final dev.turboism.sdk.plugin.PluginContext context)
                    throws dev.turboism.sdk.cubism.edit.EditSessionException {
                Objects.requireNonNull(context, "context");
                return withActiveLeaseOrThrow(
                        unavailable -> new dev.turboism.sdk.cubism.edit.EditUnavailableException(
                                "cubism.edit.unavailable", "The Cubism edit surface is unavailable"),
                        lease -> {
                            if (!(lease.modelAccess() instanceof RuntimeEditSessionProvider provider)) {
                                throw new dev.turboism.sdk.cubism.edit.EditUnavailableException(
                                        "cubism.edit.unavailable", "Editor edit sessions are unavailable on this host");
                            }
                            return provider.editSessions(owner, checkedDocument).isEditApproved(context);
                        });
            }

            @Override
            public dev.turboism.sdk.cubism.edit.EditSession open(
                    final dev.turboism.sdk.plugin.PluginContext context,
                    final dev.turboism.sdk.cubism.id.DocumentId document,
                    final dev.turboism.sdk.cubism.edit.EditSessionOptions options)
                    throws dev.turboism.sdk.cubism.edit.EditSessionException {
                Objects.requireNonNull(context, "context");
                Objects.requireNonNull(document, "document");
                Objects.requireNonNull(options, "options");
                return withActiveLeaseOrThrow(
                        unavailable -> new dev.turboism.sdk.cubism.edit.EditUnavailableException(
                                "cubism.edit.unavailable", "The Cubism edit surface is unavailable"),
                        lease -> {
                            if (!(lease.modelAccess() instanceof RuntimeEditSessionProvider provider)) {
                                throw new dev.turboism.sdk.cubism.edit.EditUnavailableException(
                                        "cubism.edit.unavailable", "Editor edit sessions are unavailable on this host");
                            }
                            return provider.editSessions(owner, checkedDocument).open(context, document, options);
                        });
            }
        };
    }

    @Override
    public WarpMirrorService warpMirrorService(final String pluginId) {
        final String owner = Objects.requireNonNull(pluginId, "pluginId").strip();
        if (owner.isEmpty()) {
            throw new IllegalArgumentException("pluginId must not be blank");
        }
        return request -> {
            final WarpMirrorRequest checked = Objects.requireNonNull(request, "request");
            return withActiveLeaseOrFallback(
                    () -> WarpMirrorResult.blocked(java.util.List.of(new WarpMirrorBlocker(
                            WarpMirrorBlockerCode.UNAVAILABLE, "The Editor host session is unavailable."))),
                    lease -> {
                        if (!(lease.modelAccess() instanceof RuntimeWarpMirrorProvider provider)) {
                            return WarpMirrorResult.blocked(java.util.List.of(new WarpMirrorBlocker(
                                    WarpMirrorBlockerCode.UNAVAILABLE,
                                    "The Warp mirror provider is unavailable on this host.")));
                        }
                        return provider.warpMirrorService(owner).apply(checked);
                    });
        };
    }

    @Override
    public boolean isAvailable() {
        synchronized (callGate) {
            return current.isAvailable();
        }
    }

    @Override
    public CubismModel active() {
        return withActiveLease(lease -> {
            final CubismModel model = Objects.requireNonNull(lease.modelAccess().active(), "active model");
            return new SessionModel(
                    this,
                    lease.generation(),
                    modelGeneration(lease.modelAccess(), lease.generation()),
                    Objects.requireNonNull(model.id(), "active model id"),
                    model);
        });
    }

    @Override
    public void requireCreateSupported(final ModelObjectCreateRequest request) {
        withActiveLeaseVoid(lease -> createProvider(lease).requireCreateSupported(request));
    }

    @Override
    public ModelObjectReference createModelObject(
            final CubismModel activeModel, final ModelObjectCreateRequest request) {
        if (!(activeModel instanceof SessionModel sessionModel)) {
            throw staleFailure();
        }
        final AccessLease lease = acquireLease(sessionModel.generation());
        try {
            return createProvider(lease).createModelObject(sessionModel.delegate(), request);
        } finally {
            release(lease);
        }
    }

    private static RuntimeModelObjectCreateProvider createProvider(final AccessLease lease) {
        if (lease.modelAccess() instanceof RuntimeModelObjectCreateProvider provider) {
            return provider;
        }
        throw new ModelObjectProviderUnavailableException(
                "Model-object creation provider is unavailable for the active host session");
    }

    void connect(final CubismModelAccess modelAccess) {
        final CubismModelAccess next = Objects.requireNonNull(modelAccess, "modelAccess");
        final boolean interrupted;
        synchronized (callGate) {
            acceptingCalls = false;
            interrupted = awaitNoInFlight();
            current = next;
            generation++;
            acceptingCalls = true;
        }
        restoreInterrupt(interrupted);
    }

    /**
     * Forwards a best-effort borrowed-model release to the connected access when it is
     * Editor-backed; no-op otherwise. Called on successful project-file close completion.
     */
    void releaseUnboundBorrowedModel() {
        final CubismModelAccess delegate;
        synchronized (callGate) {
            delegate = current;
        }
        if (delegate instanceof dev.turboism.adapter.cubism.BorrowedModelRelease release) {
            release.releaseUnboundBorrowedModel();
        }
    }

    void deactivate() {
        final boolean interrupted;
        synchronized (callGate) {
            acceptingCalls = false;
            interrupted = awaitNoInFlight();
            current = UnavailableCubismModelAccess.INSTANCE;
            generation++;
        }
        restoreInterrupt(interrupted);
    }

    private AccessLease acquireActiveLease() {
        synchronized (callGate) {
            if (!acceptingCalls) {
                throw new IllegalStateException("No verified active Cubism Core model is available.");
            }
            inFlight++;
            return new AccessLease(current, generation);
        }
    }

    private AccessLease acquireLease(final long expectedGeneration) {
        synchronized (callGate) {
            if (!acceptingCalls || generation != expectedGeneration) {
                throw staleFailure();
            }
            inFlight++;
            return new AccessLease(current, generation);
        }
    }

    private void release(final AccessLease lease) {
        synchronized (callGate) {
            inFlight--;
            if (inFlight == 0) {
                callGate.notifyAll();
            }
        }
    }

    private boolean awaitNoInFlight() {
        boolean interrupted = false;
        while (inFlight != 0) {
            try {
                callGate.wait();
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        return interrupted;
    }

    private static void restoreInterrupt(final boolean interrupted) {
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    static IllegalStateException staleFailure() {
        return new IllegalStateException("Cubism model reference is stale for the active host session.");
    }

    <T> T current(final long expectedGeneration, final Function<CubismModel, T> operation, final CubismModel model) {
        return guarded(expectedGeneration, () -> operation.apply(model));
    }

    Part unwrapPart(final long expectedGeneration, final Part value) {
        if (value == null) return null;
        if (!(value instanceof SessionPart wrapped) || wrapped.generation != expectedGeneration) {
            throw staleFailure();
        }
        return wrapped.delegate;
    }

    Drawable unwrapDrawable(final long expectedGeneration, final Drawable value) {
        if (!(value instanceof SessionDrawable wrapped) || wrapped.generation != expectedGeneration) {
            throw staleFailure();
        }
        return wrapped.delegate;
    }

    Deformer unwrapDeformer(final long expectedGeneration, final Deformer value) {
        if (value instanceof SessionDeformer wrapped && wrapped.generation == expectedGeneration) {
            return wrapped.delegate;
        }
        if (value instanceof SessionWarpDeformer wrapped && wrapped.generation == expectedGeneration) {
            return wrapped.delegate;
        }
        if (value instanceof SessionRotationDeformer wrapped && wrapped.generation == expectedGeneration) {
            return wrapped.delegate;
        }
        throw staleFailure();
    }

    private dev.turboism.ui.appearance.control.RuntimeModelAppearanceAccess appearanceAccess() {
        synchronized (callGate) {
            return appearanceAccess;
        }
    }

    PartAppearance appearancePart(final ModelId modelId, final PartId partId, final long modelGeneration) {
        final dev.turboism.ui.appearance.control.RuntimeModelAppearanceAccess access = appearanceAccess();
        return access == null
                ? PartAppearance.unavailable()
                : access.part(modelId.value(), partId.value(), modelGeneration);
    }

    DeformerAppearance appearanceDeformer(
            final ModelId modelId, final DeformerId deformerId, final long modelGeneration) {
        final dev.turboism.ui.appearance.control.RuntimeModelAppearanceAccess access = appearanceAccess();
        return access == null
                ? DeformerAppearance.unavailable()
                : access.deformer(modelId.value(), deformerId.value(), modelGeneration);
    }

    DrawableAppearance appearanceDrawable(
            final ModelId modelId, final ArtMeshId drawableId, final long modelGeneration) {
        final dev.turboism.ui.appearance.control.RuntimeModelAppearanceAccess access = appearanceAccess();
        return access == null
                ? DrawableAppearance.unavailable()
                : access.drawable(modelId.value(), drawableId.value(), modelGeneration);
    }

    ParameterAppearance appearanceParameter(
            final ModelId modelId,
            final dev.turboism.sdk.cubism.id.ParameterId parameterId,
            final long modelGeneration) {
        final dev.turboism.ui.appearance.control.RuntimeModelAppearanceAccess access = appearanceAccess();
        return access == null
                ? ParameterAppearance.unavailable()
                : access.parameter(modelId.value(), parameterId.value(), modelGeneration);
    }

    ParameterGroupAppearance appearanceParameterGroup(
            final ModelId modelId,
            final dev.turboism.sdk.cubism.id.ParameterGroupId groupId,
            final long modelGeneration) {
        final dev.turboism.ui.appearance.control.RuntimeModelAppearanceAccess access = appearanceAccess();
        return access == null
                ? ParameterGroupAppearance.unavailable()
                : access.parameterGroup(modelId.value(), groupId.value(), modelGeneration);
    }

    <T> T guarded(final long expectedGeneration, final java.util.function.Supplier<T> call) {
        final AccessLease lease = acquireLease(expectedGeneration);
        try {
            return call.get();
        } finally {
            release(lease);
        }
    }

    void guardedVoid(final long expectedGeneration, final Runnable call) {
        final AccessLease lease = acquireLease(expectedGeneration);
        try {
            call.run();
        } finally {
            release(lease);
        }
    }

    @FunctionalInterface
    private interface LeaseAction<T, E extends Throwable> {
        T apply(AccessLease lease) throws E;
    }

    private <T> T withActiveLease(final Function<AccessLease, T> action) {
        final AccessLease lease = acquireActiveLease();
        try {
            return action.apply(lease);
        } finally {
            release(lease);
        }
    }

    private void withActiveLeaseVoid(final java.util.function.Consumer<AccessLease> action) {
        final AccessLease lease = acquireActiveLease();
        try {
            action.accept(lease);
        } finally {
            release(lease);
        }
    }

    private <T> T withActiveLeaseOrFallback(
            final java.util.function.Supplier<T> fallbackWhenUnavailable, final Function<AccessLease, T> action) {
        final AccessLease lease;
        try {
            lease = acquireActiveLease();
        } catch (IllegalStateException unavailable) {
            return fallbackWhenUnavailable.get();
        }
        try {
            return action.apply(lease);
        } finally {
            release(lease);
        }
    }

    private <T, E extends Throwable> T withActiveLeaseOrThrow(
            final Function<IllegalStateException, E> exceptionMapper, final LeaseAction<T, E> action) throws E {
        final AccessLease lease;
        try {
            lease = acquireActiveLease();
        } catch (IllegalStateException unavailable) {
            throw exceptionMapper.apply(unavailable);
        }
        try {
            return action.apply(lease);
        } finally {
            release(lease);
        }
    }

    @Override
    public NativeLabelColorState readNativeLabelColor(final NativeLabelColorTarget target) {
        return withActiveLease(lease -> labelAuthoring(lease).readNativeLabelColor(target));
    }

    @Override
    public void setNativeLabelColor(final NativeLabelColorTarget target, final NativeLabelColor color) {
        withActiveLeaseVoid(lease -> labelAuthoring(lease).setNativeLabelColor(target, color));
    }

    private static NativeLabelColorAuthoring labelAuthoring(final AccessLease lease) {
        if (!(lease.modelAccess() instanceof NativeLabelColorAuthoring authoring)) {
            throw new UnsupportedOperationException(
                    "Native label-color authoring is unavailable for the active host session.");
        }
        return authoring;
    }

    private record AccessLease(CubismModelAccess modelAccess, long generation) {}
}
