package dev.turboism.plugin.historypanel.service;

import dev.turboism.sdk.cubism.history.CubismHistory;
import dev.turboism.sdk.cubism.history.HistoryAction;
import dev.turboism.sdk.cubism.history.HistoryEntry;
import dev.turboism.sdk.cubism.history.HistoryMoveResult;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.plugin.CancellationToken;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.task.FixedDelayTaskRequest;
import dev.turboism.sdk.task.PluginTaskKind;
import dev.turboism.sdk.task.PluginTaskPriority;
import dev.turboism.sdk.task.PluginTaskRequest;
import dev.turboism.sdk.task.PluginTaskScheduler;
import dev.turboism.sdk.task.TaskHandle;
import dev.turboism.sdk.task.TaskId;
import dev.turboism.sdk.task.TaskOutcome;
import dev.turboism.sdk.task.TaskProgress;
import dev.turboism.sdk.task.TaskRejectionReason;
import dev.turboism.sdk.task.TaskSubmission;
import dev.turboism.sdk.task.TaskSubmissionStatus;

import java.util.concurrent.CompletionStage;
import dev.turboism.sdk.ui.EmbeddedPanelContribution;
import dev.turboism.sdk.ui.PanelView;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
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

        // The top Scroll -> Column children form the exact node-type sequence for
        // two entries: centered count text, header separator, entry toggle, row
        // separator, entry toggle. The last entry has no trailing separator.
        final PanelView.Column column = (PanelView.Column) ((PanelView.Scroll) view).child();
        final List<PanelView> children = column.children();
        assertEquals(5, children.size(), "children=" + children);
        assertTrue(children.get(0) instanceof PanelView.Text, "first child is the count text");
        final PanelView.Text countText = (PanelView.Text) children.get(0);
        assertTrue(countText.centered(), "count text is centered");
        assertEquals("Current records: 2", countText.value());
        assertTrue(children.get(1) instanceof PanelView.Separator, "header separator");
        assertTrue(children.get(2) instanceof PanelView.Toggle, "first entry toggle");
        assertTrue(children.get(3) instanceof PanelView.Separator, "row separator between entries");
        assertTrue(children.get(4) instanceof PanelView.Toggle, "second entry toggle");
        final String text = flatten(view);
        // Top bar shows only the entry count; no cursor/availability stats.
        assertTrue(text.contains("Current records: 2"), text);
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
    void eachEntryIsOneWrappingToggleWithRetainedIdentityAndDetail() {
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
        final List<PanelView.Toggle> toggles = toggles(view);
        assertEquals(2, toggles.size(), "exactly one functional Toggle per entry");

        // The wrapping toggle carries the full label plus the structured
        // detail in one label, so long entries wrap inside the viewport.
        final PanelView.Toggle first = toggles.get(0);
        assertEquals("history.entry.toggle.0", first.id());
        assertEquals("history.entry.move.0", first.actionId());
        assertTrue(first.label().contains("1 Set Parameter Value"), first.label());
        assertTrue(first.label().contains("ParamAngleX value: -19.8 → -4.199999 (SET_PARAMETER_VALUE, FULL)"), first.label());
        assertTrue(first.selected(), "applied entry is checked");
        assertFalse(first.grayed(), "applied entry keeps its color");

        final PanelView.Toggle second = toggles.get(1);
        assertEquals("history.entry.toggle.1", second.id());
        assertEquals("history.entry.move.1", second.actionId());
        assertTrue(second.label().contains("2 Set Parameter Value"), second.label());
        assertTrue(second.label().contains("ParamAngleX value: -4.199999 → 12.599998 (SET_PARAMETER_VALUE, FULL)"), second.label());
        assertFalse(second.selected(), "undone entry is unchecked");
        assertTrue(second.grayed(), "undone entry label is grayed");
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
        assertTrue(text.contains("Current records: 1"), text);
        assertTrue(text.contains("1 Native Action"), text);
        assertTrue(text.contains("no structured detail"), text);
    }

    @Test
    void enableRefreshesImmediatelyThenSchedulesOneFixedDelayPollWithCadenceTickAndIdempotentClose() {
        final FakeHistory history = new FakeHistory(available(1, 0, 0, List.of(), false, false));
        final RecordingUiHost uiHost = new RecordingUiHost();
        final RecordingTaskScheduler tasks = new RecordingTaskScheduler();
        final HistoryPanelService service = new HistoryPanelService(history, uiHost, tasks, new NullLogger(), new FakeLocalization());

        final Registration registration = service.enable();

        // One immediate refresh, then exactly one scheduled fixed-delay task.
        assertEquals(1, uiHost.panels().size());
        assertEquals(1, tasks.requests().size(), "exactly one poll task scheduled");
        final FixedDelayTaskRequest request = tasks.requests().get(0);
        assertEquals(new TaskId("history-panel-poll"), request.id());
        assertEquals(PluginTaskKind.LOW_FREQUENCY_REFRESH, request.kind());
        assertEquals(PluginTaskPriority.LOW, request.priority());
        assertEquals(Duration.ofSeconds(1), request.initialDelay());
        assertEquals(Duration.ofSeconds(1), request.delay());
        assertEquals(1, tasks.handles().size());

        // A tick picks up a snapshot change.
        history.push(available(2, 1, 1, List.of(new HistoryEntry(0, "Write", true, Optional.empty())), true, false));
        tasks.tick();
        assertEquals(1, uiHost.panels().size());
        assertTrue(flatten(uiHost.panels().get(0).content()).contains("Current records: 1"));

        // Close is idempotent and closes the accepted handle exactly once.
        registration.close();
        registration.close();
        assertEquals(1, tasks.closedHandles(), "handle closed exactly once");
        assertEquals(0, uiHost.panels().size());
    }

    @Test
    void rejectedOrThrowingSchedulerKeepsPanelUsableAndLogsBoundedWarning() {
        // Rejected submission: initial panel stays usable, bounded warning, no handle.
        final FakeHistory history = new FakeHistory(available(1, 0, 0, List.of(), false, false));
        final RecordingUiHost uiHost = new RecordingUiHost();
        final RecordingLogger logger = new RecordingLogger();
        final RecordingTaskScheduler rejecting = new RecordingTaskScheduler(true);
        final HistoryPanelService service =
            new HistoryPanelService(history, uiHost, rejecting, logger, new FakeLocalization());

        final Registration registration = service.enable();

        assertEquals(1, uiHost.panels().size(), "initial refresh keeps the panel usable");
        assertTrue(flatten(uiHost.panels().get(0).content()).contains("Current records: 0"));
        assertEquals(0, rejecting.handles().size());
        assertTrue(logger.warns().stream().anyMatch(message -> message.contains("poller")),
            "bounded warning logged: " + logger.warns());
        registration.close();
        assertEquals(0, uiHost.panels().size());

        // Throwing scheduler: same fail-closed outcome.
        final RecordingUiHost throwingUiHost = new RecordingUiHost();
        final RecordingLogger throwingLogger = new RecordingLogger();
        final HistoryPanelService throwingService = new HistoryPanelService(
            new FakeHistory(available(1, 0, 0, List.of(), false, false)),
            throwingUiHost,
            new ThrowingTaskScheduler(),
            throwingLogger,
            new FakeLocalization()
        );
        final Registration throwingRegistration = throwingService.enable();
        assertEquals(1, throwingUiHost.panels().size(), "throwing scheduler keeps the panel usable");
        assertTrue(throwingLogger.warns().stream().anyMatch(message -> message.contains("poller")),
            "bounded warning logged: " + throwingLogger.warns());
        throwingRegistration.close();
        assertEquals(0, throwingUiHost.panels().size());
    }

    @Test
    void enableRegistersPanelAndPollingThenCloseStopsBoth() {
        final FakeHistory history = new FakeHistory(available(1, 0, 0, List.of(), false, false));
        final RecordingUiHost uiHost = new RecordingUiHost();
        final RecordingTaskScheduler tasks = new RecordingTaskScheduler();
        final HistoryPanelService service = new HistoryPanelService(history, uiHost, tasks, new NullLogger(), new FakeLocalization());

        final Registration registration = service.enable();

        assertEquals(1, uiHost.panels().size());
        assertTrue(flatten(uiHost.panels().get(0).content()).contains("Current records: 0"));

        // A snapshot change is picked up by the next poll tick.
        history.push(available(2, 1, 1, List.of(new HistoryEntry(0, "Write", true, Optional.empty())), true, false));
        tasks.tick();
        assertEquals(1, uiHost.panels().size());
        assertTrue(flatten(uiHost.panels().get(0).content()).contains("Current records: 1"));

        registration.close();
        assertEquals(0, uiHost.panels().size());
    }

    @Test
    void unchangedSnapshotDoesNotRecontribute() {
        final FakeHistory history = new FakeHistory(available(1, 0, 0, List.of(), false, false));
        final RecordingUiHost uiHost = new RecordingUiHost();
        final RecordingTaskScheduler tasks = new RecordingTaskScheduler();
        final HistoryPanelService service = new HistoryPanelService(history, uiHost, tasks, new NullLogger(), new FakeLocalization());

        service.enable();
        tasks.tick();
        tasks.tick();

        assertEquals(1, uiHost.panels().size());
        assertEquals(1, uiHost.registrations().size(), "unchanged snapshots never re-contribute");
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

    /**
     * Records fixed-delay scheduling so the test can assert the cadence, drive
     * ticks deterministically, and observe idempotent handle cancellation.
     */
    private static final class RecordingTaskScheduler implements PluginTaskScheduler {

        private final List<FixedDelayTaskRequest> requests = new ArrayList<>();
        private final List<TaskHandle> handles = new ArrayList<>();
        private final boolean reject;

        private RecordingTaskScheduler() {
            this(false);
        }

        private RecordingTaskScheduler(final boolean reject) {
            this.reject = reject;
        }

        @Override
        public TaskSubmission submit(final PluginTaskRequest request) {
            throw new AssertionError("history panel must not use one-shot submit");
        }

        @Override
        public TaskSubmission scheduleWithFixedDelay(final FixedDelayTaskRequest request) {
            requests.add(request);
            if (reject) {
                return new TaskSubmission(
                    TaskSubmissionStatus.REJECTED,
                    noOpHandle(request.id()),
                    Optional.of(TaskRejectionReason.BACKPRESSURE)
                );
            }
            final TaskHandle handle = new TaskHandle() {
                private boolean closed;

                @Override
                public TaskId id() {
                    return request.id();
                }

                @Override
                public TaskProgress progress() {
                    return new TaskProgress(0, Optional.empty());
                }

                @Override
                public boolean cancel() {
                    closed = true;
                    return true;
                }

                @Override
                public CompletionStage<TaskOutcome> completion() {
                    return null;
                }

                @Override
                public void close() {
                    if (!closed) {
                        closed = true;
                        closedCount++;
                    }
                }
            };
            handles.add(handle);
            return new TaskSubmission(TaskSubmissionStatus.ACCEPTED, handle, Optional.empty());
        }

        private List<FixedDelayTaskRequest> requests() {
            return List.copyOf(requests);
        }

        private List<TaskHandle> handles() {
            return List.copyOf(handles);
        }

        private long closedHandles() {
            return closedCount;
        }

        private void tick() {
            if (requests.isEmpty()) {
                throw new IllegalStateException("no poll task scheduled");
            }
            final FixedDelayTaskRequest request = requests.get(0);
            try {
                request.action().run(new CancellationToken() {
                    @Override
                    public boolean isCancellationRequested() {
                        return false;
                    }

                    @Override
                    public void checkCanceled() {
                    }
                });
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            }
        }

        private int closedCount;
    }

    private static final class ThrowingTaskScheduler implements PluginTaskScheduler {

        @Override
        public TaskSubmission submit(final PluginTaskRequest request) {
            throw new AssertionError("history panel must not use one-shot submit");
        }

        @Override
        public TaskSubmission scheduleWithFixedDelay(final FixedDelayTaskRequest request) {
            throw new IllegalStateException("scheduler unavailable");
        }
    }

    private static TaskHandle noOpHandle(final TaskId id) {
        return new TaskHandle() {
            @Override
            public TaskId id() {
                return id;
            }

            @Override
            public TaskProgress progress() {
                return new TaskProgress(0, Optional.empty());
            }

            @Override
            public boolean cancel() {
                return false;
            }

            @Override
            public CompletionStage<TaskOutcome> completion() {
                return null;
            }

            @Override
            public void close() {
            }
        };
    }

    private static HistoryPanelService service(
        final FakeHistory history,
        final RecordingUiHost uiHost
    ) {
        return new HistoryPanelService(history, uiHost, new RecordingTaskScheduler(), new NullLogger(), new FakeLocalization());
    }

    private static final class RecordingLogger implements PluginLogger {

        private final List<String> warns = new ArrayList<>();

        private List<String> warns() {
            return List.copyOf(warns);
        }

        @Override
        public void debug(final String message) { }
        @Override
        public void info(final String message) { }
        @Override
        public void warn(final String message) {
            warns.add(message);
        }
        @Override
        public void error(final String message) { }
        @Override
        public void error(final String message, final Throwable failure) { }
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
                return "Current records: " + arguments[0];
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
