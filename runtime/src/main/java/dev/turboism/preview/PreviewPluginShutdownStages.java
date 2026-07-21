package dev.turboism.preview;

import dev.turboism.core.lifecycle.PluginLifecycleState;

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
        final String id
    ) {
        final List<LocalPluginRuntime.PluginSummaryFailure> failures = new ArrayList<>();
        final String disableState = disable(loadedPlugin, failures, id);
        final String shutdownState = shutdown(loadedPlugin, failures, id);
        final ScopeResult scope = closeScope(loadedPlugin, failures, id);
        final String classloaderState = closeClassLoader(loadedPlugin, scope.closed(), failures, id);
        return new PreviewPluginShutdownResult(
            disableState, shutdownState, unload(loadedPlugin, scope.closed(), classloaderState),
            scope.state(), classloaderState, failures
        );
    }

    private String disable(
        final LocalPluginRuntime.LoadedPlugin loadedPlugin,
        final List<LocalPluginRuntime.PluginSummaryFailure> failures,
        final String id
    ) {
        if (loadedPlugin.runtime().state() != PluginLifecycleState.ENABLED) {
            return "NOT_REQUIRED";
        }
        boolean failed = false;
        for (int index = loadedPlugin.entrypoints().size() - 1; index >= 0; index--) {
            try {
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
        return failed ? "FAILED" : "SUCCEEDED";
    }

    private String shutdown(
        final LocalPluginRuntime.LoadedPlugin loadedPlugin,
        final List<LocalPluginRuntime.PluginSummaryFailure> failures,
        final String id
    ) {
        boolean failed = false;
        for (int index = loadedPlugin.entrypoints().size() - 1; index >= 0; index--) {
            try {
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
        final List<LocalPluginRuntime.PluginSummaryFailure> failures,
        final String id
    ) {
        if (!scopeClosed) {
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

    private static String unload(
        final LocalPluginRuntime.LoadedPlugin loadedPlugin,
        final boolean scopeClosed,
        final String classloaderState
    ) {
        if (loadedPlugin.runtime().state() == PluginLifecycleState.SHUTDOWN
            && scopeClosed && "SUCCEEDED".equals(classloaderState)) {
            loadedPlugin.runtime().transitionTo(PluginLifecycleState.UNLOADED);
            return "SUCCEEDED";
        }
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
            "Runtime shutdown stage failed safely: " + code,
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
