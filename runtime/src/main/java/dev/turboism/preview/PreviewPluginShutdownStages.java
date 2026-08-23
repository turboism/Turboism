package dev.turboism.preview;

import dev.turboism.core.lifecycle.PluginLifecycleState;
import dev.turboism.core.runtime.ContextClassLoaderScope;

import java.util.ArrayList;
import java.util.List;

/** Executes disable, shutdown, scope, classloader, and unload stages in order. */
final class PreviewPluginShutdownStages {

    private final PreviewLog log;

    PreviewPluginShutdownStages(final PreviewLog log) {
        this.log = log;
    }

    PreviewPluginShutdownResult close(
        final LocalPluginRuntime.LoadedPlugin loadedPlugin,
        final String id,
        final boolean eventQuiesced
    ) {
        log.info(id, "Plugin lifecycle: close started");
        final List<LocalPluginRuntime.PluginSummaryFailure> failures = new ArrayList<>();
        if (!eventQuiesced) {
            failures.add(failure(
                "PLUGIN_EVENT_QUIESCENCE_FAILED", "event-quiescence",
                "Plugin event callbacks did not quiesce before cleanup."
            ));
            logFailure(id, "PLUGIN_EVENT_QUIESCENCE_FAILED");
            log.warn(id, "Plugin lifecycle: close deferred until event callbacks quiesce");
            return new PreviewPluginShutdownResult(
                "NOT_STARTED", "NOT_STARTED", "FAILED",
                "NOT_STARTED", "NOT_STARTED", failures
            );
        }
        final boolean backupQuiesced = quiesceBackup(loadedPlugin, failures, id);
        if (!backupQuiesced) {
            log.warn(id, "Plugin lifecycle: close deferred until backup work quiesces");
            return new PreviewPluginShutdownResult(
                "NOT_STARTED", "NOT_STARTED", "FAILED",
                "NOT_STARTED", "NOT_STARTED", failures
            );
        }
        final String disableState = disable(loadedPlugin, failures, id);
        final String shutdownState = shutdown(loadedPlugin, failures, id);
        final ScopeResult scope = closeScope(loadedPlugin, failures, id);
        final String classloaderState = closeClassLoader(
            loadedPlugin, scope.closed(), eventQuiesced, failures, id
        );
        final String unloadState = unload(
            loadedPlugin, scope.closed(), eventQuiesced, classloaderState, id
        );
        log.info(
            id,
            "Plugin lifecycle: close complete disable=" + disableState
                + " shutdown=" + shutdownState
                + " unload=" + unloadState
        );
        return new PreviewPluginShutdownResult(
            disableState, shutdownState, unloadState,
            scope.state(), classloaderState, failures
        );
    }

    private boolean quiesceBackup(
        final LocalPluginRuntime.LoadedPlugin loadedPlugin,
        final List<LocalPluginRuntime.PluginSummaryFailure> failures,
        final String id
    ) {
        try {
            if (loadedPlugin.context() != null) {
                loadedPlugin.context().quiesceBackupOperations();
            }
            return true;
        } catch (Throwable exception) {
            failures.add(failure(
                "PLUGIN_BACKUP_QUIESCENCE_FAILED",
                "backup-quiescence",
                "Plugin backup work did not quiesce before lifecycle shutdown."
            ));
            logFailure(id, "PLUGIN_BACKUP_QUIESCENCE_FAILED");
            return false;
        }
    }

    private String disable(
        final LocalPluginRuntime.LoadedPlugin loadedPlugin,
        final List<LocalPluginRuntime.PluginSummaryFailure> failures,
        final String id
    ) {
        if (loadedPlugin.runtime().state() != PluginLifecycleState.ENABLED) {
            log.debug(id, "Plugin lifecycle: disable not required state=" + loadedPlugin.runtime().state());
            return "NOT_REQUIRED";
        }
        log.info(id, "Plugin lifecycle: disable started");
        boolean failed = false;
        for (int index = loadedPlugin.entrypoints().size() - 1; index >= 0; index--) {
            try (ContextClassLoaderScope ignored = ContextClassLoaderScope.bind(
                loadedPlugin.classLoader()
            )) {
                loadedPlugin.entrypoints().get(index).disable();
            } catch (Throwable exception) {
                failed = true;
                failures.add(failure(
                    "PLUGIN_DISABLE_FAILED",
                    "disable",
                    "Plugin entrypoint disable failed safely."
                ));
                logFailure(id, "PLUGIN_DISABLE_FAILED");
            }
        }
        loadedPlugin.runtime().transitionTo(
            failed ? PluginLifecycleState.DISABLE_FAILED : PluginLifecycleState.DISABLED
        );
        log.info(
            id,
            "Plugin lifecycle: disable " + (failed ? "failed" : "succeeded")
        );
        return failed ? "FAILED" : "SUCCEEDED";
    }

