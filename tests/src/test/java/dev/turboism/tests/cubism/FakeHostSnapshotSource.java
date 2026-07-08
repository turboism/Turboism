package dev.turboism.tests.cubism;

import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.sdk.cubism.DeformerType;
import dev.turboism.test.fake.FakeCubismArtMesh;
import dev.turboism.test.fake.FakeCubismDeformer;
import dev.turboism.test.fake.FakeCubismDocument;
import dev.turboism.test.fake.FakeCubismHost;
import dev.turboism.test.fake.FakeCubismModel;
import dev.turboism.test.fake.FakeCubismParameter;
import dev.turboism.test.fake.FakeCubismProject;
import dev.turboism.test.fake.FakeCubismSelection;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

final class FakeHostSnapshotSource implements HostSnapshotSource {

    private final FakeCubismHost host;

    FakeHostSnapshotSource(final FakeCubismHost host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    @Override
    public Optional<HostProject> activeProject() {
        return Optional.ofNullable(host.getActiveProject()).map(this::project);
    }

    @Override
    public Optional<HostDocument> activeDocument() {
        return Optional.ofNullable(host.getActiveDocument()).map(this::document);
    }

    @Override
    public Optional<HostModel> activeModel() {
        return Optional.ofNullable(host.getActiveModel()).map(this::model);
    }

    @Override
    public HostSelection selection() {
        final FakeCubismSelection selection = host.getSelection();
        return new HostSelection(
            selection.getSelectedIds(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        );
    }

    @Override
    public boolean isHostPresent() {
        return host.isRunning();
    }

    private HostProject project(final FakeCubismProject project) {
        return new HostProject(
            project.getId(),
            project.getName(),
            Optional.of(Path.of("projects", project.getId())),
            project.getDocuments().stream().map(this::document).toList()
        );
    }

    private HostDocument document(final FakeCubismDocument document) {
        return new HostDocument(
            document.getId(),
            document.getName(),
            "documents/" + document.getId() + ".cdi3.json",
            Optional.of(Path.of("documents", document.getId() + ".cdi3.json")),
            document.getModels().stream().findFirst().map(this::model)
        );
    }

    private HostModel model(final FakeCubismModel model) {
        return new HostModel(
            model.getId(),
            model.getName(),
            model.getParameters().stream().map(this::parameter).toList(),
            model.getArtMeshes().stream().map(this::artMesh).toList(),
            model.getDeformers().stream().map(this::deformer).toList()
        );
    }

    private HostParameter parameter(final FakeCubismParameter parameter) {
        return new HostParameter(
            parameter.getId(),
            parameter.getName(),
            parameter.getValue(),
            parameter.getDefaultValue(),
            parameter.getMinValue(),
            parameter.getMaxValue(),
            true,
            true
        );
    }

    private HostArtMesh artMesh(final FakeCubismArtMesh artMesh) {
        return new HostArtMesh(
            artMesh.getId(),
            artMesh.getName(),
            Optional.empty(),
            true,
            true
        );
    }

    private HostDeformer deformer(final FakeCubismDeformer deformer) {
        return new HostDeformer(
            deformer.getId(),
            deformer.getName(),
            deformerType(deformer.getDeformerType()),
            Optional.empty(),
            List.of()
        );
    }

    private DeformerType deformerType(final String value) {
        if ("warp".equalsIgnoreCase(value)) {
            return DeformerType.WARP;
        }
        if ("rotation".equalsIgnoreCase(value)) {
            return DeformerType.ROTATION;
        }
        if ("translation".equalsIgnoreCase(value)) {
            return DeformerType.TRANSLATION;
        }
        if ("root".equalsIgnoreCase(value)) {
            return DeformerType.ROOT;
        }
        return DeformerType.OTHER;
    }
}
