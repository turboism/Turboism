package dev.turboism.plugin.projectworktime;

import dev.turboism.sdk.cubism.worktime.ProjectWorkTime;
import dev.turboism.sdk.cubism.worktime.ProjectWorkTimeService;
import dev.turboism.sdk.cubism.worktime.ProjectWorkTimeSnapshot;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;
import dev.turboism.sdk.task.FixedDelayTaskRequest;
import dev.turboism.sdk.task.PluginTaskKind;
import dev.turboism.sdk.task.PluginTaskPriority;
import dev.turboism.sdk.task.PluginTaskScheduler;
import dev.turboism.sdk.task.TaskHandle;
import dev.turboism.sdk.task.TaskId;
import dev.turboism.sdk.task.TaskSubmission;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Shows the runtime-owned work-time accounting in Cubism's bottom status bar.
 *
 * <p>Two compact resident metrics are published: the current Editor-session foreground time
 * and the active project's cross-session cumulative time. The plugin owns only presentation;
 * the runtime tracker is the single accounting source shared by every plugin.</p>
 */
public final class ProjectWorkTimePlugin implements TurboismPlugin {

    static final Duration REFRESH_INTERVAL = Duration.ofSeconds(1);
    static final String SESSION_STATUS_ID = "project-work-time.session";
    static final String PROJECT_STATUS_ID = "project-work-time.project";
    private static final String TASK_ID = "project-work-time.refresh";

    private final Object lifecycleLock = new Object();
    private PluginContext context;
    private PluginLogger logger;
    private volatile UiHostCapabilityService uiHost;
    private volatile ProjectWorkTimeService workTime;
    private TaskHandle refreshTask;
    private Registration sessionStatus;
    private Registration projectStatus;
    private String lastSessionText;
    private String lastProjectText;
    private boolean initialized;
    private boolean enabled;
    private long generation;

    @Override
    public void init(final PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
        this.logger = context.logger();
        context.disposableScope().register(this::deactivate);
        synchronized (lifecycleLock) {
            initialized = true;
        }
        logger.info("Project Work Time initialized");
    }

    @Override
    public void enable() {
        final long enableGeneration;
        synchronized (lifecycleLock) {
            if (!initialized) {
                throw new IllegalStateException("Project Work Time must be initialized before enable.");
            }
            if (enabled) {
                return;
            }
            enabled = true;
            enableGeneration = ++generation;
        }
        uiHost = context.services().find(UiHostCapabilityService.class).orElse(null);
        workTime = context.services().find(ProjectWorkTimeService.class).orElse(null);
        if (uiHost == null || !uiHost.isAvailable()) {
            logger.warn("project-work-time: status-bar service is unavailable; metrics are not shown");
            disableGeneration(enableGeneration);
            return;
        }
        if (workTime == null || !workTime.isAvailable()) {
            logger.warn("project-work-time: work-time tracker is unavailable; metrics are not shown");
            disableGeneration(enableGeneration);
            return;
        }
        final PluginTaskScheduler tasks = context.tasks();
        if (!tasks.isAvailable()) {
            logger.warn("project-work-time: task scheduler is unavailable; metrics are not refreshed");
            disableGeneration(enableGeneration);
            return;
        }
        final TaskSubmission submission;
        try {
            submission = tasks.scheduleWithFixedDelay(new FixedDelayTaskRequest(
                    new TaskId(TASK_ID),
                    PluginTaskKind.LOW_FREQUENCY_REFRESH,
                    PluginTaskPriority.LOW,
                    Duration.ZERO,
                    REFRESH_INTERVAL,
                    ignored -> refresh(enableGeneration)));
        } catch (RuntimeException rejected) {
            logger.warn("project-work-time: refresh task rejected: "
                    + rejected.getClass().getSimpleName());
            disableGeneration(enableGeneration);
            return;
        }
        if (!submission.accepted()) {
            logger.warn("project-work-time: refresh task not accepted: "
                    + submission.rejectionReason().map(Object::toString).orElse("unknown"));
            disableGeneration(enableGeneration);
            return;
        }
        synchronized (lifecycleLock) {
            if (enabled && generation == enableGeneration) {
                refreshTask = submission.handle();
            } else {
                submission.handle().close();
            }
        }
        logger.info("Project Work Time enabled");
    }

