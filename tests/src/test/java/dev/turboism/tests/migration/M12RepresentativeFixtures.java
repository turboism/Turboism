package dev.turboism.tests.migration;

import dev.turboism.adapter.cubism.CubismFacadeImpl;
import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.adapter.cubism.write.FakeHostWriteAdapter;
import dev.turboism.core.diagnostics.CallbackBudgetEvent;
import dev.turboism.core.runtime.PluginExecutorRegistry;
import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.WorkBudgetPolicy;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.diagnostics.CubismFacadeAuditEvent;
import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.config.PluginConfigRegistry;
import dev.turboism.sdk.cubism.DeformerType;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.plugin.WorkBudget;
import dev.turboism.test.fake.FakeCubismArtMesh;
import dev.turboism.test.fake.FakeCubismDeformer;
import dev.turboism.test.fake.FakeCubismDocument;
import dev.turboism.test.fake.FakeCubismHost;
import dev.turboism.test.fake.FakeCubismModel;
import dev.turboism.test.fake.FakeCubismParameter;
import dev.turboism.test.fake.FakeCubismProject;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

final class M12RepresentativeFixtures {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T00:00:00Z"), ZoneOffset.UTC);
    static final ModelId MODEL_ID = new ModelId("model-1");
    static final ParameterId PARAMETER_ID = new ParameterId("parameter-1");

    private M12RepresentativeFixtures() {
    }

    static CubismFacadeImpl facadeFor(FakeCubismHost host, String... permissions) {
        List<CubismFacadeAuditEvent> auditEvents = new ArrayList<>();
        return new CubismFacadeImpl(new TestHostSnapshotSource(host), new CubismPermissionGate(
            "plugin.demo",
            java.util.Arrays.stream(permissions).map(M12RepresentativeFixtures::permission).toList(),
            auditEvents::add,
            CLOCK
        ));
    }

    static FakeCubismHost sampleHost() {
        FakeCubismHost host = new FakeCubismHost();
        FakeCubismProject project = new FakeCubismProject("project-1", "Demo Project");
        FakeCubismDocument document = new FakeCubismDocument("document-1", "Demo Document");
        FakeCubismModel model = new FakeCubismModel(MODEL_ID.value(), "Demo Model");
        model.addParameter(new FakeCubismParameter(PARAMETER_ID.value(), "Angle X"));
        model.addArtMesh(new FakeCubismArtMesh("mesh-1", "Face"));
        FakeCubismDeformer deformer = new FakeCubismDeformer("deformer-1", "Warp");
        deformer.setDeformerType("warp");
        model.addDeformer(deformer);
        document.addModel(model);
        project.addDocument(document);
        host.start();
        host.addProject(project);
        host.setActiveProjectId(project.getId());
        host.setActiveDocument(document);
        host.setActiveModel(model);
        return host;
    }

    static FakeHostWriteAdapter adapterWithParameterValue(double value) {
        FakeHostWriteAdapter adapter = new FakeHostWriteAdapter();
        adapter.addDocument("document-1", "Document", new FakeHostWriteAdapter.FakeModel(
            MODEL_ID,
            "Model",
            List.of(new FakeHostWriteAdapter.FakeParameter(PARAMETER_ID.value(), "Parameter", value))
        ));
        return adapter;
    }

    static RuntimeScheduler scheduler(RecordingPolicy policy) {
        List<CallbackBudgetEvent> events = new CopyOnWriteArrayList<>();
        return new RuntimeScheduler(policy, new PluginExecutorRegistry(1, 8, events::add, CLOCK), SidecarDispatcher.noop(), events::add);
    }

    static PluginPermission permission(String id) {
        return new PluginPermission() {
            @Override public String id() { return id; }
            @Override public String scope() { return "application"; }
            @Override public String reason() { return "test"; }
        };
    }

    static final class RecordingPolicy implements WorkBudgetPolicy {
        final CountDownLatch dispatched = new CountDownLatch(1);
        String taskType;

        @Override
        public WorkBudget classify(PluginTask task) {
            taskType = task.taskType();
            dispatched.countDown();
            return WorkBudget.LIGHTWEIGHT;
        }
    }

    record TestPluginContext(PluginDescriptor descriptor) implements PluginContext {
        TestPluginContext(String pluginId) {
            this(descriptor(pluginId));
        }

