package dev.turboism.adapter.cubism;

import dev.turboism.adapter.cubism.service.read.CubismReadPermissionGate;
import dev.turboism.adapter.cubism.lifecycle.ParameterLifecycleCoordinator;
import dev.turboism.adapter.cubism.write.HostWriteAdapter;
import dev.turboism.core.diagnostics.PluginWorkBudgetEvent;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.adapter.cubism.write.RuntimeTransactionManager;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.CubismRuntimeSnapshot;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.SelectionSnapshot;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterGroups;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.cubism.transaction.TransactionManager;
import dev.turboism.sdk.permission.CubismPermissionException;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

public final class CubismFacadeImpl implements CubismFacade {

    public static final String PROJECT_READ_PERMISSION = "turboism.cubism.project.read";
    public static final String MODEL_READ_PERMISSION = "turboism.cubism.model.read";
    public static final String MODEL_WRITE_PERMISSION = "turboism.cubism.model.write";
    public static final String MESH_READ_PERMISSION = "turboism.cubism.mesh.read";

    private static final HostSnapshotSource.HostSelection EMPTY_SELECTION = new HostSnapshotSource.HostSelection(
        List.of(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty()
    );

    private final HostSnapshotSource source;
    private final CubismPermissionGate permissionGate;
    private final ImmutableSnapshotFactory snapshotFactory;
    private final TransactionManager transactionManager;
    private final CubismModelAccess modelAccess;
    private final ParameterLifecycleCoordinator parameterLifecycle;

    public CubismFacadeImpl(final HostSnapshotSource source, final CubismPermissionGate permissionGate) {
        this(
            source,
            permissionGate,
            new ImmutableSnapshotFactory(),
            unavailableTransactionManager(),
            unavailableModelAccess(),
            new ParameterLifecycleCoordinator()
        );
    }

    public CubismFacadeImpl(
        final HostSnapshotSource source,
        final CubismPermissionGate permissionGate,
        final CubismModelAccess modelAccess
    ) {
        this(
            source,
            permissionGate,
            new ImmutableSnapshotFactory(),
            unavailableTransactionManager(),
            modelAccess,
            new ParameterLifecycleCoordinator()
        );
    }

    public CubismFacadeImpl(
        final HostSnapshotSource source,
        final CubismPermissionGate permissionGate,
        final CubismModelAccess modelAccess,
        final ParameterLifecycleCoordinator parameterLifecycle
    ) {
        this(
            source,
            permissionGate,
            new ImmutableSnapshotFactory(),
            unavailableTransactionManager(),
            modelAccess,
            parameterLifecycle
        );
    }

    public CubismFacadeImpl(
        final HostSnapshotSource source,
        final CubismPermissionGate permissionGate,
        final HostWriteAdapter writeAdapter
    ) {
        this(source, permissionGate, writeAdapter, defaultScheduler());
    }

    public CubismFacadeImpl(
        final HostSnapshotSource source,
        final CubismPermissionGate permissionGate,
        final HostWriteAdapter writeAdapter,
        final RuntimeScheduler runtimeScheduler
    ) {
        this(source, permissionGate, new ImmutableSnapshotFactory(), new RuntimeTransactionManager(
            writeAdapter,
            PermissionChecker.from(permissionGate),
            runtimeScheduler
        ));
    }

    CubismFacadeImpl(
        final HostSnapshotSource source,
        final CubismPermissionGate permissionGate,
        final ImmutableSnapshotFactory snapshotFactory,
        final TransactionManager transactionManager
    ) {
        this(
            source,
            permissionGate,
            snapshotFactory,
            transactionManager,
            unavailableModelAccess(),
            new ParameterLifecycleCoordinator()
        );
    }

    CubismFacadeImpl(
        final HostSnapshotSource source,
        final CubismPermissionGate permissionGate,
        final ImmutableSnapshotFactory snapshotFactory,
        final TransactionManager transactionManager,
        final CubismModelAccess modelAccess
    ) {
        this(
            source,
            permissionGate,
            snapshotFactory,
            transactionManager,
            modelAccess,
            new ParameterLifecycleCoordinator()
        );
    }

    CubismFacadeImpl(
        final HostSnapshotSource source,
        final CubismPermissionGate permissionGate,
        final ImmutableSnapshotFactory snapshotFactory,
        final TransactionManager transactionManager,
        final CubismModelAccess modelAccess,
        final ParameterLifecycleCoordinator parameterLifecycle
    ) {
        this.source = Objects.requireNonNull(source, "source");
        this.permissionGate = Objects.requireNonNull(permissionGate, "permissionGate");
        this.snapshotFactory = Objects.requireNonNull(snapshotFactory, "snapshotFactory");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager");
        this.parameterLifecycle = Objects.requireNonNull(parameterLifecycle, "parameterLifecycle");
        this.modelAccess = permissionCheckedModelAccess(
            Objects.requireNonNull(modelAccess, "modelAccess")
        );
    }

    @Override
    public CubismRuntimeSnapshot runtime() {
        return runtimeSnapshot();
    }

    public SnapshotWithVersion runtimeWithVersion() {
        final CubismRuntimeSnapshot snapshot = runtimeSnapshot();
        return new SnapshotWithVersion(snapshot, source.invalidationToken());
    }

    /** Returns the original audit-capable gate for capability-aware read services. */
    public CubismReadPermissionGate readPermissionGate() {
        return permissionGate::require;
    }

    private CubismRuntimeSnapshot runtimeSnapshot() {
        final Optional<HostSnapshotSource.HostProject> project = runtimeProjectSnapshot();
        final Optional<HostSnapshotSource.HostDocument> document = source.activeDocument();
        final Optional<HostSnapshotSource.HostModel> model = source.activeModel();
        final HostSnapshotSource.HostSelection selection = source.selection();
        if (document.isPresent() || model.isPresent() || hasSelection(selection)) {
            permissionGate.require(MODEL_READ_PERMISSION, "runtime");
        }
        return snapshotFactory.runtime(project, document, model, selection);
    }

    @Override
    public Optional<ProjectSnapshot> activeProject() {
        permissionGate.require(PROJECT_READ_PERMISSION, "activeProject");
        return source.activeProject().map(snapshotFactory::project);
    }

    @Override
    public Optional<DocumentSnapshot> activeDocument() {
        permissionGate.require(MODEL_READ_PERMISSION, "activeDocument");
        return source.activeDocument().map(snapshotFactory::document);
    }

    @Override
    public Optional<ModelSnapshot> activeModel() {
        permissionGate.require(MODEL_READ_PERMISSION, "activeModel");
        return source.activeModel().map(snapshotFactory::model);
    }

    @Override
    public boolean isHostPresent() {
        return source.isHostPresent();
    }

    @Override
    public CubismModelAccess model() {
        permissionGate.require(MODEL_READ_PERMISSION, "model");
        return modelAccess;
    }

    @Override
    public TransactionManager transactionManager() {
        return transactionManager;
    }

    private Optional<HostSnapshotSource.HostProject> runtimeProjectSnapshot() {
        final Optional<HostSnapshotSource.HostProject> project = source.activeProject();
        if (project.isEmpty()) {
            return Optional.empty();
        }
        try {
            permissionGate.require(PROJECT_READ_PERMISSION, "runtime");
            return project;
        } catch (CubismPermissionException ignored) {
            // runtime() redacts only the project portion on project-read denial so model-read plugins can still inspect model state.
            return Optional.empty();
        }
    }

    private boolean hasSelection(final HostSnapshotSource.HostSelection selection) {
        return !selection.selectedObjectIds().isEmpty()
            || selection.activeParameterId().isPresent()
            || selection.activeArtMeshId().isPresent()
            || selection.activeDeformerId().isPresent();
    }

    public static CubismRuntimeSnapshot emptyRuntimeSnapshot() {
        return new CubismRuntimeSnapshot(
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            new SelectionSnapshot(
                EMPTY_SELECTION.selectedObjectIds(),
                EMPTY_SELECTION.activeParameterId(),
                EMPTY_SELECTION.activeArtMeshId(),
                EMPTY_SELECTION.activeDeformerId()
            ),
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );
    }

    private static TransactionManager unavailableTransactionManager() {
        return (ctx, docId) -> {
            throw new UnsupportedOperationException("transaction manager is not available");
        };
    }

    private static CubismModelAccess unavailableModelAccess() {
        return () -> {
            throw new UnsupportedOperationException(
                "Unified Cubism model access is unavailable"
            );
        };
    }

    private static RuntimeScheduler defaultScheduler() {
        final Consumer<PluginWorkBudgetEvent> diagnostics = ignored -> {
        };
        return new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(1, 16, diagnostics, Clock.systemUTC()),
            SidecarDispatcher.noop(),
            diagnostics
        );
    }