    private void refresh(final long refreshGeneration) {
        synchronized (lifecycleLock) {
            if (!enabled || generation != refreshGeneration) {
                return;
            }
        }
        final ProjectWorkTimeService service = workTime;
        final UiHostCapabilityService statusHost = uiHost;
        if (service == null || statusHost == null) {
            return;
        }
        final ProjectWorkTimeSnapshot snapshot;
        try {
            snapshot = service.snapshot();
        } catch (RuntimeException failure) {
            logger.warn("project-work-time: snapshot read failed safely: "
                    + failure.getClass().getSimpleName());
            return;
        }
        final String sessionText = sessionText(snapshot.sessionForegroundTime());
        final String projectText = projectText(snapshot.activeProject());
        // Publish the project metric first so the session metric mounts to its left on a
        // fresh status bar and the pair reads "Session <time>  Project <time>".
        publish(PROJECT_STATUS_ID, projectText, false, refreshGeneration);
        publish(SESSION_STATUS_ID, sessionText, true, refreshGeneration);
    }

    private void publish(
            final String id, final String text, final boolean sessionMetric, final long refreshGeneration) {
        synchronized (lifecycleLock) {
            if (!enabled || generation != refreshGeneration) {
                return;
            }
            if (text.equals(sessionMetric ? lastSessionText : lastProjectText)) {
                return;
            }
        }
        final Registration next;
        try {
            next = uiHost.notifyStatus(
                    new StatusNotification(id, "INFO", text, StatusNotification.Presentation.COMPACT_METRIC));
        } catch (RuntimeException failure) {
            logger.warn("project-work-time: status update failed safely: "
                    + failure.getClass().getSimpleName());
            return;
        }
        final Registration previous;
        final boolean accepted;
        synchronized (lifecycleLock) {
            accepted = enabled && generation == refreshGeneration;
            if (!accepted) {
                previous = null;
            } else if (sessionMetric) {
                previous = sessionStatus;
                sessionStatus = next;
                lastSessionText = text;
            } else {
                previous = projectStatus;
                projectStatus = next;
                lastProjectText = text;
            }
        }
        if (!accepted) {
            next.close();
        } else if (previous != null) {
            previous.close();
        }
    }

    private String sessionText(final Duration duration) {
        final String label = context.localization().text("status.session", "Session");
        return label + " " + format(duration);
    }

    private String projectText(final Optional<ProjectWorkTime> activeProject) {
        final String label = context.localization().text("status.project", "Project");
        final String value = activeProject
                .map(ProjectWorkTime::totalTime)
                .map(ProjectWorkTimePlugin::format)
                .orElseGet(() -> context.localization().text("status.no-project", "--:--:--"));
        return label + " " + value;
    }

    static String format(final Duration duration) {
        final long totalSeconds = Math.max(0L, duration.getSeconds());
        final long hours = totalSeconds / 3600L;
        final long minutes = (totalSeconds % 3600L) / 60L;
        final long seconds = totalSeconds % 60L;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds);
    }

    @Override
    public void disable() {
        deactivate();
    }

    @Override
    public void shutdown() {
        deactivate();
    }

    private void deactivate() {
        synchronized (lifecycleLock) {
            if (!initialized) {
                return;
            }
            enabled = false;
            generation++;
            closeQuietly(refreshTask);
            refreshTask = null;
            closeQuietly(sessionStatus);
            closeQuietly(projectStatus);
            sessionStatus = null;
            projectStatus = null;
            lastSessionText = null;
            lastProjectText = null;
        }
        if (logger != null) {
            logger.info("Project Work Time disabled");
        }
    }

    private void disableGeneration(final long expectedGeneration) {
        synchronized (lifecycleLock) {
            if (generation == expectedGeneration) {
                enabled = false;
                generation++;
            }
        }
    }

    private void closeQuietly(final AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (RuntimeException failure) {
            logger.warn("project-work-time: status cleanup failed safely: "
                    + failure.getClass().getSimpleName());
        } catch (Exception failure) {
            logger.warn("project-work-time: status cleanup failed safely: "
                    + failure.getClass().getSimpleName());
        }
    }
}
