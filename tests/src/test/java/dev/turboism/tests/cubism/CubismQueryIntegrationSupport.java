package dev.turboism.tests.cubism;

import dev.turboism.adapter.cubism.CubismFacadeImpl;
import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.core.plugin.context.CorePluginContext;
import dev.turboism.core.runtime.PluginExecutorRegistry;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.sdk.plugin.WorkBudget;
import dev.turboism.diagnostics.CubismFacadeAuditEvent;
import dev.turboism.sdk.cubism.DeformerType;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.test.fake.FakeCubismArtMesh;
import dev.turboism.test.fake.FakeCubismDeformer;
import dev.turboism.test.fake.FakeCubismDocument;
import dev.turboism.test.fake.FakeCubismHost;
import dev.turboism.test.fake.FakeCubismModel;
import dev.turboism.test.fake.FakeCubismParameter;
import dev.turboism.test.fake.FakeCubismProject;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

final class CubismQueryIntegrationSupport {

    static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-08T04:00:00Z"), ZoneOffset.UTC);
    static final String PLUGIN_ID = "plugin.query-tests";
    static final String MODEL_READ_PERMISSION = CubismFacadeImpl.MODEL_READ_PERMISSION;
    static final String MESH_READ_PERMISSION = CubismFacadeImpl.MESH_READ_PERMISSION;
    static final String PARAMETER_READ_PERMISSION = "turboism.cubism.parameter.read";

    private CubismQueryIntegrationSupport() {
    }

    static QueryEnvironment environment(final FakeCubismHost host, final String... permissionIds) {
        return environment(new FakeHostSnapshotSource(host), permissionIds);
    }

    static QueryEnvironment environment(final HostSnapshotSource source, final String... permissionIds) {
        final List<CubismFacadeAuditEvent> auditEvents = new ArrayList<>();
        final DisposableScope disposableScope = new DisposableScope();
        final CorePluginContext context = new CorePluginContext(new CorePluginContext.Dependencies(
            TestPluginDependencies.descriptor(),
            TestPluginDependencies.silentLogger(),
            TestPluginDependencies.paths(),
            List.of(permissionIds).stream().map(CubismQueryIntegrationSupport::permission).toList(),
            TestPluginDependencies.noOpEventBus(),
            TestPluginDependencies.noOpActions(),
            TestPluginDependencies.noOpMenus(),
            TestPluginDependencies.directUiScheduler(),
            directRuntimeScheduler(),
            TestPluginDependencies.emptyDiagnostics(),
            disposableScope,
            source,
            auditEvents::add,
            FIXED_CLOCK
        ));
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

    static VersionedSource versionedSource(final List<HostSnapshotSource.HostParameter> parameters) {
        return new VersionedSource(parameters, true);
    }

    static VersionedSource absentModelSource() {
        return new VersionedSource(List.of(), false);
    }

    static HostSnapshotSource.HostParameter hostParameter(final String id, final String name) {
        return new HostSnapshotSource.HostParameter(id, name, 0.0, 0.0, -1.0, 1.0, true, true);
    }

    static PluginPermission permission(final String id) {
        return new PluginPermission() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String scope() {
                return "read";
            }

            @Override
            public String reason() {
                return "integration test";
            }
        };
    }

    static RuntimeScheduler directRuntimeScheduler() {
        return new RuntimeScheduler(
            task -> WorkBudget.SIDECAR,
            new PluginExecutorRegistry(1, 2, event -> { }, FIXED_CLOCK),
            (task, callback) -> {
                callback.run();
                return CompletableFuture.completedFuture(dev.turboism.core.runtime.sidecar.SidecarResult.success(""));
            },
            event -> { }
        );
    }

    record QueryEnvironment(CorePluginContext context, List<CubismFacadeAuditEvent> auditEvents, DisposableScope disposableScope) {
    }

    static final class VersionedSource implements HostSnapshotSource {
        private static final HostArtMesh MESH = new HostArtMesh("mesh-face", "Face Mesh", Optional.empty(), true, true);
        private static final HostDeformer DEFORMER = new HostDeformer("deformer-root", "Root", DeformerType.ROOT, Optional.empty(), List.of("mesh-face"));

        private final boolean hasModel;
        private List<HostParameter> parameters;
        private long invalidationToken;

        private VersionedSource(final List<HostParameter> parameters, final boolean hasModel) {
            this.parameters = List.copyOf(parameters);
            this.hasModel = hasModel;
        }

        void replaceParametersWithoutInvalidation(final List<HostParameter> nextParameters) {
            parameters = List.copyOf(nextParameters);
        }

        void advanceInvalidationToken() {
            invalidationToken++;
        }

        @Override
        public Optional<HostProject> activeProject() {
            return Optional.empty();
        }

        @Override
        public Optional<HostDocument> activeDocument() {
            return Optional.empty();
        }

        @Override
        public Optional<HostModel> activeModel() {
            if (!hasModel) {
                return Optional.empty();
            }
            return Optional.of(new HostModel("model-1", "Model", parameters, List.of(MESH), List.of(DEFORMER)));
        }

        @Override
        public HostSelection selection() {
            return new HostSelection(List.of(), Optional.empty(), Optional.empty(), Optional.empty());
        }

        @Override
        public boolean isHostPresent() {
            return true;
        }

        @Override
        public long invalidationToken() {
            return invalidationToken;
        }
    }

}