    private CubismModelAccess permissionCheckedModelAccess(final CubismModelAccess delegate) {
        return () -> new PermissionCheckedModel(delegate.active());
    }

    private final class PermissionCheckedModel implements CubismModel {
        private final CubismModel delegate;

        private PermissionCheckedModel(final CubismModel delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override public dev.turboism.sdk.cubism.id.ModelId id() { return delegate.id(); }
        @Override public boolean defaultKeyformLocked() { return delegate.defaultKeyformLocked(); }
        @Override public dev.turboism.sdk.cubism.model.Canvas canvas() { return delegate.canvas(); }
        @Override public Parameters parameters() {
            final Parameters parameters = delegate.parameters();
            return new Parameters() {
                @Override public List<Parameter> all() {
                    return parameters.all().stream()
                        .map(value -> (Parameter) new PermissionCheckedParameter(value))
                        .toList();
                }
                @Override public Parameter find(final dev.turboism.sdk.cubism.id.ParameterId id) {
                    return new PermissionCheckedParameter(parameters.find(id));
                }
            };
        }
        @Override public ParameterGroups parameterGroups() { return delegate.parameterGroups(); }
        @Override public dev.turboism.sdk.cubism.model.Parts parts() { return delegate.parts(); }
        @Override public dev.turboism.sdk.cubism.model.Drawables drawables() { return delegate.drawables(); }
        @Override public dev.turboism.sdk.cubism.model.Deformers deformers() { return delegate.deformers(); }
        @Override public dev.turboism.sdk.cubism.model.Glues glues() { return delegate.glues(); }
        @Override public void update() { delegate.update(); }
    }

