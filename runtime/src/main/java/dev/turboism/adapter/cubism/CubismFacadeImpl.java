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
    private final dev.turboism.sdk.cubism.core.CoreRuntimeInfo coreRuntime;
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
        final CubismModelAccess modelAccess,
        final dev.turboism.sdk.cubism.core.CoreRuntimeInfo coreRuntime,
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
            coreRuntime,
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

    public CubismFacadeImpl(
        final HostSnapshotSource source,
        final CubismPermissionGate permissionGate,
        final dev.turboism.adapter.cubism.core.RuntimeCoreModelBackend coreBackend
    ) {
        this(
            source,
            permissionGate,
            new ImmutableSnapshotFactory(),
            unavailableTransactionManager(),
            coreBackend.modelAccess(),
            coreBackend.coreRuntimeInfo(),
            new ParameterLifecycleCoordinator(),
            new PartLifecycleCoordinator(),
            new EditorObjectLifecycleCoordinator(),
            () -> true
        );
    }

    CubismFacadeImpl(
        final HostSnapshotSource source,
        final CubismPermissionGate permissionGate,
        final dev.turboism.sdk.cubism.core.CoreRuntimeInfo coreRuntime
    ) {
        this(
            source,
            permissionGate,
            new ImmutableSnapshotFactory(),
            unavailableTransactionManager(),
            unavailableModelAccess(),
            coreRuntime,
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
        final ParameterLifecycleCoordinator parameterLifecycle,
        final PartLifecycleCoordinator partLifecycle,
        final EditorObjectLifecycleCoordinator editorObjectLifecycle,
        final BooleanSupplier activeScope
    ) {
        this(
            source,
            permissionGate,
            snapshotFactory,
            transactionManager,
            modelAccess,
            unavailableCoreRuntime(),
            parameterLifecycle,
            partLifecycle,
            editorObjectLifecycle,
            activeScope
        );
    }

    CubismFacadeImpl(
        final HostSnapshotSource source,
        final CubismPermissionGate permissionGate,
        final ImmutableSnapshotFactory snapshotFactory,
        final TransactionManager transactionManager,
        final CubismModelAccess modelAccess,
        final dev.turboism.sdk.cubism.core.CoreRuntimeInfo coreRuntime,
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
        this.coreRuntime = Objects.requireNonNull(coreRuntime, "coreRuntime");
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
    public dev.turboism.sdk.cubism.core.CoreRuntimeInfo coreRuntime() {
        requireModelRead("coreRuntime");
        return permissionCheckedCoreRuntime(coreRuntime);
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

    private static dev.turboism.sdk.cubism.core.CoreRuntimeInfo unavailableCoreRuntime() {
        return new dev.turboism.sdk.cubism.core.CoreRuntimeInfo() {
            @Override public dev.turboism.sdk.cubism.core.CoreVersion version() {
                throw new UnsupportedOperationException("Core runtime metadata is unavailable.");
            }
            @Override public dev.turboism.sdk.cubism.core.CoreCapabilities capabilities() {
                throw new UnsupportedOperationException("Core runtime capabilities are unavailable.");
            }
            @Override public dev.turboism.sdk.cubism.core.MocInspector mocInspector() {
                throw new UnsupportedOperationException("Core MOC inspection is unavailable.");
            }
        };
    }

    private dev.turboism.sdk.cubism.core.CoreRuntimeInfo permissionCheckedCoreRuntime(
        final dev.turboism.sdk.cubism.core.CoreRuntimeInfo delegate
    ) {
        Objects.requireNonNull(delegate, "delegate");
        return new dev.turboism.sdk.cubism.core.CoreRuntimeInfo() {
            @Override public dev.turboism.sdk.cubism.core.CoreVersion version() {
                requireModelRead("coreRuntime.version");
                return delegate.version();
            }
            @Override public dev.turboism.sdk.cubism.core.CoreCapabilities capabilities() {
                requireModelRead("coreRuntime.capabilities");
                return delegate.capabilities();
            }
            @Override public dev.turboism.sdk.cubism.core.MocInspector mocInspector() {
                requireModelRead("coreRuntime.mocInspector");
                final dev.turboism.sdk.cubism.core.MocInspector inspector = delegate.mocInspector();
                return new dev.turboism.sdk.cubism.core.MocInspector() {
                    @Override public dev.turboism.sdk.cubism.core.MocVersion latestVersion() {
                        requireModelRead("coreRuntime.mocInspector.latestVersion");
                        return inspector.latestVersion();
                    }
                    @Override public dev.turboism.sdk.cubism.core.MocInfo inspect(
                        final dev.turboism.sdk.cubism.core.MocData data
                    ) {
                        requireModelRead("coreRuntime.mocInspector.inspect");
                        return inspector.inspect(data);
                    }
                };
            }
        };
    }

    private CubismModelAccess permissionCheckedModelAccess(final CubismModelAccess delegate) {
        return () -> {
            requireModelRead("model.active");
            return new PermissionCheckedModel(delegate.active());
        };
    }

    private final class PermissionCheckedModel implements CubismModel {
        private final CubismModel delegate;

        private PermissionCheckedModel(final CubismModel delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override public dev.turboism.sdk.cubism.id.ModelId id() {
            requireModelRead("model.id");
            return delegate.id();
        }
        @Override public String name() {
            requireModelRead("model.name");
            return delegate.name();
        }
        @Override public void setName(final String name) {
            requireModelWrite("model.setName");
            final String value = Objects.requireNonNull(name, "name");
            if (value.strip().isEmpty()) throw new IllegalArgumentException("name must not be blank");
            delegate.setName(value);
        }
        @Override public dev.turboism.sdk.cubism.core.MocInfo mocInfo() {
            requireModelRead("model.mocInfo");
            return delegate.mocInfo();
        }
        @Override public dev.turboism.sdk.cubism.model.ParameterDefinitions parameterDefinitions() {
            requireModelRead("model.parameterDefinitions");
            final dev.turboism.sdk.cubism.model.ParameterDefinitions definitions =
                delegate.parameterDefinitions();
            return new dev.turboism.sdk.cubism.model.ParameterDefinitions() {
                @Override public List<dev.turboism.sdk.cubism.model.ParameterDefinition> all() {
                    requireModelRead("model.parameterDefinitions.all");
                    return definitions.all();
                }
                @Override public dev.turboism.sdk.cubism.model.ParameterDefinition find(
                    final dev.turboism.sdk.cubism.id.ParameterId id
                ) {
                    requireModelRead("model.parameterDefinitions.find");
                    return definitions.find(Objects.requireNonNull(id, "id"));
                }
            };
        }
        @Override public boolean defaultKeyformLocked() {
            requireModelRead("model.defaultKeyformLocked");
            return delegate.defaultKeyformLocked();
        }
        @Override public void setDefaultKeyformLocked(final boolean locked) {
            requireModelWrite("model.setDefaultKeyformLocked");
            delegate.setDefaultKeyformLocked(locked);
        }
        @Override public dev.turboism.sdk.cubism.model.Canvas canvas() {
            requireModelRead("model.canvas");
            final dev.turboism.sdk.cubism.model.Canvas canvas = delegate.canvas();
            return new dev.turboism.sdk.cubism.model.Canvas() {
                @Override public float widthPixels() {
                    requireModelRead("model.canvas.widthPixels");
                    return canvas.widthPixels();
                }
                @Override public float heightPixels() {
                    requireModelRead("model.canvas.heightPixels");
                    return canvas.heightPixels();
                }
                @Override public float originXPixels() {
                    requireModelRead("model.canvas.originXPixels");
                    return canvas.originXPixels();
                }
                @Override public float originYPixels() {
                    requireModelRead("model.canvas.originYPixels");
                    return canvas.originYPixels();
                }
                @Override public float pixelsPerUnit() {
                    requireModelRead("model.canvas.pixelsPerUnit");
                    return canvas.pixelsPerUnit();
                }
            };
        }
        @Override public Parameters parameters() {
            requireModelRead("model.parameters");
            final Parameters parameters = delegate.parameters();
            return new Parameters() {
                @Override public List<Parameter> all() {
                    requireModelRead("model.parameters.all");
                    return parameters.all().stream()
                        .map(value -> (Parameter) new PermissionCheckedParameter(value))
                        .toList();
                }
                @Override public Parameter find(final dev.turboism.sdk.cubism.id.ParameterId id) {
                    requireModelRead("model.parameters.find");
                    return new PermissionCheckedParameter(
                        parameters.find(Objects.requireNonNull(id, "id"))
                    );
                }
            };
        }
        @Override public ParameterGroups parameterGroups() {
            requireModelRead("model.parameterGroups");
            final ParameterGroups groups = delegate.parameterGroups();
            return new ParameterGroups() {
                @Override public List<ParameterGroup> all() {
                    requireModelRead("model.parameterGroups.all");
                    return groups.all().stream()
                        .map(value -> (ParameterGroup) new PermissionCheckedParameterGroup(value))
                        .toList();
                }
                @Override public ParameterGroup root() {
                    requireModelRead("model.parameterGroups.root");
                    return new PermissionCheckedParameterGroup(groups.root());
                }
                @Override public ParameterGroup find(
                    final dev.turboism.sdk.cubism.id.ParameterGroupId id
                ) {
                    requireModelRead("model.parameterGroups.find");
                    return new PermissionCheckedParameterGroup(
                        groups.find(Objects.requireNonNull(id, "id"))
                    );
                }
            };
        }
        @Override public dev.turboism.sdk.cubism.model.ParameterBindingOperations parameterBindings(
            final dev.turboism.sdk.cubism.id.ParameterId parameterId
        ) {
            requireModelRead("model.parameterBindings");
            final dev.turboism.sdk.cubism.model.ParameterBindingOperations operations =
                delegate.parameterBindings(Objects.requireNonNull(parameterId, "parameterId"));
            return new dev.turboism.sdk.cubism.model.ParameterBindingOperations() {
                private void write(final String operation, final Runnable mutation) {
                    requireModelWrite(operation);
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
            requireModelRead("model.parameterBindingBatch");
            final dev.turboism.sdk.cubism.model.ParameterBindingBatchOperations operations =
                delegate.parameterBindingBatch();
            return new dev.turboism.sdk.cubism.model.ParameterBindingBatchOperations() {
                @Override public void invert(
                    final List<dev.turboism.sdk.cubism.model.ParameterBindingTarget> targets
                ) {
                    requireModelWrite("model.parameterBindingBatch.invert");
                    operations.invert(targets);
                }
                @Override public void transfer(
                    final dev.turboism.sdk.cubism.model.ParameterBindingTransferPlan plan
                ) {
                    requireModelWrite("model.parameterBindingBatch.transfer");
                    operations.transfer(plan);
                }
            };
        }
        @Override public dev.turboism.sdk.cubism.model.Parts parts() {
            requireModelRead("model.parts");
            final dev.turboism.sdk.cubism.model.Parts parts = delegate.parts();
            return new dev.turboism.sdk.cubism.model.Parts() {
                @Override public List<dev.turboism.sdk.cubism.model.Part> all() {
                    requireModelRead("model.parts.all");
                    return parts.all().stream()
                        .map(value -> (dev.turboism.sdk.cubism.model.Part) new PermissionCheckedPart(value))
                        .toList();
                }
                @Override public dev.turboism.sdk.cubism.model.Part find(
                    final dev.turboism.sdk.cubism.model.PartId id
                ) {
                    requireModelRead("model.parts.find");
                    return new PermissionCheckedPart(
                        parts.find(Objects.requireNonNull(id, "id"))
                    );
                }
            };
        }
        @Override public dev.turboism.sdk.cubism.model.Drawables drawables() {
            requireModelRead("model.drawables");
            final dev.turboism.sdk.cubism.model.Drawables values = delegate.drawables();
            return new dev.turboism.sdk.cubism.model.Drawables() {
                @Override public List<dev.turboism.sdk.cubism.model.Drawable> all() {
                    requireModelRead("model.drawables.all");
                    return values.all().stream()
                        .map(value -> (dev.turboism.sdk.cubism.model.Drawable)
                            new PermissionCheckedDrawable(value))
                        .toList();
                }
                @Override public dev.turboism.sdk.cubism.model.Drawable find(
                    final dev.turboism.sdk.cubism.id.ArtMeshId id
                ) {
                    requireModelRead("model.drawables.find");
                    return new PermissionCheckedDrawable(
                        values.find(Objects.requireNonNull(id, "id"))
                    );
                }
            };
        }
        @Override public dev.turboism.sdk.cubism.model.Deformers deformers() {
            requireModelRead("model.deformers");
            final dev.turboism.sdk.cubism.model.Deformers values = delegate.deformers();
            return new dev.turboism.sdk.cubism.model.Deformers() {
                @Override public List<dev.turboism.sdk.cubism.model.Deformer> all() {
                    requireModelRead("model.deformers.all");
                    return values.all().stream()
                        .map(PermissionCheckedModel.this::wrapDeformer)
                        .toList();
                }
                @Override public dev.turboism.sdk.cubism.model.Deformer find(
                    final dev.turboism.sdk.cubism.id.DeformerId id
                ) {
                    requireModelRead("model.deformers.find");
                    return wrapDeformer(values.find(Objects.requireNonNull(id, "id")));
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
            requireModelRead("model.warpDeformers");
            final dev.turboism.sdk.cubism.model.WarpDeformers values = delegate.warpDeformers();
            return new dev.turboism.sdk.cubism.model.WarpDeformers() {
                @Override public List<dev.turboism.sdk.cubism.model.WarpDeformer> all() {
                    requireModelRead("model.warpDeformers.all");
                    return values.all().stream()
                        .map(value -> (dev.turboism.sdk.cubism.model.WarpDeformer)
                            new PermissionCheckedWarpDeformer(value))
                        .toList();
                }
                @Override public dev.turboism.sdk.cubism.model.WarpDeformer find(
                    final dev.turboism.sdk.cubism.id.DeformerId id
                ) {
                    requireModelRead("model.warpDeformers.find");
                    return new PermissionCheckedWarpDeformer(
                        values.find(Objects.requireNonNull(id, "id"))
                    );
                }
            };
        }
        @Override public dev.turboism.sdk.cubism.model.RotationDeformers rotationDeformers() {
            requireModelRead("model.rotationDeformers");
            final dev.turboism.sdk.cubism.model.RotationDeformers values =
                delegate.rotationDeformers();
            return new dev.turboism.sdk.cubism.model.RotationDeformers() {
                @Override public List<dev.turboism.sdk.cubism.model.RotationDeformer> all() {
                    requireModelRead("model.rotationDeformers.all");
                    return values.all().stream()
                        .map(value -> (dev.turboism.sdk.cubism.model.RotationDeformer)
                            new PermissionCheckedRotationDeformer(value))
                        .toList();
                }
                @Override public dev.turboism.sdk.cubism.model.RotationDeformer find(
                    final dev.turboism.sdk.cubism.id.DeformerId id
                ) {
                    requireModelRead("model.rotationDeformers.find");
                    return new PermissionCheckedRotationDeformer(
                        values.find(Objects.requireNonNull(id, "id"))
                    );
                }
            };
        }
        @Override public dev.turboism.sdk.cubism.model.Glues glues() {
            requireModelRead("model.glues");
            final dev.turboism.sdk.cubism.model.Glues values = delegate.glues();
            return new dev.turboism.sdk.cubism.model.Glues() {
                @Override public List<dev.turboism.sdk.cubism.model.Glue> all() {
                    requireModelRead("model.glues.all");
                    return values.all().stream()
                        .map(value -> (dev.turboism.sdk.cubism.model.Glue)
                            new PermissionCheckedGlue(value))
                        .toList();
                }
                @Override public dev.turboism.sdk.cubism.model.Glue find(
                    final dev.turboism.sdk.cubism.model.GlueId id
                ) {
                    requireModelRead("model.glues.find");
                    return new PermissionCheckedGlue(
                        values.find(Objects.requireNonNull(id, "id"))
                    );
                }
            };
        }
        @Override public void update() {
            requireModelWrite("model.update");
            delegate.update();
        }
    }

    private final class PermissionCheckedDrawable
        implements dev.turboism.sdk.cubism.model.Drawable {
        private final dev.turboism.sdk.cubism.model.Drawable delegate;
        private PermissionCheckedDrawable(final dev.turboism.sdk.cubism.model.Drawable delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }
        @Override public dev.turboism.sdk.cubism.id.ArtMeshId id() {
            requireModelRead("artMesh.id");
            return delegate.id();
        }
        @Override public int index() {
            requireModelRead("artMesh.index");
            return delegate.index();
        }
        @Override public boolean doubleSided() {
            requireModelRead("artMesh.doubleSided");
            return delegate.doubleSided();
        }
        @Override public dev.turboism.sdk.cubism.model.DrawableEvaluationState evaluationState() {
            requireModelRead("artMesh.evaluationState");
            return delegate.evaluationState();
        }
        @Override public Optional<dev.turboism.sdk.cubism.model.PartId> parentPartId() {
            requireModelRead("artMesh.parentPartId");
            return delegate.parentPartId();
        }
        @Override public Optional<dev.turboism.sdk.cubism.id.DeformerId> parentDeformerId() {
            requireModelRead("artMesh.parentDeformerId");
            return delegate.parentDeformerId();
        }
        @Override public List<dev.turboism.sdk.cubism.id.ParameterId> parameterIds() {
            requireModelRead("artMesh.parameterIds");
            return delegate.parameterIds();
        }
        @Override public List<dev.turboism.sdk.cubism.id.ArtMeshId> maskIds() {
            requireModelRead("artMesh.maskIds");
            return delegate.maskIds();
        }
        @Override public String name() {
            requireModelRead("artMesh.name");
            return delegate.name();
        }
        @Override public boolean visible() {
            requireModelRead("artMesh.visible");
            return delegate.visible();
        }
        @Override public void setVisible(final boolean visible) {
            requireModelWrite("artMesh.setVisible");
            editorObjectLifecycle.drawable().setVisible(this, visible, delegate::setVisible);
        }
        @Override public boolean locked() {
            requireModelRead("artMesh.locked");
            return delegate.locked();
        }
        @Override public void setLocked(final boolean locked) {
            requireModelWrite("artMesh.setLocked");
            editorObjectLifecycle.drawable().setLocked(this, locked, delegate::setLocked);
        }
        @Override public boolean visibleInHierarchy() {
            requireModelRead("artMesh.visibleInHierarchy");
            return delegate.visibleInHierarchy();
        }
        @Override public boolean lockedInHierarchy() {
            requireModelRead("artMesh.lockedInHierarchy");
            return delegate.lockedInHierarchy();
        }
        @Override public byte constantFlag() {
            requireModelRead("artMesh.constantFlag");
            return delegate.constantFlag();
        }
        @Override public byte dynamicFlag() {
            requireModelRead("artMesh.dynamicFlag");
            return delegate.dynamicFlag();
        }
        @Override public dev.turboism.sdk.cubism.model.BlendMode blendMode() {
            requireModelRead("artMesh.blendMode");
            return delegate.blendMode();
        }
        @Override public int textureIndex() {
            requireModelRead("artMesh.textureIndex");
            return delegate.textureIndex();
        }
        @Override public int drawOrder() {
            requireModelRead("artMesh.drawOrder");
            return delegate.drawOrder();
        }
        @Override public int renderOrder() {
            requireModelRead("artMesh.renderOrder");
            return delegate.renderOrder();
        }
        @Override public float getOpacity() {
            requireModelRead("artMesh.getOpacity");
            return delegate.getOpacity();
        }
        @Override public void setOpacity(final float opacity) {
            requireModelWrite("artMesh.setOpacity");
            editorObjectLifecycle.drawable().setOpacity(this, opacity, delegate::setOpacity);
        }
        @Override public dev.turboism.sdk.cubism.model.ArtMeshGeometry geometry() {
            requireModelRead("artMesh.geometry");
            return delegate.geometry();
        }
        @Override public void replaceGeometry(
            final dev.turboism.sdk.cubism.model.ArtMeshGeometry geometry
        ) {
            requireModelWrite("artMesh.replaceGeometry");
            editorObjectLifecycle.drawable().replaceGeometry(this, geometry, delegate::replaceGeometry);
        }
        @Override public dev.turboism.sdk.cubism.model.IntSequence masks() {
            requireModelRead("artMesh.masks");
            return delegate.masks();
        }
        @Override public boolean invertedMask() {
            requireModelRead("artMesh.invertedMask");
            return delegate.invertedMask();
        }
        @Override public boolean culling() {
            requireModelRead("artMesh.culling");
            return delegate.culling();
        }
        @Override public String userData() {
            requireModelRead("artMesh.userData");
            return delegate.userData();
        }
        @Override public dev.turboism.sdk.cubism.model.FloatSequence vertexPositions() {
            requireModelRead("artMesh.vertexPositions");
            return delegate.vertexPositions();
        }
        @Override public dev.turboism.sdk.cubism.model.FloatSequence vertexUvs() {
            requireModelRead("artMesh.vertexUvs");
            return delegate.vertexUvs();
        }
        @Override public dev.turboism.sdk.cubism.model.IntSequence indices() {
            requireModelRead("artMesh.indices");
            return delegate.indices();
        }
        @Override public dev.turboism.sdk.cubism.model.Color multiplyColor() {
            requireModelRead("artMesh.multiplyColor");
            return delegate.multiplyColor();
        }
        @Override public dev.turboism.sdk.cubism.model.Color screenColor() {
            requireModelRead("artMesh.screenColor");
            return delegate.screenColor();
        }
        @Override public int parentPartIndex() {
            requireModelRead("artMesh.parentPartIndex");
            return delegate.parentPartIndex();
        }
        @Override public int parentDeformerIndex() {
            requireModelRead("artMesh.parentDeformerIndex");
            return delegate.parentDeformerIndex();
        }
        @Override public dev.turboism.sdk.cubism.model.IntSequence parameters() {
            requireModelRead("artMesh.parameters");
            return delegate.parameters();
        }
        @Override public List<dev.turboism.sdk.cubism.model.ParameterBinding> getParameterBindings() {
            requireModelRead("artMesh.getParameterBindings");
            return delegate.getParameterBindings();
        }
    }

    private class PermissionCheckedDeformer implements dev.turboism.sdk.cubism.model.Deformer {
        protected final dev.turboism.sdk.cubism.model.Deformer delegate;
        private PermissionCheckedDeformer(final dev.turboism.sdk.cubism.model.Deformer delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }
        @Override public dev.turboism.sdk.cubism.id.DeformerId id() { requireModelRead("deformer.id"); return delegate.id(); }
        @Override public int index() {
            requireModelRead("deformer.index");
            return delegate.index();
        }
        @Override public Optional<dev.turboism.sdk.cubism.model.PartId> parentPartId() {
            requireModelRead("deformer.parentPartId");
            return delegate.parentPartId();
        }
        @Override public Optional<dev.turboism.sdk.cubism.id.DeformerId> parentDeformerId() {
            requireModelRead("deformer.parentDeformerId");
            return delegate.parentDeformerId();
        }
        @Override public List<dev.turboism.sdk.cubism.id.ParameterId> parameterIds() {
            requireModelRead("deformer.parameterIds");
            return delegate.parameterIds();
        }
        @Override public String name() { requireModelRead("deformer.name"); return delegate.name(); }
        @Override public boolean visible() { requireModelRead("deformer.visible"); return delegate.visible(); }
        @Override public void setVisible(final boolean visible) {
            requireModelWrite("deformer.setVisible");
            editorObjectLifecycle.deformer().setVisible(this, visible, delegate::setVisible);
        }
        @Override public boolean locked() { requireModelRead("deformer.locked"); return delegate.locked(); }
        @Override public void setLocked(final boolean locked) {
            requireModelWrite("deformer.setLocked");
            editorObjectLifecycle.deformer().setLocked(this, locked, delegate::setLocked);
        }
        @Override public boolean visibleInHierarchy() { requireModelRead("deformer.visibleInHierarchy"); return delegate.visibleInHierarchy(); }
        @Override public boolean lockedInHierarchy() { requireModelRead("deformer.lockedInHierarchy"); return delegate.lockedInHierarchy(); }
        @Override public float getOpacity() { requireModelRead("deformer.getOpacity"); return delegate.getOpacity(); }
        @Override public void setOpacity(final float opacity) {
            requireModelWrite("deformer.setOpacity");
            editorObjectLifecycle.deformer().setOpacity(this, opacity, delegate::setOpacity);
        }
        @Override public dev.turboism.sdk.cubism.model.Color multiplyColor() {
            requireModelRead("deformer.multiplyColor");
            return delegate.multiplyColor();
        }
        @Override public dev.turboism.sdk.cubism.model.Color screenColor() {
            requireModelRead("deformer.screenColor");
            return delegate.screenColor();
        }
        @Override public int parentPartIndex() { requireModelRead("deformer.parentPartIndex"); return delegate.parentPartIndex(); }
        @Override public int parentDeformerIndex() { requireModelRead("deformer.parentDeformerIndex"); return delegate.parentDeformerIndex(); }
        @Override public dev.turboism.sdk.cubism.model.IntSequence parameters() {
            requireModelRead("deformer.parameters");
            return delegate.parameters();
        }
        @Override public List<dev.turboism.sdk.cubism.model.ParameterBinding> getParameterBindings() {
            requireModelRead("deformer.getParameterBindings");
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
        @Override public dev.turboism.sdk.cubism.model.WarpGrid grid() { requireModelRead("warpDeformer.grid"); return warp.grid(); }
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
        @Override public float baseAngle() { requireModelRead("rotationDeformer.baseAngle"); return rotation.baseAngle(); }
        @Override public void setBaseAngle(final float angle) {
            requireModelWrite("rotationDeformer.setBaseAngle");
            editorObjectLifecycle.deformer().setBaseAngle(this, angle, rotation::setBaseAngle);
        }
        @Override public dev.turboism.sdk.cubism.model.RotationDeformerForm form() {
            requireModelRead("rotationDeformer.form");
            return rotation.form();
        }
        @Override public void replaceForm(
            final dev.turboism.sdk.cubism.model.RotationDeformerForm form
        ) {
            requireModelWrite("rotationDeformer.replaceForm");
            editorObjectLifecycle.deformer().replaceForm(this, form, rotation::replaceForm);
        }
    }

    private final class PermissionCheckedGlue implements dev.turboism.sdk.cubism.model.Glue {
        private final dev.turboism.sdk.cubism.model.Glue delegate;

        private PermissionCheckedGlue(final dev.turboism.sdk.cubism.model.Glue delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override public dev.turboism.sdk.cubism.model.GlueId id() { requireModelRead("glue.id"); return delegate.id(); }
        @Override public int index() {
            requireModelRead("glue.index");
            return delegate.index();
        }
        @Override public int drawableA() { requireModelRead("glue.drawableA"); return delegate.drawableA(); }
        @Override public int drawableB() { requireModelRead("glue.drawableB"); return delegate.drawableB(); }
        @Override public dev.turboism.sdk.cubism.model.IntSequence parameters() {
            requireModelRead("glue.parameters");
            return delegate.parameters();
        }
        @Override public dev.turboism.sdk.cubism.id.ArtMeshId drawableAId() {
            requireModelRead("glue.drawableAId");
            return delegate.drawableAId();
        }
        @Override public dev.turboism.sdk.cubism.id.ArtMeshId drawableBId() {
            requireModelRead("glue.drawableBId");
            return delegate.drawableBId();
        }
        @Override public List<dev.turboism.sdk.cubism.id.ParameterId> parameterIds() {
            requireModelRead("glue.parameterIds");
            return delegate.parameterIds();
        }
    }

    private void requireModelRead(final String operation) {
        requireActiveScope();
        permissionGate.require(MODEL_READ_PERMISSION, operation);
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
            requireModelRead("parameterGroup.id");
            return delegate.id();
        }
        @Override public java.util.Optional<String> name() { requireModelRead("parameterGroup.name"); return delegate.name(); }
        @Override public dev.turboism.sdk.cubism.model.Color labelColor() {
            requireModelRead("parameterGroup.labelColor");
            return delegate.labelColor();
        }
        @Override public void setLabelColor(
            final dev.turboism.sdk.cubism.model.Color color
        ) {
            requireModelWrite("parameterGroup.setLabelColor");
            delegate.setLabelColor(color);
        }
        @Override public java.util.Optional<dev.turboism.sdk.cubism.id.ParameterGroupId> parentId() {
            requireModelRead("parameterGroup.parentId");
            return delegate.parentId();
        }
        @Override public List<dev.turboism.sdk.cubism.id.ParameterGroupId> childGroupIds() {
            requireModelRead("parameterGroup.childGroupIds");
            return delegate.childGroupIds();
        }
        @Override public List<dev.turboism.sdk.cubism.id.ParameterId> parameterIds() {
            requireModelRead("parameterGroup.parameterIds");
            return delegate.parameterIds();
        }
    }

    private final class PermissionCheckedParameter implements Parameter {
        private final Parameter delegate;

        private PermissionCheckedParameter(final Parameter delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override public dev.turboism.sdk.cubism.id.ParameterId id() { requireModelRead("parameter.id"); return delegate.id(); }
        @Override public int index() {
            requireModelRead("parameter.index");
            return delegate.index();
        }
        @Override public dev.turboism.sdk.cubism.model.FloatSequence keyValues() {
            requireModelRead("parameter.keyValues");
            return delegate.keyValues();
        }
        @Override public java.util.Optional<String> name() { requireModelRead("parameter.name"); return delegate.name(); }
        @Override public dev.turboism.sdk.cubism.model.ParameterType type() { requireModelRead("parameter.type"); return delegate.type(); }
        @Override public java.util.Optional<Boolean> repeat() { requireModelRead("parameter.repeat"); return delegate.repeat(); }
        @Override public java.util.Optional<Boolean> combined() { requireModelRead("parameter.combined"); return delegate.combined(); }
        @Override public java.util.Optional<dev.turboism.sdk.cubism.id.ParameterId> combinedWith() {
            requireModelRead("parameter.combinedWith");
            return delegate.combinedWith();
        }
        @Override public List<dev.turboism.sdk.cubism.model.ParameterBinding> getParameterBindings() {
            requireModelRead("parameter.getParameterBindings");
            return delegate.getParameterBindings();
        }
        @Override public void combineWith(
            final dev.turboism.sdk.cubism.id.ParameterId partnerId
        ) {
            requireModelWrite("parameter.combineWith");
            delegate.combineWith(partnerId);
        }
        @Override public void uncombine() {
            requireModelWrite("parameter.uncombine");
            delegate.uncombine();
        }
        @Override public float getValue() { requireModelRead("parameter.getValue"); return delegate.getValue(); }
        @Override public float getMinimumValue() { requireModelRead("parameter.getMinimumValue"); return delegate.getMinimumValue(); }
        @Override public float getMaximumValue() { requireModelRead("parameter.getMaximumValue"); return delegate.getMaximumValue(); }
        @Override public float getDefaultValue() { requireModelRead("parameter.getDefaultValue"); return delegate.getDefaultValue(); }
        @Override public void resetToDefault() {
            requireModelWrite("parameter.resetToDefault");
            parameterLifecycle.setValue(this, delegate.getDefaultValue(), delegate::setValue);
        }
        @Override public void setValue(final float value) {
            requireModelWrite("parameter.setValue");
            parameterLifecycle.setValue(this, value, delegate::setValue);
        }
        @Override public void updateDefinition(
            final dev.turboism.sdk.cubism.model.ParameterDefinition definition
        ) {
            requireModelWrite("parameter.updateDefinition");
            delegate.updateDefinition(definition);
        }
    }

    private final class PermissionCheckedPart implements dev.turboism.sdk.cubism.model.Part {
        private final dev.turboism.sdk.cubism.model.Part delegate;

        private PermissionCheckedPart(final dev.turboism.sdk.cubism.model.Part delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override public dev.turboism.sdk.cubism.model.PartId id() { requireModelRead("part.id"); return delegate.id(); }
        @Override public int index() {
            requireModelRead("part.index");
            return delegate.index();
        }
        @Override public Optional<String> shortName() {
            requireModelRead("part.shortName");
            return delegate.shortName();
        }
        @Override public void setShortName(final Optional<String> value) {
            requireModelWrite("part.setShortName");
            final Optional<String> checked = Objects.requireNonNull(value, "value");
            if (checked.filter(String::isBlank).isPresent()) {
                throw new IllegalArgumentException("short name must not be blank");
            }
            delegate.setShortName(checked);
        }
        @Override public Optional<dev.turboism.sdk.cubism.model.PartId> parentId() {
            requireModelRead("part.parentId");
            return delegate.parentId();
        }
        @Override public List<dev.turboism.sdk.cubism.model.PartId> childIds() {
            requireModelRead("part.childIds");
            return delegate.childIds();
        }
        @Override public boolean visible() {
            requireModelRead("part.visible");
            return delegate.visible();
        }
        @Override public void setVisible(final boolean value) {
            requireModelWrite("part.setVisible");
            delegate.setVisible(value);
        }
        @Override public boolean visibleInHierarchy() {
            requireModelRead("part.visibleInHierarchy");
            return delegate.visibleInHierarchy();
        }
        @Override public boolean locked() {
            requireModelRead("part.locked");
            return delegate.locked();
        }
        @Override public void setLocked(final boolean value) {
            requireModelWrite("part.setLocked");
            delegate.setLocked(value);
        }
        @Override public boolean lockedInHierarchy() {
            requireModelRead("part.lockedInHierarchy");
            return delegate.lockedInHierarchy();
        }
        @Override public Optional<dev.turboism.sdk.cubism.model.Color> editColor() {
            requireModelRead("part.editColor");
            return delegate.editColor();
        }
        @Override public void setEditColor(
            final Optional<dev.turboism.sdk.cubism.model.Color> value
        ) {
            requireModelWrite("part.setEditColor");
            delegate.setEditColor(Objects.requireNonNull(value, "value"));
        }
        @Override public boolean sketch() {
            requireModelRead("part.sketch");
            return delegate.sketch();
        }
        @Override public void setSketch(final boolean value) {
            requireModelWrite("part.setSketch");
            delegate.setSketch(value);
        }
        @Override public int defaultOrder() {
            requireModelRead("part.defaultOrder");
            return delegate.defaultOrder();
        }
        @Override public void setDefaultOrder(final int value) {
            requireModelWrite("part.setDefaultOrder");
            delegate.setDefaultOrder(value);
        }
        @Override public String name() { requireModelRead("part.name"); return delegate.name(); }
        @Override public void setName(final String name) {
            requireModelWrite("part.setName");
            partLifecycle.setName(this, name, delegate::setName);
        }
        @Override public float getOpacity() { requireModelRead("part.getOpacity"); return delegate.getOpacity(); }
        @Override public int parentIndex() { requireModelRead("part.parentIndex"); return delegate.parentIndex(); }
        @Override public void setOpacity(final float opacity) {
            requireModelWrite("part.setOpacity");
            partLifecycle.setOpacity(this, opacity, delegate::setOpacity);
        }
    }
}
