package dev.turboism.adapter.cubism;

import dev.turboism.sdk.cubism.DeformerType;
import dev.turboism.sdk.cubism.DocumentKind;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ProjectContentKind;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.ResourceKind;
import dev.turboism.sdk.cubism.SelectionSnapshot;

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

    /**
     * @return the project currently open on the host; empty when no host is attached or no
     *         project is open
     */
    Optional<HostProject> activeProject();

    /**
     * @return the document currently active on the host; empty when no host is attached or no
     *         document is active
     */
    Optional<HostDocument> activeDocument();

    /** Model owned by the active MODEL document only. */
    Optional<HostModel> activeModel();

    /**
     * @return the observed selection; an empty-valued snapshot when no host is attached,
     *         never null
     */
    HostSelection selection();

    /**
     * @return whether this source currently observes host content; the meaning is
     *         implementation-defined (e.g. whether a project or document was observed), not
     *         a transport-level connection indicator — when {@code false} the accessors
     *         answer with empty values rather than throwing
     */
    boolean isHostPresent();

    /**
     * An implementation-defined invalidation signal: it advances when the inputs this
     * particular source watches change.
     *
     * <p>What counts as a change is specific to each implementation — a source may advance
     * only when its own observed inputs differ, so an observable value that is not part of
     * those inputs can change without advancing the token. The token is therefore a
     * may-have-changed signal, not a complete change counter.</p>
     *
     * @return the current token; comparing two tokens detects possible invalidation but
     *         equality alone does not prove nothing changed unless the implementation
     *         documents stronger semantics
     */
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
     * One coherent runtime read at SDK level.
     *
     * <p>A source whose underlying read already produces SDK snapshots overrides this so the
     * caller skips the intermediate {@code Host*} projection and the SDK re-projection it
     * feeds. The default delegates to {@link #observe()} and keeps the host-typed observation
     * in {@link SdkRuntimeObservation#host()} for the caller to project as before.</p>
     *
     * @return one observation owned by this source; never null
     */
    default SdkRuntimeObservation observeSdkRuntime() {
        return new SdkRuntimeObservation(observe(), null, null, null, null);
    }

    /**
     * Returns the invalidation token for one {@link #observeSdkRuntime()} result.
     *
     * <p>The default applies {@link #versionOf(Observation)} to a host-shaped observation, or
     * {@link #invalidationToken()} for an SDK-shaped one the source did not specialize; a source
     * that fills {@link SdkRuntimeObservation#evidence()} overrides this to compare exactly what
     * was observed instead of re-reading the host.</p>
     *
     * @param observed a result returned by this source; never null
     * @return the token that corresponds to the supplied observation
     */
    default long versionOfSdkRuntime(final SdkRuntimeObservation observed) {
        Objects.requireNonNull(observed, "observed");
        return observed.host() != null ? versionOf(observed.host()) : invalidationToken();
    }

    /**
     * The result of {@link #observeSdkRuntime()}: either {@link #host()} carrying the raw
     * observation for the caller to project, or SDK-level {@link #project()}/{@link #document()}/
     * {@link #selection()} with {@link #evidence()} the producing source recognizes for
     * versioning.
     */
    record SdkRuntimeObservation(
        Observation host,
        ProjectSnapshot project,
        DocumentSnapshot document,
        SelectionSnapshot selection,
        Object evidence
    ) {
        public SdkRuntimeObservation {
            if (host != null
                && (project != null || document != null || selection != null || evidence != null)) {
                throw new IllegalArgumentException(
                    "SdkRuntimeObservation carries either a host observation or SDK snapshots"
                );
            }
            if (host == null && evidence == null) {
                throw new IllegalArgumentException(
                    "SDK-shaped observation requires evidence for versioning"
                );
            }
        }
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

    /**
     * Immutable projection of one host project.
     *
     * @param projectId stable host identity of the project
     * @param name display name of the project
     * @param projectDirectory on-disk project directory when the host reported one; never null
     * @param contents project contents (copied defensively); never null
     * @param documents documents belonging to the project (copied defensively); never null
     */
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

    /**
     * Immutable projection of one project content entry.
     *
     * @param contentId stable host identity of the content
     * @param name display name of the content
     * @param kind the project content classification; never null
     * @param filePath on-disk file when the host reported one; never null
     * @param documentIds ids of the documents this content produced (copied defensively); never null
     * @param resources resources attached to the content (copied defensively); never null
     */
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

    /**
     * Immutable projection of one resource attached to a project content.
     *
     * @param resourceId stable host identity of the resource
     * @param name display name of the resource
     * @param kind the resource classification; never null
     * @param relativePath project-relative path when the host reported one; never null
     */
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

    /**
     * Immutable projection of one open host document.
     *
     * @param documentId stable host identity of the document
     * @param name display name of the document
     * @param kind the document classification; never null
     * @param relativePath project-relative path of the document
     * @param filePath on-disk file when the host reported one; never null
     * @param contentId id of the owning project content when known; never null
     * @param model the model owned by a {@link DocumentKind#MODEL} document; must be empty for
     *        any other kind; never null
     * @param animation the animation owned by an {@link DocumentKind#ANIMATION_SCENE} document;
     *        must be empty for any other kind; never null
     */
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

    /**
     * Immutable projection of one animation owned by an ANIMATION_SCENE document.
     *
     * @param animationId stable host identity of the animation
     * @param name display name of the animation
     * @param filePath on-disk file when the host reported one; never null
     * @param sceneDocumentIds ids of the scene documents inside the animation (copied
     *        defensively); never null
     * @param activeSceneDocumentId id of the currently active scene when one is active; never null
     */
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

    /**
     * Immutable projection of the model owned by a MODEL document.
     *
     * @param modelId stable host identity of the model
     * @param name display name of the model
     * @param parameters model parameters (copied defensively); never null
     * @param artMeshes model ArtMeshes (copied defensively); never null
     * @param deformers model deformers (copied defensively); never null
     */
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

    /**
     * Immutable projection of the host selection.
     *
     * @param selectedObjectIds ids of all currently selected objects (copied defensively);
     *        never null
     * @param activeParameterId id of the active parameter when one is active; never null
     * @param activeArtMeshId id of the active ArtMesh when one is active; never null
     * @param activeDeformerId id of the active deformer when one is active; never null
     */
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

        /**
         * The canonical "nothing selected" value. Also the honest answer when no verified
         * live-selection read is wired, when no document is active, or when no host is
         * attached — it is never used to mask a failed read of a live document.
         */
        public static HostSelection empty() {
            return new HostSelection(List.of(), Optional.empty(), Optional.empty(), Optional.empty());
        }
    }

    /**
     * Immutable projection of one model parameter.
     *
     * @param id stable parameter identity
     * @param name display name
     * @param value the observed value at snapshot time
     * @param defaultValue the parameter's default value
     * @param minValue the parameter's minimum value
     * @param maxValue the parameter's maximum value
     * @param visible whether the parameter is visible in the host palette
     * @param editable whether the parameter can be edited through the host palette
     */
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

    /**
     * Immutable projection of one ArtMesh.
     *
     * @param id stable ArtMesh identity
     * @param name display name
     * @param textureId id of the texture the ArtMesh is bound to when known; never null
     * @param visible whether the ArtMesh is marked visible
     * @param renderable whether the ArtMesh can actually render (for example not fully
     *        masked or clipped away)
     */
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

    /**
     * Immutable projection of one deformer.
     *
     * @param id stable deformer identity
     * @param name display name
     * @param type the deformer classification; never null
     * @param parentId id of the parent deformer when one exists; never null
     * @param childIds ids of the child deformers (copied defensively); never null
     */
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
