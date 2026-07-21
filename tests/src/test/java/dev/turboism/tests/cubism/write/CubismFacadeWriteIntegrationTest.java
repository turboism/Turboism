package dev.turboism.tests.cubism.write;

import dev.turboism.adapter.cubism.CubismFacadeImpl;
import dev.turboism.adapter.cubism.write.FakeHostWriteAdapter;
import dev.turboism.adapter.cubism.write.RuntimeTransactionManager;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.diagnostics.CubismFacadeAuditEvent;
import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.transaction.DocumentId;
import dev.turboism.sdk.cubism.transaction.ModelTransaction;
import dev.turboism.sdk.cubism.transaction.TransactionException;
import dev.turboism.sdk.cubism.write.WriteParameterCommand;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.PluginContext;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CubismFacadeWriteIntegrationTest {

    private static final DocumentId DOCUMENT_ID = new DocumentId("document-1");
    private static final ModelId MODEL_ID = new ModelId("model-1");
    private static final ParameterId PARAMETER_ID = new ParameterId("parameter-1");
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-08T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void facadeTransactionManagerCommitsWriteThroughRuntimeAdapter() throws TransactionException {
        final FakeHostWriteAdapter adapter = adapterWithParameterValue(0.25);
        final CubismFacade facade = facadeWith(
            adapter,
            permission(RuntimeTransactionManager.TURBOISM_CUBISM_WRITE),
            permission(CubismFacadeImpl.PROJECT_READ_PERMISSION),
            permission(CubismFacadeImpl.MODEL_READ_PERMISSION)
        );

        final ModelTransaction transaction = facade.transactionManager().openTransaction(
            new TestPluginContext("plugin.demo"),
            DOCUMENT_ID
        );
        transaction.enqueue(new WriteParameterCommand("command-1", MODEL_ID, PARAMETER_ID, 0.5F));
        transaction.commit();

        assertEquals(0.5, adapter.parameterValue(MODEL_ID.value(), PARAMETER_ID.value()));
        assertEquals(0.5, facade.runtime().parameters().get(0).value());
    }

    private static CubismFacade facadeWith(final FakeHostWriteAdapter adapter, final PluginPermission... permissions) {
        final List<CubismFacadeAuditEvent> auditEvents = new ArrayList<>();
        final RuntimeScheduler scheduler = new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(1, 16, ignored -> { }, FIXED_CLOCK),
            (task, callback) -> {
                callback.run();
                return java.util.concurrent.CompletableFuture.completedFuture(
                    dev.turboism.core.runtime.sidecar.SidecarResult.success("")
                );
            },
            ignored -> { }
        );
        return new CubismFacadeImpl(adapter, new CubismPermissionGate(
            "plugin.demo",
            List.of(permissions),
            auditEvents::add,
            FIXED_CLOCK
        ), adapter, scheduler);
    }

    private static FakeHostWriteAdapter adapterWithParameterValue(final double value) {
        final FakeHostWriteAdapter adapter = new FakeHostWriteAdapter();
        adapter.addDocument("document-1", "Document", new FakeHostWriteAdapter.FakeModel(
            MODEL_ID,
            "Model",
            List.of(new FakeHostWriteAdapter.FakeParameter(PARAMETER_ID.value(), "Parameter", value))
        ));
        return adapter;
    }

    private static PluginPermission permission(final String id) {
        return new PluginPermission() {
            @Override public String id() { return id; }
            @Override public String scope() { return "write"; }
            @Override public String reason() { return "test"; }
        };
    }

    private record TestPluginContext(String pluginId) implements PluginContext {
        @Override public dev.turboism.sdk.plugin.PluginDescriptor descriptor() {
            return new TestDescriptor(pluginId);
        }
        @Override public dev.turboism.sdk.plugin.PluginLogger logger() { throw new UnsupportedOperationException(); }
        @Override public dev.turboism.sdk.plugin.PluginPaths paths() { throw new UnsupportedOperationException(); }
        @Override public CubismFacade cubism() { throw new UnsupportedOperationException(); }
        @Override public List<PluginPermission> permissions() { return List.of(); }
        @Override public dev.turboism.sdk.event.EventBus eventBus() { throw new UnsupportedOperationException(); }
        @Override public dev.turboism.sdk.action.ActionRegistry actions() { throw new UnsupportedOperationException(); }
        @Override public dev.turboism.sdk.menu.MenuRegistry menus() { throw new UnsupportedOperationException(); }
        @Override public dev.turboism.sdk.ui.UiScheduler uiScheduler() { throw new UnsupportedOperationException(); }
        @Override public dev.turboism.sdk.diagnostics.DiagnosticReport diagnostics() { throw new UnsupportedOperationException(); }
        @Override public dev.turboism.sdk.plugin.DisposableScope disposableScope() { throw new UnsupportedOperationException(); }
    }

    private record TestDescriptor(String id) implements dev.turboism.sdk.plugin.PluginDescriptor {
        @Override public String name() { return "Write Test Plugin"; }
        @Override public String version() { return "0.1.0"; }
        @Override public String description() { return "Write integration test plugin"; }
        @Override public java.util.List<String> entrypoints() { return java.util.List.of("dev.turboism.tests.WritePlugin"); }
        @Override public String turboismApi() { return "[0.1.0,0.2.0)"; }
        @Override public List<Author> authors() { return List.of(); }
        @Override public String license() { return "Project License"; }
        @Override public java.util.Optional<String> website() { return java.util.Optional.of("https://turboism.dev"); }
        @Override public List<String> resources() { return List.of(); }
        @Override public I18n i18n() {
            return new I18n() {
                @Override public String baseName() { return "META-INF/turboism/i18n/messages"; }
                @Override public List<String> locales() { return List.of(); }
            };
        }
        @Override public List<DependencyRef> dependencies() { return List.of(); }
        @Override public List<PermissionRef> permissions() { return List.of(); }
        @Override public List<String> capabilities() { return List.of(); }
        @Override public Environment environment() {
            return new Environment() {
                @Override public boolean requiresCubism() { return false; }
                @Override public String ui() { return "none"; }
            };
        }
    }
}
