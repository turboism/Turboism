package dev.turboism.adapter.cubism;

import dev.turboism.sdk.cubism.DeformerType;
import dev.turboism.sdk.cubism.DocumentKind;
import dev.turboism.sdk.cubism.ProjectContentKind;
import dev.turboism.sdk.cubism.ResourceKind;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The runtime's read seam onto live Editor state.
 *
 * <p>Implementations project the host's mutable object graph into the immutable {@code Host*} records
 * declared here; those records copy their collections defensively at construction, so a returned
 * snapshot never changes underneath a caller. Reads are expected to run on the Cubism host thread,
 * and a value read here is only a snapshot of the moment it was taken.
 *
 * <p>When no host is attached, implementations report {@link #isHostPresent()} false and answer the
 * accessors with empty values rather than throwing. {@link #invalidationToken()} lets callers detect
 * that anything observable through this source may have changed.
 */
public interface HostSnapshotSource {

    Optional<HostProject> activeProject();

    Optional<HostDocument> activeDocument();

    /** Model owned by the active MODEL document only. */
    Optional<HostModel> activeModel();

    HostSelection selection();

    boolean isHostPresent();

    long invalidationToken();

    /**
     * Returns one coherent observation of the host for a single logical read.
     *
     * <p>A source that can pair its reads overrides this so the project, the document, the model and
     * the selection come from one traversal. The default composes the individual accessors, which is
     * what a source that has nothing to pair does anyway.</p>
     *
     * @return an observation owned by this source; never null
     */
    default Observation observe() {
        return new Observation(activeProject(), activeDocument(), activeModel(), selection(), null);
    }

    /**
     * Returns the invalidation token for an observation this source produced.
     *
     * <p>The default keeps {@link #invalidationToken()} semantics. A source that can compare the
     * observation it made with its previous observation answers from that comparison instead of
     * re-reading the host, so the caller's snapshot and its version describe the same moment.</p>
     *
     * @param observation an observation returned by this source; never null
     * @return the token that corresponds to the supplied observation
     */
    default long versionOf(final Observation observation) {
        Objects.requireNonNull(observation, "observation");
        return invalidationToken();
    }

    /**
     * One coherent observation of the host, created by the source that produced it.
     *
     * <p>{@code evidence} is opaque to everyone but the producing source: it is how a source keeps the
     * unprojected values it compared against without exposing them, and without letting a projection
     * that drops fields (for example {@code ModelSnapshot.objects}, which {@link HostModel} does not
     * carry) weaken the change detection.</p>
     */
    final class Observation {

        private final Optional<HostProject> project;
        private final Optional<HostDocument> document;
        private final Optional<HostModel> model;
        private final HostSelection selection;
        private final Object evidence;

        /**
         * @param project the observed project; never null
         * @param document the observed document; never null
         * @param model the model owned by the observed MODEL document; never null
         * @param selection the observed selection; never null
         * @param evidence opaque value for the producing source, or {@code null}
         */
        public Observation(
            final Optional<HostProject> project,
            final Optional<HostDocument> document,
            final Optional<HostModel> model,
            final HostSelection selection,
            final Object evidence
        ) {
            this.project = Objects.requireNonNull(project, "project");
            this.document = Objects.requireNonNull(document, "document");
            this.model = Objects.requireNonNull(model, "model");
            this.selection = Objects.requireNonNull(selection, "selection");
            this.evidence = evidence;
        }

        /** @return the observed project; empty when the host reported none or it was redacted */
        public Optional<HostProject> project() {
            return project;
        }

        /** @return the observed document; empty when the host reported none */
        public Optional<HostDocument> document() {
            return document;
        }

        /** @return the model owned by the observed MODEL document; empty for any other kind */
        public Optional<HostModel> model() {
            return model;
        }

        /** @return the observed selection */
        public HostSelection selection() {
            return selection;
        }

        /** @return the opaque evidence for the producing source; may be {@code null} */
        public Object evidence() {
            return evidence;
        }
    }

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
