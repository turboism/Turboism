package dev.turboism.preview;

import dev.turboism.core.lifecycle.PluginLifecycleState;
import dev.turboism.core.runtime.ContextClassLoaderScope;

import java.util.ArrayList;
import java.util.List;

/**
 * Executes disable, shutdown, scope, classloader, and unload stages in order.
 *
 * <p>Each stage is attempted at most once per close generation: outcomes are recorded in the
 * caller-owned {@link CloseProgress}, so a retention re-drive resumes at the first stage that has
 * not completed instead of re-invoking plugin {@code shutdown()} or letting a failed scope close
 * report success on retry. Plugin teardown bodies wait for admitted SDK calls to drain: if the
 * generation guard still has in-flight calls the close defers with
 * {@code PLUGIN_SDK_DRAIN_FAILED} and is re-driven once they settle.</p>
 */
final class PreviewPluginShutdownStages {

    private final PreviewLog log;

    PreviewPluginShutdownStages(final PreviewLog log) {
        this.log = log;
    }

    PreviewPluginShutdownResult close(
        final LocalPluginRuntime.LoadedPlugin loadedPlugin,
        final String id,
        final boolean eventQuiesced,
        final CloseProgress progress
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
            return deferredResult(progress, failures);
        }
        if (!progress.backupQuiesced) {
            progress.backupQuiesced = quiesceBackup(loadedPlugin, failures, id);
            if (!progress.backupQuiesced) {
                log.warn(id, "Plugin lifecycle: close deferred until backup work quiesces");
                return deferredResult(progress, failures);
            }
        }
        // Admitted SDK work must drain before teardown bodies: disable()/shutdown() can destroy
        // plugin state and resources an in-flight pre-fence call still touches, so the gate sits
        // ahead of teardown, not only ahead of scope/classloader disposal.
        final PluginGenerationGuard guard = loadedPlugin.guard();
        if (guard != null && !guard.drained()) {
            failures.add(failure(
                "PLUGIN_SDK_DRAIN_FAILED", "sdk-drain",
                "Plugin SDK calls admitted before fencing did not drain before teardown."
            ));
            logFailure(id, "PLUGIN_SDK_DRAIN_FAILED");
            log.warn(id, "Plugin lifecycle: close deferred until admitted SDK calls drain");
            return deferredResult(progress, failures);
        }
        if (!progress.disableAttempted) {
            progress.disableAttempted = true;
            progress.disableState = disable(loadedPlugin, failures, id);
        }
        if (!progress.shutdownAttempted) {
            progress.shutdownAttempted = true;
            progress.shutdownState = shutdown(loadedPlugin, failures, id);
        }
        if (!progress.scopeAttempted) {
            progress.scopeAttempted = true;
            final ScopeResult scope = closeScope(loadedPlugin, failures, id);
            progress.scopeClosed = scope.closed();
            progress.scopeState = scope.state();
        }
        if (!progress.classloaderAttempted) {
            progress.classloaderAttempted = true;
            progress.classloaderState = closeClassLoader(
                loadedPlugin, progress.scopeClosed, eventQuiesced, failures, id
            );
        }
        if (!progress.unloadAttempted) {
            progress.unloadAttempted = true;
            progress.unloadState = unload(
                loadedPlugin, progress.scopeClosed, eventQuiesced,
                progress.classloaderState, id
            );
        }
        log.info(
            id,
            "Plugin lifecycle: close complete disable=" + progress.disableState
                + " shutdown=" + progress.shutdownState
                + " unload=" + progress.unloadState
        );
        return progress.result(failures);
    }

    private PreviewPluginShutdownResult deferredResult(
        final CloseProgress progress,
        final List<LocalPluginRuntime.PluginSummaryFailure> failures
    ) {
        final List<LocalPluginRuntime.PluginSummaryFailure> all = new ArrayList<>(failures);
        return new PreviewPluginShutdownResult(
            orNotStarted(progress.disableState),
            orNotStarted(progress.shutdownState),
            "FAILED",
            orNotStarted(progress.scopeState),
            orNotStarted(progress.classloaderState),
            all
        );
    }

    private static String orNotStarted(final String state) {
        return state == null ? "NOT_STARTED" : state;
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

    /**
     * At-most-once phase outcomes for one close generation. Shared between the initial close task
     * and any retention re-drive so completed phases are never re-run and failed disposal steps
     * keep their recorded outcome instead of being retried into a misleading success.
     */
    static final class CloseProgress {
        boolean backupQuiesced;
        boolean disableAttempted;
        String disableState;
        boolean shutdownAttempted;
        String shutdownState;
        boolean scopeAttempted;
        boolean scopeClosed;
        String scopeState;
        boolean classloaderAttempted;
        String classloaderState;
        boolean unloadAttempted;
        String unloadState;

        PreviewPluginShutdownResult result(
            final List<LocalPluginRuntime.PluginSummaryFailure> failures
        ) {
            return new PreviewPluginShutdownResult(
                orNotStarted(disableState),
                orNotStarted(shutdownState),
                orNotStarted(unloadState),
                orNotStarted(scopeState),
                orNotStarted(classloaderState),
                failures
            );
        }
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
