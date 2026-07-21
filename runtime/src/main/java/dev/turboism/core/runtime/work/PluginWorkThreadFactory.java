package dev.turboism.core.runtime.work;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

final class PluginWorkThreadFactory implements ThreadFactory {

    private final String pluginId;
    private final AtomicInteger threadNumber = new AtomicInteger(1);

    PluginWorkThreadFactory(String pluginId) {
        this.pluginId = pluginId;
    }

    @Override
    public Thread newThread(Runnable runnable) {
        Thread thread = new Thread(runnable, "turboism-plugin-work-" + pluginId + "-" + threadNumber.getAndIncrement());
        thread.setDaemon(true);
        return thread;
    }
}
