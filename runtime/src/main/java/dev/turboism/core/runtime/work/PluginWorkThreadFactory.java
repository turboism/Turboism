package dev.turboism.core.runtime.work;

import dev.turboism.runtime.exec.RuntimeThreads;
import java.util.concurrent.ThreadFactory;

/** Names plugin work threads after the owning plugin so a host thread dump stays attributable. */
final class PluginWorkThreadFactory implements ThreadFactory {

    private final ThreadFactory delegate;

    PluginWorkThreadFactory(String pluginId) {
        this.delegate = RuntimeThreads.daemonThreads("turboism-plugin-work-" + pluginId);
    }

    @Override
    public Thread newThread(Runnable runnable) {
        return delegate.newThread(runnable);
    }
}
