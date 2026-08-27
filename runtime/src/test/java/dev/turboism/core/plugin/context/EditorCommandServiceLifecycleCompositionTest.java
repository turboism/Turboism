package dev.turboism.core.plugin.context;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.adapter.cubism.command.EditorCommandAdapter;
import dev.turboism.adapter.cubism.command.EditorFileCommandResolver;
import dev.turboism.adapter.cubism.command.ResolvedEditorFileCommand;
import dev.turboism.adapter.cubism.lifecycle.EditorObjectLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.ParameterLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.PartLifecycleCoordinator;
import dev.turboism.adapter.cubism.physics.PhysicsEditorCoordinator;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.sdk.cubism.command.EditorCommand;
import dev.turboism.sdk.cubism.command.EditorCommandResult;
import dev.turboism.sdk.cubism.command.EditorCommandService;
import dev.turboism.sdk.cubism.command.EditorFileCommand;
import dev.turboism.sdk.cubism.command.EditorFileCommandRequest;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.command.EditorOverwritePolicy;
import dev.turboism.sdk.cubism.command.EditorParameterizedRequest;
import dev.turboism.sdk.cubism.command.EditorResizeModelRequest;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorPanel;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorSession;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorUi;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithmRegistry;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutService;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.ui.UiScheduler;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UserFileHandle;
import dev.turboism.sdk.ui.UserFileHandleState;
import dev.turboism.sdk.ui.UserFileLifetime;
import dev.turboism.sdk.ui.UserFileMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Guards the DefaultCubismServicesFactory wiring: a retained EditorCommandService must fail
 * closed once the plugin DisposableScope closes, without touching the host adapter or resolver.
 */
class EditorCommandServiceLifecycleCompositionTest {

    @TempDir Path temporary;

    @Test
    void retainedTextureAtlasServicesFailClosedAfterPluginScopeCloses() throws Exception {
        DisposableScope scope = new DisposableScope();
        RuntimeScheduler scheduler = scheduler();
        try {
            DefaultCubismServicesFactory factory = DefaultCubismServicesFactoryTestSupport.withModelAccess(
                RuntimeHostAdapters.safeMode(),
                () -> { throw new IllegalStateException("no model"); }
            );
            CorePluginContext.Dependencies dependencies = new CorePluginContext.Dependencies(
                descriptor(),
                logger(),
                paths(),
                uiScheduler(),
                scheduler,
                diagnostics(),
                scope,
                noopHostSnapshotSource(),
                ignored -> { },
                CLOCK
            );
            CubismFacade facade = factory.create(dependencies).cubismFacade();
            TextureAtlasLayoutService layouts = facade.textureAtlasLayouts();
            TextureAtlasEditorSession editorSession = facade.textureAtlasEditorSession();
            TextureAtlasEditorUi editorUi = facade.textureAtlasEditorUi();
            TextureAtlasEditorPanel panel = editorUi.attach();
            TextureAtlasLayoutAlgorithmRegistry algorithms = facade.textureAtlasAlgorithms();

            scope.close();

            assertThrows(IllegalStateException.class, layouts::current);
            assertThrows(IllegalStateException.class, editorSession::summary);
            assertThrows(IllegalStateException.class, editorSession::selectedTexture);
            assertThrows(IllegalStateException.class, editorUi::attach);
            assertThrows(IllegalStateException.class, () -> panel.setText("closed"));
            assertThrows(IllegalStateException.class, panel::close);
            assertThrows(IllegalStateException.class, () -> algorithms.find("missing"));
            assertThrows(IllegalStateException.class, algorithms::algorithms);
        } finally {
            scope.close();
            if (!scheduler.isClosed()) scheduler.shutdown();
        }
    }

    @Test
    void retainedCommandServiceFailsClosedAfterPluginScopeCloses() throws Exception {
        AtomicInteger adapterCalls = new AtomicInteger();
        AtomicInteger resolverCalls = new AtomicInteger();
        EditorCommandAdapter adapter = countingAdapter(adapterCalls);
        EditorFileCommandResolver resolver = request -> {
            resolverCalls.incrementAndGet();
            return new ResolvedEditorFileCommand(
                request.command(), temporary.resolve("fixture.cmo3"), request.overwritePolicy()
            );
        };
        DisposableScope scope = new DisposableScope();
        RuntimeScheduler scheduler = scheduler();
        try {
            DefaultCubismServicesFactory factory = DefaultCubismServicesFactoryTestSupport.withEditorCommands(
                RuntimeHostAdapters.safeMode(),
                () -> { throw new IllegalStateException("no model"); },
                new ParameterLifecycleCoordinator(),
                new PartLifecycleCoordinator(),
                new EditorObjectLifecycleCoordinator(),
                new PhysicsEditorCoordinator(),
                adapter,
                resolver
            );
            CorePluginContext.Dependencies dependencies = new CorePluginContext.Dependencies(
                descriptor(),
                logger(),
                paths(),
                uiScheduler(),
                scheduler,
                diagnostics(),
                scope,
                noopHostSnapshotSource(),
                ignored -> { },
                CLOCK
            );
            EditorCommandService service = factory.create(dependencies).editorCommandService();

            assertEquals(Set.of(EditorCommand.NEXT_FRAME), service.available());
            assertEquals(
                EditorCommandResult.Status.EXECUTED,
                service.execute(EditorCommand.NEXT_FRAME).status()
            );
            int adapterCallsWhileActive = adapterCalls.get();

            scope.close();

            assertEquals(Set.of(), service.available());
            assertEquals(
                EditorCommandResult.Status.UNAVAILABLE,
                service.execute(EditorCommand.NEXT_FRAME).status()
            );
            assertEquals(EditorCommandResult.Status.UNAVAILABLE, service.execute(new EditorFileCommandRequest(
                EditorFileCommand.OPEN, handle(UserFileMode.READ), EditorOverwritePolicy.REJECT_EXISTING
            )).status());
            assertEquals(
                EditorCommandResult.Status.UNAVAILABLE,
                service.execute(new EditorResizeModelRequest(100)).status()
            );
            assertEquals(adapterCallsWhileActive, adapterCalls.get());
            assertEquals(0, resolverCalls.get());
        } finally {
            if (!scheduler.isClosed()) scheduler.shutdown();
        }
    }

