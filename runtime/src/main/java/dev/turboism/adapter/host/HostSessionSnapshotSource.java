package dev.turboism.adapter.host;

import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.adapter.cubism.ProjectWorkspaceAdapter;
import dev.turboism.sdk.cubism.AnimationSnapshot;
import dev.turboism.sdk.cubism.ArtMeshSnapshot;
import dev.turboism.sdk.cubism.DeformerSnapshot;
import dev.turboism.sdk.cubism.DocumentKind;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ParameterSnapshot;
import dev.turboism.sdk.cubism.ProjectContentSnapshot;
import dev.turboism.sdk.cubism.ProjectResourceSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Canonical Cubism facade snapshot source projected from the active host-session
 * project-workspace adapter. Every field of the adapter's immutable snapshots is
 * preserved verbatim; no live model traversal is performed here. The direct
 * model API remains {@code PluginContext.cubism().model()}.
 */
public final class HostSessionSnapshotSource implements HostSnapshotSource {

    private static final HostSelection EMPTY_SELECTION = new HostSelection(
        List.of(), Optional.empty(), Optional.empty(), Optional.empty()
    );

    private final ProjectWorkspaceAdapter projectWorkspace;

    private HostSessionSnapshotSource(final ProjectWorkspaceAdapter projectWorkspace) {
        this.projectWorkspace = Objects.requireNonNull(projectWorkspace, "projectWorkspace");
    }

    public static HostSnapshotSource forSession(final ProjectWorkspaceAdapter projectWorkspace) {
        return new HostSessionSnapshotSource(projectWorkspace);
    }

    @Override
    public Optional<HostProject> activeProject() {
        return available(projectWorkspace.activeProject()).map(this::project);
    }

    @Override
    public Optional<HostDocument> activeDocument() {
        return available(projectWorkspace.activeDocument()).map(this::document);
    }

    @Override
    public Optional<HostModel> activeModel() {
        return activeDocument()
            .filter(document -> document.kind() == DocumentKind.MODEL)
            .flatMap(HostDocument::model);
    }

    @Override
    public HostSelection selection() {
        return EMPTY_SELECTION;
    }

    @Override
    public boolean isHostPresent() {
        return activeProject().isPresent() || activeDocument().isPresent();
    }

    @Override
    public long invalidationToken() {
        return 0L;
    }

    private HostProject project(final ProjectSnapshot source) {
        return new HostProject(
            source.projectId(),
            source.name(),
            source.projectDirectory(),
            source.contents().stream().map(this::content).toList(),
            source.documents().stream().map(this::document).toList()
        );
    }

    private HostProjectContent content(final ProjectContentSnapshot source) {
        return new HostProjectContent(
            source.contentId(),
            source.name(),
            source.kind(),
            source.filePath(),
            source.documentIds(),
            source.resources().stream().map(this::resource).toList()
        );
    }

    private HostProjectResource resource(final ProjectResourceSnapshot source) {
        return new HostProjectResource(
            source.resourceId(), source.name(), source.kind(), source.relativePath()
        );
    }

    private HostDocument document(final DocumentSnapshot source) {
        return new HostDocument(
            source.documentId(),
            source.name(),
            source.kind(),
            source.relativePath(),
            source.filePath(),
            source.contentId(),
            source.model().map(this::model),
            source.animation().map(this::animation)
        );
    }

    private HostAnimation animation(final AnimationSnapshot source) {
        return new HostAnimation(
            source.animationId(),
            source.name(),
            source.filePath(),
            source.sceneDocumentIds(),
            source.activeSceneDocumentId()
        );
    }

    private HostModel model(final ModelSnapshot source) {
        return new HostModel(
            source.modelId(),
            source.name(),
            source.parameters().stream().map(this::parameter).toList(),
            source.artMeshes().stream().map(this::artMesh).toList(),
            source.deformers().stream().map(this::deformer).toList()
        );
    }

    private HostParameter parameter(final ParameterSnapshot source) {
        return new HostParameter(
            source.id(), source.name(), source.value(), source.defaultValue(),
            source.minValue(), source.maxValue(), source.visible(), source.editable()
        );
    }

    private HostArtMesh artMesh(final ArtMeshSnapshot source) {
        return new HostArtMesh(
            source.id(), source.name(), source.textureId(), source.visible(), source.renderable()
        );
    }

    private HostDeformer deformer(final DeformerSnapshot source) {
        return new HostDeformer(
            source.id(), source.name(), source.type(), source.parentId(), source.childIds()
        );
    }

    private static <T> Optional<T> available(
        final ProjectWorkspaceAdapter.AdapterResult<Optional<T>> result
    ) {
        return result.isAvailable() ? result.value().orElse(Optional.empty()) : Optional.empty();
    }
}
