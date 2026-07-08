package dev.turboism.adapter.cubism;

import dev.turboism.sdk.cubism.ArtMeshSnapshot;
import dev.turboism.sdk.cubism.CubismRuntimeSnapshot;
import dev.turboism.sdk.cubism.DeformerSnapshot;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelObjectSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ParameterSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.SelectionSnapshot;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

final class ImmutableSnapshotFactory {

    CubismRuntimeSnapshot runtime(
        final Optional<HostSnapshotSource.HostProject> project,
        final Optional<HostSnapshotSource.HostDocument> document,
        final Optional<HostSnapshotSource.HostModel> model,
        final HostSnapshotSource.HostSelection selection
    ) {
        final Optional<ModelSnapshot> modelSnapshot = model.map(this::model);
        return new CubismRuntimeSnapshot(
            project.map(this::project),
            document.map(this::document),
            modelSnapshot,
            selection(selection),
            modelSnapshot.map(ModelSnapshot::objects).orElseGet(List::of),
            modelSnapshot.map(ModelSnapshot::parameters).orElseGet(List::of),
            modelSnapshot.map(ModelSnapshot::artMeshes).orElseGet(List::of),
            modelSnapshot.map(ModelSnapshot::deformers).orElseGet(List::of)
        );
    }

    ProjectSnapshot project(final HostSnapshotSource.HostProject project) {
        Objects.requireNonNull(project, "project");
        return new ProjectSnapshot(
            project.projectId(),
            project.name(),
            relativePath(project.projectDirectory(), "projectDirectory"),
            project.documents().stream().map(this::document).toList()
        );
    }

    DocumentSnapshot document(final HostSnapshotSource.HostDocument document) {
        Objects.requireNonNull(document, "document");
        final String relativePath = normalizedRelativePath(document.relativePath(), "relativePath");
        return new DocumentSnapshot(
            document.documentId(),
            document.name(),
            relativePath,
            relativePath(document.filePath(), "filePath"),
            document.model().map(this::model)
        );
    }

    ModelSnapshot model(final HostSnapshotSource.HostModel model) {
        Objects.requireNonNull(model, "model");
        final List<ParameterSnapshot> parameters = model.parameters().stream().map(this::parameter).toList();
        final List<ArtMeshSnapshot> artMeshes = model.artMeshes().stream().map(this::artMesh).toList();
        final List<DeformerSnapshot> deformers = model.deformers().stream().map(this::deformer).toList();
        final List<ModelObjectSnapshot> objects = new ArrayList<>(parameters.size() + artMeshes.size() + deformers.size());
        objects.addAll(parameters);
        objects.addAll(artMeshes);
        objects.addAll(deformers);
        return new ModelSnapshot(model.modelId(), model.name(), objects, parameters, artMeshes, deformers);
    }

    SelectionSnapshot selection(final HostSnapshotSource.HostSelection selection) {
        Objects.requireNonNull(selection, "selection");
        return new SelectionSnapshot(
            selection.selectedObjectIds(),
            selection.activeParameterId(),
            selection.activeArtMeshId(),
            selection.activeDeformerId()
        );
    }

    private ParameterSnapshot parameter(final HostSnapshotSource.HostParameter parameter) {
        return new ParameterSnapshot(
            parameter.id(),
            parameter.name(),
            parameter.value(),
            parameter.defaultValue(),
            parameter.minValue(),
            parameter.maxValue(),
            parameter.visible(),
            parameter.editable()
        );
    }

    private ArtMeshSnapshot artMesh(final HostSnapshotSource.HostArtMesh artMesh) {
        return new ArtMeshSnapshot(
            artMesh.id(),
            artMesh.name(),
            artMesh.textureId(),
            artMesh.visible(),
            artMesh.renderable()
        );
    }

    private DeformerSnapshot deformer(final HostSnapshotSource.HostDeformer deformer) {
        return new DeformerSnapshot(
            deformer.id(),
            deformer.name(),
            deformer.type(),
            deformer.parentId(),
            deformer.childIds()
        );
    }

    private Optional<Path> relativePath(final Optional<Path> path, final String fieldName) {
        Objects.requireNonNull(path, fieldName);
        return path.map(value -> Path.of(normalizedRelativePath(value.toString(), fieldName)));
    }

    private String normalizedRelativePath(final String value, final String fieldName) {
        final String normalized = Objects.requireNonNull(value, fieldName).replace('\\', '/');
        if (normalized.isBlank() || normalized.startsWith("/") || normalized.contains("..")) {
            throw new IllegalArgumentException(fieldName + " must be a relative path without parent segments");
        }
        return normalized;
    }
}
