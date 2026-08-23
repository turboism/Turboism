package dev.turboism.adapter.cubism.service.read;

import dev.turboism.adapter.cubism.ClipMaskReadAdapter;
import dev.turboism.adapter.cubism.CubismFacadeImpl;
import dev.turboism.adapter.cubism.ProjectWorkspaceAdapter;
import dev.turboism.adapter.cubism.RenderStatusAdapter;
import dev.turboism.adapter.ui.BoundedKeyedStore;
import dev.turboism.adapter.ui.SafeModeDiagnostic;
import dev.turboism.adapter.ui.ThemeStatusAdapter;
import dev.turboism.adapter.ui.ThemeStatusAdapterImpl;
import dev.turboism.sdk.cubism.ArtMeshSnapshot;
import dev.turboism.sdk.cubism.ClipMaskSnapshot;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.CubismRuntimeSnapshot;
import dev.turboism.sdk.cubism.DeformerSnapshot;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelObjectSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ParameterSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.PsdDocumentSnapshot;
import dev.turboism.sdk.cubism.RenderStatusSnapshot;
import dev.turboism.sdk.cubism.SelectionSnapshot;
import dev.turboism.sdk.cubism.TextureAtlasSnapshot;
import dev.turboism.sdk.cubism.WorkspaceSnapshot;
import dev.turboism.sdk.cubism.service.read.CubismReadCapabilityService;
import dev.turboism.sdk.theme.ThemeStatusSnapshot;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Runtime implementation of the SDK read-service aggregation surface. It
 * composes the base facade with supplemental and host-specific adapter sources
 * for selected read families.
 */
public final class CubismReadCapabilityServiceImpl implements CubismReadCapabilityService {

    private static final int MAX_DIAGNOSTICS = 64;

    private final CubismFacade facade;
    private final M12ReadSnapshotSource m12Source;
    private final ThemeStatusAdapter themeStatusAdapter;
    private final RenderStatusAdapter renderStatusAdapter;
    private final ProjectWorkspaceAdapter projectWorkspaceAdapter;
    private final ClipMaskReadAdapter clipMaskReadAdapter;
    private final String ownerPluginId;
    private final CubismReadPermissionGate permissionGate;

    private final BoundedKeyedStore<String, SafeModeDiagnostic> themeStatusDiagnostics =
        new BoundedKeyedStore<>(MAX_DIAGNOSTICS);
    private final BoundedKeyedStore<String, SafeModeDiagnostic> renderStatusDiagnostics =
        new BoundedKeyedStore<>(MAX_DIAGNOSTICS);
    private final BoundedKeyedStore<String, SafeModeDiagnostic> projectWorkspaceDiagnostics =
        new BoundedKeyedStore<>(MAX_DIAGNOSTICS);
    private final BoundedKeyedStore<String, SafeModeDiagnostic> clipMaskDiagnostics =
        new BoundedKeyedStore<>(MAX_DIAGNOSTICS);

    public CubismReadCapabilityServiceImpl(
        final CubismFacade facade,
        final M12ReadSnapshotSource m12Source,
        final ThemeStatusAdapter themeStatusAdapter,
        final RenderStatusAdapter renderStatusAdapter,
        final ProjectWorkspaceAdapter projectWorkspaceAdapter,
        final ClipMaskReadAdapter clipMaskReadAdapter,
        final String ownerPluginId,
        final CubismReadPermissionGate permissionGate
    ) {
        this.facade = Objects.requireNonNull(facade, "facade");
        this.m12Source = Objects.requireNonNull(m12Source, "m12Source");
        this.themeStatusAdapter = Objects.requireNonNull(themeStatusAdapter, "themeStatusAdapter");
        this.renderStatusAdapter = Objects.requireNonNull(renderStatusAdapter, "renderStatusAdapter");
        this.projectWorkspaceAdapter = Objects.requireNonNull(projectWorkspaceAdapter, "projectWorkspaceAdapter");
        this.clipMaskReadAdapter = Objects.requireNonNull(clipMaskReadAdapter, "clipMaskReadAdapter");
        this.ownerPluginId = requireText(ownerPluginId, "ownerPluginId");
        this.permissionGate = Objects.requireNonNull(permissionGate, "permissionGate");
    }

    @Override
    public Optional<ProjectSnapshot> activeProject() {
        return facade.activeProject();
    }

    @Override
    public Optional<DocumentSnapshot> activeDocument() {
        return facade.activeDocument();
    }

    @Override
    public Optional<ModelSnapshot> activeModel() {
        return facade.activeModel();
    }

    @Override
    public SelectionSnapshot selection() {
        requireModelRead("selection");
        return runtime().selection();
    }

    @Override
    public List<ParameterSnapshot> parameters() {
        requireModelRead("parameters");
        return runtime().parameters();
    }

    @Override
    public List<ModelObjectSnapshot> modelObjects() {
        requireModelRead("modelObjects");
        return runtime().modelObjects();
    }

    @Override
    public List<ArtMeshSnapshot> meshes() {
        requireModelRead("meshes");
        return runtime().artMeshes();
    }

    @Override
    public List<DeformerSnapshot> deformers() {
        requireModelRead("deformers");
        return runtime().deformers();
    }

    @Override
    public List<PsdDocumentSnapshot> psdDocuments() {
        requireModelRead("psdDocuments");
        return List.copyOf(m12Source.psdDocuments());
    }

