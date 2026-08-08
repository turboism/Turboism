package dev.turboism.adapter.cubism;

import dev.turboism.sdk.cubism.DeformerType;
import dev.turboism.sdk.cubism.DocumentKind;
import dev.turboism.sdk.cubism.ProjectContentKind;
import dev.turboism.sdk.cubism.ResourceKind;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public interface HostSnapshotSource {

    Optional<HostProject> activeProject();

    Optional<HostDocument> activeDocument();

    /** Model owned by the active MODEL document only. */
    Optional<HostModel> activeModel();

    HostSelection selection();

    boolean isHostPresent();

    long invalidationToken();

    record HostProject(
        String projectId,
        String name,
        Optional<Path> projectDirectory,
        List<HostProjectContent> contents,
        List<HostDocument> documents
    ) {
        public HostProject {
            projectDirectory = Objects.requireNonNull(projectDirectory, "projectDirectory");
            contents = List.copyOf(Objects.requireNonNull(contents, "contents"));
            documents = List.copyOf(Objects.requireNonNull(documents, "documents"));
        }

        public HostProject(
            final String projectId,
            final String name,
            final Optional<Path> projectDirectory,
            final List<HostDocument> documents
        ) {
            this(projectId, name, projectDirectory, List.of(), documents);
        }
    }

    record HostProjectContent(
        String contentId,
        String name,
        ProjectContentKind kind,
        Optional<Path> filePath,
        List<String> documentIds,
        List<HostProjectResource> resources
    ) {
        public HostProjectContent {
            kind = Objects.requireNonNull(kind, "kind");
            filePath = Objects.requireNonNull(filePath, "filePath");
            documentIds = List.copyOf(Objects.requireNonNull(documentIds, "documentIds"));
            resources = List.copyOf(Objects.requireNonNull(resources, "resources"));
        }
    }

    record HostProjectResource(
        String resourceId,
        String name,
        ResourceKind kind,
        Optional<String> relativePath
    ) {
        public HostProjectResource {
            kind = Objects.requireNonNull(kind, "kind");
            relativePath = Objects.requireNonNull(relativePath, "relativePath");
        }
    }

    record HostDocument(
        String documentId,
        String name,
        DocumentKind kind,
        String relativePath,
        Optional<Path> filePath,
        Optional<String> contentId,
        Optional<HostModel> model,
        Optional<HostAnimation> animation
    ) {
        public HostDocument {
            kind = Objects.requireNonNull(kind, "kind");
            filePath = Objects.requireNonNull(filePath, "filePath");
            contentId = Objects.requireNonNull(contentId, "contentId");
            model = Objects.requireNonNull(model, "model");
            animation = Objects.requireNonNull(animation, "animation");
            if (kind != DocumentKind.MODEL && model.isPresent()) {
                throw new IllegalArgumentException("Only MODEL documents may own HostModel");
            }
            if (kind != DocumentKind.ANIMATION_SCENE && animation.isPresent()) {
                throw new IllegalArgumentException(
                    "Only ANIMATION_SCENE documents may own HostAnimation"
                );
            }
        }

        public HostDocument(
            final String documentId,
            final String name,
            final String relativePath,
            final Optional<Path> filePath,
            final Optional<HostModel> model
        ) {
            this(
                documentId,
                name,
                model.isPresent() ? DocumentKind.MODEL : DocumentKind.OTHER,
                relativePath,
                filePath,
                Optional.empty(),
                model,
                Optional.empty()
            );
        }
    }

    record HostAnimation(
        String animationId,
        String name,
        Optional<Path> filePath,
        List<String> sceneDocumentIds,
        Optional<String> activeSceneDocumentId
    ) {
        public HostAnimation {
            filePath = Objects.requireNonNull(filePath, "filePath");
            sceneDocumentIds = List.copyOf(Objects.requireNonNull(sceneDocumentIds, "sceneDocumentIds"));
            activeSceneDocumentId = Objects.requireNonNull(
                activeSceneDocumentId,
                "activeSceneDocumentId"
            );
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
