package dev.turboism.adapter.cubism;

import dev.turboism.sdk.cubism.DeformerType;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public interface HostSnapshotSource {

    Optional<HostProject> activeProject();

    Optional<HostDocument> activeDocument();

    Optional<HostModel> activeModel();

    HostSelection selection();

    boolean isHostPresent();

    long invalidationToken();

    record HostProject(
        String projectId,
        String name,
        Optional<Path> projectDirectory,
        List<HostDocument> documents
    ) {
        public HostProject {
            projectDirectory = Objects.requireNonNull(projectDirectory, "projectDirectory");
            documents = List.copyOf(documents);
        }
    }

    record HostDocument(
        String documentId,
        String name,
        String relativePath,
        Optional<Path> filePath,
        Optional<HostModel> model
    ) {
        public HostDocument {
            filePath = Objects.requireNonNull(filePath, "filePath");
            model = Objects.requireNonNull(model, "model");
        }
    }

    record HostModel(
        String modelId,
        String name,
        List<HostParameter> parameters,
        List<HostArtMesh> artMeshes,
        List<HostDeformer> deformers
    ) {
        public HostModel {
            parameters = List.copyOf(parameters);
            artMeshes = List.copyOf(artMeshes);
            deformers = List.copyOf(deformers);
        }
    }

    record HostSelection(
        List<String> selectedObjectIds,
        Optional<String> activeParameterId,
        Optional<String> activeArtMeshId,
        Optional<String> activeDeformerId
    ) {
        public HostSelection {
            selectedObjectIds = List.copyOf(selectedObjectIds);
            activeParameterId = Objects.requireNonNull(activeParameterId, "activeParameterId");
            activeArtMeshId = Objects.requireNonNull(activeArtMeshId, "activeArtMeshId");
            activeDeformerId = Objects.requireNonNull(activeDeformerId, "activeDeformerId");
        }
    }

    record HostParameter(
        String id,
        String name,
        double value,
        double defaultValue,
        double minValue,
        double maxValue,
        boolean visible,
        boolean editable
    ) {
    }

    record HostArtMesh(
        String id,
        String name,
        Optional<String> textureId,
        boolean visible,
        boolean renderable
    ) {
        public HostArtMesh {
            textureId = Objects.requireNonNull(textureId, "textureId");
        }
    }

    record HostDeformer(
        String id,
        String name,
        DeformerType type,
        Optional<String> parentId,
        List<String> childIds
    ) {
        public HostDeformer {
            parentId = Objects.requireNonNull(parentId, "parentId");
            childIds = List.copyOf(childIds);
        }
    }
}
