package dev.turboism.kernel.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.adapter.cubism.command.EditorCommandAdapter;
import dev.turboism.adapter.cubism.command.EditorFileCommandResolver;
import dev.turboism.adapter.cubism.command.ResolvedEditorFileCommand;
import dev.turboism.adapter.cubism.editor.transaction.RuntimeAuthoringTransactionProvider;
import dev.turboism.adapter.cubism.lifecycle.EditorObjectLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.ParameterLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.PartLifecycleCoordinator;
import dev.turboism.adapter.cubism.physics.PhysicsEditorCoordinator;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.command.EditorCommand;
import dev.turboism.sdk.cubism.command.EditorCommandResult;
import dev.turboism.sdk.cubism.command.EditorCommandService;
import dev.turboism.sdk.cubism.command.EditorFileCommand;
import dev.turboism.sdk.cubism.command.EditorFileCommandRequest;
import dev.turboism.sdk.cubism.command.EditorOverwritePolicy;
import dev.turboism.sdk.cubism.command.EditorParameterizedRequest;
import dev.turboism.sdk.cubism.command.EditorResizeModelRequest;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorSession;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithmRegistry;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutService;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOutcome;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionReceipt;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionResult;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionService;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionWork;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.ui.UiScheduler;
import dev.turboism.sdk.ui.UserFileHandle;
import dev.turboism.sdk.ui.UserFileHandleState;
import dev.turboism.sdk.ui.UserFileLifetime;
import dev.turboism.sdk.ui.UserFileMode;
import dev.turboism.test.Stubs;
import dev.turboism.test.plugin.FakePluginPaths;
import dev.turboism.test.ui.FakeDirectUiScheduler;
import dev.turboism.testkit.RuntimeTestKit;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guards the DefaultCubismServicesFactory wiring: a retained EditorCommandService must fail
 * closed once the plugin DisposableScope closes, without touching the host adapter or resolver.
 */
class EditorCommandServiceLifecycleCompositionTest {

    @TempDir
    Path temporary;

    @Test
    void retainedTextureAtlasServicesFailClosedAfterPluginScopeCloses() throws Exception {
        DisposableScope scope = new DisposableScope();
        RuntimeScheduler scheduler = RuntimeTestKit.scheduler(CLOCK, 1, 4);
        try {
            DefaultCubismServicesFactory factory =
                    DefaultCubismServicesFactoryTestSupport.withModelAccess(RuntimeHostAdapters.safeMode(), () -> {
                        throw new IllegalStateException("no model");
                    });
            CorePluginContext.Dependencies dependencies = new CorePluginContext.Dependencies(
                    descriptor(),
                    logger(),
                    paths(),
                    uiScheduler(),
                    scheduler,
                    diagnostics(),
                    scope,
                    noopHostSnapshotSource(),
                    ignored -> {},
                    CLOCK);
            CubismFacade facade = factory.create(dependencies).cubismFacade();
            TextureAtlasLayoutService layouts = facade.textureAtlasLayouts();
            TextureAtlasEditorSession editorSession = facade.textureAtlasEditorSession();
            TextureAtlasLayoutAlgorithmRegistry algorithms = facade.textureAtlasAlgorithms();

            scope.close();

            assertThrows(IllegalStateException.class, layouts::current);
            assertThrows(IllegalStateException.class, editorSession::summary);
            assertThrows(IllegalStateException.class, editorSession::selectedTexture);
            assertThrows(IllegalStateException.class, () -> algorithms.find("missing"));
            assertThrows(IllegalStateException.class, algorithms::algorithms);
        } finally {
            scope.close();
            if (!scheduler.isClosed()) scheduler.shutdown();
        }
    }

