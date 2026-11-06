package dev.turboism.plugin.projectworktime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.worktime.ProjectWorkTime;
import dev.turboism.sdk.cubism.worktime.ProjectWorkTimeService;
import dev.turboism.sdk.cubism.worktime.ProjectWorkTimeSnapshot;
import dev.turboism.sdk.task.FixedDelayTaskRequest;
import dev.turboism.sdk.task.PluginTaskRequest;
import dev.turboism.sdk.task.PluginTaskScheduler;
import dev.turboism.sdk.task.TaskHandle;
import dev.turboism.sdk.task.TaskId;
import dev.turboism.sdk.task.TaskOutcome;
import dev.turboism.sdk.task.TaskProgress;
import dev.turboism.sdk.task.TaskSubmission;
import dev.turboism.sdk.task.TaskSubmissionStatus;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.test.plugin.FakePluginContext;
import dev.turboism.test.ui.RecordingUiHost;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ProjectWorkTimePluginTest {

    @Test
    void formatsDurationsAsHoursMinutesSeconds() {
        assertEquals("00:00:00", ProjectWorkTimePlugin.format(Duration.ZERO));
        assertEquals("00:00:05", ProjectWorkTimePlugin.format(Duration.ofSeconds(5)));
        assertEquals("01:02:03", ProjectWorkTimePlugin.format(Duration.ofSeconds(3723)));
        assertEquals("25:00:00", ProjectWorkTimePlugin.format(Duration.ofHours(25)));
    }

    @Test
    void negativeDurationsFailClosedToZero() {
        assertEquals("00:00:00", ProjectWorkTimePlugin.format(Duration.ofSeconds(-1)));
    }

    @Test
    void enablePublishesBothMetricsAndDisableRemovesThem() {
        final RecordingUiHost uiHost = new RecordingUiHost();
        final ManualScheduler scheduler = new ManualScheduler();
        final ProjectWorkTimeSnapshot snapshot = snapshot();
        final FakePluginContext context = FakePluginContext.builder()
                .localization(FakePluginContext.passThroughLocalization())
                .tasks(scheduler)
                .uiHost(uiHost)
                .service(ProjectWorkTimeService.class, () -> snapshot)
                .build();
        final ProjectWorkTimePlugin plugin = new ProjectWorkTimePlugin();

        plugin.init(context);
        plugin.enable();
        assertTrue(scheduler.hasPending());
        scheduler.runPending();

        final List<StatusNotification> notifications = uiHost.notifications();
        assertEquals(2, notifications.size());
        assertEquals("status.session 00:00:05", notification(notifications, "project-work-time.session"));
        assertEquals("status.project 01:00:00", notification(notifications, "project-work-time.project"));
        assertTrue(notifications.stream()
                .allMatch(
                        notification -> notification.presentation() == StatusNotification.Presentation.COMPACT_METRIC));

        plugin.disable();
        assertTrue(uiHost.notifications().isEmpty());
        assertFalse(scheduler.hasPending());

        plugin.enable();
        assertTrue(scheduler.hasPending());
        scheduler.runPending();
        assertEquals(2, uiHost.notifications().size());
        plugin.shutdown();
        assertTrue(uiHost.notifications().isEmpty());
    }

    private static String notification(final List<StatusNotification> notifications, final String id) {
        return notifications.stream()
                .filter(notification -> notification.id().equals(id))
                .findFirst()
                .orElseThrow()
                .message();
    }

    private static ProjectWorkTimeSnapshot snapshot() {
        final ProjectWorkTime project = new ProjectWorkTime(
                "alpha.cmo3|",
                "alpha.cmo3",
                Duration.ofSeconds(5),
                Duration.ofHours(1),
                Instant.parse("2026-09-12T00:00:00Z"));
        return new ProjectWorkTimeSnapshot(
                true,
                Duration.ofSeconds(5),
                Optional.of(project),
                List.of(project),
                Instant.parse("2026-09-12T00:00:00Z"));
    }

    /** Scheduler fake that records one repeating request and runs it only when asked. */
    private static final class ManualScheduler implements PluginTaskScheduler {
        private FixedDelayTaskRequest pending;
        private final AtomicBoolean closed = new AtomicBoolean();

        boolean hasPending() {
            return pending != null;
        }

        void runPending() {
            final FixedDelayTaskRequest request = pending;
            if (request == null) {
                throw new AssertionError("no pending task");
            }
            try {
                request.action().run(null);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }

        @Override
        public TaskSubmission submit(final PluginTaskRequest request) {
            throw new UnsupportedOperationException("one-shot tasks are not used by this test");
        }

        @Override
        public TaskSubmission scheduleWithFixedDelay(final FixedDelayTaskRequest request) {
            pending = request;
            return new TaskSubmission(TaskSubmissionStatus.ACCEPTED, new Handle(request.id()), Optional.empty());
        }

        private final class Handle implements TaskHandle {
            private final TaskId id;
            private final CompletableFuture<TaskOutcome> completion = new CompletableFuture<>();

            private Handle(final TaskId id) {
                this.id = id;
            }

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
                pending = null;
                return closed.compareAndSet(false, true);
            }

            @Override
            public CompletionStage<TaskOutcome> completion() {
                return completion;
            }

            @Override
            public void close() {
                cancel();
            }
        }
    }
}
