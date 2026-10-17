package dev.turboism.plugin.historypanel.service;

import dev.turboism.sdk.cubism.history.CubismHistory;
import dev.turboism.sdk.cubism.history.HistoryMoveResult;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.plugin.CancellationToken;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.task.FixedDelayTaskRequest;
import dev.turboism.sdk.task.PluginTaskRequest;
import dev.turboism.sdk.task.PluginTaskScheduler;
import dev.turboism.sdk.task.TaskHandle;
import dev.turboism.sdk.task.TaskId;
import dev.turboism.sdk.task.TaskOutcome;
import dev.turboism.sdk.task.TaskProgress;
import dev.turboism.sdk.task.TaskRejectionReason;
import dev.turboism.sdk.task.TaskSubmission;
import dev.turboism.sdk.task.TaskSubmissionStatus;
import dev.turboism.sdk.ui.EmbeddedPanelContribution;
import dev.turboism.sdk.ui.PanelView;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

final class HistoryPanelTestFixtures {

    static final class FakeHistory implements CubismHistory {

        HistorySnapshot snapshot;

        FakeHistory(final HistorySnapshot snapshot) {
            this.snapshot = snapshot;
        }

        void push(final HistorySnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public HistorySnapshot snapshot() {
            return snapshot;
        }

        @Override
        public HistoryMoveResult moveTo(
                final long expectedGeneration, final long expectedRevision, final int position) {
            return new HistoryMoveResult(
                    HistoryMoveResult.Outcome.UNAVAILABLE, snapshot, Optional.of("history.move.unavailable"));
        }
    }

    static final class RecordingUiHost implements UiHostCapabilityService {

        final List<EmbeddedPanelContribution> panels = new ArrayList<>();
        final List<Registration> registrations = new ArrayList<>();

        List<EmbeddedPanelContribution> panels() {
            return List.copyOf(panels);
        }

        List<Registration> registrations() {
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
                final dev.turboism.sdk.ui.BoundingBoxOverlayButton contribution) {
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
                final dev.turboism.sdk.ui.context.ContextMenuRegistry.ContextMenuContribution contribution) {
            return noOp();
        }

        @Override
        public Registration contributeMainToolbar(
                final dev.turboism.sdk.ui.toolbar.MainToolbarRegistry.MainToolbarContribution contribution) {
            return noOp();
        }

        @Override
        public Registration contributePaletteToolbar(
                final dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry.PaletteToolbarContribution contribution) {
            return noOp();
        }

        static Registration noOp() {
            return () -> {};
        }
    }

    /**
     * Records fixed-delay scheduling so the test can assert the cadence, drive
     * ticks deterministically, and observe idempotent handle cancellation.
     */
    static final class RecordingTaskScheduler implements PluginTaskScheduler {

        final List<FixedDelayTaskRequest> requests = new ArrayList<>();
        final List<TaskHandle> handles = new ArrayList<>();
        final boolean reject;

        RecordingTaskScheduler() {
            this(false);
        }