    @Test
    void productionFactoryInjectsModelAuthoringProviderUsingTheDescriptorIdentity() throws Exception {
        final AtomicReference<String> owner = new AtomicReference<>();
        final DisposableScope scope = new DisposableScope();
        final RuntimeScheduler scheduler = RuntimeTestKit.scheduler(CLOCK, 1, 4);
        try {
            final DefaultCubismServicesFactory factory = DefaultCubismServicesFactoryTestSupport.withModelAccess(
                    RuntimeHostAdapters.safeMode(), new AuthoringProviderModelAccess(owner));
            final CorePluginContext.Dependencies dependencies = new CorePluginContext.Dependencies(
                    descriptor(),
                    logger(),
                    paths(),
                    uiScheduler(),
                    scheduler,
                    diagnostics(),
                    scope,
                    noopHostSnapshotSource(),
                    ignored -> {},
                    CLOCK);
            final CubismFacade facade = factory.create(dependencies).cubismFacade();

            final AuthoringTransactionResult<String> result = facade.authoringTransactions()
                    .execute(AuthoringTransactionOptions.of("Factory transaction"), () -> "done");

            assertEquals("test.command-lifecycle", owner.get());
            assertEquals(AuthoringTransactionOutcome.NO_CHANGE, result.outcome());
            assertEquals(Optional.of("done"), result.value());
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
                    request.command(), temporary.resolve("fixture.cmo3"), request.overwritePolicy());
        };
        DisposableScope scope = new DisposableScope();
        RuntimeScheduler scheduler = RuntimeTestKit.scheduler(CLOCK, 1, 4);
        try {
            DefaultCubismServicesFactory factory = DefaultCubismServicesFactoryTestSupport.withEditorCommands(
                    RuntimeHostAdapters.safeMode(),
                    () -> {
                        throw new IllegalStateException("no model");
                    },
                    new ParameterLifecycleCoordinator(),
                    new PartLifecycleCoordinator(),
                    new EditorObjectLifecycleCoordinator(),
                    new PhysicsEditorCoordinator(),
                    adapter,
                    resolver);
            CorePluginContext.Dependencies dependencies = new CorePluginContext.Dependencies(
                    descriptor(),
                    logger(),
                    paths(),
                    uiScheduler(),
                    scheduler,
                    diagnostics(),
                    scope,
                    noopHostSnapshotSource(),
                    ignored -> {},
                    CLOCK);
            EditorCommandService service = factory.create(dependencies).editorCommandService();

            assertEquals(Set.of(EditorCommand.NEXT_FRAME), service.available());
            assertEquals(
                    EditorCommandResult.Status.EXECUTED,
                    service.execute(EditorCommand.NEXT_FRAME).status());
            int adapterCallsWhileActive = adapterCalls.get();

            scope.close();

            assertEquals(Set.of(), service.available());
            assertEquals(
                    EditorCommandResult.Status.UNAVAILABLE,
                    service.execute(EditorCommand.NEXT_FRAME).status());
            assertEquals(
                    EditorCommandResult.Status.UNAVAILABLE,
                    service.execute(new EditorFileCommandRequest(
                                    EditorFileCommand.OPEN,
                                    handle(UserFileMode.READ),
                                    EditorOverwritePolicy.REJECT_EXISTING))
                            .status());
            assertEquals(
                    EditorCommandResult.Status.UNAVAILABLE,
                    service.execute(new EditorResizeModelRequest(100)).status());
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
        return Stubs.descriptorBuilder("test.command-lifecycle")
                .name("Command Lifecycle")
                .version("0.1.0")
                .entrypoints("dev.turboism.test.CommandLifecyclePlugin")
                .license("Project License")
                .permissions(
                        Stubs.permissionRef("turboism.cubism.model.read"),
                        Stubs.permissionRef("turboism.cubism.model.write"),
                        Stubs.permissionRef("turboism.file.read"),
                        Stubs.permissionRef("turboism.file.write"))
                .i18n("META-INF/turboism/i18n/messages")
                .build();
    }

    private static PluginLogger logger() {
        return Stubs.noopLogger();
    }

    private static PluginPaths paths() {
        return FakePluginPaths.of(Path.of("."));
    }

    private static UiScheduler uiScheduler() {
        return FakeDirectUiScheduler.immediateOnly();
    }

    private static dev.turboism.sdk.diagnostics.DiagnosticReport diagnostics() {
        return Stubs.diagnostics(CLOCK.instant());
    }

    private static HostSnapshotSource noopHostSnapshotSource() {
        return RuntimeTestKit.emptyHostSnapshotSource();
    }

    private static final class AuthoringProviderModelAccess
            implements CubismModelAccess, RuntimeAuthoringTransactionProvider {

        private final AtomicReference<String> owner;

        AuthoringProviderModelAccess(final AtomicReference<String> owner) {
            this.owner = owner;
        }

        @Override
        public CubismModel active() {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public AuthoringTransactionService authoringTransactions(final String pluginId) {
            owner.set(pluginId);
            return new AuthoringTransactionService() {
                @Override
                public <T> AuthoringTransactionResult<T> execute(
                        final AuthoringTransactionOptions options, final AuthoringTransactionWork<T> work) {
                    final T value;
                    try {
                        value = work.run();
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                    final HistorySnapshot history = new HistorySnapshot(
                            HistorySnapshot.Availability.AVAILABLE,
                            1,
                            1,
                            0,
                            List.of(),
                            false,
                            false,
                            "document-binding-1",
                            "manager-binding-1");
                    return AuthoringTransactionResult.noChange(
                            value,
                            new AuthoringTransactionReceipt(
                                    "transaction-1", options.label(), history, history, Optional.empty()));
                }
            };
        }
    }

    private static UserFileHandle handle(final UserFileMode mode) {
        return new UserFileHandle() {
            @Override
            public String id() {
                return "grant";
            }

            @Override
            public String displayName() {
                return "fixture.cmo3";
            }

            @Override
            public UserFileMode mode() {
                return mode;
            }

            @Override
            public UserFileLifetime lifetime() {
                return UserFileLifetime.UNTIL_DISABLE;
            }

            @Override
            public UserFileHandleState state() {
                return UserFileHandleState.ACTIVE;
            }

            @Override
            public void revoke() {}

            @Override
            public void close() {}
        };
    }

    private static final Clock CLOCK = Clock.systemUTC();
}
