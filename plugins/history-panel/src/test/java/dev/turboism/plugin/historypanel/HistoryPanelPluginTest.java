package dev.turboism.plugin.historypanel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.plugin.historypanel.service.HistoryPanelService;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.history.CubismHistory;
import dev.turboism.sdk.cubism.history.HistoryEntry;
import dev.turboism.sdk.cubism.history.HistoryEntryId;
import dev.turboism.sdk.cubism.history.HistoryMoveResult;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.EmbeddedPanelContribution;
import dev.turboism.sdk.ui.UiScheduler;
import dev.turboism.test.action.RecordingActionRegistry;
import dev.turboism.test.plugin.FakePluginContext;
import dev.turboism.test.ui.RecordingUiHost;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HistoryPanelPluginTest {

    @Test
    void enableRegistersVerticalToolStripAndToggleShowsAndHidesPanel() {
        final RecordingContext context = new RecordingContext();
        final HistoryPanelPlugin plugin = new HistoryPanelPlugin();

        plugin.init(context);
        plugin.enable();

        assertEquals(1, context.uiHost().verticalToolbars().size());
        assertEquals(
                "history.toolstrip", context.uiHost().verticalToolbars().get(0).contributionId());
        assertEquals(0, context.uiHost().horizontalToolbars().size());
        assertEquals(0, context.uiHost().panels().size());

        // Toggle action shows the panel; it installs already floating so no
        // separate activation round-trip is needed (no docked intermediate state).
        context.actions().execute(HistoryPanelPlugin.TOGGLE_ACTION_ID);
        assertEquals(1, context.uiHost().panels().size());
        assertTrue(context.uiHost().floatingActivations().isEmpty());
        final EmbeddedPanelContribution panel = context.uiHost().panels().get(0);
        assertEquals("history.panel", panel.id());
        assertTrue(panel.floatingByDefault());
        assertNotNull(panel.content());

        // Toggle again hides it.
        context.actions().execute(HistoryPanelPlugin.TOGGLE_ACTION_ID);
        assertEquals(0, context.uiHost().panels().size());
    }

    @Test
    void entryNavigationUsesStableIdentityAndRejectsChangedSnapshotBinding() {
        final RecordingContext context = new RecordingContext();
        context.historySnapshot = new HistorySnapshot(
                HistorySnapshot.Availability.AVAILABLE,
                7,
                11,
                2,
                List.of(
                        new HistoryEntry(
                                0,
                                "First",
                                true,
                                Optional.empty(),
                                Optional.of(new HistoryEntryId("entry-a")),
                                Optional.empty()),
                        new HistoryEntry(
                                1,
                                "Second",
                                true,
                                Optional.empty(),
                                Optional.of(new HistoryEntryId("entry-b")),
                                Optional.empty())),
                true,
                false);
        final HistoryPanelPlugin plugin = new HistoryPanelPlugin();
        plugin.init(context);
        plugin.enable();
        context.actions().execute(HistoryPanelPlugin.TOGGLE_ACTION_ID);

        context.actions().execute(HistoryPanelService.moveActionId("entry-a"));
        assertEquals(List.of(0), context.movePositions);

        context.historySnapshot = new HistorySnapshot(
                HistorySnapshot.Availability.AVAILABLE, 7, 12, 2, context.historySnapshot.entries(), true, false);
        context.actions().execute(HistoryPanelService.moveActionId("entry-b"));
        assertEquals(List.of(0), context.movePositions, "stale binding must not move by old index");
    }

    private static final class RecordingContext extends FakePluginContext {

        private final RecordingUiHost uiHost = new RecordingUiHost();
        private final DisposableScope scope = new DisposableScope();
        private final RecordingActionRegistry actionRegistry = new RecordingActionRegistry();
        private HistorySnapshot historySnapshot = HistorySnapshot.unavailable();
        private final List<Integer> movePositions = new ArrayList<>();

        RecordingContext() {
            super(FakePluginContext.builder().localization(passThroughLocalization()));
        }

        @Override
        public RecordingUiHost uiHost() {
            return uiHost;
        }

        @Override
        public RecordingActionRegistry actions() {
            return actionRegistry;
        }

        @Override
        public DisposableScope disposableScope() {
            return scope;
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
                public CubismHistory history() {
                    return new CubismHistory() {
                        @Override
                        public HistorySnapshot snapshot() {
                            return historySnapshot;
                        }

                        @Override
                        public HistoryMoveResult moveTo(
                                final long expectedGeneration, final long expectedRevision, final int position) {
                            movePositions.add(position);
                            return new HistoryMoveResult(
                                    HistoryMoveResult.Outcome.MOVED, historySnapshot, Optional.empty());
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
                    return () -> {};
                }

                @Override
                public Registration runOnUiThreadLater(final Runnable work, final java.time.Duration delay) {
                    return () -> {};
                }
            };
        }

        @Override
        public dev.turboism.sdk.plugin.PluginServiceDirectory services() {
            return dev.turboism.sdk.plugin.PluginServices.builder()
                    .supply(dev.turboism.sdk.ui.UiHostCapabilityService.class, () -> this.uiHost())
                    .fallback(dev.turboism.sdk.plugin.PluginServices.of(this))
                    .build();
        }
    }
}
