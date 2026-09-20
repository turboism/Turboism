package dev.turboism.adapter.cubism;

import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.core.diagnostics.PluginWorkBudgetEvent;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.sdk.cubism.history.CubismHistory;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutService;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionService;
import dev.turboism.sdk.cubism.transaction.TransactionManager;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/** Facade-level adapter factories: permission-checked views and unavailable fallbacks. */
final class CubismFacadeAdapters {

    private CubismFacadeAdapters() {
    }

    static TransactionManager unavailableTransactionManager() {
        return (ctx, docId) -> {
            throw new UnsupportedOperationException("transaction manager is not available");
        };
    }

    static CubismModelAccess unavailableModelAccess() {
        return () -> {
            throw new UnsupportedOperationException(
                "Unified Cubism model access is unavailable"
            );
        };
    }

    static RuntimeScheduler defaultScheduler() {
        final Consumer<PluginWorkBudgetEvent> diagnostics = ignored -> {
        };
        return new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(1, 16, diagnostics, Clock.systemUTC()),
            SidecarDispatcher.noop(),
            diagnostics
        );
    }

    static dev.turboism.sdk.cubism.core.CoreRuntimeInfo unavailableCoreRuntime() {
        return new dev.turboism.sdk.cubism.core.CoreRuntimeInfo() {
            @Override public dev.turboism.sdk.cubism.core.CoreVersion version() {
                throw new UnsupportedOperationException("Core runtime metadata is unavailable.");
            }
            @Override public dev.turboism.sdk.cubism.core.CoreCapabilities capabilities() {
                throw new UnsupportedOperationException("Core runtime capabilities are unavailable.");
            }
            @Override public dev.turboism.sdk.cubism.core.MocInspector mocInspector() {
                throw new UnsupportedOperationException("Core MOC inspection is unavailable.");
            }
        };
    }

    static dev.turboism.sdk.cubism.core.CoreRuntimeInfo permissionCheckedCoreRuntime(
        final CubismFacadeImpl facade,
        final dev.turboism.sdk.cubism.core.CoreRuntimeInfo delegate
    ) {
        Objects.requireNonNull(delegate, "delegate");
        return new dev.turboism.sdk.cubism.core.CoreRuntimeInfo() {
            @Override public dev.turboism.sdk.cubism.core.CoreVersion version() {
                facade.requireModelRead("coreRuntime.version");
                return delegate.version();
            }
            @Override public dev.turboism.sdk.cubism.core.CoreCapabilities capabilities() {
                facade.requireModelRead("coreRuntime.capabilities");
                return delegate.capabilities();
            }
            @Override public dev.turboism.sdk.cubism.core.MocInspector mocInspector() {
                facade.requireModelRead("coreRuntime.mocInspector");
                final dev.turboism.sdk.cubism.core.MocInspector inspector = delegate.mocInspector();
                return new dev.turboism.sdk.cubism.core.MocInspector() {
                    @Override public dev.turboism.sdk.cubism.core.MocVersion latestVersion() {
                        facade.requireModelRead("coreRuntime.mocInspector.latestVersion");
                        return inspector.latestVersion();
                    }
                    @Override public dev.turboism.sdk.cubism.core.MocInfo inspect(
                        final dev.turboism.sdk.cubism.core.MocData data
                    ) {
                        facade.requireModelRead("coreRuntime.mocInspector.inspect");
                        return inspector.inspect(data);
                    }
                };
            }
        };
    }

    static CubismModelAccess permissionCheckedModelAccess(final CubismFacadeImpl facade, final CubismModelAccess delegate) {
        return () -> {
            facade.requireModelRead("model.active");
            return new PermissionCheckedModel(facade, delegate.active());
        };
    }

    static CubismHistory historyView(
        final CubismFacadeImpl facade,
        final CubismHistory delegate
    ) {
        return new CubismHistory() {
            @Override
            public dev.turboism.sdk.cubism.history.HistorySnapshot snapshot() {
                facade.requireActiveScope();
                facade.permissionGate.require(CubismFacadeImpl.MODEL_READ_PERMISSION, "history.snapshot");
                return delegate.snapshot();
            }

            @Override
            public dev.turboism.sdk.cubism.history.HistoryMoveResult moveTo(
                final long expectedGeneration,
                final long expectedRevision,
                final int position
            ) {
                facade.requireActiveScope();
                facade.permissionGate.require(CubismFacadeImpl.MODEL_WRITE_PERMISSION, "history.moveTo");
                return delegate.moveTo(expectedGeneration, expectedRevision, position);
            }

            @Override
            public dev.turboism.sdk.cubism.history.HistoryMoveResult moveTo(
                final dev.turboism.sdk.cubism.history.HistorySnapshot expected,
                final int position
            ) {
                facade.requireActiveScope();
                facade.permissionGate.require(CubismFacadeImpl.MODEL_WRITE_PERMISSION, "history.moveToBound");
                return delegate.moveTo(expected, position);
            }

            @Override
            public boolean isCurrentBinding(
                final dev.turboism.sdk.cubism.history.HistorySnapshot snapshot
            ) {
                facade.requireActiveScope();
                facade.permissionGate.require(CubismFacadeImpl.MODEL_READ_PERMISSION, "history.isCurrentBinding");
                return delegate.isCurrentBinding(snapshot);
            }
        };
    }

    static AuthoringTransactionService authoringTransactionsView(
        final CubismFacadeImpl facade,
        final AuthoringTransactionService delegate
    ) {
        return new AuthoringTransactionService() {
            @Override
            public <T> dev.turboism.sdk.cubism.transaction.AuthoringTransactionResult<T> execute(
                final dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions options,
                final dev.turboism.sdk.cubism.transaction.AuthoringTransactionWork<T> work
            ) {
                facade.requireActiveScope();
                facade.permissionGate.require(
                    CubismFacadeImpl.MODEL_WRITE_PERMISSION,
                    "authoringTransactions.execute"
                );
                return delegate.execute(options, work);
            }
        };
    }

    static dev.turboism.sdk.cubism.edit.EditSessionService editSessionServiceView(
        final CubismFacadeImpl facade,
        final dev.turboism.sdk.cubism.edit.EditSessionService delegate
    ) {
        return new dev.turboism.sdk.cubism.edit.EditSessionService() {
            @Override
            public boolean isEditApproved(
                final dev.turboism.sdk.plugin.PluginContext context
            ) throws dev.turboism.sdk.cubism.edit.EditSessionException {
                facade.requireActiveScope();
                facade.permissionGate.require(
                    CubismFacadeImpl.EDIT_PERMISSION,
                    "edit.isEditApproved"
                );
                return delegate.isEditApproved(context);
            }

            @Override
            public dev.turboism.sdk.cubism.edit.EditSession open(
                final dev.turboism.sdk.plugin.PluginContext context,
                final dev.turboism.sdk.cubism.id.DocumentId document,
                final dev.turboism.sdk.cubism.edit.EditSessionOptions options
            ) throws dev.turboism.sdk.cubism.edit.EditSessionException {
                facade.requireActiveScope();
                facade.permissionGate.require(
                    CubismFacadeImpl.EDIT_PERMISSION,
                    "edit.open"
                );
                return delegate.open(context, document, options);
            }
        };
    }

    static TextureAtlasLayoutService layoutServiceView(
        final CubismFacadeImpl facade,
        final TextureAtlasLayoutService delegate
    ) {
        return new TextureAtlasLayoutService() {
            @Override
            public Optional<dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutSnapshot> current() {
                facade.requireActiveScope();
                return delegate.current();
            }

            @Override
            public dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutApplyResult apply(
                final dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutTarget target,
                final dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan plan
            ) {
                facade.requireActiveScope();
                return delegate.apply(target, plan);
            }
        };
    }

    static dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorSession editorSessionView(
        final CubismFacadeImpl facade,
        final dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorSession delegate
    ) {
        return new dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorSession() {
            @Override
            public Optional<dev.turboism.sdk.cubism.textureatlas.TextureAtlasSummary> summary() {
                facade.requireActiveScope();
                return delegate.summary();
            }

            @Override
            public Optional<dev.turboism.sdk.cubism.textureatlas.TextureAtlasSummary> selectedTexture() {
                facade.requireActiveScope();
                return delegate.selectedTexture();
            }
        };
    }

    static dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorUi editorUiView(
        final CubismFacadeImpl facade,
        final dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorUi delegate
    ) {
        return () -> {
            facade.requireActiveScope();
            final dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorPanel panel = delegate.attach();
            return new dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorPanel() {
                @Override
                public void setText(final String text) {
                    facade.requireActiveScope();
                    panel.setText(text);
                }

                @Override
                public void close() {
                    facade.requireActiveScope();
                    panel.close();
                }
            };
        };
    }

    static dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithmRegistry algorithmRegistryView(
        final CubismFacadeImpl facade,
        final dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithmRegistry delegate
    ) {
        return new dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithmRegistry() {
            @Override
            public dev.turboism.sdk.plugin.Registration register(
                final dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithm algorithm
            ) {
                facade.requireActiveScope();
                final dev.turboism.sdk.plugin.Registration registration = delegate.register(algorithm);
                return () -> {
                    facade.requireActiveScope();
                    registration.close();
                };
            }

            @Override
            public Optional<dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithm> find(
                final String id
            ) {
                facade.requireActiveScope();
                return delegate.find(id);
            }

            @Override
            public List<dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithm> algorithms() {
                facade.requireActiveScope();
                return delegate.algorithms();
            }
        };
    }

}
