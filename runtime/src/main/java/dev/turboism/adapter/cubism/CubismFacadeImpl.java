package dev.turboism.adapter.cubism;

import dev.turboism.adapter.cubism.service.read.CubismReadPermissionGate;
import dev.turboism.adapter.cubism.lifecycle.ParameterLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.PartLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.EditorObjectLifecycleCoordinator;
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
import dev.turboism.sdk.cubism.model.ParameterGroup;
import dev.turboism.sdk.cubism.model.ParameterGroups;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.cubism.transaction.TransactionManager;
import dev.turboism.sdk.permission.CubismPermissionException;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

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
    private final PartLifecycleCoordinator partLifecycle;
    private final EditorObjectLifecycleCoordinator editorObjectLifecycle;
    private final BooleanSupplier activeScope;

    public CubismFacadeImpl(final HostSnapshotSource source, final CubismPermissionGate permissionGate) {
        this(
            source,
            permissionGate,
            new ImmutableSnapshotFactory(),
            unavailableTransactionManager(),
            unavailableModelAccess(),
            new ParameterLifecycleCoordinator(),
            new PartLifecycleCoordinator(),
            new EditorObjectLifecycleCoordinator(),
            () -> true
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
            new ParameterLifecycleCoordinator(),
            new PartLifecycleCoordinator(),
            new EditorObjectLifecycleCoordinator(),
            () -> true
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
            parameterLifecycle,
            new PartLifecycleCoordinator(),
            new EditorObjectLifecycleCoordinator(),
            () -> true
        );
    }

    public CubismFacadeImpl(
        final HostSnapshotSource source,
        final CubismPermissionGate permissionGate,
        final CubismModelAccess modelAccess,
        final ParameterLifecycleCoordinator parameterLifecycle,
        final PartLifecycleCoordinator partLifecycle
    ) {
        this(
            source,
            permissionGate,
            modelAccess,
            parameterLifecycle,
            partLifecycle,
            new EditorObjectLifecycleCoordinator(),
            () -> true
        );
    }

    public CubismFacadeImpl(
        final HostSnapshotSource source,
        final CubismPermissionGate permissionGate,
        final CubismModelAccess modelAccess,
        final ParameterLifecycleCoordinator parameterLifecycle,
        final PartLifecycleCoordinator partLifecycle,
        final BooleanSupplier activeScope
    ) {
        this(
            source,
            permissionGate,
            modelAccess,
            parameterLifecycle,
            partLifecycle,
            new EditorObjectLifecycleCoordinator(),
            activeScope
        );
    }

    public CubismFacadeImpl(
        final HostSnapshotSource source,
        final CubismPermissionGate permissionGate,
        final CubismModelAccess modelAccess,
        final ParameterLifecycleCoordinator parameterLifecycle,
        final PartLifecycleCoordinator partLifecycle,
        final EditorObjectLifecycleCoordinator editorObjectLifecycle,
        final BooleanSupplier activeScope
    ) {
        this(
            source,
            permissionGate,
            new ImmutableSnapshotFactory(),
            unavailableTransactionManager(),
            modelAccess,
            parameterLifecycle,
            partLifecycle,
            editorObjectLifecycle,
            activeScope
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
            new ParameterLifecycleCoordinator(),
            new PartLifecycleCoordinator(),
            new EditorObjectLifecycleCoordinator(),
            () -> true
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
            new ParameterLifecycleCoordinator(),
            new PartLifecycleCoordinator(),
            new EditorObjectLifecycleCoordinator(),
            () -> true
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
        this(
            source,
            permissionGate,
            snapshotFactory,
            transactionManager,
            modelAccess,
            parameterLifecycle,
            new PartLifecycleCoordinator(),
            new EditorObjectLifecycleCoordinator(),
            () -> true
        );
    }

    CubismFacadeImpl(
        final HostSnapshotSource source,
        final CubismPermissionGate permissionGate,
        final ImmutableSnapshotFactory snapshotFactory,
        final TransactionManager transactionManager,
        final CubismModelAccess modelAccess,
        final ParameterLifecycleCoordinator parameterLifecycle,
        final PartLifecycleCoordinator partLifecycle
    ) {
        this(
            source,
            permissionGate,
            snapshotFactory,
            transactionManager,
            modelAccess,
            parameterLifecycle,
            partLifecycle,
            new EditorObjectLifecycleCoordinator(),
            () -> true
        );
    }

    CubismFacadeImpl(
        final HostSnapshotSource source,
        final CubismPermissionGate permissionGate,
        final ImmutableSnapshotFactory snapshotFactory,
        final TransactionManager transactionManager,
        final CubismModelAccess modelAccess,
        final ParameterLifecycleCoordinator parameterLifecycle,
        final PartLifecycleCoordinator partLifecycle,
        final BooleanSupplier activeScope
    ) {
        this(
            source,
            permissionGate,
            snapshotFactory,
            transactionManager,
            modelAccess,
            parameterLifecycle,
            partLifecycle,
            new EditorObjectLifecycleCoordinator(),
            activeScope
        );
    }

    CubismFacadeImpl(
        final HostSnapshotSource source,
        final CubismPermissionGate permissionGate,
        final ImmutableSnapshotFactory snapshotFactory,
        final TransactionManager transactionManager,
        final CubismModelAccess modelAccess,
        final ParameterLifecycleCoordinator parameterLifecycle,
        final PartLifecycleCoordinator partLifecycle,
        final EditorObjectLifecycleCoordinator editorObjectLifecycle,
        final BooleanSupplier activeScope
    ) {
        this.source = Objects.requireNonNull(source, "source");
        this.permissionGate = Objects.requireNonNull(permissionGate, "permissionGate");
        this.snapshotFactory = Objects.requireNonNull(snapshotFactory, "snapshotFactory");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager");
        this.parameterLifecycle = Objects.requireNonNull(parameterLifecycle, "parameterLifecycle");
        this.partLifecycle = Objects.requireNonNull(partLifecycle, "partLifecycle");
        this.editorObjectLifecycle = Objects.requireNonNull(editorObjectLifecycle, "editorObjectLifecycle");
        this.activeScope = Objects.requireNonNull(activeScope, "activeScope");
        this.modelAccess = permissionCheckedModelAccess(
            Objects.requireNonNull(modelAccess, "modelAccess")
        );
    }

    @Override
    public CubismRuntimeSnapshot runtime() {
        requireActiveScope();
        return runtimeSnapshot();
    }

    public SnapshotWithVersion runtimeWithVersion() {
        requireActiveScope();
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
        requireActiveScope();
        permissionGate.require(PROJECT_READ_PERMISSION, "activeProject");
        return source.activeProject().map(snapshotFactory::project);
    }

    @Override
    public Optional<DocumentSnapshot> activeDocument() {
        requireActiveScope();
        permissionGate.require(MODEL_READ_PERMISSION, "activeDocument");
        return source.activeDocument().map(snapshotFactory::document);
    }

    @Override
    public Optional<ModelSnapshot> activeModel() {
        requireActiveScope();
        permissionGate.require(MODEL_READ_PERMISSION, "activeModel");
        return source.activeModel().map(snapshotFactory::model);
    }

    @Override
    public boolean isHostPresent() {
        requireActiveScope();
        return source.isHostPresent();
    }

    @Override
    public CubismModelAccess model() {
        requireActiveScope();
        permissionGate.require(MODEL_READ_PERMISSION, "model");
        return modelAccess;
    }

    @Override
    public TransactionManager transactionManager() {
        requireActiveScope();
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
        @Override public void setDefaultKeyformLocked(final boolean locked) {
            permissionGate.require(MODEL_WRITE_PERMISSION, "model.setDefaultKeyformLocked");
            delegate.setDefaultKeyformLocked(locked);
        }
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
        @Override public ParameterGroups parameterGroups() {
            final ParameterGroups groups = delegate.parameterGroups();
            return new ParameterGroups() {
                @Override public List<ParameterGroup> all() {
                    return groups.all().stream()
                        .map(value -> (ParameterGroup) new PermissionCheckedParameterGroup(value))
                        .toList();
                }
                @Override public ParameterGroup root() {
                    return new PermissionCheckedParameterGroup(groups.root());
                }
                @Override public ParameterGroup find(
                    final dev.turboism.sdk.cubism.id.ParameterGroupId id
                ) {
                    return new PermissionCheckedParameterGroup(groups.find(id));
                }
            };
        }
        @Override public dev.turboism.sdk.cubism.model.ParameterBindingOperations parameterBindings(
            final dev.turboism.sdk.cubism.id.ParameterId parameterId
        ) {
            final dev.turboism.sdk.cubism.model.ParameterBindingOperations operations =
                delegate.parameterBindings(parameterId);
            return new dev.turboism.sdk.cubism.model.ParameterBindingOperations() {
                private void write(final String operation, final Runnable mutation) {
                    permissionGate.require(MODEL_WRITE_PERMISSION, operation);
                    mutation.run();
                }
                @Override public void bind(
                    final dev.turboism.sdk.cubism.model.ParameterBindingTarget target,
                    final List<dev.turboism.sdk.cubism.model.ParameterBindingPoint> points
                ) {
                    write("model.parameterBindings.bind", () -> operations.bind(target, points));
                }
                @Override public void createPoint(
                    final dev.turboism.sdk.cubism.model.ParameterBindingTarget target,
                    final dev.turboism.sdk.cubism.model.ParameterBindingPoint point
                ) {
                    write("model.parameterBindings.createPoint", () -> operations.createPoint(target, point));
                }
                @Override public void movePoint(
                    final dev.turboism.sdk.cubism.model.ParameterBindingTarget target,
                    final dev.turboism.sdk.cubism.id.ParameterBindingPointId pointId,
                    final float value
                ) {
                    write("model.parameterBindings.movePoint", () -> operations.movePoint(target, pointId, value));
                }
                @Override public void deletePoint(
                    final dev.turboism.sdk.cubism.model.ParameterBindingTarget target,
                    final dev.turboism.sdk.cubism.id.ParameterBindingPointId pointId
                ) {
                    write("model.parameterBindings.deletePoint", () -> operations.deletePoint(target, pointId));
                }
                @Override public void unbind(
                    final dev.turboism.sdk.cubism.model.ParameterBindingTarget target
                ) {
                    write("model.parameterBindings.unbind", () -> operations.unbind(target));
                }
            };
        }
        @Override public dev.turboism.sdk.cubism.model.ParameterBindingBatchOperations parameterBindingBatch() {
            final dev.turboism.sdk.cubism.model.ParameterBindingBatchOperations operations =
                delegate.parameterBindingBatch();
            return new dev.turboism.sdk.cubism.model.ParameterBindingBatchOperations() {
                @Override public void invert(
                    final List<dev.turboism.sdk.cubism.model.ParameterBindingTarget> targets
                ) {
                    permissionGate.require(MODEL_WRITE_PERMISSION, "model.parameterBindingBatch.invert");
                    operations.invert(targets);
                }
                @Override public void transfer(
                    final dev.turboism.sdk.cubism.model.ParameterBindingTransferPlan plan
                ) {
                    permissionGate.require(MODEL_WRITE_PERMISSION, "model.parameterBindingBatch.transfer");
                    operations.transfer(plan);
                }
            };
        }
        @Override public dev.turboism.sdk.cubism.model.Parts parts() {
            final dev.turboism.sdk.cubism.model.Parts parts = delegate.parts();
            return new dev.turboism.sdk.cubism.model.Parts() {
                @Override public List<dev.turboism.sdk.cubism.model.Part> all() {
                    return parts.all().stream()
                        .map(value -> (dev.turboism.sdk.cubism.model.Part) new PermissionCheckedPart(value))
                        .toList();
                }
                @Override public dev.turboism.sdk.cubism.model.Part find(
                    final dev.turboism.sdk.cubism.model.PartId id
                ) {
                    return new PermissionCheckedPart(parts.find(id));
                }
            };
        }
        @Override public dev.turboism.sdk.cubism.model.Drawables drawables() {
            final dev.turboism.sdk.cubism.model.Drawables values = delegate.drawables();
            return new dev.turboism.sdk.cubism.model.Drawables() {
                @Override public List<dev.turboism.sdk.cubism.model.Drawable> all() {
                    return values.all().stream()
                        .map(value -> (dev.turboism.sdk.cubism.model.Drawable)
                            new PermissionCheckedDrawable(value))
                        .toList();
                }
                @Override public dev.turboism.sdk.cubism.model.Drawable find(
                    final dev.turboism.sdk.cubism.id.ArtMeshId id
                ) {
                    return new PermissionCheckedDrawable(values.find(id));
                }
            };
        }
        @Override public dev.turboism.sdk.cubism.model.Deformers deformers() {
            final dev.turboism.sdk.cubism.model.Deformers values = delegate.deformers();
            return new dev.turboism.sdk.cubism.model.Deformers() {
                @Override public List<dev.turboism.sdk.cubism.model.Deformer> all() {
                    return values.all().stream()
                        .map(PermissionCheckedModel.this::wrapDeformer)
                        .toList();
                }
                @Override public dev.turboism.sdk.cubism.model.Deformer find(
                    final dev.turboism.sdk.cubism.id.DeformerId id
                ) {
                    return wrapDeformer(values.find(id));
                }
            };
        }
        private dev.turboism.sdk.cubism.model.Deformer wrapDeformer(
            final dev.turboism.sdk.cubism.model.Deformer value
        ) {
            if (value instanceof dev.turboism.sdk.cubism.model.WarpDeformer warp) {
                return new PermissionCheckedWarpDeformer(warp);
            }
            if (value instanceof dev.turboism.sdk.cubism.model.RotationDeformer rotation) {
                return new PermissionCheckedRotationDeformer(rotation);
            }
            return new PermissionCheckedDeformer(value);
        }
        @Override public dev.turboism.sdk.cubism.model.WarpDeformers warpDeformers() {
            final dev.turboism.sdk.cubism.model.WarpDeformers values = delegate.warpDeformers();
            return new dev.turboism.sdk.cubism.model.WarpDeformers() {
                @Override public List<dev.turboism.sdk.cubism.model.WarpDeformer> all() {
                    return values.all().stream()
                        .map(value -> (dev.turboism.sdk.cubism.model.WarpDeformer)
                            new PermissionCheckedWarpDeformer(value))
                        .toList();
                }
                @Override public dev.turboism.sdk.cubism.model.WarpDeformer find(
                    final dev.turboism.sdk.cubism.id.DeformerId id
                ) {
                    return new PermissionCheckedWarpDeformer(values.find(id));
                }
            };
        }
        @Override public dev.turboism.sdk.cubism.model.RotationDeformers rotationDeformers() {
            final dev.turboism.sdk.cubism.model.RotationDeformers values =
                delegate.rotationDeformers();
            return new dev.turboism.sdk.cubism.model.RotationDeformers() {
                @Override public List<dev.turboism.sdk.cubism.model.RotationDeformer> all() {
                    return values.all().stream()
                        .map(value -> (dev.turboism.sdk.cubism.model.RotationDeformer)
                            new PermissionCheckedRotationDeformer(value))
                        .toList();
                }
                @Override public dev.turboism.sdk.cubism.model.RotationDeformer find(
                    final dev.turboism.sdk.cubism.id.DeformerId id
                ) {
                    return new PermissionCheckedRotationDeformer(values.find(id));
                }
            };
        }
        @Override public dev.turboism.sdk.cubism.model.Glues glues() { return delegate.glues(); }
        @Override public void update() { delegate.update(); }
    }

    private final class PermissionCheckedDrawable
        implements dev.turboism.sdk.cubism.model.Drawable {
        private final dev.turboism.sdk.cubism.model.Drawable delegate;
        private PermissionCheckedDrawable(final dev.turboism.sdk.cubism.model.Drawable delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }
        @Override public dev.turboism.sdk.cubism.id.ArtMeshId id() { return delegate.id(); }
        @Override public String name() { return delegate.name(); }
        @Override public boolean visible() { return delegate.visible(); }
        @Override public void setVisible(final boolean visible) {
            requireModelWrite("artMesh.setVisible");
            editorObjectLifecycle.drawable().setVisible(this, visible, delegate::setVisible);
        }
        @Override public boolean locked() { return delegate.locked(); }
        @Override public void setLocked(final boolean locked) {
            requireModelWrite("artMesh.setLocked");
            editorObjectLifecycle.drawable().setLocked(this, locked, delegate::setLocked);
        }
        @Override public boolean visibleInHierarchy() { return delegate.visibleInHierarchy(); }
        @Override public boolean lockedInHierarchy() { return delegate.lockedInHierarchy(); }
        @Override public byte constantFlag() { return delegate.constantFlag(); }
        @Override public byte dynamicFlag() { return delegate.dynamicFlag(); }
        @Override public dev.turboism.sdk.cubism.model.BlendMode blendMode() {
            return delegate.blendMode();
        }
        @Override public int textureIndex() { return delegate.textureIndex(); }
        @Override public int drawOrder() { return delegate.drawOrder(); }
        @Override public int renderOrder() { return delegate.renderOrder(); }
        @Override public float getOpacity() { return delegate.getOpacity(); }
        @Override public void setOpacity(final float opacity) {
            requireModelWrite("artMesh.setOpacity");
            editorObjectLifecycle.drawable().setOpacity(this, opacity, delegate::setOpacity);
        }
        @Override public dev.turboism.sdk.cubism.model.ArtMeshGeometry geometry() {
            return delegate.geometry();
        }
        @Override public void replaceGeometry(
            final dev.turboism.sdk.cubism.model.ArtMeshGeometry geometry
        ) {
            requireModelWrite("artMesh.replaceGeometry");
            editorObjectLifecycle.drawable().replaceGeometry(this, geometry, delegate::replaceGeometry);
        }
        @Override public dev.turboism.sdk.cubism.model.IntSequence masks() {
            return delegate.masks();
        }
        @Override public boolean invertedMask() { return delegate.invertedMask(); }
        @Override public boolean culling() { return delegate.culling(); }
        @Override public String userData() { return delegate.userData(); }
        @Override public dev.turboism.sdk.cubism.model.FloatSequence vertexPositions() {
            return delegate.vertexPositions();
        }
        @Override public dev.turboism.sdk.cubism.model.FloatSequence vertexUvs() {
            return delegate.vertexUvs();
        }
        @Override public dev.turboism.sdk.cubism.model.IntSequence indices() {
            return delegate.indices();
        }
        @Override public dev.turboism.sdk.cubism.model.Color multiplyColor() {
            return delegate.multiplyColor();
        }
        @Override public dev.turboism.sdk.cubism.model.Color screenColor() {
            return delegate.screenColor();
        }
        @Override public int parentPartIndex() { return delegate.parentPartIndex(); }
        @Override public int parentDeformerIndex() { return delegate.parentDeformerIndex(); }
        @Override public dev.turboism.sdk.cubism.model.IntSequence parameters() {
            return delegate.parameters();
        }
        @Override public List<dev.turboism.sdk.cubism.model.ParameterBinding> getParameterBindings() {
            return delegate.getParameterBindings();
        }
    }

    private class PermissionCheckedDeformer implements dev.turboism.sdk.cubism.model.Deformer {
        protected final dev.turboism.sdk.cubism.model.Deformer delegate;
        private PermissionCheckedDeformer(final dev.turboism.sdk.cubism.model.Deformer delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }
        @Override public dev.turboism.sdk.cubism.id.DeformerId id() { return delegate.id(); }
        @Override public String name() { return delegate.name(); }
        @Override public boolean visible() { return delegate.visible(); }
        @Override public void setVisible(final boolean visible) {
            requireModelWrite("deformer.setVisible");
            editorObjectLifecycle.deformer().setVisible(this, visible, delegate::setVisible);
        }
        @Override public boolean locked() { return delegate.locked(); }
        @Override public void setLocked(final boolean locked) {
            requireModelWrite("deformer.setLocked");
            editorObjectLifecycle.deformer().setLocked(this, locked, delegate::setLocked);
        }
        @Override public boolean visibleInHierarchy() { return delegate.visibleInHierarchy(); }
        @Override public boolean lockedInHierarchy() { return delegate.lockedInHierarchy(); }
        @Override public float getOpacity() { return delegate.getOpacity(); }
        @Override public void setOpacity(final float opacity) {
            requireModelWrite("deformer.setOpacity");
            editorObjectLifecycle.deformer().setOpacity(this, opacity, delegate::setOpacity);
        }
        @Override public dev.turboism.sdk.cubism.model.Color multiplyColor() {
            return delegate.multiplyColor();
        }
        @Override public dev.turboism.sdk.cubism.model.Color screenColor() {
            return delegate.screenColor();
        }
        @Override public int parentPartIndex() { return delegate.parentPartIndex(); }
        @Override public int parentDeformerIndex() { return delegate.parentDeformerIndex(); }
        @Override public dev.turboism.sdk.cubism.model.IntSequence parameters() {
            return delegate.parameters();
        }
        @Override public List<dev.turboism.sdk.cubism.model.ParameterBinding> getParameterBindings() {
            return delegate.getParameterBindings();
        }
    }

    private final class PermissionCheckedWarpDeformer extends PermissionCheckedDeformer
        implements dev.turboism.sdk.cubism.model.WarpDeformer {
        private final dev.turboism.sdk.cubism.model.WarpDeformer warp;
        private PermissionCheckedWarpDeformer(
            final dev.turboism.sdk.cubism.model.WarpDeformer delegate
        ) {
            super(delegate);
            this.warp = delegate;
        }
        @Override public dev.turboism.sdk.cubism.model.WarpGrid grid() { return warp.grid(); }
        @Override public void replaceGrid(final dev.turboism.sdk.cubism.model.WarpGrid grid) {
            requireModelWrite("warpDeformer.replaceGrid");
            editorObjectLifecycle.deformer().replaceGrid(this, grid, warp::replaceGrid);
        }
    }

    private final class PermissionCheckedRotationDeformer extends PermissionCheckedDeformer
        implements dev.turboism.sdk.cubism.model.RotationDeformer {
        private final dev.turboism.sdk.cubism.model.RotationDeformer rotation;
        private PermissionCheckedRotationDeformer(
            final dev.turboism.sdk.cubism.model.RotationDeformer delegate
        ) {
            super(delegate);
            this.rotation = delegate;
        }
        @Override public float baseAngle() { return rotation.baseAngle(); }
        @Override public void setBaseAngle(final float angle) {
            requireModelWrite("rotationDeformer.setBaseAngle");
            editorObjectLifecycle.deformer().setBaseAngle(this, angle, rotation::setBaseAngle);
        }
        @Override public dev.turboism.sdk.cubism.model.RotationDeformerForm form() {
            return rotation.form();
        }
        @Override public void replaceForm(
            final dev.turboism.sdk.cubism.model.RotationDeformerForm form
        ) {
            requireModelWrite("rotationDeformer.replaceForm");
            editorObjectLifecycle.deformer().replaceForm(this, form, rotation::replaceForm);
        }
    }

    private void requireModelWrite(final String operation) {
        requireActiveScope();
        permissionGate.require(MODEL_WRITE_PERMISSION, operation);
    }

    private void requireActiveScope() {
        if (!activeScope.getAsBoolean()) {
            throw new IllegalStateException("Cubism service reference is stale because the owning plugin is disabled.");
        }
    }

    private final class PermissionCheckedParameterGroup implements ParameterGroup {
        private final ParameterGroup delegate;

        private PermissionCheckedParameterGroup(final ParameterGroup delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override public dev.turboism.sdk.cubism.id.ParameterGroupId id() {
            return delegate.id();
        }
        @Override public java.util.Optional<String> name() { return delegate.name(); }

        @Override public java.util.Optional<dev.turboism.sdk.cubism.id.ParameterGroupId> parentId() {
            return delegate.parentId();
        }
        @Override public List<dev.turboism.sdk.cubism.id.ParameterGroupId> childGroupIds() {
            return delegate.childGroupIds();
        }
        @Override public List<dev.turboism.sdk.cubism.id.ParameterId> parameterIds() {
            return delegate.parameterIds();
        }
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
        @Override public List<dev.turboism.sdk.cubism.model.ParameterBinding> getParameterBindings() {
            return delegate.getParameterBindings();
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

    private final class PermissionCheckedPart implements dev.turboism.sdk.cubism.model.Part {
        private final dev.turboism.sdk.cubism.model.Part delegate;

        private PermissionCheckedPart(final dev.turboism.sdk.cubism.model.Part delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override public dev.turboism.sdk.cubism.model.PartId id() { return delegate.id(); }
        @Override public String name() { return delegate.name(); }
        @Override public void setName(final String name) {
            permissionGate.require(MODEL_WRITE_PERMISSION, "part.setName");
            partLifecycle.setName(this, name, delegate::setName);
        }
        @Override public float getOpacity() { return delegate.getOpacity(); }
        @Override public int parentIndex() { return delegate.parentIndex(); }
        @Override public void setOpacity(final float opacity) {
            permissionGate.require(MODEL_WRITE_PERMISSION, "part.setOpacity");
            partLifecycle.setOpacity(this, opacity, delegate::setOpacity);
        }
    }
}
