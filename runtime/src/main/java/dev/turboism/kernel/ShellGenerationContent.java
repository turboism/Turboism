package dev.turboism.kernel;

import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.internal.core.ShellAdmission;
import dev.turboism.internal.core.ShellHandle;
import dev.turboism.internal.core.ShellServices;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.ui.host.EdtDispatch;
import dev.turboism.ui.host.EdtDispatchException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * The shell kind's content for {@link AdmittedGeneration}: the {@link ShellHandle}, the resource
 * loader the context factory needs, and the staged teardown of one runtime-owned shell generation.
 *
 * <p>The shell is not a plugin: it has no JAR, no entrypoint rollback and no plugin lifecycle
 * event surface, so it never claims a plugin executor set and never publishes a verdict — the
 * reserved {@code turboism.core} identity is never reported as a plugin. Everything the shell still
 * shares with a plugin generation (the admission guard, the disposable scope, the event owner, the
 * deadline, the one-shot stage order and the retention of an unprovable pass) comes from
 * {@link AdmittedGeneration}.</p>
 *
 * <p>The host-facing admission specifics stay here: the EDT-readiness wait before lane entry, the
 * acceptance bound around shell construction, and the shell's own resource roots. The teardown
 * bodies keep the shell's per-stage failure codes and isolate every stage, so one failing stage
 * cannot skip the rest.</p>
 */
final class ShellGenerationContent implements AdmittedGeneration.Content<ShellGenerationContent> {

    /**
     * Per-probe acceptance bound while waiting for the host event queue before shell startup.
     * It stays deliberately below {@link EdtDispatch}'s circuit-breaker trip bound so a busy
     * startup EDT only delays the shell, never degrades unrelated dispatches.
     */
    static final Duration SHELL_EDT_PROBE_BOUND = Duration.ofSeconds(5);
    /** System property overriding the total EDT-readiness wait admitted before shell startup. */
    static final String SHELL_EDT_WAIT_PROPERTY = "turboism.preview.shellStartupEdtWaitSeconds";

    static final Duration DEFAULT_SHELL_EDT_WAIT = Duration.ofMinutes(3);

    private static final String CATALOG_ANCHOR = "META-INF/turboism/i18n/messages.properties";

    private final KernelPluginContextFactory contexts;
    private final KernelPluginShutdown hookCleanup;
    private final ShellAdmission admission;
    private final ShellServices services;
    private final PluginLifecyclePolicy policy;
    private final KernelLog log;
    private URLClassLoader resources;
    private PluginContextBundle context;
    private ShellHandle shell;

    ShellGenerationContent(
            final KernelPluginContextFactory contexts,
            final KernelPluginShutdown hookCleanup,
            final ShellAdmission admission,
            final ShellServices services,
            final PluginLifecyclePolicy policy,
            final KernelLog log) {
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.hookCleanup = Objects.requireNonNull(hookCleanup, "hookCleanup");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.services = Objects.requireNonNull(services, "services");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.log = Objects.requireNonNull(log, "log");
    }

    @Override
    public String id() {
        return ShellManifest.ID;
    }

    /** The shell owns no subscriber-derived plugin executor surface, so nothing is ever claimed. */
    @Override
    public boolean claimsExecutors() {
        return false;
    }

    /**
     * The admission guard exists before the worker starts so a timeout fence always has a gate to
     * close, even when the worker has not reached context creation yet.
     */
    @Override
    public void bind(final AdmittedGeneration.GenerationState shared) {
        shared.guard = new PluginGenerationGuard(ShellManifest.ID);
    }

