package dev.turboism.preview;

import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.shell.CoreShell;
import dev.turboism.shell.ShellManifest;
import dev.turboism.shell.ShellServices;

import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Direct runtime admission and teardown for the framework shell.
 *
 * <p>The shell is not a plugin: it has no jar, no classloader of its own, and no plugin
 * lifecycle state machine. The runtime still assembles the same {@code PluginContext}
 * service bundle for it (scoped disposables, event owner, localization, task and log
 * attribution under the reserved {@code turboism.core} identity), then constructs the
 * shell with its service bundle directly and closes everything through the scope.</p>
 */
final class CoreShellRuntime implements AutoCloseable {

    private final CoreShell shell;
    private final DisposableScope scope;
    private final PluginContextBundle bundle;
    private final URLClassLoader resources;
    private final PreviewPluginShutdown hookCleanup;
    private final PreviewLog log;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private CoreShellRuntime(
        final CoreShell shell,
        final DisposableScope scope,
        final PluginContextBundle bundle,
        final URLClassLoader resources,
        final PreviewPluginShutdown hookCleanup,
        final PreviewLog log
    ) {
        this.shell = shell;
        this.scope = scope;
        this.bundle = bundle;
        this.resources = resources;
        this.hookCleanup = hookCleanup;
        this.log = log;
    }

    static CoreShellRuntime start(
        final PreviewPluginContextFactory contexts,
        final PreviewPluginShutdown hookCleanup,
        final ShellServices services,
        final PreviewLog log
    ) throws Exception {
        final DisposableScope scope = new DisposableScope();
        final URLClassLoader resources = shellResourceLoader();
        PluginContextBundle bundle = null;
        CoreShell shell = null;
        try {
            bundle = contexts.create(
                ShellManifest.descriptor(), resources, scope
            );
            shell = new CoreShell(services);
            log.info(ShellManifest.ID, "Shell startup: begin");
            bundle.eventOwner().beginInitializing();
            bundle.eventOwner().registerAnnotated(List.of(), List.of(shell));
            shell.start(bundle.context());
            bundle.eventOwner().beginEnabling();
            bundle.eventOwner().activate();
            log.info(ShellManifest.ID, "Shell startup: ready");
            return new CoreShellRuntime(shell, scope, bundle, resources, hookCleanup, log);
        } catch (Throwable failure) {
            if (bundle != null) {
                bundle.eventOwner().beginClosing();
            }
            if (shell != null) {
                try {
                    shell.close();
                } catch (Throwable cleanup) {
                    log.error(ShellManifest.ID, "Shell startup rollback failed safely", cleanup);
                }
            }
            try {
                scope.close();
            } catch (Throwable cleanup) {
                log.error(ShellManifest.ID, "Shell scope cleanup failed safely", cleanup);
            }
            try {
                resources.close();
            } catch (Throwable cleanup) {
                log.error(ShellManifest.ID, "Shell resource loader cleanup failed safely", cleanup);
            }
            if (failure instanceof Exception exception) {
                throw exception;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Shell startup failed", failure);
        }
    }

    /**
     * Tears the shell down before external plugins close.
     *
     * <p>Mirrors the plugin close order without the plugin machinery: event delivery
     * stops first so no callbacks land in a half-closed shell, hook registries drop
     * anything attributed to the shell's owner key, pending backup work quiesces, the
     * shell's own state is released, and the disposable scope finishes every
     * registration — including the event-owner teardown callback installed by
     * {@link PreviewPluginContextFactory}. Every stage is isolated so one failure
     * cannot skip the rest.</p>
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        final String id = ShellManifest.ID;
        try {
            bundle.eventOwner().beginClosing();
            if (!bundle.eventOwner().awaitQuiescence(Duration.ofSeconds(5))) {
                logFailure(id, "SHELL_EVENT_QUIESCENCE_FAILED");
            }
        } catch (Throwable failure) {
            logFailure(id, "SHELL_EVENT_QUIESCENCE_FAILED");
        }
        try {
            hookCleanup.unregisterOwnedHooks(bundle.eventOwner().key());
        } catch (Throwable failure) {
            logFailure(id, "SHELL_HOOK_CLEANUP_FAILED");
        }
        try {
            bundle.context().quiesceBackupOperations();
        } catch (Throwable failure) {
            logFailure(id, "SHELL_BACKUP_QUIESCENCE_FAILED");
        }
        try {
            shell.close();
        } catch (Throwable failure) {
            logFailure(id, "SHELL_CLOSE_FAILED");
        }
        try {
            scope.close();
        } catch (Throwable failure) {
            logFailure(id, "SHELL_SCOPE_CLOSE_FAILED");
        }
        try {
            bundle.eventOwner().close();
        } catch (Throwable failure) {
            logFailure(id, "SHELL_EVENT_OWNER_CLOSE_FAILED");
        }
        try {
            resources.close();
        } catch (Throwable failure) {
            logFailure(id, "SHELL_RESOURCE_LOADER_CLOSE_FAILED");
        }
        log.info(id, "Shell closed");
    }

    private void logFailure(final String id, final String code) {
        log.error(id, "Shell close stage failed safely: " + code, new IllegalStateException(code));
    }

    /**
     * The context factory requires a {@link URLClassLoader}: plugin catalogs are read
     * plugin-locally from its URLs rather than through classpath delegation. The shell
     * lives inside the agent jar, so its resource loader is a thin loader over the
     * agent jar location (or the classes directory in exploded dev layouts).
     */
    private static URLClassLoader shellResourceLoader() {
        final ClassLoader parent = CoreShell.class.getClassLoader();
        return new URLClassLoader(new URL[]{shellSource(parent)}, parent);
    }

    /**
     * The agent jar that carries the shell. With {@code Boot-Class-Path} the shell
     * classes may be bootstrap-loaded with a null class loader, in which case the
     * protection domain may still expose a CodeSource; when it does not, recover the
     * jar URL from the shell i18n catalog anchor resource.
     */
    private static URL shellSource(final ClassLoader parent) {
        final java.security.CodeSource codeSource =
            CoreShell.class.getProtectionDomain().getCodeSource();
        if (codeSource != null && codeSource.getLocation() != null) {
            return codeSource.getLocation();
        }
        final URL anchor = parent != null
            ? parent.getResource(CATALOG_ANCHOR)
            : ClassLoader.getSystemResource(CATALOG_ANCHOR);
        if (anchor == null) {
            throw new IllegalStateException("shell resource anchor is missing");
        }
        try {
            return anchorSource(anchor);
        } catch (MalformedURLException | URISyntaxException failure) {
            throw new IllegalStateException("shell resource source is invalid", failure);
        }
    }

    /**
     * {@code jar:file:/.../turboism-agent.jar!/META-INF/...} resolves to the jar URL;
     * {@code file:/.../resources/META-INF/...} resolves to the exploded resource root.
     */
    static URL anchorSource(final URL anchor) throws MalformedURLException, URISyntaxException {
        final String spec = anchor.toExternalForm();
        if ("jar".equals(anchor.getProtocol())) {
            final int separator = spec.indexOf("!/");
            if (separator > 0) {
                return java.net.URI.create(spec.substring(4, separator)).toURL();
            }
            return anchor;
        }
        if ("file".equals(anchor.getProtocol()) && spec.endsWith(CATALOG_ANCHOR)) {
            return java.net.URI.create(spec.substring(0, spec.length() - CATALOG_ANCHOR.length()))
                .toURL();
        }
        return anchor;
    }

    private static final String CATALOG_ANCHOR = "META-INF/turboism/i18n/messages.properties";
}
