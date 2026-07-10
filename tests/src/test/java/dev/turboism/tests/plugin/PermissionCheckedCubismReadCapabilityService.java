package dev.turboism.tests.plugin;

import dev.turboism.adapter.cubism.CubismFacadeImpl;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.ArtMeshSnapshot;
import dev.turboism.sdk.cubism.ClipMaskSnapshot;
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
 * Test-only decorator that keeps injected fake read services behind the same
 * project/model permission families used by the production Cubism facade.
 */
final class PermissionCheckedCubismReadCapabilityService implements CubismReadCapabilityService {

    private final PermissionChecker permissions;
    private final CubismReadCapabilityService delegate;

    private PermissionCheckedCubismReadCapabilityService(
        final PermissionChecker permissions,
        final CubismReadCapabilityService delegate
    ) {
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    static CubismReadCapabilityService wrap(
        final PermissionChecker permissions,
        final CubismReadCapabilityService delegate
    ) {
        if (delegate == null) {
            return null;
        }
        return new PermissionCheckedCubismReadCapabilityService(permissions, delegate);
    }

    @Override
    public Optional<ProjectSnapshot> activeProject() {
        requireProjectRead("activeProject");
        return delegate.activeProject();
    }

    @Override
    public Optional<DocumentSnapshot> activeDocument() {
        requireModelRead("activeDocument");
        return delegate.activeDocument();
    }

    @Override
    public Optional<ModelSnapshot> activeModel() {
        requireModelRead("activeModel");
        return delegate.activeModel();
    }

    @Override
    public SelectionSnapshot selection() {
        requireModelRead("selection");
        return delegate.selection();
    }

    @Override
    public List<ParameterSnapshot> parameters() {
        requireModelRead("parameters");
        return delegate.parameters();
    }

    @Override
    public List<ModelObjectSnapshot> modelObjects() {
        requireModelRead("modelObjects");
        return delegate.modelObjects();
    }

    @Override
    public List<ArtMeshSnapshot> meshes() {
        requireModelRead("meshes");
        return delegate.meshes();
    }

    @Override
    public List<DeformerSnapshot> deformers() {
        requireModelRead("deformers");
        return delegate.deformers();
    }

    @Override
    public List<PsdDocumentSnapshot> psdDocuments() {
        requireModelRead("psdDocuments");
        return delegate.psdDocuments();
    }

    @Override
    public List<ClipMaskSnapshot> clipMasks() {
        requireModelRead("clipMasks");
        return delegate.clipMasks();
    }

    @Override
    public List<TextureAtlasSnapshot> textureAtlases() {
        requireModelRead("textureAtlases");
        return delegate.textureAtlases();
    }

    @Override
    public Optional<RenderStatusSnapshot> renderStatus() {
        requireModelRead("renderStatus");
        return delegate.renderStatus();
    }

    @Override
    public Optional<WorkspaceSnapshot> workspace() {
        requireProjectRead("workspace");
        return delegate.workspace();
    }

    @Override
    public Optional<ThemeStatusSnapshot> themeStatus() {
        requireProjectRead("themeStatus");
        return delegate.themeStatus();
    }

    private void requireModelRead(final String operation) {
        permissions.check(CubismFacadeImpl.MODEL_READ_PERMISSION, operation);
    }

    private void requireProjectRead(final String operation) {
        permissions.check(CubismFacadeImpl.PROJECT_READ_PERMISSION, operation);
    }
}