    private static EditorCommandAdapter countingAdapter(final AtomicInteger calls) {
        return new EditorCommandAdapter() {
            @Override
            public Set<EditorCommand> available() {
                return Set.of(EditorCommand.NEXT_FRAME);
            }

            @Override
            public EditorCommandResult execute(final EditorCommand command) {
                calls.incrementAndGet();
                return new EditorCommandResult(EditorCommandResult.Status.EXECUTED, command.id());
            }

            @Override
            public EditorCommandResult execute(final ResolvedEditorFileCommand command) {
                calls.incrementAndGet();
                return new EditorCommandResult(EditorCommandResult.Status.EXECUTED, command.commandId());
            }

            @Override
            public EditorCommandResult execute(final EditorParameterizedRequest command) {
                calls.incrementAndGet();
                return new EditorCommandResult(EditorCommandResult.Status.EXECUTED, command.commandId());
            }
        };
    }

    private static PluginDescriptor descriptor() {
        return new PluginDescriptor() {
            @Override public String id() { return "test.command-lifecycle"; }
            @Override public String name() { return "Command Lifecycle"; }
            @Override public String version() { return "0.1.0"; }
            @Override public String description() { return "Test"; }
            @Override public List<String> entrypoints() { return List.of("dev.turboism.test.CommandLifecyclePlugin"); }
            @Override public String turboismApi() { return "[0.1.0,0.2.0)"; }
            @Override public List<Author> authors() { return List.of(); }
            @Override public String license() { return "Project License"; }
            @Override public Optional<String> website() { return Optional.empty(); }
            @Override public List<String> resources() { return List.of(); }
            @Override public I18n i18n() { return new I18n() {
                @Override public String baseName() { return "META-INF/turboism/i18n/messages"; }
                @Override public List<String> locales() { return List.of(); }
            }; }
            @Override public List<DependencyRef> dependencies() { return List.of(); }
            @Override public List<PermissionRef> permissions() {
                return List.of(
                    permission("turboism.cubism.model.read"),
                    permission("turboism.cubism.model.write"),
                    permission("turboism.file.read"),
                    permission("turboism.file.write")
                );
            }
            @Override public List<String> capabilities() { return List.of(); }
            @Override public Environment environment() { return new Environment() {
                @Override public boolean requiresCubism() { return false; }
                @Override public String ui() { return "none"; }
            }; }
        };
    }

    private static PluginDescriptor.PermissionRef permission(final String id) {
        return new PluginDescriptor.PermissionRef() {
            @Override public String id() { return id; }
            @Override public String scope() { return "application"; }
            @Override public Optional<String> reason() { return Optional.empty(); }
        };
    }

    private static PluginLogger logger() {
        return new PluginLogger() {
            @Override public void debug(String message) { }
            @Override public void info(String message) { }
            @Override public void warn(String message) { }
            @Override public void error(String message) { }
            @Override public void error(String message, Throwable throwable) { }
        };
    }

    private static PluginPaths paths() {
        return new PluginPaths() {
            @Override public Path dataDir() { return Path.of("."); }
            @Override public Path logsDir() { return Path.of("."); }
            @Override public Path stateDir() { return Path.of("."); }
            @Override public Path cacheDir() { return Path.of("."); }
        };
    }

    private static UiScheduler uiScheduler() {
        return new UiScheduler() {
            @Override public Registration runOnUiThread(Runnable work) { work.run(); return () -> { }; }
            @Override public Registration runOnUiThreadLater(Runnable work, Duration delay) { return () -> { }; }
        };
    }

    private static RuntimeScheduler scheduler() {
        return new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(1, 4, ignored -> { }, CLOCK),
            SidecarDispatcher.noop(),
            ignored -> { }
        );
    }

    private static dev.turboism.sdk.diagnostics.DiagnosticReport diagnostics() {
        return new dev.turboism.sdk.diagnostics.DiagnosticReport() {
            @Override public Instant createdAt() { return CLOCK.instant(); }
            @Override public List<Problem> problems() { return List.of(); }
        };
    }

    private static HostSnapshotSource noopHostSnapshotSource() {
        return new HostSnapshotSource() {
            @Override public Optional<HostProject> activeProject() { return Optional.empty(); }
            @Override public Optional<HostDocument> activeDocument() { return Optional.empty(); }
            @Override public Optional<HostModel> activeModel() { return Optional.empty(); }
            @Override public HostSelection selection() {
                return new HostSelection(List.of(), Optional.empty(), Optional.empty(), Optional.empty());
            }
            @Override public boolean isHostPresent() { return false; }
            @Override public long invalidationToken() { return 0; }
        };
    }

    private static UserFileHandle handle(final UserFileMode mode) {
        return new UserFileHandle() {
            @Override public String id() { return "grant"; }
            @Override public String displayName() { return "fixture.cmo3"; }
            @Override public UserFileMode mode() { return mode; }
            @Override public UserFileLifetime lifetime() { return UserFileLifetime.UNTIL_DISABLE; }
            @Override public UserFileHandleState state() { return UserFileHandleState.ACTIVE; }
            @Override public void revoke() { }
            @Override public void close() { }
        };
    }

    private static final Clock CLOCK = Clock.systemUTC();
}