    private final class PermissionCheckedParameter implements Parameter {
        private final Parameter delegate;

        private PermissionCheckedParameter(final Parameter delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override public dev.turboism.sdk.cubism.id.ParameterId id() { return delegate.id(); }
        @Override public java.util.Optional<String> name() { return delegate.name(); }
        @Override public dev.turboism.sdk.cubism.model.ParameterType type() { return delegate.type(); }
        @Override public java.util.Optional<Boolean> repeat() { return delegate.repeat(); }
        @Override public java.util.Optional<Boolean> combined() { return delegate.combined(); }
        @Override public java.util.Optional<dev.turboism.sdk.cubism.id.ParameterId> combinedWith() {
            return delegate.combinedWith();
        }
        @Override public void combineWith(
            final dev.turboism.sdk.cubism.id.ParameterId partnerId
        ) {
            permissionGate.require(MODEL_WRITE_PERMISSION, "parameter.combineWith");
            delegate.combineWith(partnerId);
        }
        @Override public void uncombine() {
            permissionGate.require(MODEL_WRITE_PERMISSION, "parameter.uncombine");
            delegate.uncombine();
        }
        @Override public float getValue() { return delegate.getValue(); }
        @Override public float getMinimumValue() { return delegate.getMinimumValue(); }
        @Override public float getMaximumValue() { return delegate.getMaximumValue(); }
        @Override public float getDefaultValue() { return delegate.getDefaultValue(); }
        @Override public void resetToDefault() {
            permissionGate.require(MODEL_WRITE_PERMISSION, "parameter.resetToDefault");
            parameterLifecycle.setValue(this, delegate.getDefaultValue(), delegate::setValue);
        }
        @Override public void setValue(final float value) {
            permissionGate.require(MODEL_WRITE_PERMISSION, "parameter.setValue");
            parameterLifecycle.setValue(this, value, delegate::setValue);
        }
        @Override public void updateDefinition(
            final dev.turboism.sdk.cubism.model.ParameterDefinition definition
        ) {
            permissionGate.require(MODEL_WRITE_PERMISSION, "parameter.updateDefinition");
            delegate.updateDefinition(definition);
        }
    }
}
