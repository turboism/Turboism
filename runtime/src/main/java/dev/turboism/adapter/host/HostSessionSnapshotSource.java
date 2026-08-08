package dev.turboism.adapter.host;

import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.adapter.cubism.ProjectWorkspaceAdapter;
import dev.turboism.sdk.cubism.AnimationSnapshot;
import dev.turboism.sdk.cubism.ArtMeshSnapshot;
import dev.turboism.sdk.cubism.DeformerSnapshot;
import dev.turboism.sdk.cubism.DeformerType;
import dev.turboism.sdk.cubism.DocumentKind;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ParameterSnapshot;
import dev.turboism.sdk.cubism.ProjectContentSnapshot;
import dev.turboism.sdk.cubism.ProjectResourceSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.model.Deformer;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.hostread.ProjectWorkspaceSnapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Complete Cubism facade snapshot source composed from one HostSession view. */
public final class HostSessionSnapshotSource implements HostSnapshotSource {
    private static final HostSelection EMPTY_SELECTION = new HostSelection(
        List.of(), Optional.empty(), Optional.empty(), Optional.empty()
    );

    private final ProjectWorkspaceAdapter projectWorkspace;
    private final CubismModelAccess modelAccess;

    private HostSessionSnapshotSource(
        final ProjectWorkspaceAdapter projectWorkspace,
        final CubismModelAccess modelAccess
    ) {
        this.projectWorkspace = Objects.requireNonNull(projectWorkspace, "projectWorkspace");
        this.modelAccess = Objects.requireNonNull(modelAccess, "modelAccess");
    }

    public static HostSnapshotSource forSession(
        final ProjectWorkspaceAdapter projectWorkspace,
        final CubismModelAccess modelAccess
    ) {
        return new HostSessionSnapshotSource(projectWorkspace, modelAccess);
    }

    @Override
    public Optional<HostProject> activeProject() {
        return state().flatMap(value -> value.project().map(project -> project(project, value)));
    }

    @Override
    public Optional<HostDocument> activeDocument() {
        return state().flatMap(value -> value.document().map(document -> document(
            document, value.model()
        )));
    }

    @Override
    public Optional<HostModel> activeModel() {
        return state().flatMap(State::model);
    }

    @Override
    public HostSelection selection() {
        return EMPTY_SELECTION;
    }

    @Override
    public boolean isHostPresent() {
        return state().isPresent();
    }

    @Override
    public long invalidationToken() {
        if (modelAccess instanceof DynamicCubismModelAccess dynamic) {
            try {
                return dynamic.modelGeneration();
            } catch (RuntimeException unavailable) {
                return 0L;
            }
        }
        return 0L;
    }

    private Optional<State> state() {
        try {
            final ProjectWorkspaceAdapter.AdapterResult<ProjectWorkspaceSnapshot> result =
                projectWorkspace.projectWorkspaceSnapshot();
            if (!result.isAvailable()) return Optional.empty();
            final ProjectWorkspaceSnapshot snapshot = result.value().orElseThrow();
            final Optional<DocumentSnapshot> document = activeDocumentSnapshot();
            if (snapshot.project().isEmpty() && document.isEmpty()) return Optional.empty();
            return Optional.of(new State(
                snapshot.project(),
                document,
                document.flatMap(this::activeModelSnapshot)
            ));
        } catch (RuntimeException unavailable) {
            return Optional.empty();
        }
    }

    private Optional<DocumentSnapshot> activeDocumentSnapshot() {
        final ProjectWorkspaceAdapter.AdapterResult<Optional<DocumentSnapshot>> result =
            projectWorkspace.activeDocument();
        return result.isAvailable() ? result.value().orElse(Optional.empty()) : Optional.empty();
    }

    private Optional<HostModel> activeModelSnapshot(final DocumentSnapshot document) {
        if (document.kind() != DocumentKind.MODEL) return Optional.empty();
        final Optional<HostModel> baseline = document.model().map(this::model);
        try {
            return Optional.of(model(modelAccess.active(), baseline));
        } catch (RuntimeException unavailable) {
            return baseline;
        }
    }