    @Override
    public List<ClipMaskSnapshot> clipMasks() {
        requireModelRead("clipMasks");
        final ClipMaskReadAdapter.AdapterResult<List<ClipMaskSnapshot>> adapterResult = clipMaskReadAdapter.clipMasks();
        if (adapterResult.isAvailable()) {
            return List.copyOf(adapterResult.value().orElseThrow());
        }
        adapterResult.diagnostic().ifPresent(diagnostic -> record(clipMaskDiagnostics, diagnostic));
        return List.copyOf(m12Source.clipMasks());
    }

    @Override
    public List<TextureAtlasSnapshot> textureAtlases() {
        requireModelRead("textureAtlases");
        return List.copyOf(m12Source.textureAtlases());
    }

    @Override
    public Optional<RenderStatusSnapshot> renderStatus() {
        requireModelRead("renderStatus");
        final RenderStatusAdapter.AdapterResult<Optional<RenderStatusSnapshot>> adapterResult =
            renderStatusAdapter.renderStatus();
        if (adapterResult.isAvailable()) {
            return adapterResult.value().orElseThrow();
        }
        adapterResult.diagnostic().ifPresent(diagnostic -> record(renderStatusDiagnostics, diagnostic));
        return m12Source.renderStatus();
    }

    @Override
    public Optional<WorkspaceSnapshot> workspace() {
        requireProjectRead("workspace");
        final ProjectWorkspaceAdapter.AdapterResult<Optional<WorkspaceSnapshot>> adapterResult =
            projectWorkspaceAdapter.workspace();
        if (adapterResult.isAvailable()) {
            return adapterResult.value().orElseThrow();
        }
        adapterResult.diagnostic().ifPresent(diagnostic -> record(projectWorkspaceDiagnostics, diagnostic));
        return m12Source.workspace();
    }

    @Override
    public Optional<ThemeStatusSnapshot> themeStatus() {
        requireProjectRead("themeStatus");
        final ThemeStatusAdapter.AdapterResult<Optional<ThemeStatusSnapshot>> adapterResult = themeStatusAdapter.themeStatus();
        if (adapterResult.isAvailable()) {
            return adapterResult.value().orElseThrow();
        }
        adapterResult.diagnostic().ifPresent(diagnostic -> record(themeStatusDiagnostics, diagnostic));
        return m12Source.themeStatus();
    }

    /**
     * @return an immutable snapshot of the safe-mode diagnostics recorded while the theme-status
     *     adapter was unavailable and reads fell back to the minimum snapshot source; capped at 64
     *     entries and empty when the adapter has always answered
     */
    public List<SafeModeDiagnostic> themeStatusDiagnostics() {
        return themeStatusDiagnostics.snapshot();
    }

    /**
     * @return an immutable snapshot of the safe-mode diagnostics recorded while the render-status
     *     adapter was unavailable and reads fell back to the minimum snapshot source; capped at 64
     *     entries
     */
    public List<SafeModeDiagnostic> renderStatusDiagnostics() {
        return renderStatusDiagnostics.snapshot();
    }

    /**
     * @return an immutable snapshot of the safe-mode diagnostics recorded while the project-workspace
     *     adapter was unavailable and reads fell back to the minimum snapshot source; capped at 64
     *     entries
     */
    public List<SafeModeDiagnostic> projectWorkspaceDiagnostics() {
        return projectWorkspaceDiagnostics.snapshot();
    }

    /**
     * @return an immutable snapshot of the safe-mode diagnostics recorded while the clip-mask read
     *     adapter was unavailable and reads fell back to the minimum snapshot source; capped at 64
     *     entries
     */
    public List<SafeModeDiagnostic> clipMaskDiagnostics() {
        return clipMaskDiagnostics.snapshot();
    }

    /**
     * @deprecated use {@link #themeStatusDiagnostics()} after theme status was split from status-toolbar adapter
     */
    @Deprecated
    public List<SafeModeDiagnostic> statusToolbarDiagnostics() {
        return themeStatusDiagnostics();
    }

    private void record(
        final BoundedKeyedStore<String, SafeModeDiagnostic> store,
        final SafeModeDiagnostic diagnostic
    ) {
        store.put(
            ownerPluginId + "|" + diagnostic.code().name() + "|" + diagnostic.capability(),
            diagnostic
        );
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private CubismRuntimeSnapshot runtime() {
        return facade.runtime();
    }

    private void requireModelRead(final String operation) {
        requireCapability(
            CubismFacadeImpl.MODEL_READ_PERMISSION,
            operation,
            capabilityIdFor(operation)
        );
    }

    private void requireProjectRead(final String operation) {
        requireCapability(
            CubismFacadeImpl.PROJECT_READ_PERMISSION,
            operation,
            capabilityIdFor(operation)
        );
    }

    private void requireCapability(
        final String permissionId,
        final String operation,
        final String capabilityId
    ) {
        permissionGate.require(permissionId, "cubismRead." + operation, capabilityId);
    }


    private static String capabilityIdFor(final String operation) {
        return switch (operation) {
            case "selection" -> "cubism.selection.read";
            case "parameters" -> "cubism.parameter.read";
            case "modelObjects" -> "cubism.model-tree.read";
            case "workspace" -> ProjectWorkspaceAdapter.WORKSPACE_CAPABILITY_ID;
            case "meshes" -> "cubism.mesh.read";
            case "deformers" -> "cubism.deformer.read";
            case "psdDocuments" -> "cubism.psd.read";
            case "clipMasks" -> ClipMaskReadAdapter.CAPABILITY_ID;
            case "textureAtlases" -> "cubism.texture-atlas.read";
            case "renderStatus" -> RenderStatusAdapter.CAPABILITY_ID;
            case "themeStatus" -> ThemeStatusAdapter.CAPABILITY_ID;
            default -> throw new IllegalArgumentException("Unknown Cubism read operation " + operation);
        };
    }
}
