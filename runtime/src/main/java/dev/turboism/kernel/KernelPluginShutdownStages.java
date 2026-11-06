package dev.turboism.kernel;

import dev.turboism.core.lifecycle.PluginLifecycleState;
import dev.turboism.core.runtime.ContextClassLoaderScope;
import dev.turboism.core.runtime.work.FatalErrors;
import java.util.List;

/**
 * Executes one plugin's disable, shutdown, scope, classloader and unload stage bodies.
 *
 * <p>The order, the one-shot semantics and the deferral gates belong to
 * {@link AdmittedGeneration}: this type only runs the plugin-specific bodies and records their
 * plugin-visible outcome in the caller-owned {@link CloseReport}. Every stage is isolated per
 * entrypoint so one failure cannot skip the rest, and a failed step keeps its recorded outcome
 * instead of being retried into a misleading success — a retry of {@code DisposableScope.close()}
 * would be an empty success that erases the failure and frees the classloader early.</p>
 */
final class KernelPluginShutdownStages {

    private final KernelLog log;

    KernelPluginShutdownStages(final KernelLog log) {
        this.log = log;
    }

    /** @return {@code false} when backup work did not quiesce; the caller defers the pass */
    boolean quiesceBackup(final LocalPluginRuntime.LoadedPlugin loadedPlugin) {
        try {
            if (loadedPlugin.context() != null) {
                loadedPlugin.context().quiesceBackupOperations();
            }
            return true;
        } catch (Throwable exception) {
            FatalErrors.rethrowIfFatal(exception);
            return false;
        }
    }

    String disable(
            final LocalPluginRuntime.LoadedPlugin loadedPlugin,
            final List<LocalPluginRuntime.PluginSummaryFailure> failures) {
        final String id = loadedPlugin.runtime().id();
        if (loadedPlugin.runtime().state() != PluginLifecycleState.ENABLED) {
            log.debug(
                    id,
                    "Plugin lifecycle: disable not required state="
                            + loadedPlugin.runtime().state());
            return "NOT_REQUIRED";
        }
        log.info(id, "Plugin lifecycle: disable started");
        boolean failed = false;
        for (int index = loadedPlugin.entrypoints().size() - 1; index >= 0; index--) {
            try (ContextClassLoaderScope ignored = ContextClassLoaderScope.bind(loadedPlugin.classLoader())) {
                loadedPlugin.entrypoints().get(index).disable();
            } catch (Throwable exception) {
                FatalErrors.rethrowIfFatal(exception);
                failed = true;
                failures.add(failure("PLUGIN_DISABLE_FAILED", "disable", "Plugin entrypoint disable failed safely."));
                logFailure(id, "PLUGIN_DISABLE_FAILED");
            }
        }
        loadedPlugin
                .runtime()
                .transitionTo(failed ? PluginLifecycleState.DISABLE_FAILED : PluginLifecycleState.DISABLED);
        log.info(id, "Plugin lifecycle: disable " + (failed ? "failed" : "succeeded"));
        return failed ? "FAILED" : "SUCCEEDED";
    }

    String shutdown(
            final LocalPluginRuntime.LoadedPlugin loadedPlugin,
            final List<LocalPluginRuntime.PluginSummaryFailure> failures) {
        final String id = loadedPlugin.runtime().id();
        log.info(id, "Plugin lifecycle: shutdown started");
        boolean failed = false;
        for (int index = loadedPlugin.entrypoints().size() - 1; index >= 0; index--) {
            try (ContextClassLoaderScope ignored = ContextClassLoaderScope.bind(loadedPlugin.classLoader())) {
                loadedPlugin.entrypoints().get(index).shutdown();
            } catch (Throwable exception) {
                FatalErrors.rethrowIfFatal(exception);
                failed = true;
                failures.add(
                        failure("PLUGIN_SHUTDOWN_FAILED", "shutdown", "Plugin entrypoint shutdown failed safely."));
                logFailure(id, "PLUGIN_SHUTDOWN_FAILED");
            }
        }
        loadedPlugin
                .runtime()
                .transitionTo(failed ? PluginLifecycleState.SHUTDOWN_FAILED : PluginLifecycleState.SHUTDOWN);
        log.info(id, "Plugin lifecycle: shutdown " + (failed ? "failed" : "succeeded"));
        return failed ? "FAILED" : "SUCCEEDED";
    }