        @Override public PluginLogger logger() { throw new UnsupportedOperationException(); }
        @Override public PluginPaths paths() { throw new UnsupportedOperationException(); }
        @Override public dev.turboism.sdk.cubism.CubismFacade cubism() { throw new UnsupportedOperationException(); }
        @Override public List<PluginPermission> permissions() { return List.of(); }
        @Override public EventBus eventBus() { throw new UnsupportedOperationException(); }
        @Override public ActionRegistry actions() { throw new UnsupportedOperationException(); }
        @Override public MenuRegistry menus() { throw new UnsupportedOperationException(); }
        @Override public PluginConfigRegistry config() { throw new UnsupportedOperationException(); }
        @Override public dev.turboism.sdk.ui.UiScheduler uiScheduler() { throw new UnsupportedOperationException(); }
        @Override public DiagnosticReport diagnostics() { throw new UnsupportedOperationException(); }
        @Override public DisposableScope disposableScope() { throw new UnsupportedOperationException(); }

        private static PluginDescriptor descriptor(String pluginId) {
            return new PluginDescriptor() {
                @Override public String id() { return pluginId; }
                @Override public String name() { return "Test Plugin"; }
                @Override public String version() { return "0.1.0"; }
                @Override public String description() { return "test"; }
                @Override public Map<String, String> entrypoints() { return Map.of(); }
                @Override public String turboismApi() { return "0.1.0"; }
                @Override public List<Author> authors() { return List.of(); }
                @Override public String license() { return "Project License"; }
                @Override public Optional<String> homepage() { return Optional.empty(); }
                @Override public List<DependencyRef> dependencies() { return List.of(); }
                @Override public List<PermissionRef> permissions() { return List.of(); }
                @Override public List<String> capabilities() { return List.of(); }
                @Override public Environment environment() {
                    return new Environment() {
                        @Override public boolean requiresCubism() { return false; }
                        @Override public String ui() { return "none"; }
                    };
                }
            };
        }
    }

    private static final class TestHostSnapshotSource implements HostSnapshotSource {
        private final FakeCubismHost host;

        private TestHostSnapshotSource(FakeCubismHost host) {
            this.host = Objects.requireNonNull(host, "host");
        }

        @Override public Optional<HostProject> activeProject() {
            return Optional.ofNullable(host.getActiveProject()).map(project -> new HostProject(
                project.getId(), project.getName(), Optional.of(Path.of("projects", project.getId())),
                project.getDocuments().stream().map(this::document).toList()
            ));
        }

        @Override public Optional<HostDocument> activeDocument() {
            return Optional.ofNullable(host.getActiveDocument()).map(this::document);
        }

        @Override public Optional<HostModel> activeModel() {
            return Optional.ofNullable(host.getActiveModel()).map(this::model);
        }

        @Override public HostSelection selection() {
            return new HostSelection(host.getSelection().getSelectedIds(), Optional.empty(), Optional.empty(), Optional.empty());
        }

        @Override public boolean isHostPresent() {
            return host.isRunning();
        }

        @Override public long invalidationToken() {
            return host.getInvalidationToken();
        }

        private HostDocument document(FakeCubismDocument document) {
            return new HostDocument(
                document.getId(), document.getName(), "documents/" + document.getId() + ".cdi3.json",
                Optional.of(Path.of("documents", document.getId() + ".cdi3.json")),
                document.getModels().stream().findFirst().map(this::model)
            );
        }

        private HostModel model(FakeCubismModel model) {
            return new HostModel(
                model.getId(), model.getName(),
                model.getParameters().stream().map(parameter -> new HostParameter(
                    parameter.getId(), parameter.getName(), parameter.getValue(), parameter.getDefaultValue(),
                    parameter.getMinValue(), parameter.getMaxValue(), true, true
                )).toList(),
                model.getArtMeshes().stream().map(mesh -> new HostArtMesh(mesh.getId(), mesh.getName(), Optional.empty(), true, true)).toList(),
                model.getDeformers().stream().map(deformer -> new HostDeformer(
                    deformer.getId(), deformer.getName(), DeformerType.WARP, Optional.empty(), List.of()
                )).toList()
            );
        }
    }
}