    private HostProject project(final ProjectSnapshot source, final State state) {
        final List<HostDocument> documents = new ArrayList<>();
        for (DocumentSnapshot document : source.documents()) {
            documents.add(document(document, modelFor(document, state)));
        }
        state.document().ifPresent(active -> {
            if (documents.stream().noneMatch(document -> document.documentId().equals(active.documentId()))) {
                documents.add(document(active, state.model()));
            }
        });
        return new HostProject(
            source.projectId(),
            source.name(),
            source.projectDirectory(),
            source.contents().stream().map(this::content).toList(),
            documents
        );
    }

    private Optional<HostModel> modelFor(final DocumentSnapshot document, final State state) {
        if (state.document().filter(active -> active.documentId().equals(document.documentId())).isPresent()) {
            return state.model();
        }
        return Optional.empty();
    }

    private HostDocument document(
        final DocumentSnapshot source,
        final Optional<HostModel> modelOverride
    ) {
        final Optional<HostModel> model = source.kind() == DocumentKind.MODEL
            ? modelOverride.or(() -> source.model().map(this::model))
            : Optional.empty();
        return new HostDocument(
            source.documentId(),
            source.name(),
            source.kind(),
            source.relativePath(),
            source.filePath(),
            source.contentId(),
            model,
            source.animation().map(this::animation)
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
            source.parameters().stream().map(this::hostParameter).toList(),
            source.artMeshes().stream().map(this::hostArtMesh).toList(),
            source.deformers().stream().map(this::hostDeformer).toList()
        );
    }

    private HostParameter hostParameter(final ParameterSnapshot source) {
        return new HostParameter(
            source.id(), source.name(), source.value(), source.defaultValue(), source.minValue(),
            source.maxValue(), source.visible(), source.editable()
        );
    }

    private HostArtMesh hostArtMesh(final ArtMeshSnapshot source) {
        return new HostArtMesh(
            source.id(), source.name(), source.textureId(), source.visible(), source.renderable()
        );
    }

    private HostDeformer hostDeformer(final DeformerSnapshot source) {
        return new HostDeformer(
            source.id(), source.name(), source.type(), source.parentId(), source.childIds()
        );
    }

    private HostModel model(
        final CubismModel source,
        final Optional<HostModel> baseline
    ) {
        final String modelId = text(
            () -> source.id().value(), baseline.map(HostModel::modelId).orElse("")
        );
        final String name = text(
            source::name, baseline.map(HostModel::name).orElse(modelId)
        );
        final List<HostParameter> parameters = parameters(
            source, baseline.map(HostModel::parameters).orElse(List.of())
        );
        final List<HostArtMesh> artMeshes = artMeshes(
            source, baseline.map(HostModel::artMeshes).orElse(List.of())
        );
        final List<HostDeformer> deformers = deformers(
            source, baseline.map(HostModel::deformers).orElse(List.of())
        );
        return new HostModel(modelId, name, parameters, artMeshes, deformers);
    }

    private List<HostParameter> parameters(
        final CubismModel model,
        final List<HostParameter> fallback
    ) {
        try {
            final List<HostParameter> result = new ArrayList<>();
            for (Parameter parameter : model.parameters().all()) {
                final String id = parameter.id().value();
                final HostParameter previous = findParameter(fallback, id);
                final String name = text(
                    () -> parameter.name().orElse(previous == null ? id : previous.name()),
                    previous == null ? id : previous.name()
                );
                result.add(new HostParameter(
                    id,
                    name,
                    parameter.getValue(),
                    parameter.getDefaultValue(),
                    parameter.getMinimumValue(),
                    parameter.getMaximumValue(),
                    previous == null || previous.visible(),
                    previous == null || previous.editable()
                ));
            }
            return List.copyOf(result);
        } catch (RuntimeException unavailable) {
            return fallback;
        }
    }

    private List<HostArtMesh> artMeshes(
        final CubismModel model,
        final List<HostArtMesh> fallback
    ) {
        try {
            final List<HostArtMesh> result = new ArrayList<>();
            for (Drawable drawable : model.drawables().all()) {
                final String id = drawable.id().value();
                final HostArtMesh previous = findArtMesh(fallback, id);
                final String name = text(
                    drawable::name, previous == null ? id : previous.name()
                );
                final boolean visible = value(
                    drawable::visible, previous == null || previous.visible()
                );
                final boolean renderable = value(
                    () -> drawable.evaluationState().evaluatedVisible(),
                    previous == null ? visible : previous.renderable()
                );
                result.add(new HostArtMesh(
                    id,
                    name,
                    previous == null ? Optional.empty() : previous.textureId(),
                    visible,
                    renderable
                ));
            }
            return List.copyOf(result);
        } catch (RuntimeException unavailable) {
            return fallback;
        }
    }