    private String shutdown(
        final LocalPluginRuntime.LoadedPlugin loadedPlugin,
        final List<LocalPluginRuntime.PluginSummaryFailure> failures,
        final String id
    ) {
        log.info(id, "Plugin lifecycle: shutdown started");
        boolean failed = false;
        for (int index = loadedPlugin.entrypoints().size() - 1; index >= 0; index--) {
            try (ContextClassLoaderScope ignored = ContextClassLoaderScope.bind(
                loadedPlugin.classLoader()
            )) {
                loadedPlugin.entrypoints().get(index).shutdown();
            } catch (Throwable exception) {
                failed = true;
                failures.add(failure(
                    "PLUGIN_SHUTDOWN_FAILED",
                    "shutdown",
                    "Plugin entrypoint shutdown failed safely."
                ));
                logFailure(id, "PLUGIN_SHUTDOWN_FAILED");
            }
        }
        loadedPlugin.runtime().transitionTo(
            failed ? PluginLifecycleState.SHUTDOWN_FAILED : PluginLifecycleState.SHUTDOWN
        );
        log.info(
            id,
            "Plugin lifecycle: shutdown " + (failed ? "failed" : "succeeded")
        );
        return failed ? "FAILED" : "SUCCEEDED";
    }

    private ScopeResult closeScope(
        final LocalPluginRuntime.LoadedPlugin loadedPlugin,
        final List<LocalPluginRuntime.PluginSummaryFailure> failures,
        final String id
    ) {
        try {
            loadedPlugin.scope().close();
            return new ScopeResult(true, "SUCCEEDED");
        } catch (Throwable exception) {
            loadedPlugin.runtime().transitionTo(PluginLifecycleState.SHUTDOWN_FAILED);
            failures.add(failure(
                "PLUGIN_SCOPE_CLEANUP_FAILED", "scope-cleanup", "Plugin scope cleanup failed safely."
            ));
            logFailure(id, "PLUGIN_SCOPE_CLEANUP_FAILED");
            return new ScopeResult(false, "FAILED");
        }
    }

    private String closeClassLoader(
        final LocalPluginRuntime.LoadedPlugin loadedPlugin,
        final boolean scopeClosed,
        final boolean eventQuiesced,
        final List<LocalPluginRuntime.PluginSummaryFailure> failures,
        final String id
    ) {
        if (!scopeClosed || !eventQuiesced) {
            failures.add(failure(
                "PLUGIN_CLASSLOADER_RETAINED", "classloader-cleanup",
                "Plugin classloader was retained because cleanup did not quiesce."
            ));
            logFailure(id, "PLUGIN_CLASSLOADER_RETAINED");
            return "NOT_STARTED";
        }
        try {
            loadedPlugin.classLoader().close();
            return "SUCCEEDED";
        } catch (Throwable exception) {
            loadedPlugin.runtime().transitionTo(PluginLifecycleState.SHUTDOWN_FAILED);
            failures.add(failure(
                "PLUGIN_CLASSLOADER_CLOSE_FAILED", "classloader-cleanup",
                "Plugin classloader cleanup failed safely."
            ));
            logFailure(id, "PLUGIN_CLASSLOADER_CLOSE_FAILED");
            return "FAILED";
        }
    }

    private String unload(
        final LocalPluginRuntime.LoadedPlugin loadedPlugin,
        final boolean scopeClosed,
        final boolean eventQuiesced,
        final String classloaderState,
        final String id
    ) {
        if (loadedPlugin.runtime().state() == PluginLifecycleState.SHUTDOWN
            && scopeClosed && eventQuiesced && "SUCCEEDED".equals(classloaderState)) {
            loadedPlugin.runtime().transitionTo(PluginLifecycleState.UNLOADED);
            log.info(id, "Plugin lifecycle: unload succeeded");
            return "SUCCEEDED";
        }
        log.warn(
            id,
            "Plugin lifecycle: unload failed state=" + loadedPlugin.runtime().state()
                + " scopeClosed=" + scopeClosed
                + " eventQuiesced=" + eventQuiesced
                + " classloader=" + classloaderState
        );
        return "FAILED";
    }

    private static LocalPluginRuntime.PluginSummaryFailure failure(
        final String code,
        final String phase,
        final String message
    ) {
        return new LocalPluginRuntime.PluginSummaryFailure(code, phase, message);
    }

    private void logFailure(final String component, final String code) {
        log.error(
            component,
            "Plugin lifecycle stage failed safely: " + code,
            new IllegalStateException(code)
        );
    }

    private record ScopeResult(boolean closed, String state) {
    }
}

record PreviewPluginShutdownResult(
    String disableState,
    String shutdownState,
    String unloadState,
    String scopeCleanupState,
    String classloaderCleanupState,
    List<LocalPluginRuntime.PluginSummaryFailure> failures
) {
}
