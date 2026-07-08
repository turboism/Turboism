package dev.turboism.core.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

final class PluginCallback implements Runnable {

    private final PluginTask task;
    private final Runnable callback;
    private final AtomicInteger completed = new AtomicInteger(0);
    private volatile Thread runningThread;

    PluginCallback(PluginTask task, Runnable callback) {
        this.task = Objects.requireNonNull(task, "task");
        this.callback = Objects.requireNonNull(callback, "callback");
    }

    PluginTask task() {
        return task;
    }

    @Override
    public void run() {
        runningThread = Thread.currentThread();
        try {
            callback.run();
        } finally {
            completed.set(1);
            runningThread = null;
        }
    }

    void interruptRunningThread() {
        Thread thread = runningThread;
        if (completed.get() == 0 && thread != null) {
            thread.interrupt();
        }
    }
}
