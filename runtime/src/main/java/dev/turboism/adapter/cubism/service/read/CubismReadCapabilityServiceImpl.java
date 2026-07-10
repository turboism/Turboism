package dev.turboism.adapter.cubism.service.read;

import dev.turboism.adapter.cubism.ClipMaskReadAdapter;
import dev.turboism.adapter.cubism.CubismFacadeImpl;
import dev.turboism.adapter.cubism.ProjectWorkspaceAdapter;
import dev.turboism.adapter.cubism.RenderStatusAdapter;
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
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fake-first runtime implementation of the M12 read-capability aggregation
 * surface. It composes the existing M6 facade with a supplemental adapter source
 * and M14 simulated-host adapters for selected capability families.
 */
public final class CubismReadCapabilityServiceImpl implements CubismReadCapabilityService {

    private final CubismFacade facade;
    private final M12ReadSnapshotSource m12Source;
    private final ThemeStatusAdapter themeStatusAdapter;
    private final RenderStatusAdapter renderStatusAdapter;
    private final ProjectWorkspaceAdapter projectWorkspaceAdapter;
    private final ClipMaskReadAdapter clipMaskReadAdapter;
    private final List<SafeModeDiagnostic> themeStatusDiagnostics = new CopyOnWriteArrayList<>();
    private final List<SafeModeDiagnostic> renderStatusDiagnostics = new CopyOnWriteArrayList<>();
    private final List<SafeModeDiagnostic> projectWorkspaceDiagnostics = new CopyOnWriteArrayList<>();
    private final List<SafeModeDiagnostic> clipMaskDiagnostics = new CopyOnWriteArrayList<>();

    public CubismReadCapabilityServiceImpl(final CubismFacade facade) {
        this(facade, M12ReadSnapshotSource.EMPTY);
    }

    public CubismReadCapabilityServiceImpl(final CubismFacade facade, final M12ReadSnapshotSource m12Source) {
        this(facade, m12Source, ThemeStatusAdapterImpl.safeMode());
    }

    public CubismReadCapabilityServiceImpl(
        final CubismFacade facade,
        final M12ReadSnapshotSource m12Source,
        final ThemeStatusAdapter themeStatusAdapter
    ) {
        this(
            facade,
            m12Source,
            themeStatusAdapter,
            RenderStatusAdapter.Impl.safeMode(),
            ProjectWorkspaceAdapter.Impl.safeMode(),
            ClipMaskReadAdapter.Impl.safeMode()
        );
    }

    public CubismReadCapabilityServiceImpl(
        final CubismFacade facade,
        final M12ReadSnapshotSource m12Source,
        final ThemeStatusAdapter themeStatusAdapter,
        final RenderStatusAdapter renderStatusAdapter,
        final ProjectWorkspaceAdapter projectWorkspaceAdapter,
        final ClipMaskReadAdapter clipMaskReadAdapter
    ) {
        this.facade = Objects.requireNonNull(facade, "facade");
        this.m12Source = Objects.requireNonNull(m12Source, "m12Source");
        this.themeStatusAdapter = Objects.requireNonNull(themeStatusAdapter, "themeStatusAdapter");
        this.renderStatusAdapter = Objects.requireNonNull(renderStatusAdapter, "renderStatusAdapter");
        this.projectWorkspaceAdapter = Objects.requireNonNull(projectWorkspaceAdapter, "projectWorkspaceAdapter");
        this.clipMaskReadAdapter = Objects.requireNonNull(clipMaskReadAdapter, "clipMaskReadAdapter");
    }

    @Override
    public Optional<ProjectSnapshot> activeProject() {
        final ProjectWorkspaceAdapter.AdapterResult<Optional<ProjectSnapshot>> adapterResult =
            projectWorkspaceAdapter.activeProject();
        if (adapterResult.isAvailable()) {
            return adapterResult.value().orElseThrow();
        }
        adapterResult.diagnostic().ifPresent(projectWorkspaceDiagnostics::add);
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
        return runtime().selection();
    }

    @Override
    public List<ParameterSnapshot> parameters() {
        requireModelRead("parameters");
        return runtime().parameters();
    }

    @Override
    public List<ModelObjectSnapshot> modelObjects() {
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
        adapterResult.diagnostic().ifPresent(clipMaskDiagnostics::add);
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
        adapterResult.diagnostic().ifPresent(renderStatusDiagnostics::add);
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
        adapterResult.diagnostic().ifPresent(projectWorkspaceDiagnostics::add);
        return m12Source.workspace();
    }

    @Override
    public Optional<ThemeStatusSnapshot> themeStatus() {
        requireProjectRead("themeStatus");
        final ThemeStatusAdapter.AdapterResult<Optional<ThemeStatusSnapshot>> adapterResult = themeStatusAdapter.themeStatus();
        if (adapterResult.isAvailable()) {
            return adapterResult.value().orElseThrow();
        }
        adapterResult.diagnostic().ifPresent(themeStatusDiagnostics::add);
        return m12Source.themeStatus();
    }

    public List<SafeModeDiagnostic> themeStatusDiagnostics() {
        return List.copyOf(themeStatusDiagnostics);
    }

    public List<SafeModeDiagnostic> renderStatusDiagnostics() {
        return List.copyOf(renderStatusDiagnostics);
    }

    public List<SafeModeDiagnostic> projectWorkspaceDiagnostics() {
        return List.copyOf(projectWorkspaceDiagnostics);
    }

    public List<SafeModeDiagnostic> clipMaskDiagnostics() {
        return List.copyOf(clipMaskDiagnostics);
    }

    /**
     * @deprecated use {@link #themeStatusDiagnostics()} after theme status was split from status-toolbar adapter
     */
    @Deprecated
    public List<SafeModeDiagnostic> statusToolbarDiagnostics() {
        return themeStatusDiagnostics();
    }

    private CubismRuntimeSnapshot runtime() {
        return facade.runtime();
    }

    private void requireModelRead(String operation) {
        if (facade instanceof CubismFacadeImpl impl) {
            impl.activeModel();
            return;
        }
        facade.runtime();
    }

    private void requireProjectRead(String operation) {
        facade.activeProject();
    }
}