        RecordingTaskScheduler(final boolean reject) {
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
                        Optional.of(TaskRejectionReason.BACKPRESSURE));
            }
            final TaskHandle handle = new TaskHandle() {
                boolean closed;

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

        List<FixedDelayTaskRequest> requests() {
            return List.copyOf(requests);
        }

        List<TaskHandle> handles() {
            return List.copyOf(handles);
        }

        long closedHandles() {
            return closedCount;
        }

        void tick() {
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
                    public void checkCanceled() {}
                });
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            }
        }

        int closedCount;
    }

    static final class ThrowingTaskScheduler implements PluginTaskScheduler {

        @Override
        public TaskSubmission submit(final PluginTaskRequest request) {
            throw new AssertionError("history panel must not use one-shot submit");
        }

        @Override
        public TaskSubmission scheduleWithFixedDelay(final FixedDelayTaskRequest request) {
            throw new IllegalStateException("scheduler unavailable");
        }
    }

    static TaskHandle noOpHandle(final TaskId id) {
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
            public void close() {}
        };
    }

    static HistoryPanelService service(final FakeHistory history, final RecordingUiHost uiHost) {
        return new HistoryPanelService(
                history, uiHost, new RecordingTaskScheduler(), new NullLogger(), new FakeLocalization());
    }

    static final class RecordingLogger implements PluginLogger {

        final List<String> warns = new ArrayList<>();

        List<String> warns() {
            return List.copyOf(warns);
        }

        @Override
        public void debug(final String message) {}

        @Override
        public void info(final String message) {}

        @Override
        public void warn(final String message) {
            warns.add(message);
        }

        @Override
        public void error(final String message) {}

        @Override
        public void error(final String message, final Throwable failure) {}
    }

    static final class FakeLocalization implements dev.turboism.sdk.i18n.PluginLocalization {
        @Override
        public java.util.Locale locale() {
            return java.util.Locale.ENGLISH;
        }

        @Override
        public String text(final String key) {
            return switch (key) {
                case "history.panel.unavailable" -> "History unavailable";
                case "history.entry.no-detail" -> "no structured detail";
                case "history.entry.level.full" -> "full detail";
                case "history.entry.level.partial" -> "partial detail";
                case "history.entry.level.label_only" -> "label only";
                case "history.entry.target.unknown" -> "unknown target";
                case "history.entry.context.default-form" -> " default shape";
                case "history.property.value" -> "value";
                case "history.property.name" -> "name";
                case "history.property.id" -> "ID";
                case "history.property.intensity" -> "intensity";
                case "history.property.drawable-a" -> "drawable A";
                case "history.property.drawable-b" -> "drawable B";
                case "history.property.opacity" -> "opacity";
                case "history.property.draw-order" -> "draw order";
                case "history.property.multiply-color" -> "multiply color";
                case "history.property.screen-color" -> "screen color";
                case "history.property.vertex-positions" -> "vertex positions";
                case "history.property.control-point-positions" -> "control point positions";
                case "history.property.angle" -> "angle";
                case "history.property.origin" -> "origin";
                case "history.property.scale" -> "scale";
                case "history.property.reflect-x" -> "reflect X";
                case "history.property.reflect-y" -> "reflect Y";
                case "history.property.translation" -> "translation";
                case "history.panel.empty" -> "No history yet";
                case "history.entry.action.add" -> "Add";
                case "history.entry.action.remove" -> "Remove";
                case "history.entry.action.move" -> "Move";
                case "history.entry.action.vertex-move" -> "Vertex move";
                case "history.icon.art-mesh" -> "Artmesh icon";
                case "history.icon.warp-deformer" -> "Warp deformer icon";
                case "history.icon.rotation-deformer" -> "Rotation deformer icon";
                case "history.icon.part" -> "Part icon";
                case "history.relation.deformer-parent.set" -> "{0} is set under {1} as its child";
                case "history.relation.part-membership.join" -> "{0} joins {1}";
                case "history.relation.deformer-parent.detach" -> "{0} leaves deformer {1}";
                case "history.relation.part-membership.detach" -> "{0} leaves part {1}";
                case "history.target.art-mesh" -> "Artmesh";
                case "history.target.parameter" -> "Parameter";
                case "history.target.part" -> "Part";
                case "history.target.warp-deformer" -> "Warp deformer";
                case "history.target.rotation-deformer" -> "Rotation deformer";
                case "history.target.glue" -> "Glue";
                case "history.target.document" -> "Document";
                default -> key;
            };
        }

        @Override
        public String format(final String key, final Object... arguments) {
            if (key.equals("history.entry.affected")) {
                return arguments[0] + " affected";
            }
            if (key.equals("history.entry.origin.turboism")) {
                return "Turboism (" + arguments[0] + ")";
            }
            if (key.equals("history.entry.action.set")) {
                return "Set " + arguments[0];
            }
            if (key.equals("history.entry.target.named")) {
                return arguments[0] + "(" + arguments[1] + ")";
            }
            if (key.equals("history.entry.context.coordinate")) {
                return arguments[0] + "=" + arguments[1];
            }
            if (key.equals("history.entry.context.keyform")) {
                return " at keyform [" + arguments[0] + "]";
            }
            if (key.equals("history.entry.change.set")) {
                return arguments[0] + "" + arguments[1] + " " + arguments[2] + " changed from " + arguments[3] + " to "
                        + arguments[4];
            }
            if (key.equals("history.entry.change.set-after")) {
                return arguments[0] + "" + arguments[1] + " " + arguments[2] + " changed to " + arguments[3];
            }
            if (key.equals("history.entry.change.add")) {
                return arguments[0] + "" + arguments[1] + " added";
            }
            if (key.equals("history.entry.change.remove")) {
                return arguments[0] + "" + arguments[1] + " removed";
            }
            if (key.equals("history.entry.change.move")) {
                return arguments[0] + "" + arguments[1] + " moved" + arguments[2];
            }
            return text(key);
        }

        @Override
        public boolean contains(final String key) {
            return true;
        }
    }

    static final class NullLogger implements PluginLogger {
        @Override
        public void debug(final String message) {}

        @Override
        public void info(final String message) {}

        @Override
        public void warn(final String message) {}

        @Override
        public void error(final String message) {}

        @Override
        public void error(final String message, final Throwable failure) {}
    }

    static PanelView render(final HistorySnapshot snapshot) {
        return service(new FakeHistory(snapshot), new RecordingUiHost()).render(snapshot);
    }
}
