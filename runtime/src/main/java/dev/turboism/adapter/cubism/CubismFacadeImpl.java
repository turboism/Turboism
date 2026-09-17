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
import dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorSession;
import dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorUi;
import dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasLayoutAlgorithmRegistry;
import dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasLayoutService;
import dev.turboism.adapter.cubism.textureatlas.TextureAtlasLayoutCoordinator;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.history.CubismHistory;
import dev.turboism.sdk.cubism.CubismRuntimeSnapshot;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.SelectionSnapshot;
import dev.turboism.sdk.cubism.event.CubismOperation;
import dev.turboism.sdk.cubism.event.CubismOperationOrigin;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterGroup;
import dev.turboism.sdk.cubism.model.ParameterGroups;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionService;
import dev.turboism.sdk.cubism.transaction.TransactionManager;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutService;
import dev.turboism.sdk.permission.CubismPermissionException;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The runtime's implementation of the plugin-facing Cubism facade.
 *
 * <p>Every accessor is permission-checked and scope-checked: reads require the corresponding
 * {@code turboism.cubism.*} permission of the owning plugin, and any call made after that plugin has
 * been disabled fails with {@link IllegalStateException} rather than touching the host, so a leaked
 * facade reference cannot outlive its plugin.
 *
 * <p>The snapshots handed out are immutable projections of host state taken at call time; they do not
 * track later host changes. Calls are expected to originate on the Cubism host thread. Where the host
 * offers no capability, the facade degrades to empty snapshots and no-op services instead of throwing.
 */
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
    final CubismPermissionGate permissionGate;
    private final ImmutableSnapshotFactory snapshotFactory;
    private final TransactionManager transactionManager;
    private final CubismModelAccess modelAccess;
    private CubismHistory history = CubismHistory.unavailable();
    private AuthoringTransactionService authoringTransactions =
        AuthoringTransactionService.unavailable();
    private final dev.turboism.sdk.cubism.core.CoreRuntimeInfo coreRuntime;
    final ParameterLifecycleCoordinator parameterLifecycle;
    final PartLifecycleCoordinator partLifecycle;
    private final TextureAtlasLayoutService textureAtlasLayouts;
    final EditorObjectLifecycleCoordinator editorObjectLifecycle;
    private final BooleanSupplier activeScope;
    private final RuntimeTextureAtlasEditorUi textureAtlasEditorUi;
    private final RuntimeTextureAtlasEditorSession textureAtlasEditorSession;
    private final RuntimeTextureAtlasLayoutAlgorithmRegistry textureAtlasAlgorithms;

    public CubismFacadeImpl(final HostSnapshotSource source, final CubismPermissionGate permissionGate) {
        this(
            source,
            permissionGate,
            new ImmutableSnapshotFactory(),
            unavailableTransactionManager(),
            unavailableModelAccess(),
            new ParameterLifecycleCoordinator(),
            new PartLifecycleCoordinator(),
            new RuntimeTextureAtlasLayoutService(new TextureAtlasLayoutCoordinator(), permissionGate),
            new EditorObjectLifecycleCoordinator(),
            () -> true,
            new RuntimeTextureAtlasEditorUi(),
            RuntimeTextureAtlasEditorSession.unavailable(),
            new RuntimeTextureAtlasLayoutAlgorithmRegistry()
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
            new RuntimeTextureAtlasLayoutService(new TextureAtlasLayoutCoordinator(), permissionGate),
            new EditorObjectLifecycleCoordinator(),
            () -> true,
            new RuntimeTextureAtlasEditorUi(),
            RuntimeTextureAtlasEditorSession.unavailable(),
            new RuntimeTextureAtlasLayoutAlgorithmRegistry()
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
            new RuntimeTextureAtlasLayoutService(new TextureAtlasLayoutCoordinator(), permissionGate),
            new EditorObjectLifecycleCoordinator(),
            () -> true,
            new RuntimeTextureAtlasEditorUi(),
            RuntimeTextureAtlasEditorSession.unavailable(),
            new RuntimeTextureAtlasLayoutAlgorithmRegistry()
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
            new ImmutableSnapshotFactory(),
            unavailableTransactionManager(),
            modelAccess,
            parameterLifecycle,
            partLifecycle,
            new RuntimeTextureAtlasLayoutService(new TextureAtlasLayoutCoordinator(), permissionGate),
            new EditorObjectLifecycleCoordinator(),
            () -> true,
            new RuntimeTextureAtlasEditorUi(),
            RuntimeTextureAtlasEditorSession.unavailable(),
            new RuntimeTextureAtlasLayoutAlgorithmRegistry()
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
            new TextureAtlasLayoutCoordinator(),
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
        final TextureAtlasLayoutCoordinator textureAtlasLayouts,
        final EditorObjectLifecycleCoordinator editorObjectLifecycle,
        final BooleanSupplier activeScope
    ) {
        this(
            source,
            permissionGate,
            modelAccess,
            unavailableCoreRuntime(),
            parameterLifecycle,
            partLifecycle,
            textureAtlasLayouts,
            new dev.turboism.adapter.cubism.textureatlas.TextureAtlasNativeInvocationCoordinator(),
            editorObjectLifecycle,
            activeScope,
            new RuntimeTextureAtlasEditorUi(),
            RuntimeTextureAtlasEditorSession.unavailable(),
            new RuntimeTextureAtlasLayoutAlgorithmRegistry()
        );
    }

    public CubismFacadeImpl(
        final HostSnapshotSource source,
        final CubismPermissionGate permissionGate,
        final CubismModelAccess modelAccess,
        final dev.turboism.sdk.cubism.core.CoreRuntimeInfo coreRuntime,
        final ParameterLifecycleCoordinator parameterLifecycle,
        final PartLifecycleCoordinator partLifecycle,
        final TextureAtlasLayoutCoordinator textureAtlasLayouts,
        final dev.turboism.adapter.cubism.textureatlas.TextureAtlasNativeInvocationCoordinator nativeInvocations,
        final EditorObjectLifecycleCoordinator editorObjectLifecycle,
        final BooleanSupplier activeScope,
        final RuntimeTextureAtlasEditorUi textureAtlasEditorUi,
        final RuntimeTextureAtlasEditorSession textureAtlasEditorSession,
        final RuntimeTextureAtlasLayoutAlgorithmRegistry textureAtlasAlgorithms
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
            new RuntimeTextureAtlasLayoutService(textureAtlasLayouts, permissionGate, nativeInvocations),
            editorObjectLifecycle,
            activeScope,
            textureAtlasEditorUi,
            textureAtlasEditorSession,
            textureAtlasAlgorithms
        );
    }

    /**
     * Full production construction seam including the native Undo history
     * access. Kept separate from the canonical constructor so existing
     * callers stay source-compatible; history is installed only by the
     * verified host-session wiring.
     */
    public CubismFacadeImpl(
        final HostSnapshotSource source,
        final CubismPermissionGate permissionGate,
        final CubismModelAccess modelAccess,
        final dev.turboism.sdk.cubism.core.CoreRuntimeInfo coreRuntime,
        final ParameterLifecycleCoordinator parameterLifecycle,
        final PartLifecycleCoordinator partLifecycle,
        final TextureAtlasLayoutCoordinator textureAtlasLayouts,
        final dev.turboism.adapter.cubism.textureatlas.TextureAtlasNativeInvocationCoordinator nativeInvocations,
        final EditorObjectLifecycleCoordinator editorObjectLifecycle,
        final BooleanSupplier activeScope,
        final RuntimeTextureAtlasEditorUi textureAtlasEditorUi,
        final RuntimeTextureAtlasEditorSession textureAtlasEditorSession,
        final RuntimeTextureAtlasLayoutAlgorithmRegistry textureAtlasAlgorithms,
        final CubismHistory history
    ) {
        this(
            source,
            permissionGate,
            modelAccess,
            coreRuntime,
            parameterLifecycle,
            partLifecycle,
            textureAtlasLayouts,
            nativeInvocations,
            editorObjectLifecycle,
            activeScope,
            textureAtlasEditorUi,
            textureAtlasEditorSession,
            textureAtlasAlgorithms,
            history,
            AuthoringTransactionService.unavailable()
        );
    }

    /** Full production construction seam including history and authoring transactions. */
    public CubismFacadeImpl(
        final HostSnapshotSource source,
        final CubismPermissionGate permissionGate,
        final CubismModelAccess modelAccess,
        final dev.turboism.sdk.cubism.core.CoreRuntimeInfo coreRuntime,
        final ParameterLifecycleCoordinator parameterLifecycle,
        final PartLifecycleCoordinator partLifecycle,
        final TextureAtlasLayoutCoordinator textureAtlasLayouts,
        final dev.turboism.adapter.cubism.textureatlas.TextureAtlasNativeInvocationCoordinator nativeInvocations,
        final EditorObjectLifecycleCoordinator editorObjectLifecycle,
        final BooleanSupplier activeScope,
        final RuntimeTextureAtlasEditorUi textureAtlasEditorUi,
        final RuntimeTextureAtlasEditorSession textureAtlasEditorSession,
        final RuntimeTextureAtlasLayoutAlgorithmRegistry textureAtlasAlgorithms,
        final CubismHistory history,
        final AuthoringTransactionService authoringTransactions
    ) {
        this(
            source,
            permissionGate,
            modelAccess,
            coreRuntime,
            parameterLifecycle,
            partLifecycle,
            textureAtlasLayouts,
            nativeInvocations,
            editorObjectLifecycle,
            activeScope,
            textureAtlasEditorUi,
            textureAtlasEditorSession,
            textureAtlasAlgorithms
        );
        this.history = Objects.requireNonNull(history, "history");
        this.authoringTransactions = Objects.requireNonNull(
            authoringTransactions,
            "authoringTransactions"
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
            new RuntimeTextureAtlasLayoutService(new TextureAtlasLayoutCoordinator(), permissionGate),
            editorObjectLifecycle,
            activeScope,
            new RuntimeTextureAtlasEditorUi(),
            RuntimeTextureAtlasEditorSession.unavailable(),
            new RuntimeTextureAtlasLayoutAlgorithmRegistry()
        );
    }

    public CubismFacadeImpl(
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
            new RuntimeTextureAtlasLayoutService(new TextureAtlasLayoutCoordinator(), permissionGate),
            editorObjectLifecycle,
            activeScope,
            new RuntimeTextureAtlasEditorUi(),
            RuntimeTextureAtlasEditorSession.unavailable(),
            new RuntimeTextureAtlasLayoutAlgorithmRegistry()
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
            new RuntimeTextureAtlasLayoutService(new TextureAtlasLayoutCoordinator(), permissionGate),
            editorObjectLifecycle,
            activeScope,
            new RuntimeTextureAtlasEditorUi(),
            RuntimeTextureAtlasEditorSession.unavailable(),
            new RuntimeTextureAtlasLayoutAlgorithmRegistry()
        );
    }

    public CubismFacadeImpl(
        final HostSnapshotSource source,
        final CubismPermissionGate permissionGate,
        final CubismModelAccess modelAccess,
        final ParameterLifecycleCoordinator parameterLifecycle,
        final PartLifecycleCoordinator partLifecycle,
        final EditorObjectLifecycleCoordinator editorObjectLifecycle,
        final BooleanSupplier activeScope,
        final CubismHistory history
    ) {
        this(source, permissionGate, modelAccess, parameterLifecycle, partLifecycle, editorObjectLifecycle, activeScope);
        this.history = Objects.requireNonNull(history, "history");
    }

    CubismFacadeImpl(
        final HostSnapshotSource source,
        final CubismPermissionGate permissionGate,
        final CubismModelAccess modelAccess,
        final BooleanSupplier activeScope,
        final AuthoringTransactionService authoringTransactions
    ) {
        this(
            source,
            permissionGate,
            modelAccess,
            new ParameterLifecycleCoordinator(),
            new PartLifecycleCoordinator(),
            new EditorObjectLifecycleCoordinator(),
            activeScope
        );
        this.authoringTransactions = Objects.requireNonNull(
            authoringTransactions,
            "authoringTransactions"
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
            new RuntimeTextureAtlasLayoutService(new TextureAtlasLayoutCoordinator(), permissionGate),
            new EditorObjectLifecycleCoordinator(),
            () -> true,
            new RuntimeTextureAtlasEditorUi(),
            RuntimeTextureAtlasEditorSession.unavailable(),
            new RuntimeTextureAtlasLayoutAlgorithmRegistry()
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
            new RuntimeTextureAtlasLayoutService(new TextureAtlasLayoutCoordinator(), permissionGate),
            new EditorObjectLifecycleCoordinator(),
            () -> true,
            new RuntimeTextureAtlasEditorUi(),
            RuntimeTextureAtlasEditorSession.unavailable(),
            new RuntimeTextureAtlasLayoutAlgorithmRegistry()
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
            new RuntimeTextureAtlasLayoutService(new TextureAtlasLayoutCoordinator(), permissionGate),
            new EditorObjectLifecycleCoordinator(),
            () -> true,
            new RuntimeTextureAtlasEditorUi(),
            RuntimeTextureAtlasEditorSession.unavailable(),
            new RuntimeTextureAtlasLayoutAlgorithmRegistry()
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
            new RuntimeTextureAtlasLayoutService(new TextureAtlasLayoutCoordinator(), permissionGate),
            new EditorObjectLifecycleCoordinator(),
            () -> true,
            new RuntimeTextureAtlasEditorUi(),
            RuntimeTextureAtlasEditorSession.unavailable(),
            new RuntimeTextureAtlasLayoutAlgorithmRegistry()
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
            new RuntimeTextureAtlasLayoutService(new TextureAtlasLayoutCoordinator(), permissionGate),
            new EditorObjectLifecycleCoordinator(),
            () -> true,
            new RuntimeTextureAtlasEditorUi(),
            RuntimeTextureAtlasEditorSession.unavailable(),
            new RuntimeTextureAtlasLayoutAlgorithmRegistry()
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
            new RuntimeTextureAtlasLayoutService(new TextureAtlasLayoutCoordinator(), permissionGate),
            new EditorObjectLifecycleCoordinator(),
            () -> true,
            new RuntimeTextureAtlasEditorUi(),
            RuntimeTextureAtlasEditorSession.unavailable(),
            new RuntimeTextureAtlasLayoutAlgorithmRegistry()
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
        final TextureAtlasLayoutService textureAtlasLayouts,
        final EditorObjectLifecycleCoordinator editorObjectLifecycle,
        final BooleanSupplier activeScope,
        final RuntimeTextureAtlasEditorUi textureAtlasEditorUi,
        final RuntimeTextureAtlasEditorSession textureAtlasEditorSession,
        final RuntimeTextureAtlasLayoutAlgorithmRegistry textureAtlasAlgorithms
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
            textureAtlasLayouts,
            editorObjectLifecycle,
            activeScope,
            textureAtlasEditorUi,
            textureAtlasEditorSession,
            textureAtlasAlgorithms
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
        this(
            source,
            permissionGate,
            snapshotFactory,
            transactionManager,
            modelAccess,
            coreRuntime,
            parameterLifecycle,
            partLifecycle,
            new RuntimeTextureAtlasLayoutService(new TextureAtlasLayoutCoordinator(), permissionGate),
            editorObjectLifecycle,
            activeScope,
            new RuntimeTextureAtlasEditorUi(),
            RuntimeTextureAtlasEditorSession.unavailable(),
            new RuntimeTextureAtlasLayoutAlgorithmRegistry()
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
        final TextureAtlasLayoutService textureAtlasLayouts,
        final EditorObjectLifecycleCoordinator editorObjectLifecycle,
        final BooleanSupplier activeScope,
        final RuntimeTextureAtlasEditorUi textureAtlasEditorUi,
        final RuntimeTextureAtlasEditorSession textureAtlasEditorSession,
        final RuntimeTextureAtlasLayoutAlgorithmRegistry textureAtlasAlgorithms
    ) {
        this.source = Objects.requireNonNull(source, "source");
        this.permissionGate = Objects.requireNonNull(permissionGate, "permissionGate");
        this.snapshotFactory = Objects.requireNonNull(snapshotFactory, "snapshotFactory");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager");
        this.parameterLifecycle = Objects.requireNonNull(parameterLifecycle, "parameterLifecycle");
        this.partLifecycle = Objects.requireNonNull(partLifecycle, "partLifecycle");
        this.textureAtlasLayouts = Objects.requireNonNull(textureAtlasLayouts, "textureAtlasLayouts");
        this.editorObjectLifecycle = Objects.requireNonNull(editorObjectLifecycle, "editorObjectLifecycle");
        this.activeScope = Objects.requireNonNull(activeScope, "activeScope");
        this.coreRuntime = Objects.requireNonNull(coreRuntime, "coreRuntime");
        this.textureAtlasEditorUi = textureAtlasEditorUi == null
            ? new RuntimeTextureAtlasEditorUi()
            : textureAtlasEditorUi;
        this.textureAtlasEditorSession = textureAtlasEditorSession == null
            ? RuntimeTextureAtlasEditorSession.unavailable()
            : textureAtlasEditorSession;
        this.textureAtlasAlgorithms = textureAtlasAlgorithms == null
            ? new RuntimeTextureAtlasLayoutAlgorithmRegistry()
            : textureAtlasAlgorithms;
        this.modelAccess = permissionCheckedModelAccess(
            Objects.requireNonNull(modelAccess, "modelAccess")
        );
    }

    @Override
    public CubismRuntimeSnapshot runtime() {
        requireActiveScope();
        return runtimeSnapshot();
    }

    /**
     * Reads the runtime snapshot together with the host invalidation token it was taken at.
     *
     * <p>Callers that cache a snapshot can compare the version to decide whether a re-read is needed;
     * an unchanged version means the host reported no invalidation between the two reads.
     *
     * @return the snapshot and the host version it was observed at
     * @throws IllegalStateException     if the owning plugin has been disabled, making this facade
     *                                   reference stale
     * @throws CubismPermissionException if the owning plugin lacks the required read permission
     */
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
    public CubismHistory history() {
        requireActiveScope();
        permissionGate.require(MODEL_READ_PERMISSION, "history");
        final CubismHistory delegate = history;
        return new CubismHistory() {
            @Override
            public dev.turboism.sdk.cubism.history.HistorySnapshot snapshot() {
                requireActiveScope();
                permissionGate.require(MODEL_READ_PERMISSION, "history.snapshot");
                return delegate.snapshot();
            }

            @Override
            public dev.turboism.sdk.cubism.history.HistoryMoveResult moveTo(
                final long expectedGeneration,
                final long expectedRevision,
                final int position
            ) {
                requireActiveScope();
                permissionGate.require(MODEL_WRITE_PERMISSION, "history.moveTo");
                return delegate.moveTo(expectedGeneration, expectedRevision, position);
            }

            @Override
            public dev.turboism.sdk.cubism.history.HistoryMoveResult moveTo(
                final dev.turboism.sdk.cubism.history.HistorySnapshot expected,
                final int position
            ) {
                requireActiveScope();
                permissionGate.require(MODEL_WRITE_PERMISSION, "history.moveToBound");
                return delegate.moveTo(expected, position);
            }

            @Override
            public boolean isCurrentBinding(
                final dev.turboism.sdk.cubism.history.HistorySnapshot snapshot
            ) {
                requireActiveScope();
                permissionGate.require(MODEL_READ_PERMISSION, "history.isCurrentBinding");
                return delegate.isCurrentBinding(snapshot);
            }
        };
    }

    @Override
    public AuthoringTransactionService authoringTransactions() {
        requireActiveScope();
        final AuthoringTransactionService delegate = authoringTransactions;
        return new AuthoringTransactionService() {
            @Override
            public <T> dev.turboism.sdk.cubism.transaction.AuthoringTransactionResult<T> execute(
                final dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions options,
                final dev.turboism.sdk.cubism.transaction.AuthoringTransactionWork<T> work
            ) {
                requireActiveScope();
                permissionGate.require(
                    MODEL_WRITE_PERMISSION,
                    "authoringTransactions.execute"
                );
                return delegate.execute(options, work);
            }
        };
    }

    @Override
    public TransactionManager transactionManager() {
        requireActiveScope();
        return transactionManager;
    }

    @Override
    public TextureAtlasLayoutService textureAtlasLayouts() {
        requireActiveScope();
        final TextureAtlasLayoutService delegate = textureAtlasLayouts;
        return new TextureAtlasLayoutService() {
            @Override
            public Optional<dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutSnapshot> current() {
                requireActiveScope();
                return delegate.current();
            }

            @Override
            public dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutApplyResult apply(
                final dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutTarget target,
                final dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan plan
            ) {
                requireActiveScope();
                return delegate.apply(target, plan);
            }
        };
    }

    @Override
    public dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorSession textureAtlasEditorSession() {
        requireActiveScope();
        final dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorSession delegate =
            textureAtlasEditorSession;
        return new dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorSession() {
            @Override
            public Optional<dev.turboism.sdk.cubism.textureatlas.TextureAtlasSummary> summary() {
                requireActiveScope();
                return delegate.summary();
            }

            @Override
            public Optional<dev.turboism.sdk.cubism.textureatlas.TextureAtlasSummary> selectedTexture() {
                requireActiveScope();
                return delegate.selectedTexture();
            }
        };
    }

    @Override
    public dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorUi textureAtlasEditorUi() {
        requireActiveScope();
        final dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorUi delegate = textureAtlasEditorUi;
        return () -> {
            requireActiveScope();
            final dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorPanel panel = delegate.attach();
            return new dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorPanel() {
                @Override
                public void setText(final String text) {
                    requireActiveScope();
                    panel.setText(text);
                }

                @Override
                public void close() {
                    requireActiveScope();
                    panel.close();
                }
            };
        };
    }

    @Override
    public dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithmRegistry textureAtlasAlgorithms() {
        requireActiveScope();
        final dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithmRegistry delegate =
            textureAtlasAlgorithms;
        return new dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithmRegistry() {
            @Override
            public dev.turboism.sdk.plugin.Registration register(
                final dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithm algorithm
            ) {
                requireActiveScope();
                final dev.turboism.sdk.plugin.Registration registration = delegate.register(algorithm);
                return () -> {
                    requireActiveScope();
                    registration.close();
                };
            }

            @Override
            public Optional<dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithm> find(
                final String id
            ) {
                requireActiveScope();
                return delegate.find(id);
            }

            @Override
            public List<dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithm> algorithms() {
                requireActiveScope();
                return delegate.algorithms();
            }
        };
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

    /**
     * The snapshot used when no host state is observable: no project, document or model, an empty
     * selection, and empty collections throughout.
     *
     * <p>Returned instead of null or an exception so callers can render a consistent empty state. It
     * requires neither permission nor an active scope, since it reads nothing from the host.
     *
     * @return a fully empty runtime snapshot
     */
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
            return new PermissionCheckedModel(this, delegate.active());
        };
    }

    dev.turboism.sdk.cubism.model.Part unwrapPart(
        final Object expectedOwner,
        final dev.turboism.sdk.cubism.model.Part value
    ) {
        if (value == null) return null;
        if (!(value instanceof PermissionCheckedPart checked)
            || checked.owner != expectedOwner) {
            throw new IllegalArgumentException(
                "Part belongs to another Cubism facade or model generation"
            );
        }
        return checked.delegate;
    }

    dev.turboism.sdk.cubism.model.Drawable unwrapDrawable(
        final dev.turboism.sdk.cubism.model.Drawable value
    ) {
        if (!(value instanceof PermissionCheckedDrawable checked)) {
            throw new IllegalArgumentException(
                "Drawable belongs to another Cubism facade or model generation"
            );
        }
        return checked.delegate;
    }

    dev.turboism.sdk.cubism.model.Deformer unwrapDeformer(
        final dev.turboism.sdk.cubism.model.Deformer value
    ) {
        if (value instanceof PermissionCheckedWarpDeformer checked) {
            return checked.warp;
        }
        if (value instanceof PermissionCheckedRotationDeformer checked) {
            return checked.rotation;
        }
        if (value instanceof PermissionCheckedDeformer checked) {
            return checked.delegate;
        }
        throw new IllegalArgumentException(
            "Deformer belongs to another Cubism facade or model generation"
        );
    }

    <T> void runSemantic(
        final CubismOperation operation,
        final String subjectId,
        final Supplier<T> state,
        final Runnable invocation
    ) {
        editorObjectLifecycle.semantic().runComparing(
            operation,
            CubismOperationOrigin.TURBOISM_API,
            Optional.of(subjectId),
            state,
            invocation
        );
    }

    <T> void runSemanticComparingTo(
        final CubismOperation operation,
        final String subjectId,
        final Supplier<T> state,
        final T finalState,
        final Runnable invocation
    ) {
        editorObjectLifecycle.semantic().runComparingTo(
            operation,
            CubismOperationOrigin.TURBOISM_API,
            Optional.of(subjectId),
            state,
            finalState,
            invocation
        );
    }

    void runSemanticConfirmed(
        final CubismOperation operation,
        final String subjectId,
        final Runnable invocation
    ) {
        editorObjectLifecycle.semantic().runConfirmed(
            operation,
            CubismOperationOrigin.TURBOISM_API,
            Optional.of(subjectId),
            invocation
        );
    }

    void requireModelRead(final String operation) {
        requireActiveScope();
        permissionGate.require(MODEL_READ_PERMISSION, operation);
    }

    void requireModelWrite(final String operation) {
        requireActiveScope();
        permissionGate.require(MODEL_WRITE_PERMISSION, operation);
    }

    void requireActiveScope() {
        if (!activeScope.getAsBoolean()) {
            throw new IllegalStateException("Cubism service reference is stale because the owning plugin is disabled.");
        }
    }

}
