package dev.turboism.tests.cubism;

import dev.turboism.adapter.cubism.CubismFacadeImpl;
import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.diagnostics.CubismFacadeAuditEvent;
import dev.turboism.kernel.context.CorePluginContext;
import dev.turboism.sdk.cubism.DeformerType;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.WorkBudget;
import dev.turboism.test.Stubs;
import dev.turboism.test.fake.FakeCubismArtMesh;
import dev.turboism.test.fake.FakeCubismDeformer;
import dev.turboism.test.fake.FakeCubismDocument;
import dev.turboism.test.fake.FakeCubismHost;
import dev.turboism.test.fake.FakeCubismModel;
import dev.turboism.test.fake.FakeCubismParameter;
import dev.turboism.test.fake.FakeCubismProject;
import dev.turboism.testkit.RecordingHostSnapshotSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class CubismQueryIntegrationSupport {

    static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-08T04:00:00Z"), ZoneOffset.UTC);
    static final String PLUGIN_ID = "plugin.query-tests";
    static final String MODEL_READ_PERMISSION = CubismFacadeImpl.MODEL_READ_PERMISSION;
    static final String MESH_READ_PERMISSION = CubismFacadeImpl.MESH_READ_PERMISSION;

    private static final HostSnapshotSource.HostArtMesh MESH =
            new HostSnapshotSource.HostArtMesh("mesh-face", "Face Mesh", Optional.empty(), true, true);
    private static final HostSnapshotSource.HostDeformer DEFORMER = new HostSnapshotSource.HostDeformer(
            "deformer-root", "Root", DeformerType.ROOT, Optional.empty(), List.of("mesh-face"));

    private CubismQueryIntegrationSupport() {}

    static QueryEnvironment environment(final FakeCubismHost host, final String... permissionIds) {
        return environment(new FakeHostSnapshotSource(host), permissionIds);
    }

    static QueryEnvironment environment(final HostSnapshotSource source, final String... permissionIds) {
        final List<CubismFacadeAuditEvent> auditEvents = new ArrayList<>();
        final DisposableScope disposableScope = new DisposableScope();
        final CorePluginContext context = new CorePluginContext(new CorePluginContext.Dependencies(
                TestPluginDependencies.descriptor(permissionIds),
                Stubs.noopLogger(),
                TestPluginDependencies.paths(),
                TestPluginDependencies.directUiScheduler(),
                directRuntimeScheduler(),
                TestPluginDependencies.emptyDiagnostics(),
                disposableScope,
                source,
                auditEvents::add,
                FIXED_CLOCK));
        return new QueryEnvironment(context, auditEvents, disposableScope);
    }

    static FakeCubismHost sampleHost() {
        final FakeCubismHost host = new FakeCubismHost();
        final FakeCubismProject project = new FakeCubismProject("project-1", "Demo Project");
        final FakeCubismDocument document = new FakeCubismDocument("document-1", "Demo Document");
        final FakeCubismModel model = new FakeCubismModel("model-1", "Demo Model");
        final FakeCubismParameter angleX = new FakeCubismParameter("param-angle-x", "Angle X");
        angleX.setValue(3.0F);
        angleX.setDefaultValue(0.0F);
        angleX.setMinValue(-30.0F);
        angleX.setMaxValue(30.0F);
        final FakeCubismParameter opacity = new FakeCubismParameter("param-opacity", "Opacity");
        opacity.setValue(1.0F);
        opacity.setDefaultValue(1.0F);
        opacity.setMinValue(0.0F);
        opacity.setMaxValue(1.0F);
        final FakeCubismArtMesh faceMesh = new FakeCubismArtMesh("mesh-face", "Face Mesh");
        final FakeCubismDeformer root = new FakeCubismDeformer("deformer-root", "Root");
        root.setDeformerType("root");
        model.addParameter(angleX);
        model.addParameter(opacity);
        model.addArtMesh(faceMesh);
        model.addDeformer(root);
        document.addModel(model);
        project.addDocument(document);
        host.start();
        host.addProject(project);
        host.setActiveProjectId(project.getId());
        host.setActiveDocument(document);
        host.setActiveModel(model);
        return host;
    }

    static RecordingHostSnapshotSource versionedSource(final List<HostSnapshotSource.HostParameter> parameters) {
        return RecordingHostSnapshotSource.builder()
                .model(model(parameters))
                .hostPresent(true)
                .build();
    }

    static RecordingHostSnapshotSource absentModelSource() {
        return RecordingHostSnapshotSource.builder().hostPresent(true).build();
    }

    /** Replaces the observed parameters without moving the token: "same version, new data". */
    static void replaceParameters(
            final RecordingHostSnapshotSource source, final List<HostSnapshotSource.HostParameter> parameters) {
        source.setModel(model(parameters));
    }

    private static HostSnapshotSource.HostModel model(final List<HostSnapshotSource.HostParameter> parameters) {
        return new HostSnapshotSource.HostModel("model-1", "Model", parameters, List.of(MESH), List.of(DEFORMER));
    }

    static HostSnapshotSource.HostParameter hostParameter(final String id, final String name) {
        return new HostSnapshotSource.HostParameter(id, name, 0.0, 0.0, -1.0, 1.0, true, true);
    }

    static PluginPermission permission(final String id) {
        return Stubs.permission(id, "read", "integration test");
    }

    static RuntimeScheduler directRuntimeScheduler() {
        return new RuntimeScheduler(
                task -> "event.subscribe".equals(task.taskType()) ? WorkBudget.LIGHTWEIGHT : WorkBudget.HEAVY,
                new PluginWorkExecutorRegistry(1, 2, event -> {}, FIXED_CLOCK),
                event -> {});
    }

    record QueryEnvironment(
            CorePluginContext context, List<CubismFacadeAuditEvent> auditEvents, DisposableScope disposableScope) {}
}
