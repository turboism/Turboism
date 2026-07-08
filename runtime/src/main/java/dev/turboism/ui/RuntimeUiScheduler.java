package dev.turboism.ui;

import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RuntimeUiScheduler implements UiScheduler {

    private static final String UI_TASK_TYPE = "ui.schedule";
    private static final String DEFAULT_CAPABILITY = "none";

    private final RuntimeScheduler scheduler;
    private final String pluginId;
    private final ScheduledExecutorService timer;

    public RuntimeUiScheduler(RuntimeScheduler scheduler, String pluginId) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.pluginId = requireText(pluginId, "pluginId");
        this.timer = Executors.newSingleThreadScheduledExecutor(new UiTimerThreadFactory(pluginId));
    }

    @Override
    public Registration runOnUiThread(Runnable work) {
        Objects.requireNonNull(work, "work");
        AtomicBoolean cancelled = new AtomicBoolean();
        scheduler.dispatch(task("immediate UI work"), () -> {
            if (!cancelled.get()) {
                work.run();
            }
        });
        return () -> cancelled.set(true);
    }

    @Override
    public Registration runOnUiThreadLater(Runnable work, Duration delay) {
        Objects.requireNonNull(work, "work");
        Objects.requireNonNull(delay, "delay");
        AtomicBoolean cancelled = new AtomicBoolean();
        ScheduledFuture<?> scheduled = timer.schedule(
            () -> scheduler.dispatch(task("delayed UI work"), () -> {
                if (!cancelled.get()) {
                    work.run();
                }
            }),
            Math.max(0L, delay.toMillis()),
            TimeUnit.MILLISECONDS
        );
        return () -> {
            cancelled.set(true);
            scheduled.cancel(false);
        };
    }

    private PluginTask task(String payloadDescription) {
        return new PluginTask(UI_TASK_TYPE, pluginId, payloadDescription, DEFAULT_CAPABILITY);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private record UiTimerThreadFactory(String pluginId) implements ThreadFactory {

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "turboism-ui-timer-" + pluginId);
            thread.setDaemon(true);
            return thread;
        }
    }
}
