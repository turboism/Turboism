package dev.turboism.plugin.historypanel.service;

import dev.turboism.sdk.cubism.history.CubismHistory;
import dev.turboism.sdk.cubism.history.HistoryAction;
import dev.turboism.sdk.cubism.history.HistoryEntry;
import dev.turboism.sdk.cubism.history.HistoryMoveResult;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;

import java.util.concurrent.atomic.AtomicReference;
import dev.turboism.sdk.ui.EmbeddedPanelContribution;
import dev.turboism.sdk.ui.PanelView;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryPanelServiceTest {

    @Test
    void rendersEntriesInsideScrollWithCheckboxPerRowAndNoTopButtons() {
        final HistorySnapshot snapshot = available(
            1,
            2,
            1,
            List.of(
                new HistoryEntry(0, "Set Parameter Value", true, Optional.of(action("ParamAngleX", "-19.8", "-4.199999"))),
                new HistoryEntry(1, "Set Parameter Value", true, Optional.of(action("ParamAngleX", "-4.199999", "12.599998")))
            ),
            true,
            false
        );

        final PanelView view = service(new FakeHistory(snapshot), new RecordingUiHost()).render(snapshot);

        // The whole undo/redo list is wrapped in a scroll view.
        assertTrue(view instanceof PanelView.Scroll, "list must be inside a scroll view");
        final String text = flatten(view);
        // Top bar shows only the entry count; no cursor/availability stats.
        assertTrue(text.contains("History: 2 entries"), text);
        assertFalse(text.contains("cursor"), "no cursor statistics");
        assertTrue(text.contains("2 Set Parameter Value"), text);
        assertTrue(text.contains("ParamAngleX value: -4.199999 → 12.599998 (SET_PARAMETER_VALUE, FULL)"), text);
        assertFalse(text.contains("jump to that state"), "no bottom click hint");

        // Top level carries no undo/redo buttons.
        assertFalse(hasButton(view, "history.panel.undo"), "top undo button removed");
        assertFalse(hasButton(view, "history.panel.redo"), "top redo button removed");

        // Each row leads with a checkbox: applied (index < cursor) checked and
        // full color; undone (index >= cursor) unchecked and grayed.
        final List<PanelView.Toggle> toggles = toggles(view);
        assertEquals(2, toggles.size());
        assertTrue(toggles.get(0).selected(), "applied entry is checked");
        assertFalse(toggles.get(0).grayed(), "applied entry keeps its color");
        assertFalse(toggles.get(1).selected(), "undone entry is unchecked");
        assertTrue(toggles.get(1).grayed(), "undone entry label is grayed");
    }

    @Test
    void rendersUnavailableStateWithoutEntries() {
        final PanelView view = service(new FakeHistory(HistorySnapshot.unavailable()), new RecordingUiHost()).render(HistorySnapshot.unavailable());

        final String text = flatten(view);
        assertTrue(text.contains("History unavailable"), text);
        assertFalse(text.contains("[toggle:"), "unavailable state renders no checkboxes");
    }

    @Test
    void labelsEntriesWithoutStructuredDetail() {
        final HistorySnapshot snapshot = available(
            1,
            0,
            0,
            List.of(new HistoryEntry(0, "Native Action", true, Optional.empty())),
            true,
            false
        );

        final String text = flatten(service(new FakeHistory(snapshot), new RecordingUiHost()).render(snapshot));
        assertTrue(text.contains("History: 1 entries"), text);
        assertTrue(text.contains("1 Native Action"), text);
        assertTrue(text.contains("no structured detail"), text);
    }

    @Test
    void enableRegistersPanelAndPollingThenCloseStopsBoth() {
        final FakeHistory history = new FakeHistory(available(1, 0, 0, List.of(), false, false));
        final RecordingUiHost uiHost = new RecordingUiHost();
        final HistoryPanelService service = new HistoryPanelService(history, uiHost, new NullLogger(), new FakeLocalization());

        final Registration registration = service.enable();

        assertEquals(1, uiHost.panels().size());
        assertTrue(flatten(uiHost.panels().get(0).content()).contains("History: 0 entries"));

        // A snapshot change is picked up by the poller thread.
        history.push(available(2, 1, 1, List.of(new HistoryEntry(0, "Write", true, Optional.empty())), true, false));
        awaitPoll();
        assertEquals(1, uiHost.panels().size());
        assertTrue(flatten(uiHost.panels().get(0).content()).contains("History: 1 entries"));

        registration.close();
        assertEquals(0, uiHost.panels().size());
    }

    @Test
    void unchangedSnapshotDoesNotRecontribute() {
        final FakeHistory history = new FakeHistory(available(1, 0, 0, List.of(), false, false));
        final RecordingUiHost uiHost = new RecordingUiHost();
        final HistoryPanelService service = new HistoryPanelService(history, uiHost, new NullLogger(), new FakeLocalization());

        service.enable();
        awaitPoll();
        awaitPoll();

        assertEquals(1, uiHost.panels().size());
    }

    private static HistoryAction action(final String targetId, final String before, final String after) {
        return new HistoryAction(
            HistoryAction.Kind.SET_PARAMETER_VALUE,
            "PARAMETER",
            targetId,
            "value",
            Optional.of(before),
            Optional.of(after),
            HistoryAction.DetailLevel.FULL
        );
    }

    private static HistorySnapshot available(
        final long generation,
        final long revision,
        final int position,
        final List<HistoryEntry> entries,
        final boolean canUndo,
        final boolean canRedo
    ) {
        return new HistorySnapshot(
            HistorySnapshot.Availability.AVAILABLE,
            generation,
            revision,
            position,
            entries,
            canUndo,
            canRedo
        );
    }

    private static String flatten(final PanelView view) {
        final StringBuilder builder = new StringBuilder();
        flatten(view, builder);
        return builder.toString();
    }

    private static void flatten(final PanelView view, final StringBuilder builder) {
        if (view instanceof PanelView.Column column) {
            column.children().forEach(child -> flatten(child, builder));
        } else if (view instanceof PanelView.Row row) {
            row.children().forEach(child -> flatten(child, builder));
        } else if (view instanceof PanelView.Scroll scroll) {
            flatten(scroll.child(), builder);
        } else if (view instanceof PanelView.Button button) {
            builder.append("[button:").append(button.label()).append("]\n");
        } else if (view instanceof PanelView.Toggle toggle) {
            builder.append("[toggle:").append(toggle.selected() ? "1" : "0").append(":").append(toggle.id()).append(":").append(toggle.label()).append("]\n");
        } else if (view instanceof PanelView.Text text) {
            builder.append(text.value()).append('\n');
        }
    }

    private static boolean hasButton(final PanelView view, final String id) {
        if (view instanceof PanelView.Button button) {
            return button.id().equals(id);
        }
        if (view instanceof PanelView.Column column) {
            return column.children().stream().anyMatch(child -> hasButton(child, id));
        }
        if (view instanceof PanelView.Row row) {
            return row.children().stream().anyMatch(child -> hasButton(child, id));
        }
        if (view instanceof PanelView.Scroll scroll) {
            return hasButton(scroll.child(), id);
        }
        return false;
    }

    private static List<PanelView.Toggle> toggles(final PanelView view) {
        final List<PanelView.Toggle> result = new ArrayList<>();
        collectToggles(view, result);
        return result;
    }

    private static void collectToggles(final PanelView view, final List<PanelView.Toggle> result) {
        if (view instanceof PanelView.Toggle toggle) {
            result.add(toggle);
        } else if (view instanceof PanelView.Column column) {
            column.children().forEach(child -> collectToggles(child, result));
        } else if (view instanceof PanelView.Row row) {
            row.children().forEach(child -> collectToggles(child, result));
        } else if (view instanceof PanelView.Scroll scroll) {
            collectToggles(scroll.child(), result);
        }
    }

    private static final class FakeHistory implements CubismHistory {

        private HistorySnapshot snapshot;

        private FakeHistory(final HistorySnapshot snapshot) {
            this.snapshot = snapshot;
        }

        private void push(final HistorySnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public HistorySnapshot snapshot() {
            return snapshot;
        }

        @Override
        public HistoryMoveResult moveTo(final long expectedGeneration, final long expectedRevision, final int position) {
            return new HistoryMoveResult(
                HistoryMoveResult.Outcome.UNAVAILABLE,
                snapshot,
                Optional.of("history.move.unavailable")
            );
        }
    }

    private static final class RecordingUiHost implements UiHostCapabilityService {

        private final List<EmbeddedPanelContribution> panels = new ArrayList<>();
        private final List<Registration> registrations = new ArrayList<>();

        private List<EmbeddedPanelContribution> panels() {
            return List.copyOf(panels);
        }

        private List<Registration> registrations() {
            return List.copyOf(registrations);
        }

        @Override
        public Registration contributeEmbeddedPanel(final EmbeddedPanelContribution contribution) {
            // Mirrors the runtime authority: same identity replaces the previous
            // contribution (content refresh) instead of adding a second panel.
            panels.removeIf(existing -> existing.id().equals(contribution.id()));
            panels.add(contribution);
            final Registration registration =
                () -> panels.removeIf(existing -> existing.id().equals(contribution.id()));
            registrations.add(registration);
            return registration;
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

    private static void awaitPoll() {
        try {
            Thread.sleep(1_250L);
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(interruption);
        }
    }

    private static HistoryPanelService service(
        final FakeHistory history,
        final RecordingUiHost uiHost
    ) {
        return new HistoryPanelService(history, uiHost, new NullLogger(), new FakeLocalization());
    }

    private static final class FakeLocalization implements dev.turboism.sdk.i18n.PluginLocalization {
        @Override
        public java.util.Locale locale() {
            return java.util.Locale.ENGLISH;
        }

        @Override
        public String text(final String key) {
            return switch (key) {
                case "history.panel.unavailable" -> "History unavailable";
                case "history.entry.no-detail" -> "no structured detail";
                default -> key;
            };
        }

        @Override
        public String format(final String key, final Object... arguments) {
            if (key.equals("history.panel.count")) {
                return "History: " + arguments[0] + " entries";
            }
            return text(key);
        }

        @Override
        public boolean contains(final String key) {
            return true;
        }
    }

    private static final class NullLogger implements PluginLogger {
        @Override
        public void debug(final String message) { }
        @Override
        public void info(final String message) { }
        @Override
        public void warn(final String message) { }
        @Override
        public void error(final String message) { }
        @Override
        public void error(final String message, final Throwable failure) { }
    }
}