    private List<HostDeformer> deformers(
        final CubismModel model,
        final List<HostDeformer> fallback
    ) {
        try {
            final List<Deformer> source = model.deformers().all();
            final List<DeformerEntry> entries = new ArrayList<>();
            for (int index = 0; index < source.size(); index++) {
                final Deformer deformer = source.get(index);
                final String id = deformer.id().value();
                final HostDeformer previous = findDeformer(fallback, id);
                final int parentIndex = deformer.parentDeformerIndex();
                final Optional<String> parentId = parentIndex >= 0 && parentIndex < source.size()
                    ? Optional.of(source.get(parentIndex).id().value())
                    : previous == null ? Optional.empty() : previous.parentId();
                final DeformerType type = deformer instanceof dev.turboism.sdk.cubism.model.WarpDeformer
                    ? DeformerType.WARP
                    : deformer instanceof dev.turboism.sdk.cubism.model.RotationDeformer
                        ? DeformerType.ROTATION
                        : previous == null ? DeformerType.OTHER : previous.type();
                entries.add(new DeformerEntry(
                    id,
                    text(deformer::name, previous == null ? id : previous.name()),
                    type,
                    parentId
                ));
            }
            final Map<String, List<String>> children = new HashMap<>();
            for (DeformerEntry entry : entries) {
                entry.parentId().ifPresent(parent ->
                    children.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(entry.id())
                );
            }
            return entries.stream().map(entry -> new HostDeformer(
                entry.id(),
                entry.name(),
                entry.type(),
                entry.parentId(),
                children.getOrDefault(entry.id(), List.of())
            )).toList();
        } catch (RuntimeException unavailable) {
            return fallback;
        }
    }

    private ParameterSnapshot parameter(final HostParameter source) {
        return new ParameterSnapshot(
            source.id(), source.name(), source.value(), source.defaultValue(),
            source.minValue(), source.maxValue(), source.visible(), source.editable()
        );
    }

    private ArtMeshSnapshot artMesh(final HostArtMesh source) {
        return new ArtMeshSnapshot(
            source.id(), source.name(), source.textureId(), source.visible(), source.renderable()
        );
    }

    private DeformerSnapshot deformer(final HostDeformer source) {
        return new DeformerSnapshot(
            source.id(), source.name(), source.type(), source.parentId(), source.childIds()
        );
    }

    private static HostParameter findParameter(final List<HostParameter> values, final String id) {
        return values.stream().filter(value -> value.id().equals(id)).findFirst().orElse(null);
    }

    private static HostArtMesh findArtMesh(final List<HostArtMesh> values, final String id) {
        return values.stream().filter(value -> value.id().equals(id)).findFirst().orElse(null);
    }

    private static HostDeformer findDeformer(final List<HostDeformer> values, final String id) {
        return values.stream().filter(value -> value.id().equals(id)).findFirst().orElse(null);
    }

    private static String text(final Supplier<String> supplier, final String fallback) {
        try {
            final String value = supplier.get();
            return value == null || value.isBlank() ? fallback : value;
        } catch (RuntimeException unavailable) {
            return fallback;
        }
    }

    private static boolean value(final Supplier<Boolean> supplier, final boolean fallback) {
        try {
            return Boolean.TRUE.equals(supplier.get());
        } catch (RuntimeException unavailable) {
            return fallback;
        }
    }

    private record State(
        Optional<ProjectSnapshot> project,
        Optional<DocumentSnapshot> document,
        Optional<HostModel> model
    ) {
        private State {
            project = Objects.requireNonNull(project, "project");
            document = Objects.requireNonNull(document, "document");
            model = Objects.requireNonNull(model, "model");
        }
    }

    private record DeformerEntry(
        String id,
        String name,
        DeformerType type,
        Optional<String> parentId
    ) { }
}
