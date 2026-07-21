package dev.turboism.core.runtime.work;

import dev.turboism.core.runtime.PluginTask;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

final class PluginWorkItem implements Runnable {

    private final PluginTask task;
    private final Runnable work;
    private final AtomicInteger completed = new AtomicInteger(0);
    private volatile Thread runningThread;

    PluginWorkItem(PluginTask task, Runnable work) {
        this.task = Objects.requireNonNull(task, "task");
        this.work = Objects.requireNonNull(work, "work");
    }

    PluginTask task() {
        return task;
    }

    @Override
    public void run() {
        runningThread = Thread.currentThread();
        try {
            work.run();
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