    ScopeResult closeScope(
            final LocalPluginRuntime.LoadedPlugin loadedPlugin,
            final List<LocalPluginRuntime.PluginSummaryFailure> failures) {
        final String id = loadedPlugin.runtime().id();
        try {
            loadedPlugin.scope().close();
            return new ScopeResult(true, "SUCCEEDED");
        } catch (Throwable exception) {
            FatalErrors.rethrowIfFatal(exception);
            loadedPlugin.runtime().transitionTo(PluginLifecycleState.SHUTDOWN_FAILED);
            failures.add(
                    failure("PLUGIN_SCOPE_CLEANUP_FAILED", "scope-cleanup", "Plugin scope cleanup failed safely."));
            logFailure(id, "PLUGIN_SCOPE_CLEANUP_FAILED");
            return new ScopeResult(false, "FAILED");
        }
    }

    String closeClassLoader(
            final LocalPluginRuntime.LoadedPlugin loadedPlugin,
            final boolean scopeClosed,
            final boolean eventQuiesced,
            final List<LocalPluginRuntime.PluginSummaryFailure> failures) {
        final String id = loadedPlugin.runtime().id();
        if (!scopeClosed || !eventQuiesced) {
            failures.add(failure(
                    "PLUGIN_CLASSLOADER_RETAINED",
                    "classloader-cleanup",
                    "Plugin classloader was retained because cleanup did not quiesce."));
            logFailure(id, "PLUGIN_CLASSLOADER_RETAINED");
            return "NOT_STARTED";
        }
        try {
            loadedPlugin.classLoader().close();
            return "SUCCEEDED";
        } catch (Throwable exception) {
            FatalErrors.rethrowIfFatal(exception);
            loadedPlugin.runtime().transitionTo(PluginLifecycleState.SHUTDOWN_FAILED);
            failures.add(failure(
                    "PLUGIN_CLASSLOADER_CLOSE_FAILED",
                    "classloader-cleanup",
                    "Plugin classloader cleanup failed safely."));
            logFailure(id, "PLUGIN_CLASSLOADER_CLOSE_FAILED");
            return "FAILED";
        }
    }

    String unload(
            final LocalPluginRuntime.LoadedPlugin loadedPlugin,
            final boolean scopeClosed,
            final boolean eventQuiesced,
            final String classloaderState) {
        final String id = loadedPlugin.runtime().id();
        if (loadedPlugin.runtime().state() == PluginLifecycleState.SHUTDOWN
                && scopeClosed
                && eventQuiesced
                && "SUCCEEDED".equals(classloaderState)) {
            loadedPlugin.runtime().transitionTo(PluginLifecycleState.UNLOADED);
            log.info(id, "Plugin lifecycle: unload succeeded");
            return "SUCCEEDED";
        }
        log.warn(
                id,
                "Plugin lifecycle: unload failed state="
                        + loadedPlugin.runtime().state()
                        + " scopeClosed=" + scopeClosed
                        + " eventQuiesced=" + eventQuiesced
                        + " classloader=" + classloaderState);
        return "FAILED";
    }

    private static LocalPluginRuntime.PluginSummaryFailure failure(
            final String code, final String phase, final String message) {
        return new LocalPluginRuntime.PluginSummaryFailure(code, phase, message);
    }

    private void logFailure(final String component, final String code) {
        log.error(component, "Plugin lifecycle stage failed safely: " + code, new IllegalStateException(code));
    }

    record ScopeResult(boolean closed, String state) {}

    /**
     * Plugin-visible verdict data of one close generation: the state each stage reached. Persisted
     * across retention re-drives — a stage that already ran is never re-run, so its state is the
     * final word — while the failure list is per pass and belongs to the caller.
     */
    static final class CloseReport {
        String disableState;
        String shutdownState;
        String scopeState;
        String classloaderState;
        String unloadState;

        PreviewPluginShutdownResult result(final List<LocalPluginRuntime.PluginSummaryFailure> failures) {
            return new PreviewPluginShutdownResult(
                    orNotStarted(disableState),
                    orNotStarted(shutdownState),
                    orNotStarted(unloadState),
                    orNotStarted(scopeState),
                    orNotStarted(classloaderState),
                    List.copyOf(failures));
        }

        /** A deferred pass reports the unload as failed and leaves the unattempted stages open. */
        PreviewPluginShutdownResult deferred(final List<LocalPluginRuntime.PluginSummaryFailure> failures) {
            return new PreviewPluginShutdownResult(
                    orNotStarted(disableState),
                    orNotStarted(shutdownState),
                    "FAILED",
                    orNotStarted(scopeState),
                    orNotStarted(classloaderState),
                    List.copyOf(failures));
        }

        private static String orNotStarted(final String state) {
            return state == null ? "NOT_STARTED" : state;
        }
    }
}

record PreviewPluginShutdownResult(
        String disableState,
        String shutdownState,
        String unloadState,
        String scopeCleanupState,
        String classloaderCleanupState,
        List<LocalPluginRuntime.PluginSummaryFailure> failures) {}
