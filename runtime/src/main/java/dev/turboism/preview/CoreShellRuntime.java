package dev.turboism.preview;

import dev.turboism.internal.core.ShellAdmission;
import dev.turboism.internal.core.ShellHandle;
import dev.turboism.internal.core.ShellServices;
import dev.turboism.sdk.plugin.DisposableScope;

import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Direct runtime admission and teardown for the framework shell.
 *
 * <p>The shell is not a plugin: it has no jar, no classloader of its own, and no plugin
 * lifecycle state machine, so it never produces plugin lifecycle events. The runtime still
 * assembles the same {@code PluginContext} service bundle for it (scoped disposables, event
 * owner, localization, task and log attribution under the reserved {@code turboism.core}
 * identity), constructs the shell through the composition-supplied {@link ShellAdmission},
 * and closes everything through staged, idempotent cleanup.</p>
 *
 * <p>Admission runs on the bounded lifecycle lane under {@link PluginLifecyclePolicy#loadTimeout},
 * exactly like external plugin loads: a shell that exceeds its deadline is fenced — its event
 * owner stops delivering, its scope is sealed, its context's admitted SDK calls are fenced — and
 * a late worker completion cannot resurrect the timed-out generation. Generations whose cleanup
 * cannot be proven complete are retained in {@link RetainedPluginGenerations} and re-driven on
 * the lane until their resources are actually released.</p>
 */
final class CoreShellRuntime implements AutoCloseable {

    private final ShellHandle shell;
    private final DisposableScope scope;
    private final PluginContextBundle bundle;
    private final URLClassLoader resources;
    private final PreviewPluginShutdown hookCleanup;
    private final PluginLifecyclePolicy policy;
    private final PluginLifecycleLane lane;
    private final RetainedPluginGenerations retention;
    private final PreviewLog log;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ShellLoad state;

    private CoreShellRuntime(
        final ShellHandle shell,
        final DisposableScope scope,
        final PluginContextBundle bundle,
        final URLClassLoader resources,
        final PreviewPluginShutdown hookCleanup,
        final PluginLifecyclePolicy policy,
        final PluginLifecycleLane lane,
        final RetainedPluginGenerations retention,
        final PreviewLog log,
        final ShellLoad state
    ) {
        this.shell = shell;
        this.scope = scope;
        this.bundle = bundle;
        this.resources = resources;
        this.hookCleanup = hookCleanup;
        this.policy = policy;
        this.lane = lane;
        this.retention = retention;
        this.log = log;
        this.state = state;
    }

    /**
     * The production admission: constructs the built-in framework shell. Resolved lazily by the
     * default composition paths, so a runtime carrying a {@code null} admission — the supported
     * headless composition — never resolves the shell implementation class, which keeps a
     * runtime artifact physically stripped of the shell package linkable.
     */
    static ShellAdmission frameworkAdmission() {
        return dev.turboism.shell.CoreShell::new;
    }

    static CoreShellRuntime start(
        final PreviewPluginContextFactory contexts,
        final PreviewPluginShutdown hookCleanup,
        final ShellAdmission admission,
        final ShellServices services,
        final PluginLifecyclePolicy policy,
        final PluginLifecycleLane lane,
        final RetainedPluginGenerations retention,
        final PreviewLog log
    ) throws Exception {
        final ShellLoad state = new ShellLoad();
        final PluginLifecycleLease lease = new PluginLifecycleLease(ShellManifest.ID);
        final PluginLifecycleLane.Invocation<CoreShellRuntime> invocation =
            lane.submit(ShellManifest.ID, "load", () -> {
                try {
                    return startOnLane(
                        contexts, hookCleanup, admission, services,
                        policy, lane, retention, log, state, lease
                    );
                } catch (Throwable failure) {
                    state.cleanupComplete = cleanupShell(
                        state, hookCleanup, log, policy, true,
                        () -> retention.drainedExcept(ShellManifest.ID)
                    );
                    throw failure;
                }
            });
        final PluginLifecycleLane.AwaitResult<CoreShellRuntime> result =
            lane.await(invocation, policy.loadTimeout(), lease);
        switch (result.outcome) {
            case SUCCEEDED -> {
                if (result.value != null) {
                    return result.value;
                }
                // The lease rejected the worker's late commit: the generation is already
                // fenced, so retain whatever the worker built and report the deadline.
                fence(state, log);
                retainIfIncomplete(state, hookCleanup, log, policy, retention, invocation);
                throw new IllegalStateException(
                    "Shell startup exceeded " + policy.loadTimeout(),
                    new TimeoutException("shell lifecycle deadline expired")
                );
            }
            case FAILED -> {
                retainIfIncomplete(state, hookCleanup, log, policy, retention, invocation);
                throw propagate(result.failure);
            }
            case REJECTED -> {
                fence(state, log);
                throw new IllegalStateException(
                    "Shell admission rejected: lifecycle lane is saturated or closed"
                );
            }
            default -> {
                fence(state, log);
                retainIfIncomplete(state, hookCleanup, log, policy, retention, invocation);
                throw new IllegalStateException(
                    "Shell startup exceeded " + policy.loadTimeout(),
                    new TimeoutException("shell lifecycle deadline expired")
                );
            }
        }
    }

    /**
     * Retains the failed or fenced shell generation while its cleanup is incomplete — an event
     * owner that has not quiesced, an undrained guard, or a failed scope/resource disposal — so
     * the retention watcher re-drives {@link #cleanupShell} instead of dropping the references.
     * A retained entry whose sticky cleanup state can never converge is not re-run: the one-shot
     * flags inside {@link ShellLoad} make every later re-drive a no-op that keeps the
     * generation retained.
     */
    static void retainIfIncomplete(
        final ShellLoad state,
        final PreviewPluginShutdown hookCleanup,
        final PreviewLog log,
        final PluginLifecyclePolicy policy,
        final RetainedPluginGenerations retention,
        final PluginLifecycleLane.Invocation<?> invocation
    ) {
        if (state.cleanupComplete) {
            return;
        }
        retention.retain(new RetainedPluginGenerations.RetainedGeneration(
            ShellManifest.ID,
            invocation == null ? null : invocation.workerDone,
            state.context == null ? null : state.context.eventOwner(),
            state.guard,
            // Dormant once every destructive stage has been attempted; the drain barrier
            // excludes this id anyway, but a shell generation retained alongside external
            // plugins should still report its pending stages honestly.
            () -> state.shellCloseAttempted
                && state.scopeAttempted
                && state.eventOwnerCloseAttempted
                && state.resourcesAttempted,
            () -> cleanupShell(state, hookCleanup, log, policy, false,
                () -> retention.drainedExcept(ShellManifest.ID))
        ));
    }

    private static CoreShellRuntime startOnLane(
        final PreviewPluginContextFactory contexts,
        final PreviewPluginShutdown hookCleanup,
        final ShellAdmission admission,
        final ShellServices services,
        final PluginLifecyclePolicy policy,
        final PluginLifecycleLane lane,
        final RetainedPluginGenerations retention,
        final PreviewLog log,
        final ShellLoad state,
        final PluginLifecycleLease lease
    ) throws Exception {
        state.resources = shellResourceLoader();
        state.scope = new DisposableScope();
        state.context = contexts.create(
            ShellManifest.descriptor(), state.resources, state.scope
        );
        state.guard = new PluginGenerationGuard(ShellManifest.ID);
        log.info(ShellManifest.ID, "Shell startup: begin");
        state.context.eventOwner().beginInitializing();
        state.shell = admission.create(services);
        if (state.shell == null) {
            throw new IllegalStateException("Shell admission returned no shell");
        }
        state.context.eventOwner().registerAnnotated(List.of(), List.of(state.shell));
        lease.checkpoint();
        state.shell.start(state.guard.wrap(state.context.context()));
        state.context.eventOwner().beginEnabling();
        lease.checkpoint();
        return lease.commit(() -> {
            state.context.eventOwner().activate();
            log.info(ShellManifest.ID, "Shell startup: ready");
            return new CoreShellRuntime(
                state.shell, state.scope, state.context, state.resources, hookCleanup,
                policy, lane, retention, log, state
            );
        });
    }

    /**
     * Immediate non-blocking fence for a timed-out or rejected shell generation: the event
     * owner stops delivering, admitted SDK calls through the guarded context are fenced, and
     * the disposable scope is sealed.
     */
    private static void fence(final ShellLoad state, final PreviewLog log) {
        if (state.guard != null) {
            state.guard.fence();
        }
        if (state.context != null) {
            try {
                state.context.eventOwner().beginClosing();
            } catch (Throwable failure) {
                log.error(ShellManifest.ID, "Shell event fencing failed safely", failure);
            }
        }
        if (state.scope != null) {
            state.scope.seal();
        }
    }

    private static Exception propagate(final Throwable failure) {
        if (failure instanceof Exception exception) {
            return exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("Shell startup failed", failure);
    }

    /**
     * Tears the shell down after external plugins drain.
     *
     * <p>Mirrors the plugin close order without the plugin machinery: event delivery stops
     * first so no callbacks land in a half-closed shell, hook registries drop anything
     * attributed to the shell's owner key, pending backup work quiesces, admitted SDK calls
     * must have drained, the shell's own state is released, and the disposable scope finishes
     * every registration — including the event-owner teardown callback installed by
     * {@link PreviewPluginContextFactory}. Every stage is isolated so one failure cannot skip
     * the rest; a stage that cannot complete leaves the generation retained for a later
     * re-drive rather than silently released.</p>
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // Same admission shape as an external-plugin close: an immediate non-blocking
        // fence on the caller thread, then the staged teardown runs on the bounded lane
        // under the close deadline. A shell close body that ignores interruption can
        // stall the lane worker but never the caller — the timed-out generation is
        // retained until its worker exits and cleanup re-drives.
        fence(state, log);
        final PluginLifecycleLane.Invocation<Boolean> invocation = lane.submit(
            ShellManifest.ID, "close",
            () -> cleanupShell(
                state, hookCleanup, log, policy, true,
                () -> retention.drainedExcept(ShellManifest.ID)
            )
        );
        final PluginLifecycleLane.AwaitResult<Boolean> result =
            lane.await(invocation, policy.closeTimeout(), null);
        switch (result.outcome) {
            case SUCCEEDED -> {
                if (Boolean.TRUE.equals(result.value)) {
                    state.cleanupComplete = true;
                    log.info(ShellManifest.ID, "Shell closed");
                } else {
                    // The worker finished but some stage stayed incomplete; the retention
                    // log inside cleanupShell already explains which.
                    retainIfIncomplete(
                        state, hookCleanup, log, policy, retention, invocation
                    );
                }
            }
            case FAILED -> {
                log.error(
                    ShellManifest.ID,
                    "Shell close failed; generation retained",
                    result.failure
                );
                retainIfIncomplete(state, hookCleanup, log, policy, retention, invocation);
            }
            case TIMED_OUT -> {
                log.error(
                    ShellManifest.ID,
                    "Shell close exceeded " + policy.closeTimeout()
                        + "; generation retained until its worker exits",
                    new TimeoutException("shell close deadline expired")
                );
                retainIfIncomplete(state, hookCleanup, log, policy, retention, invocation);
            }
            case REJECTED -> {
                log.error(
                    ShellManifest.ID,
                    "Shell close admission rejected; generation retained",
                    new IllegalStateException("lifecycle lane is saturated or closed")
                );
                retainIfIncomplete(state, hookCleanup, log, policy, retention, invocation);
            }
        }
    }

    /**
     * Idempotent staged teardown for a failed, fenced or closing shell generation. Returns
     * {@code true} when every stage completed; {@code false} leaves the generation retained for
     * a later re-drive. Package-private so lifecycle tests can drive the ordering directly.
     *
     * @param policy deadline source; {@code null} waits are treated as unbounded on the first
     *     synchronous pass and {@link Duration#ZERO} on retention re-drives
     * @param awaitEvents whether event-owner quiescence may block up to the policy deadline
     * @param externalGenerationsDrained drain barrier over external plugin generations —
     *     the shell body, scope and event owner are not released while external plugin
     *     lifecycle work can still run, because late plugin teardown may still touch
     *     shell-contributed services
     */
    static boolean cleanupShell(
        final ShellLoad state,
        final PreviewPluginShutdown hookCleanup,
        final PreviewLog log,
        final PluginLifecyclePolicy policy,
        final boolean awaitEvents,
        final java.util.function.BooleanSupplier externalGenerationsDrained
    ) {
        // Every torn-down shell generation is fenced first, so its guarded context stops
        // admitting work before rollback runs.
        fence(state, log);
        if (!state.eventOwnerQuiesced) {
            if (state.context == null) {
                state.eventOwnerQuiesced = true;
            } else {
                final Duration bound = awaitEvents && policy != null
                    ? policy.eventQuiescenceTimeout()
                    : Duration.ZERO;
                final boolean quiesced;
                try {
                    quiesced = state.context.eventOwner().awaitQuiescence(bound);
                } catch (Throwable failure) {
                    logFailure(log, "SHELL_EVENT_QUIESCENCE_FAILED", failure);
                    return false;
                }
                if (!quiesced) {
                    return false;
                }
                state.eventOwnerQuiesced = true;
            }
        }
        // Each destructive stage is attempted at most once and records its own outcome:
        // a failure stays recorded, keeps the generation's references retained, and is
        // never reported as success — matching the plugin path's sticky close verdicts.
        // Stages after a failure still run, so one failed stage cannot skip the rest.
        if (!state.hooksAttempted) {
            state.hooksAttempted = true;
            if (state.context == null) {
                state.hooksUnregistered = true;
            } else {
                try {
                    hookCleanup.unregisterOwnedHooks(state.context.eventOwner().key());
                    state.hooksUnregistered = true;
                } catch (Throwable failure) {
                    logFailure(log, "SHELL_HOOK_CLEANUP_FAILED", failure);
                }
            }
        }
        if (!state.backupAttempted) {
            state.backupAttempted = true;
            if (state.context == null) {
                state.backupQuiesced = true;
            } else {
                try {
                    state.context.context().quiesceBackupOperations();
                    state.backupQuiesced = true;
                } catch (Throwable failure) {
                    logFailure(log, "SHELL_BACKUP_QUIESCENCE_FAILED", failure);
                }
            }
        }
        // Admitted SDK calls must drain before the shell body is released: close() can destroy
        // state an in-flight pre-fence call still touches — same ordering as the external
        // plugin shutdown path.
        if (state.guard != null && !state.guard.drained()) {
            return false;
        }
        // External plugin teardown drains before shell teardown: a plugin generation whose
        // close timed out is still running plugin code that can hold shell-contributed
        // services, so the shell body stays admitted-but-fenced until every external
        // generation is inert. A generation retained only for a permanently failed disposal
        // is inert and does not hold this gate open.
        if (!externalGenerationsDrained.getAsBoolean()) {
            return false;
        }
        if (!state.shellCloseAttempted) {
            state.shellCloseAttempted = true;
            if (state.shell == null) {
                state.shellClosed = true;
            } else {
                try {
                    state.shell.close();
                    state.shellClosed = true;
                } catch (Throwable failure) {
                    logFailure(log, "SHELL_CLOSE_FAILED", failure);
                }
            }
        }
        // One-shot disposal: DisposableScope.close() marks itself closed before running closers,
        // so a retry after a failure would be an empty success that erases the recorded failure
        // and releases the classloader early. Attempted and outcome are tracked separately; a
        // failed step keeps its outcome and the generation stays retained.
        if (!state.scopeAttempted) {
            state.scopeAttempted = true;
            state.scopeClosed = closeScope(state.scope, log);
        }
        if (!state.eventOwnerCloseAttempted) {
            state.eventOwnerCloseAttempted = true;
            if (state.context == null) {
                state.eventOwnerClosed = true;
            } else {
                try {
                    state.context.eventOwner().close();
                    state.eventOwnerClosed = true;
                } catch (Throwable failure) {
                    logFailure(log, "SHELL_EVENT_OWNER_CLOSE_FAILED", failure);
                }
            }
        }
        // The resource loader stays open while the scope is unproven: scope closers can
        // still resolve classes through it on a later re-drive.
        if (state.scopeClosed && !state.resourcesAttempted) {
            state.resourcesAttempted = true;
            state.resourcesClosed = closeResources(state.resources, log);
        }
        final boolean complete = state.eventOwnerQuiesced
            && state.hooksUnregistered
            && state.backupQuiesced
            && state.shellClosed
            && state.scopeClosed
            && state.eventOwnerClosed
            && state.resourcesClosed;
        if (!complete && !state.retentionLogged) {
            state.retentionLogged = true;
            log.error(
                ShellManifest.ID,
                "Shell generation retained because cleanup did not quiesce",
                new IllegalStateException("Shell cleanup is incomplete")
            );
        }
        return complete;
    }

    private static boolean closeScope(
        final DisposableScope scope,
        final PreviewLog log
    ) {
        if (scope == null) {
            return true;
        }
        try {
            scope.close();
            return true;
        } catch (Throwable failure) {
            logFailure(log, "SHELL_SCOPE_CLOSE_FAILED", failure);
            return false;
        }
    }

    private static boolean closeResources(
        final URLClassLoader resources,
        final PreviewLog log
    ) {
        if (resources == null) {
            return true;
        }
        try {
            resources.close();
            return true;
        } catch (Throwable failure) {
            logFailure(log, "SHELL_RESOURCE_LOADER_CLOSE_FAILED", failure);
            return false;
        }
    }

    private static void logFailure(
        final PreviewLog log,
        final String code,
        final Throwable failure
    ) {
        log.error(ShellManifest.ID, "Shell close stage failed safely: " + code, failure);
    }

    /**
     * The context factory requires a {@link URLClassLoader}: plugin catalogs are read
     * plugin-locally from its URLs rather than through classpath delegation. The loader's
     * roots therefore cover both layouts the shell can ship in: the i18n catalog anchor
     * locates the resource root authoritatively — the agent jar URL when packaged, the
     * {@code resources} output directory in Gradle's exploded layout — while the runtime's
     * {@code CodeSource} adds the classes directory that the anchor cannot see there.
     */
    private static URLClassLoader shellResourceLoader() {
        final ClassLoader parent = CoreShellRuntime.class.getClassLoader();
        final java.util.List<URL> roots = new java.util.ArrayList<>(2);
        final URL anchor = parent != null
            ? parent.getResource(CATALOG_ANCHOR)
            : ClassLoader.getSystemResource(CATALOG_ANCHOR);
        if (anchor != null) {
            try {
                roots.add(anchorSource(anchor));
            } catch (MalformedURLException | URISyntaxException failure) {
                throw new IllegalStateException("shell resource source is invalid", failure);
            }
        }
        final java.security.CodeSource codeSource =
            CoreShellRuntime.class.getProtectionDomain().getCodeSource();
        if (codeSource != null
            && codeSource.getLocation() != null
            && !roots.contains(codeSource.getLocation())) {
            roots.add(codeSource.getLocation());
        }
        if (roots.isEmpty()) {
            throw new IllegalStateException("shell resource anchor is missing");
        }
        return new URLClassLoader(roots.toArray(URL[]::new), parent);
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

    /**
     * Mutable per-generation shell state shared by the lane worker, the fencing caller and the
     * retained cleanup re-drive. Sticky one-shot flags make every stage idempotent.
     */
    static final class ShellLoad {
        volatile URLClassLoader resources;
        volatile DisposableScope scope;
        volatile PluginContextBundle context;
        volatile PluginGenerationGuard guard;
        volatile ShellHandle shell;
        volatile boolean cleanupComplete;
        volatile boolean eventOwnerQuiesced;
        volatile boolean hooksAttempted;
        volatile boolean hooksUnregistered;
        volatile boolean backupAttempted;
        volatile boolean backupQuiesced;
        volatile boolean shellCloseAttempted;
        volatile boolean shellClosed;
        volatile boolean scopeAttempted;
        volatile boolean scopeClosed;
        volatile boolean eventOwnerCloseAttempted;
        volatile boolean eventOwnerClosed;
        volatile boolean resourcesAttempted;
        volatile boolean resourcesClosed;
        volatile boolean retentionLogged;
    }
}
