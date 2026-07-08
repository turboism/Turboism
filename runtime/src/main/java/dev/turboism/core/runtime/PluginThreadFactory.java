package dev.turboism.core.runtime;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

final class PluginThreadFactory implements ThreadFactory {

    private final String pluginId;
    private final AtomicInteger threadNumber = new AtomicInteger(1);

    PluginThreadFactory(String pluginId) {
        this.pluginId = pluginId;
    }

    @Override
    public Thread newThread(Runnable runnable) {
        Thread thread = new Thread(runnable, "turboism-plugin-callback-" + pluginId + "-" + threadNumber.getAndIncrement());
        thread.setDaemon(true);
        return thread;
    }
}
