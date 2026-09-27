package dev.turboism.adapter.cubism;

import dev.turboism.adapter.cubism.service.read.CubismReadPermissionGate;
import dev.turboism.adapter.cubism.lifecycle.ParameterLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.PartLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.EditorObjectLifecycleCoordinator;
import dev.turboism.adapter.cubism.write.HostWriteAdapter;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.adapter.cubism.write.RuntimeTransactionManager;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorSession;
import dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorUi;
import dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasLayoutAlgorithmRegistry;
import dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasLayoutService;
import dev.turboism.adapter.cubism.textureatlas.TextureAtlasLayoutCoordinator;
import dev.turboism.core.runtime.psd.PsdExportHost;
import dev.turboism.core.runtime.psd.PsdReplaceHost;
import dev.turboism.core.runtime.psd.RuntimePsdExportService;
import dev.turboism.core.runtime.psd.RuntimePsdReplaceService;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.history.CubismHistory;
import dev.turboism.sdk.cubism.CubismRuntimeSnapshot;
import dev.turboism.sdk.cubism.DocumentKind;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.SelectionSnapshot;
import dev.turboism.sdk.cubism.event.CubismOperation;
import dev.turboism.sdk.cubism.event.CubismOperationOrigin;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterGroup;
import dev.turboism.sdk.cubism.model.ParameterGroups;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionService;
import dev.turboism.sdk.cubism.transaction.TransactionManager;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutService;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PermissionIds;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.Optional;
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
    public static final String EDIT_PERMISSION = "turboism.cubism.edit";

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
    private dev.turboism.sdk.cubism.edit.EditSessionService editSessions =
        dev.turboism.sdk.cubism.edit.EditSessionService.unavailable();
    private dev.turboism.sdk.cubism.mirror.WarpMirrorService warpMirror =
        dev.turboism.sdk.cubism.mirror.WarpMirrorService.unavailable();
    private final dev.turboism.sdk.cubism.core.CoreRuntimeInfo coreRuntime;
    final ParameterLifecycleCoordinator parameterLifecycle;
    final PartLifecycleCoordinator partLifecycle;
    private final TextureAtlasLayoutService textureAtlasLayouts;
    final EditorObjectLifecycleCoordinator editorObjectLifecycle;
    private final BooleanSupplier activeScope;
    private final RuntimeTextureAtlasEditorUi textureAtlasEditorUi;
    private final RuntimeTextureAtlasEditorSession textureAtlasEditorSession;
    private final RuntimeTextureAtlasLayoutAlgorithmRegistry textureAtlasAlgorithms;
    private RuntimePsdExportService psdExportService;
    private RuntimePsdReplaceService psdReplaceService;
    /** Plugin scope auto-tied to atlas registrations; null outside the production composition. */
    private dev.turboism.sdk.plugin.DisposableScope pluginScope;
    private volatile BooleanSupplier pluginSealed = () -> false;

    private static final SelectionSnapshot EMPTY_RUNTIME_SELECTION = new SelectionSnapshot(
        List.of(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty()
    );

    final Object animationGraphOwner = new Object();

    public CubismFacadeImpl(final HostSnapshotSource source, final CubismPermissionGate permissionGate) {
        this(
            source,
            permissionGate,
            new ImmutableSnapshotFactory(),
            CubismFacadeAdapters.unavailableTransactionManager(),
            CubismFacadeAdapters.unavailableModelAccess(),
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
            CubismFacadeAdapters.unavailableTransactionManager(),
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
            CubismFacadeAdapters.unavailableTransactionManager(),
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
            CubismFacadeAdapters.unavailableTransactionManager(),
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
            CubismFacadeAdapters.unavailableCoreRuntime(),
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
            (RuntimePsdExportService) null,
            (RuntimePsdReplaceService) null
        );
    }

    CubismFacadeImpl(
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
        final RuntimePsdExportService psdExportService,
        final RuntimePsdReplaceService psdReplaceService
    ) {
        this(
            source,
            permissionGate,
            new ImmutableSnapshotFactory(),
            CubismFacadeAdapters.unavailableTransactionManager(),
            modelAccess,
            coreRuntime,
            parameterLifecycle,
            partLifecycle,
            new RuntimeTextureAtlasLayoutService(textureAtlasLayouts, permissionGate, nativeInvocations),
            editorObjectLifecycle,
            activeScope,
            textureAtlasEditorUi,
            textureAtlasEditorSession,
            textureAtlasAlgorithms,
            psdExportService,
            psdReplaceService
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
            textureAtlasAlgorithms,
            history,
            authoringTransactions,
            null,
            () -> false
        );
    }

    /**
     * Production seam that additionally binds texture-atlas algorithm registrations to the
     * plugin's {@link DisposableScope}, so a plugin that forgets to close a registration is
     * still detached — after waiting for its in-flight dispatches — when the scope closes.
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
        final CubismHistory history,
        final AuthoringTransactionService authoringTransactions,
        final dev.turboism.sdk.plugin.DisposableScope pluginScope,
        final BooleanSupplier pluginSealed
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
        this.pluginScope = pluginScope;
        this.pluginSealed = Objects.requireNonNull(pluginSealed, "pluginSealed");
    }

    /**
     * Full production construction seam including history, authoring transactions, and the
     * external-application editing-session service (spec 046, T2).
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
        final CubismHistory history,
        final AuthoringTransactionService authoringTransactions,
        final dev.turboism.sdk.cubism.edit.EditSessionService editSessions
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
            authoringTransactions
        );
        this.editSessions = Objects.requireNonNull(editSessions, "editSessions");
    }

    /**
     * Full production seam combining plugin-scope owner liveness with the
     * external-application editing-session service.
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
        final CubismHistory history,
        final AuthoringTransactionService authoringTransactions,
        final dev.turboism.sdk.plugin.DisposableScope pluginScope,
        final BooleanSupplier pluginSealed,
        final dev.turboism.sdk.cubism.edit.EditSessionService editSessions
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
            authoringTransactions,
            pluginScope,
            pluginSealed
        );
        this.editSessions = Objects.requireNonNull(editSessions, "editSessions");
    }

    /** Full production construction seam including editing sessions and the Warp mirror operation service. */
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
        final AuthoringTransactionService authoringTransactions,
        final dev.turboism.sdk.plugin.DisposableScope pluginScope,
        final BooleanSupplier pluginSealed,
        final dev.turboism.sdk.cubism.edit.EditSessionService editSessions,
        final dev.turboism.sdk.cubism.mirror.WarpMirrorService warpMirror
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
            authoringTransactions,
            pluginScope,
            pluginSealed,
            editSessions
        );
        this.warpMirror = Objects.requireNonNull(warpMirror, "warpMirror");
    }
    /** Full production seam with the external-edit PSD services bound to the shared registry. */
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
        final AuthoringTransactionService authoringTransactions,
        final RuntimePsdExportService psdExportService,
        final RuntimePsdReplaceService psdReplaceService,
        final dev.turboism.sdk.plugin.DisposableScope pluginScope,
        final BooleanSupplier pluginSealed,
        final dev.turboism.sdk.cubism.edit.EditSessionService editSessions,
        final dev.turboism.sdk.cubism.mirror.WarpMirrorService warpMirror
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
            authoringTransactions,
            pluginScope,
            pluginSealed,
            editSessions,
            warpMirror
        );
        this.psdExportService = psdExportService;
        this.psdReplaceService = psdReplaceService;
    }

    /** Full production construction seam with the optional runtime PSD observation service. */
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
        final AuthoringTransactionService authoringTransactions,
        final RuntimePsdExportService psdExportService,
        final RuntimePsdReplaceService psdReplaceService
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
            psdExportService,
            psdReplaceService
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
            CubismFacadeAdapters.unavailableTransactionManager(),
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
            CubismFacadeAdapters.unavailableCoreRuntime(),
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
            CubismFacadeAdapters.unavailableTransactionManager(),
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
        this(source, permissionGate, writeAdapter, CubismFacadeAdapters.defaultScheduler());
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
            CubismFacadeAdapters.unavailableModelAccess(),
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
            CubismFacadeAdapters.unavailableTransactionManager(),
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
            CubismFacadeAdapters.unavailableTransactionManager(),
            CubismFacadeAdapters.unavailableModelAccess(),
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
            CubismFacadeAdapters.unavailableCoreRuntime(),
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
        this(
            source,
            permissionGate,
            snapshotFactory,
            transactionManager,
            modelAccess,
            coreRuntime,
            parameterLifecycle,
            partLifecycle,
            textureAtlasLayouts,
            editorObjectLifecycle,
            activeScope,
            textureAtlasEditorUi,
            textureAtlasEditorSession,
            textureAtlasAlgorithms,
            null,
            null
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
        final RuntimeTextureAtlasLayoutAlgorithmRegistry textureAtlasAlgorithms,
        final RuntimePsdExportService psdExportService,
        final RuntimePsdReplaceService psdReplaceService
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
        this.psdExportService = psdExportService;
        this.psdReplaceService = psdReplaceService;
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
        final RuntimeRead read = observeRuntimeRead();
        // The version is derived from the very observation the snapshot was built from, so the two
        // can never disagree and the host is not read again just to compute it.
        return new SnapshotWithVersion(
            runtimeSnapshot(read),
            source.versionOfSdkRuntime(read.observed())
        );
    }

    /** Returns the original audit-capable gate for capability-aware read services. */
    public CubismReadPermissionGate readPermissionGate() {
        return permissionGate::require;
    }

    /**
     * One normalized runtime read at SDK level.
     *
     * <p>The source owns the pairing, so the project, the document, the model and the selection come
     * from one traversal and the active model is never read through a second document read. Sources
     * that already hold SDK snapshots skip the intermediate {@code Host*} projection entirely;
     * host-shaped observations are projected here exactly as before. The permission gate still runs
     * before any invalidation version is computed.</p>
     */
    private RuntimeRead observeRuntimeRead() {
        final HostSnapshotSource.SdkRuntimeObservation observed = source.observeSdkRuntime();
        if (observed.host() == null) {
            final Optional<DocumentSnapshot> document = Optional.ofNullable(observed.document());
            if (document.isPresent()) {
                permissionGate.require(MODEL_READ_PERMISSION, "runtime");
            }
            final Optional<ProjectSnapshot> project = observed.project() != null
                && projectReadAllowed()
                ? Optional.of(observed.project())
                : Optional.empty();
            return new RuntimeRead(
                project,
                document,
                document.flatMap(DocumentSnapshot::model),
                observed.selection() != null ? observed.selection() : EMPTY_RUNTIME_SELECTION,
                observed
            );
        }
        final HostSnapshotSource.Observation host = observed.host();
        // Project-read denial redacts only the project portion; the model portion stays readable.
        final Optional<HostSnapshotSource.HostProject> project =
            runtimeProjectSnapshot(host.project());
        if (host.document().isPresent()
            || host.model().isPresent()
            || hasSelection(host.selection())) {
            permissionGate.require(MODEL_READ_PERMISSION, "runtime");
        }
        return new RuntimeRead(
            project.map(snapshotFactory::project),
            host.document().map(snapshotFactory::document),
            host.model().map(snapshotFactory::model),
            snapshotFactory.selection(host.selection()),
            observed
        );
    }

    private CubismRuntimeSnapshot runtimeSnapshot() {
        return runtimeSnapshot(observeRuntimeRead());
    }

    private CubismRuntimeSnapshot runtimeSnapshot(final RuntimeRead read) {
        final Optional<ModelSnapshot> model = read.model();
        return new CubismRuntimeSnapshot(
            read.project(),
            read.document(),
            model,
            read.selection(),
            model.map(ModelSnapshot::objects).orElseGet(List::of),
            model.map(ModelSnapshot::parameters).orElseGet(List::of),
            model.map(ModelSnapshot::artMeshes).orElseGet(List::of),
            model.map(ModelSnapshot::deformers).orElseGet(List::of)
        );
    }

    /**
     * The normalized result of one runtime read: SDK-level snapshots plus the observation whose
     * evidence {@link HostSnapshotSource#versionOfSdkRuntime} versions.
     */
    private record RuntimeRead(
        Optional<ProjectSnapshot> project,
        Optional<DocumentSnapshot> document,
        Optional<ModelSnapshot> model,
        SelectionSnapshot selection,
        HostSnapshotSource.SdkRuntimeObservation observed
    ) {
    }

    @Override
    public Optional<ProjectSnapshot> activeProject() {
        requireActiveScope();
        permissionGate.require(PROJECT_READ_PERMISSION, "activeProject");
        final HostSnapshotSource.SdkRuntimeObservation observed = source.observeSdkRuntime();
        return observed.host() != null
            ? observed.host().project().map(snapshotFactory::project)
            : Optional.ofNullable(observed.project());
    }

    @Override
    public Optional<DocumentSnapshot> activeDocument() {
        requireActiveScope();
        permissionGate.require(MODEL_READ_PERMISSION, "activeDocument");
        final HostSnapshotSource.SdkRuntimeObservation observed = source.observeSdkRuntime();
        return observed.host() != null
            ? observed.host().document().map(snapshotFactory::document)
            : Optional.ofNullable(observed.document());
    }

    @Override
    public Optional<ModelSnapshot> activeModel() {
        requireActiveScope();
        permissionGate.require(MODEL_READ_PERMISSION, "activeModel");
        final HostSnapshotSource.SdkRuntimeObservation observed = source.observeSdkRuntime();
        if (observed.host() != null) {
            return observed.host().model().map(snapshotFactory::model);
        }
        final Optional<DocumentSnapshot> document = Optional.ofNullable(observed.document());
        return document
            .filter(active -> active.kind() == DocumentKind.MODEL)
            .flatMap(DocumentSnapshot::model);
    }

    @Override
    public dev.turboism.sdk.cubism.core.CoreRuntimeInfo coreRuntime() {
        requireModelRead("coreRuntime");
        return CubismFacadeAdapters.permissionCheckedCoreRuntime(this, coreRuntime);
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
    public dev.turboism.sdk.cubism.mirror.WarpMirrorService warpMirror() {
        requireActiveScope();
        final dev.turboism.sdk.cubism.mirror.WarpMirrorService delegate = warpMirror;
        return new dev.turboism.sdk.cubism.mirror.WarpMirrorService() {
            @Override
            public dev.turboism.sdk.cubism.mirror.WarpMirrorResult apply(
                final dev.turboism.sdk.cubism.mirror.WarpMirrorRequest request
            ) {
                requireActiveScope();
                permissionGate.require(MODEL_READ_PERMISSION, "warpMirror.apply");
                permissionGate.require(MODEL_WRITE_PERMISSION, "warpMirror.apply");
                return delegate.apply(request);
            }
        };
    }

    @Override
    public TransactionManager transactionManager() {
        requireActiveScope();
        return transactionManager;
    }

    @Override
    public CubismHistory history() {
        requireActiveScope();
        permissionGate.require(MODEL_READ_PERMISSION, "history");
        final CubismHistory delegate = history;
        return CubismFacadeAdapters.historyView(this, delegate);
    }

    @Override
    public AuthoringTransactionService authoringTransactions() {
        requireActiveScope();
        final AuthoringTransactionService delegate = authoringTransactions;
        return CubismFacadeAdapters.authoringTransactionsView(this, delegate);
    }

    @Override
    public dev.turboism.sdk.cubism.edit.EditSessionService edit() {
        requireActiveScope();
        final dev.turboism.sdk.cubism.edit.EditSessionService delegate = editSessions;
        return CubismFacadeAdapters.editSessionServiceView(this, delegate);
    }

    @Override
    public TextureAtlasLayoutService textureAtlasLayouts() {
        requireActiveScope();
        final TextureAtlasLayoutService delegate = textureAtlasLayouts;
        return CubismFacadeAdapters.layoutServiceView(this, delegate);
    }

    @Override
    public dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonLayoutService textureAtlasPolygonLayouts() {
        requireActiveScope();
        if (!(textureAtlasLayouts
            instanceof dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonLayoutService delegate)) {
            throw new UnsupportedOperationException(
                "Texture atlas polygon layout service is unavailable"
            );
        }
        return new dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonLayoutService() {
            @Override
            public Optional<dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonLayoutSnapshot> currentPolygon() {
                requireActiveScope();
                return delegate.currentPolygon();
            }

            @Override
            public dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutApplyResult apply(
                final dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutTarget target,
                final dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonPlan plan
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
        return CubismFacadeAdapters.editorSessionView(this, delegate);
    }

    @Override
    public dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorUi textureAtlasEditorUi() {
        requireActiveScope();
        final dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorUi delegate = textureAtlasEditorUi;
        return CubismFacadeAdapters.editorUiView(this, delegate);
    }

    @Override
    public dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithmRegistry textureAtlasAlgorithms() {
        requireActiveScope();
        final RuntimeTextureAtlasLayoutAlgorithmRegistry delegate = textureAtlasAlgorithms;
        return CubismFacadeAdapters.algorithmRegistryView(this, delegate);
    }

    /**
     * Owner liveness for texture-atlas algorithm registrations: the facade scope must be
     * active AND the plugin scope must not be sealed for teardown. The seal observation
     * is wired by the services factory; once the lifecycle exposes {@code
     * DisposableScope.isSealed()} it is passed as the {@code pluginSealed} supplier so
     * dispatch cannot start or commit after the admission seal while disable/shutdown
     * is still running.
     */
    boolean textureAtlasOwnerLive() {
        return activeScope.getAsBoolean() && !pluginSealed.getAsBoolean();
    }

    /**
     * The plugin-owned disposal scope registrations are mirrored into, or {@code null}
     * when this facade was composed without one. Read at registration time so a scope
     * bound after the facade is created still captures later registrations.
     */
    dev.turboism.sdk.plugin.DisposableScope pluginScope() {
        return pluginScope;
    }

    private Optional<HostSnapshotSource.HostProject> runtimeProjectSnapshot(
        final Optional<HostSnapshotSource.HostProject> project
    ) {
        if (project.isEmpty()) {
            return Optional.empty();
        }
        // runtime() redacts only the project portion on project-read denial so model-read plugins can still inspect model state.
        return projectReadAllowed() ? project : Optional.empty();
    }

    private boolean projectReadAllowed() {
        try {
            permissionGate.require(PROJECT_READ_PERMISSION, "runtime");
            return true;
        } catch (CubismPermissionException ignored) {
            return false;
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
        private final Object wrapperOwner = new Object();
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
        @Override public List<dev.turboism.sdk.cubism.model.ModelInstance> modelInstances() {
            requireModelRead("model.modelInstances");
            return delegate.modelInstances();
        }
        @Override public java.util.Optional<dev.turboism.sdk.cubism.model.ModelInstance> currentModelInstance() {
            requireModelRead("model.currentModelInstance");
            return delegate.currentModelInstance();
        }
        @Override public boolean modelEditing() {
            requireModelRead("model.modelEditing");
            return delegate.modelEditing();
        }
        @Override public dev.turboism.sdk.cubism.core.MocInfo mocInfo() {
            requireModelRead("model.mocInfo");
            return delegate.mocInfo();
        }

        @Override public dev.turboism.sdk.cubism.model.ModelProfile profile() {
            requireModelRead("model.profile");
            return delegate.profile();
        }

        @Override public dev.turboism.sdk.cubism.model.PhysicsSettings physicsSettings() {
            requireModelRead("model.physicsSettings");
            return delegate.physicsSettings();
        }

        @Override public dev.turboism.sdk.cubism.model.AutoYure autoYure() {
            requireModelRead("model.autoYure");
            return delegate.autoYure();
        }

        @Override public List<dev.turboism.sdk.cubism.model.AnimationDocument> animationDocuments() {
            requireModelRead("model.animationDocuments");
            return delegate.animationDocuments();
        }
        @Override public dev.turboism.sdk.cubism.model.ModelTextures textures() {
            requireModelRead("model.textures");
            final dev.turboism.sdk.cubism.model.ModelTextures textures = delegate.textures();
            return new dev.turboism.sdk.cubism.model.ModelTextures() {
                @Override public List<dev.turboism.sdk.cubism.model.RawTexture> rawImages() {
                    requireModelRead("model.textures.rawImages");
                    return textures.rawImages();
                }
                @Override public List<dev.turboism.sdk.cubism.model.ModelImageGroup> modelImageGroups() {
                    requireModelRead("model.textures.modelImageGroups");
                    return textures.modelImageGroups();
                }
                @Override public List<dev.turboism.sdk.cubism.model.AtlasTexture> textureAtlases() {
                    requireModelRead("model.textures.textureAtlases");
                    return textures.textureAtlases();
                }
                @Override public dev.turboism.sdk.cubism.model.TextureRelationsSnapshot relations() {
                    requireModelRead("model.textures.relations");
                    return textures.relations();
                }
                @Override
                public java.util.concurrent.CompletionStage<dev.turboism.sdk.cubism.psd.PsdExportResult>
                    exportRawImagePsd(
                        final dev.turboism.sdk.cubism.id.RawImageId source
                    ) {
                    requireModelRead("model.textures.exportRawImagePsd");
                    permissionGate.require(
                        PermissionIds.TURBOISM_FILE_WRITE,
                        "model.textures.exportRawImagePsd"
                    );
                    final dev.turboism.sdk.cubism.id.RawImageId rawImage =
                        Objects.requireNonNull(source, "source");
                    if (psdExportService != null && textures instanceof PsdExportHost exportHost) {
                        return psdExportService.exportRawImagePsd(exportHost, rawImage);
                    }
                    return textures.exportRawImagePsd(rawImage);
                }

                @Override
                public java.util.concurrent.CompletionStage<dev.turboism.sdk.cubism.psd.PsdReplaceResult>
                    replaceRawImagePsd(
                        final dev.turboism.sdk.cubism.id.RawImageId target,
                        final dev.turboism.sdk.cubism.psd.PsdEditFile file,
                        final dev.turboism.sdk.cubism.psd.PsdFileRevision revision
                    ) {
                    requireModelWrite("model.textures.replaceRawImagePsd");
                    permissionGate.require(
                        PermissionIds.TURBOISM_FILE_READ,
                        "model.textures.replaceRawImagePsd"
                    );
                    permissionGate.require(
                        PermissionIds.TURBOISM_FILE_WRITE,
                        "model.textures.replaceRawImagePsd"
                    );
                    final dev.turboism.sdk.cubism.id.RawImageId rawImage =
                        Objects.requireNonNull(target, "target");
                    final dev.turboism.sdk.cubism.psd.PsdEditFile editFile =
                        Objects.requireNonNull(file, "file");
                    final dev.turboism.sdk.cubism.psd.PsdFileRevision token =
                        Objects.requireNonNull(revision, "revision");
                    if (psdReplaceService != null && textures instanceof PsdReplaceHost replaceHost) {
                        return psdReplaceService.replaceRawImagePsd(
                            replaceHost, rawImage, editFile, token);
                    }
                    return textures.replaceRawImagePsd(rawImage, editFile, token);
                }
                @Override public void addModelImageGroup(final String name) {
                    requireModelWrite("model.textures.addModelImageGroup");
                    textures.addModelImageGroup(name);
                }
                @Override public void removeModelImage(
                    final dev.turboism.sdk.cubism.id.ModelImageId id
                ) {
                    requireModelWrite("model.textures.removeModelImage");
                    textures.removeModelImage(id);
                }
                @Override public dev.turboism.sdk.cubism.id.TextureAtlasId addTextureAtlas(
                    final String name,
                    final int widthPixels,
                    final int heightPixels
                ) {
                    requireModelWrite("model.textures.addTextureAtlas");
                    return textures.addTextureAtlas(name, widthPixels, heightPixels);
                }
                @Override public void removeTextureAtlas(
                    final dev.turboism.sdk.cubism.id.TextureAtlasId id
                ) {
                    requireModelWrite("model.textures.removeTextureAtlas");
                    textures.removeTextureAtlas(id);
                }
                @Override public void removeRawImage(final dev.turboism.sdk.cubism.id.RawImageId id) {
                    requireModelWrite("model.textures.removeRawImage");
                    textures.removeRawImage(id);
                }
            };
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
        @Override public dev.turboism.sdk.cubism.model.ModelStatistics statistics() {
            requireModelRead("model.statistics");
            return delegate.statistics();
        }

        @Override public java.util.List<dev.turboism.sdk.cubism.clipmask.PsdClipMaskDocumentSnapshot> psdDocuments() {
            requireModelRead("model.psdDocuments");
            return delegate.psdDocuments();
        }
        @Override public boolean defaultKeyformLocked() {
            requireModelRead("model.defaultKeyformLocked");
            return delegate.defaultKeyformLocked();
        }
        @Override public void setDefaultKeyformLocked(final boolean locked) {
            requireModelWrite("model.setDefaultKeyformLocked");
            runSemantic(
                CubismOperation.SET_MODEL_DEFAULT_KEYFORM_LOCKED,
                id().value(),
                delegate::defaultKeyformLocked,
                () -> delegate.setDefaultKeyformLocked(locked)
            );
        }
        @Override public dev.turboism.sdk.cubism.model.ModelEditLevel editLevel() {
            requireModelRead("model.editLevel");
            return delegate.editLevel();
        }
        @Override public void setEditLevel(
            final dev.turboism.sdk.cubism.model.ModelEditLevel level
        ) {
            requireModelWrite("model.setEditLevel");
            delegate.setEditLevel(level);
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
                        .map(value -> (Parameter) new PermissionCheckedParameter(CubismFacadeImpl.this, value))
                        .toList();
                }
                @Override public Parameter find(final dev.turboism.sdk.cubism.id.ParameterId id) {
                    requireModelRead("model.parameters.find");
                    return new PermissionCheckedParameter(CubismFacadeImpl.this, 
                        parameters.find(Objects.requireNonNull(id, "id"))
                    );
                }

                @Override public Parameter create(
                    final dev.turboism.sdk.cubism.model.ParameterDefinition definition
                ) {
                    requireModelWrite("model.parameters.create");
                    return new PermissionCheckedParameter(CubismFacadeImpl.this, parameters.create(definition));
                }

                @Override public Parameter create(
                    final dev.turboism.sdk.cubism.model.ParameterDefinition definition,
                    final Optional<dev.turboism.sdk.cubism.id.ParameterGroupId> folderId
                ) {
                    requireModelWrite("model.parameters.create");
                    return new PermissionCheckedParameter(CubismFacadeImpl.this, parameters.create(definition, folderId));
                }

                @Override public Parameter copy(final dev.turboism.sdk.cubism.id.ParameterId id) {
                    requireModelWrite("model.parameters.copy");
                    return new PermissionCheckedParameter(CubismFacadeImpl.this, parameters.copy(id));
                }

                @Override public void remove(final dev.turboism.sdk.cubism.id.ParameterId id) {
                    requireModelWrite("model.parameters.remove");
                    parameters.remove(id);
                }

                @Override public java.util.Optional<Parameter> findById(
                    final dev.turboism.sdk.cubism.id.ParameterId id
                ) {
                    requireModelRead("model.parameters.findById");
                    return parameters.findById(Objects.requireNonNull(id, "id"))
                        .map(value -> (Parameter) new PermissionCheckedParameter(CubismFacadeImpl.this, value));
                }

                @Override public java.util.Optional<Parameter> findById(final String id) {
                    return findById(new dev.turboism.sdk.cubism.id.ParameterId(
                        Objects.requireNonNull(id, "id")
                    ));
                }

                @Override public List<Parameter> findByName(final String name) {
                    Objects.requireNonNull(name, "name");
                    return filter(parameter -> parameter.name().filter(name::equals).isPresent());
                }

                @Override public List<Parameter> search(final String text) {
                    Objects.requireNonNull(text, "text");
                    final String query = text.toLowerCase(java.util.Locale.ROOT);
                    return filter(parameter ->
                        parameter.id().value().toLowerCase(java.util.Locale.ROOT).contains(query)
                            || parameter.name()
                                .map(value -> value.toLowerCase(java.util.Locale.ROOT).contains(query))
                                .orElse(false)
                    );
                }

                @Override public List<Parameter> filter(
                    final java.util.function.Predicate<Parameter> predicate
                ) {
                    Objects.requireNonNull(predicate, "predicate");
                    requireModelRead("model.parameters.filter");
                    return all().stream().filter(predicate).toList();
                }

                @Override public List<Parameter> createMany(
                    final List<dev.turboism.sdk.cubism.model.ParameterDefinition> definitions
                ) {
                    return createMany(definitions, java.util.Optional.empty());
                }

                @Override public List<Parameter> createMany(
                    final List<dev.turboism.sdk.cubism.model.ParameterDefinition> definitions,
                    final java.util.Optional<dev.turboism.sdk.cubism.id.ParameterGroupId> folderId
                ) {
                    requireModelWrite("model.parameters.createMany");
                    return parameters.createMany(definitions, folderId).stream()
                        .map(value -> (Parameter) new PermissionCheckedParameter(CubismFacadeImpl.this, value))
                        .toList();
                }

                @Override public void removeMany(
                    final List<dev.turboism.sdk.cubism.id.ParameterId> ids
                ) {
                    requireModelWrite("model.parameters.removeMany");
                    parameters.removeMany(ids);
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
                        .map(value -> (ParameterGroup) new PermissionCheckedParameterGroup(CubismFacadeImpl.this, value))
                        .toList();
                }
                @Override public ParameterGroup root() {
                    requireModelRead("model.parameterGroups.root");
                    return new PermissionCheckedParameterGroup(CubismFacadeImpl.this, groups.root());
                }
                @Override public ParameterGroup find(
                    final dev.turboism.sdk.cubism.id.ParameterGroupId id
                ) {
                    requireModelRead("model.parameterGroups.find");
                    return new PermissionCheckedParameterGroup(CubismFacadeImpl.this, 
                        groups.find(Objects.requireNonNull(id, "id"))
                    );
                }

                @Override public ParameterGroup addGroup(final String name) {
                    requireModelWrite("model.parameterGroups.addGroup");
                    return new PermissionCheckedParameterGroup(CubismFacadeImpl.this, groups.addGroup(name));
                }

                @Override public void removeGroup(
                    final dev.turboism.sdk.cubism.id.ParameterGroupId id
                ) {
                    requireModelWrite("model.parameterGroups.removeGroup");
                    groups.removeGroup(id);
                }

                @Override public void moveParameter(
                    final dev.turboism.sdk.cubism.id.ParameterId parameterId,
                    final dev.turboism.sdk.cubism.id.ParameterGroupId targetGroupId
                ) {
                    requireModelWrite("model.parameterGroups.moveParameter");
                    groups.moveParameter(parameterId, targetGroupId);
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
                private void write(
                    final CubismOperation semanticOperation,
                    final String operation,
                    final Runnable mutation
                ) {
                    requireModelWrite(operation);
                    runSemantic(
                        semanticOperation,
                        parameterId.value(),
                        () -> bindingSnapshot(parameterId),
                        mutation
                    );
                }
                @Override public void bind(
                    final dev.turboism.sdk.cubism.model.ParameterBindingTarget target,
                    final List<dev.turboism.sdk.cubism.model.ParameterBindingPoint> points
                ) {
                    write(
                        CubismOperation.BIND_PARAMETER,
                        "model.parameterBindings.bind",
                        () -> operations.bind(target, points)
                    );
                }
                @Override public void createPoint(
                    final dev.turboism.sdk.cubism.model.ParameterBindingTarget target,
                    final dev.turboism.sdk.cubism.model.ParameterBindingPoint point
                ) {
                    write(
                        CubismOperation.CREATE_PARAMETER_BINDING_POINT,
                        "model.parameterBindings.createPoint",
                        () -> operations.createPoint(target, point)
                    );
                }
                @Override public void movePoint(
                    final dev.turboism.sdk.cubism.model.ParameterBindingTarget target,
                    final dev.turboism.sdk.cubism.id.ParameterBindingPointId pointId,
                    final float value
                ) {
                    write(
                        CubismOperation.MOVE_PARAMETER_BINDING_POINT,
                        "model.parameterBindings.movePoint",
                        () -> operations.movePoint(target, pointId, value)
                    );
                }
                @Override public void deletePoint(
                    final dev.turboism.sdk.cubism.model.ParameterBindingTarget target,
                    final dev.turboism.sdk.cubism.id.ParameterBindingPointId pointId
                ) {
                    write(
                        CubismOperation.DELETE_PARAMETER_BINDING_POINT,
                        "model.parameterBindings.deletePoint",
                        () -> operations.deletePoint(target, pointId)
                    );
                }
                @Override public void unbind(
                    final dev.turboism.sdk.cubism.model.ParameterBindingTarget target
                ) {
                    write(
                        CubismOperation.UNBIND_PARAMETER,
                        "model.parameterBindings.unbind",
                        () -> operations.unbind(target)
                    );
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
                    runSemantic(
                        CubismOperation.INVERT_PARAMETER_BINDINGS,
                        id().value(),
                        PermissionCheckedModel.this::allBindingSnapshot,
                        () -> operations.invert(targets)
                    );
                }
                @Override public void transfer(
                    final dev.turboism.sdk.cubism.model.ParameterBindingTransferPlan plan
                ) {
                    requireModelWrite("model.parameterBindingBatch.transfer");
                    runSemantic(
                        CubismOperation.TRANSFER_PARAMETER_BINDINGS,
                        id().value(),
                        PermissionCheckedModel.this::allBindingSnapshot,
                        () -> operations.transfer(plan)
                    );
                }
                @Override public void transferClamped(
                    final dev.turboism.sdk.cubism.model.ParameterBindingTransferPlan plan
                ) {
                    requireModelWrite("model.parameterBindingBatch.transferClamped");
                    runSemantic(
                        CubismOperation.TRANSFER_PARAMETER_BINDINGS,
                        id().value(),
                        PermissionCheckedModel.this::allBindingSnapshot,
                        () -> operations.transferClamped(plan)
                    );
                }
                @Override public void transferMorphClamped(
                    final dev.turboism.sdk.cubism.model.ParameterBindingTransferPlan plan
                ) {
                    requireModelWrite("model.parameterBindingBatch.transferMorphClamped");
                    runSemantic(
                        CubismOperation.TRANSFER_PARAMETER_BINDINGS,
                        id().value(),
                        PermissionCheckedModel.this::allBindingSnapshot,
                        () -> operations.transferMorphClamped(plan)
                    );
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
                        .map(value -> (dev.turboism.sdk.cubism.model.Part)
                            new PermissionCheckedPart(CubismFacadeImpl.this, wrapperOwner, value))
                        .toList();
                }
                @Override public dev.turboism.sdk.cubism.model.Part find(
                    final dev.turboism.sdk.cubism.model.PartId id
                ) {
                    requireModelRead("model.parts.find");
                    return new PermissionCheckedPart(CubismFacadeImpl.this, 
                        wrapperOwner,
                        parts.find(Objects.requireNonNull(id, "id"))
                    );
                }

                @Override public dev.turboism.sdk.cubism.model.Part add(
                    final dev.turboism.sdk.cubism.model.PartId id
                ) {
                    requireModelWrite("model.parts.add");
                    return new PermissionCheckedPart(CubismFacadeImpl.this, wrapperOwner, parts.add(id));
                }

                @Override public dev.turboism.sdk.cubism.model.Part add(
                    final dev.turboism.sdk.cubism.model.PartId id,
                    final dev.turboism.sdk.cubism.model.PartId parentId
                ) {
                    requireModelWrite("model.parts.add");
                    return new PermissionCheckedPart(CubismFacadeImpl.this, 
                        wrapperOwner,
                        parts.add(id, parentId)
                    );
                }

                @Override public dev.turboism.sdk.cubism.model.Part copy(
                    final dev.turboism.sdk.cubism.model.PartId id
                ) {
                    requireModelWrite("model.parts.copy");
                    return new PermissionCheckedPart(CubismFacadeImpl.this, wrapperOwner, parts.copy(id));
                }

                @Override public void remove(final dev.turboism.sdk.cubism.model.PartId id) {
                    requireModelWrite("model.parts.remove");
                    parts.remove(id);
                }

                @Override public dev.turboism.sdk.cubism.model.Part create(
                    final String name,
                    final dev.turboism.sdk.cubism.model.Part parent,
                    final int index
                ) {
                    requireModelWrite("model.parts.create");
                    return new PermissionCheckedPart(CubismFacadeImpl.this, wrapperOwner, parts.create(
                        name,
                        unwrapPart(wrapperOwner, parent),
                        index
                    ));
                }
                @Override public void remove(
                    final dev.turboism.sdk.cubism.model.Part part
                ) {
                    requireModelWrite("model.parts.remove");
                    parts.remove(unwrapPart(wrapperOwner, part));
                }

                @Override public dev.turboism.sdk.cubism.model.Part create(final String name) {
                    return create(name, null, -1);
                }

                @Override public dev.turboism.sdk.cubism.model.Part add(final String id) {
                    return add(new dev.turboism.sdk.cubism.model.PartId(id));
                }

                @Override public dev.turboism.sdk.cubism.model.Part add(
                    final String id,
                    final dev.turboism.sdk.cubism.model.PartId parentId
                ) {
                    return add(new dev.turboism.sdk.cubism.model.PartId(id), parentId);
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
                            new PermissionCheckedDrawable(CubismFacadeImpl.this, wrapperOwner, value))
                        .toList();
                }
                @Override public dev.turboism.sdk.cubism.model.Drawable find(
                    final dev.turboism.sdk.cubism.id.ArtMeshId id
                ) {
                    requireModelRead("model.drawables.find");
                    return new PermissionCheckedDrawable(CubismFacadeImpl.this, 
                        wrapperOwner,
                        values.find(Objects.requireNonNull(id, "id"))
                    );
                }
                @Override public dev.turboism.sdk.cubism.model.Drawable create(
                    final String name,
                    final dev.turboism.sdk.cubism.model.Part parent,
                    final int index,
                    final dev.turboism.sdk.cubism.model.ArtMeshGeometry geometry
                ) {
                    requireModelWrite("model.drawables.create");
                    return new PermissionCheckedDrawable(CubismFacadeImpl.this, wrapperOwner, values.create(
                        name,
                        unwrapPart(wrapperOwner, parent),
                        index,
                        geometry
                    ));
                }
                @Override public void remove(
                    final dev.turboism.sdk.cubism.model.Drawable drawable
                ) {
                    requireModelWrite("model.drawables.remove");
                    values.remove(unwrapDrawable(drawable));
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
                @Override public dev.turboism.sdk.cubism.model.WarpDeformer createWarp(
                    final String name,
                    final dev.turboism.sdk.cubism.model.Part parent,
                    final int index,
                    final int rows,
                    final int columns
                ) {
                    requireModelWrite("model.deformers.createWarp");
                    return new PermissionCheckedWarpDeformer(CubismFacadeImpl.this, wrapperOwner, values.createWarp(
                        name,
                        unwrapPart(wrapperOwner, parent),
                        index,
                        rows,
                        columns
                    ));
                }
                @Override public dev.turboism.sdk.cubism.model.RotationDeformer createRotation(
                    final String name,
                    final dev.turboism.sdk.cubism.model.Part parent,
                    final int index
                ) {
                    requireModelWrite("model.deformers.createRotation");
                    return new PermissionCheckedRotationDeformer(CubismFacadeImpl.this, 
                        wrapperOwner,
                        values.createRotation(
                            name,
                            unwrapPart(wrapperOwner, parent),
                            index
                        )
                    );
                }
                @Override public void remove(
                    final dev.turboism.sdk.cubism.model.Deformer deformer
                ) {
                    requireModelWrite("model.deformers.remove");
                    values.remove(unwrapDeformer(wrapperOwner, deformer));
                }
            };
        }
        private dev.turboism.sdk.cubism.model.Deformer wrapDeformer(
            final dev.turboism.sdk.cubism.model.Deformer value
        ) {
            if (value instanceof dev.turboism.sdk.cubism.model.WarpDeformer warp) {
                return new PermissionCheckedWarpDeformer(CubismFacadeImpl.this, wrapperOwner, warp);
            }
            if (value instanceof dev.turboism.sdk.cubism.model.RotationDeformer rotation) {
                return new PermissionCheckedRotationDeformer(CubismFacadeImpl.this, wrapperOwner, rotation);
            }
            return new PermissionCheckedDeformer(CubismFacadeImpl.this, wrapperOwner, value);
        }
        @Override public dev.turboism.sdk.cubism.model.WarpDeformers warpDeformers() {
            requireModelRead("model.warpDeformers");
            final dev.turboism.sdk.cubism.model.WarpDeformers values = delegate.warpDeformers();
            return new dev.turboism.sdk.cubism.model.WarpDeformers() {
                @Override public List<dev.turboism.sdk.cubism.model.WarpDeformer> all() {
                    requireModelRead("model.warpDeformers.all");
                    return values.all().stream()
                        .map(value -> (dev.turboism.sdk.cubism.model.WarpDeformer)
                            new PermissionCheckedWarpDeformer(CubismFacadeImpl.this, wrapperOwner, value))
                        .toList();
                }
                @Override public dev.turboism.sdk.cubism.model.WarpDeformer find(
                    final dev.turboism.sdk.cubism.id.DeformerId id
                ) {
                    requireModelRead("model.warpDeformers.find");
                    return new PermissionCheckedWarpDeformer(CubismFacadeImpl.this, 
                        wrapperOwner,
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
                            new PermissionCheckedRotationDeformer(CubismFacadeImpl.this, wrapperOwner, value))
                        .toList();
                }
                @Override public dev.turboism.sdk.cubism.model.RotationDeformer find(
                    final dev.turboism.sdk.cubism.id.DeformerId id
                ) {
                    requireModelRead("model.rotationDeformers.find");
                    return new PermissionCheckedRotationDeformer(CubismFacadeImpl.this, 
                        wrapperOwner,
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
                            new PermissionCheckedGlue(CubismFacadeImpl.this, value))
                        .toList();
                }
                @Override public dev.turboism.sdk.cubism.model.Glue find(
                    final dev.turboism.sdk.cubism.model.GlueId id
                ) {
                    requireModelRead("model.glues.find");
                    return new PermissionCheckedGlue(CubismFacadeImpl.this, 
                        values.find(Objects.requireNonNull(id, "id"))
                    );
                }
                @Override public java.util.Optional<String> providerVersion() {
                    requireModelRead("model.glues.providerVersion");
                    return values.providerVersion();
                }
            };
        }
        @Override public void update() {
            requireModelWrite("model.update");
            runSemanticConfirmed(CubismOperation.UPDATE_MODEL, id().value(), delegate::update);
        }

        private List<dev.turboism.sdk.cubism.model.ParameterBinding> bindingSnapshot(
            final dev.turboism.sdk.cubism.id.ParameterId parameterId
        ) {
            return List.copyOf(delegate.parameters().find(parameterId).getParameterBindings());
        }

        private List<dev.turboism.sdk.cubism.model.ParameterBinding> allBindingSnapshot() {
            return delegate.parameters().all().stream()
                .flatMap(parameter -> parameter.getParameterBindings().stream())
                .toList();
        }

        @Override
        public void replaceArtMeshClipMasks(
            final List<dev.turboism.sdk.cubism.clipmask.ClipMaskReplacement> replacements
        ) {
            requireModelWrite("model.replaceArtMeshClipMasks");
            delegate.replaceArtMeshClipMasks(List.copyOf(Objects.requireNonNull(replacements, "replacements")));
        }
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


    dev.turboism.sdk.cubism.model.AnimationAttribute unwrapAnimationAttribute(
        final Object expectedOwner,
        final dev.turboism.sdk.cubism.model.AnimationAttribute value
    ) {
        if (!(value instanceof PermissionCheckedAnimationAttribute checked)
            || checked.owner != expectedOwner) {
            throw new IllegalArgumentException(
                "Animation attribute belongs to another Cubism facade"
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
        final Object expectedOwner,
        final dev.turboism.sdk.cubism.model.Deformer value
    ) {
        if (value instanceof PermissionCheckedWarpDeformer checked
            && checked.ownedBy(expectedOwner)) {
            return checked.warp;
        }
        if (value instanceof PermissionCheckedRotationDeformer checked
            && checked.ownedBy(expectedOwner)) {
            return checked.rotation;
        }
        if (value instanceof PermissionCheckedDeformer checked
            && checked.ownedBy(expectedOwner)) {
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