    /**
     * Builds the shell on the lane: resource loader, context bundle, construction through the
     * composition-supplied admission, then start. The commit section activates the event owner and
     * publishes this content, so a late worker hits the lease fence here instead of activating a
     * generation the caller already reported timed out.
     */
    @Override
    public ShellGenerationContent start(
            final AdmittedGeneration.GenerationState shared, final PluginLifecycleLease lease) throws Exception {
        try (var ignored = EdtDispatch.defaultAcceptTimeoutScope(policy.shellLoadTimeout())) {
            final long startupStartedAt = System.nanoTime();
            resources = shellResourceLoader();
            shared.scope = new DisposableScope();
            context = contexts.create(
                    ShellManifest.descriptor(), resources, shared.scope, () -> !shared.guard.isFenced());
            shared.eventOwner = context.eventOwner();
            log.info(ShellManifest.ID, "Shell startup: begin");
            context.eventOwner().beginInitializing();
            shell = admission.create(services);
            if (shell == null) {
                throw new IllegalStateException("Shell admission returned no shell");
            }
            context.eventOwner().registerAnnotated(List.of(), List.of(shell));
            lease.checkpoint();
            shell.start(shared.guard.wrap(context.context()));
            context.eventOwner().beginEnabling();
            lease.checkpoint();
            return lease.commit(() -> {
                context.eventOwner().activate();
                log.info(
                        ShellManifest.ID,
                        "Shell startup: ready after "
                                + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startupStartedAt) + " ms");
                return this;
            });
        }
    }

    /** D1: claiming a plugin executor set would allocate one this generation never uses. */
    @Override
    public void claimExecutors(final AdmittedGeneration.GenerationState shared, final AdmittedGeneration.Cause cause) {}

    /**
     * Drops anything the shell's owner key registered in the shared hook registries. A failure is
     * logged, not thrown: the shell's remaining teardown stages still run, exactly as before.
     */
    @Override
    public void unregisterHooks(final AdmittedGeneration.GenerationState shared) {
        if (shared.eventOwner == null) {
            return;
        }
        try {
            hookCleanup.unregisterOwnedHooks(shared.eventOwner.key());
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            logStageFailure("SHELL_HOOK_CLEANUP_FAILED", failure);
        }
    }

    /** No shell state is destroyed while in-flight backup writes still touch it. */
    @Override
    public boolean quiesceBackup(final AdmittedGeneration.GenerationState shared) {
        if (context == null) {
            return true;
        }
        try {
            context.context().quiesceBackupOperations();
            return true;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            logStageFailure("SHELL_BACKUP_QUIESCENCE_FAILED", failure);
            return false;
        }
    }

    /**
     * The shell's own one-shot body: {@link ShellHandle#close()}. A thrown close is logged and the
     * generation still releases its scope and loader, matching the plugin kind whose body failures
     * are its own verdict data.
     */
    @Override
    public void closeBody(final AdmittedGeneration.GenerationState shared, final AdmittedGeneration.Cause cause) {
        if (shell == null) {
            return;
        }
        try {
            shell.close();
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            logStageFailure("SHELL_CLOSE_FAILED", failure);
        }
    }

    /** One-shot disposal of the shell's disposable scope. */
    @Override
    public AdmittedGeneration.StageOutcome closeScope(final AdmittedGeneration.GenerationState shared) {
        final DisposableScope scope = shared.scope;
        if (scope == null) {
            return AdmittedGeneration.StageOutcome.succeeded();
        }
        try {
            scope.close();
            return AdmittedGeneration.StageOutcome.succeeded();
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            logStageFailure("SHELL_SCOPE_CLOSE_FAILED", failure);
            return AdmittedGeneration.StageOutcome.failed();
        }
    }

    /**
     * The resource loader stays open while the scope is unproven: a scope closer may still resolve
     * classes through it. The stage reports itself attempted so a generation whose scope failed
     * permanently is inert for the drain barrier, and it is never retried into an early release.
     */
    @Override
    public AdmittedGeneration.StageOutcome closeResources(final AdmittedGeneration.GenerationState shared) {
        if (!shared.scopeClosed) {
            return AdmittedGeneration.StageOutcome.failed();
        }
        if (resources == null) {
            return AdmittedGeneration.StageOutcome.succeeded();
        }
        try {
            resources.close();
            return AdmittedGeneration.StageOutcome.succeeded();
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            logStageFailure("SHELL_RESOURCE_LOADER_CLOSE_FAILED", failure);
            return AdmittedGeneration.StageOutcome.failed();
        }
    }

    /** The shell publishes no unload verdict: it is not a plugin lifecycle participant. */
    @Override
    public void finishDisposal(final AdmittedGeneration.GenerationState shared) {}

    /** D1: the shell never claimed an executor set, so the terminal tail releases nothing. */
    @Override
    public void releaseExecutors(
            final AdmittedGeneration.GenerationState shared, final AdmittedGeneration.Cause cause) {}

    @Override
    public String incompleteMessage() {
        return "Shell generation retained because cleanup did not quiesce";
    }

    @Override
    public String retainedMessage() {
        return "Shell generation retained until lifecycle work quiesces";
    }

    /**
     * Bounded wait for the host event queue to accept work before the lifecycle lane starts.
     * Adapter admission can pass while the host is still pumping a startup task — a
     * command-line document load or first-run shader compilation can occupy the EDT for tens
     * of seconds. The lane deadline exists to bound the shell's own lifecycle work, not to
     * expire while every dispatch queues behind host startup, so contention is waited out
     * here and {@link PluginLifecyclePolicy#shellLoadTimeout} keeps measuring only the shell. When
     * the queue stays saturated past the wait budget the lane is still entered and the legacy
     * deadline behavior applies.
     */
    static void awaitHostEventQueue(final KernelLog log) {
        final Duration budget = shellEdtWaitBudget();
        if (budget.isZero()) {
            return;
        }
        final long deadline = System.nanoTime() + budget.toNanos();
        boolean announced = false;
        while (true) {
            try {
                EdtDispatch.call("shell-startup-edt-probe", SHELL_EDT_PROBE_BOUND, () -> null);
                if (announced) {
                    log.info(ShellManifest.ID, "Shell startup: host event queue accepted work");
                }
                return;
            } catch (EdtDispatchException rejected) {
                if (rejected.reason() == EdtDispatchException.Reason.INTERRUPTED) {
                    return;
                }
                if (!announced) {
                    announced = true;
                    log.info(ShellManifest.ID, "Shell startup: waiting for the host event queue");
                }
                if (System.nanoTime() >= deadline) {
                    log.warn(
                            ShellManifest.ID,
                            "Shell startup: host event queue still busy after " + budget + "; proceeding");
                    return;
                }
            }
        }
    }

    /** Resolves the EDT-readiness wait budget; a non-positive override disables the wait. */
    static Duration shellEdtWaitBudget() {
        final String override = System.getProperty(SHELL_EDT_WAIT_PROPERTY, "").trim();
        if (override.isEmpty()) {
            return DEFAULT_SHELL_EDT_WAIT;
        }
        try {
            final long seconds = Long.parseLong(override);
            return seconds <= 0 ? Duration.ZERO : Duration.ofSeconds(seconds);
        } catch (NumberFormatException invalid) {
            return DEFAULT_SHELL_EDT_WAIT;
        }
    }

    /**
     * The context factory requires a {@link URLClassLoader}: plugin catalogs are read
     * plugin-locally from its URLs rather than through classpath delegation. The loader's
     * roots therefore cover both layouts the shell can ship in: the i18n catalog anchor
     * locates the resource root authoritatively — the agent jar URL when packaged, the
     * {@code resources} output directory in Gradle's exploded layout — while the runtime's
     * {@code CodeSource} adds the classes directory that the anchor cannot see there.
     */
    static URLClassLoader shellResourceLoader() {
        final ClassLoader parent = ShellGenerationContent.class.getClassLoader();
        final List<URL> roots = new ArrayList<>(2);
        final URL anchor =
                parent != null ? parent.getResource(CATALOG_ANCHOR) : ClassLoader.getSystemResource(CATALOG_ANCHOR);
        if (anchor != null) {
            try {
                roots.add(anchorSource(anchor));
            } catch (MalformedURLException | URISyntaxException failure) {
                throw new IllegalStateException("shell resource source is invalid", failure);
            }
        }
        final java.security.CodeSource codeSource =
                ShellGenerationContent.class.getProtectionDomain().getCodeSource();
        if (codeSource != null && codeSource.getLocation() != null && !roots.contains(codeSource.getLocation())) {
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
            return java.net
                    .URI
                    .create(spec.substring(0, spec.length() - CATALOG_ANCHOR.length()))
                    .toURL();
        }
        return anchor;
    }

    private void logStageFailure(final String code, final Throwable failure) {
        log.error(ShellManifest.ID, "Shell close stage failed safely: " + code, failure);
    }
}
