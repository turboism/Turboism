package dev.turboism.plugin.historypanel;

import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.history.CubismHistory;
import dev.turboism.sdk.cubism.history.HistoryMoveResult;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.plugin.CancellationToken;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;
import dev.turboism.sdk.ui.EmbeddedPanelContribution;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class HistoryPanelPluginTest {

    @Test
    void enableRegistersHistoryPanelThroughUiHost() {
        final RecordingContext context = new RecordingContext();
        final HistoryPanelPlugin plugin = new HistoryPanelPlugin();

        plugin.init(context);
        plugin.enable();

        assertEquals(1, context.uiHost().panels().size());
        final EmbeddedPanelContribution panel = context.uiHost().panels().get(0);
        assertEquals("history.panel", panel.id());
        assertEquals("History", panel.title());
        assertEquals("side", panel.placement());
        assertEquals(50, panel.priority());
        assertNotNull(panel.content());
    }

    private static final class RecordingContext implements PluginContext {

        private final RecordingUiHost uiHost = new RecordingUiHost();
        private final DisposableScope scope = new DisposableScope();

        @Override
        public RecordingUiHost uiHost() {
            return uiHost;
        }

        @Override
        public CubismFacade cubism() {
            return new CubismFacade() {
                @Override
                public dev.turboism.sdk.cubism.CubismRuntimeSnapshot runtime() {
                    return null;
                }

                @Override
                public Optional<dev.turboism.sdk.cubism.ProjectSnapshot> activeProject() {
                    return Optional.empty();
                }

                @Override
                public Optional<dev.turboism.sdk.cubism.DocumentSnapshot> activeDocument() {
                    return Optional.empty();
                }

                @Override
                public Optional<dev.turboism.sdk.cubism.ModelSnapshot> activeModel() {
                    return Optional.empty();
                }

                @Override
                public boolean isHostPresent() {
                    return false;
                }

                @Override
                public dev.turboism.sdk.cubism.transaction.TransactionManager transactionManager() {
                    return null;
                }

                @Override
                public CubismHistory history() {
                    return new CubismHistory() {
                        @Override
                        public HistorySnapshot snapshot() {
                            return HistorySnapshot.unavailable();
                        }

                        @Override
                        public HistoryMoveResult moveTo(
                            final long expectedGeneration,
                            final long expectedRevision,
                            final int position
                        ) {
                            return new HistoryMoveResult(
                                HistoryMoveResult.Outcome.UNAVAILABLE,
                                HistorySnapshot.unavailable(),
                                Optional.of("history.move.unavailable")
                            );
                        }
                    };
                }
            };
        }

        @Override
        public UiScheduler uiScheduler() {
            return new UiScheduler() {
                @Override
                public Registration runOnUiThread(final Runnable work) {
                    return () -> { };
                }

                @Override
                public Registration runOnUiThreadLater(final Runnable work, final java.time.Duration delay) {
                    return () -> { };
                }
            };
        }

        @Override
        public dev.turboism.sdk.diagnostics.DiagnosticReport diagnostics() {
            return null;
        }

        @Override
        public dev.turboism.sdk.plugin.PluginDescriptor descriptor() {
            return null;
        }

        @Override
        public dev.turboism.sdk.plugin.PluginPaths paths() {
            return null;
        }

        @Override
        public java.util.List<dev.turboism.sdk.permission.PluginPermission> permissions() {
            return java.util.List.of();
        }

        @Override
        public dev.turboism.sdk.event.EventBus eventBus() {
            return null;
        }

        @Override
        public dev.turboism.sdk.action.ActionRegistry actions() {
            return null;
        }

        @Override
        public dev.turboism.sdk.menu.MenuRegistry menus() {
            return null;
        }

        @Override
        public DisposableScope disposableScope() {
            return scope;
        }

        @Override
        public PluginLogger logger() {
            return new PluginLogger() {
                @Override
                public void debug(final String message) { }
                @Override
                public void info(final String message) { }
                @Override
                public void warn(final String message) { }
                @Override
                public void error(final String message) { }
                @Override
                public void error(final String message, final Throwable throwable) { }
            };
        }

    }

    private static final class RecordingUiHost implements UiHostCapabilityService {

        private final List<EmbeddedPanelContribution> panels = new ArrayList<>();

        List<EmbeddedPanelContribution> panels() {
            return List.copyOf(panels);
        }

        @Override
        public Registration contributeEmbeddedPanel(final EmbeddedPanelContribution contribution) {
            panels.add(contribution);
            return () -> panels.remove(contribution);
        }

        @Override
        public Registration contributeOverlay(final dev.turboism.sdk.ui.OverlayContribution contribution) {
            return noOp();
        }

        @Override
        public Registration contributeBoundingBoxOverlayButton(
            final dev.turboism.sdk.ui.BoundingBoxOverlayButton contribution
        ) {
            return noOp();
        }

        @Override
        public dev.turboism.sdk.ui.context.ContextSourceSnapshot contextSource() {
            return null;
        }

        @Override
        public dev.turboism.sdk.ui.ViewportSnapshot viewport() {
            return null;
        }

        @Override
        public Registration openDialog(final dev.turboism.sdk.ui.DialogRequest request) {
            return noOp();
        }

        @Override
        public boolean confirmDialog(final dev.turboism.sdk.ui.DialogRequest request) {
            return false;
        }

        @Override
        public Optional<String> requestFile(final dev.turboism.sdk.ui.FileChooserRequest request) {
            return Optional.empty();
        }

        @Override
        public Registration notifyStatus(final dev.turboism.sdk.ui.StatusNotification notification) {
            return noOp();
        }

        @Override
        public Registration contributeContextMenu(
            final dev.turboism.sdk.ui.context.ContextMenuRegistry.ContextMenuContribution contribution
        ) {
            return noOp();
        }

        @Override
        public Registration contributeMainToolbar(
            final dev.turboism.sdk.ui.toolbar.MainToolbarRegistry.MainToolbarContribution contribution
        ) {
            return noOp();
        }

        @Override
        public Registration contributePaletteToolbar(
            final dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry.PaletteToolbarContribution contribution
        ) {
            return noOp();
        }

        private static Registration noOp() {
            return () -> { };
        }
    }
}
