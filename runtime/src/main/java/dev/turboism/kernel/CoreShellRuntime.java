package dev.turboism.kernel;

import dev.turboism.internal.core.ShellAdmission;
import dev.turboism.internal.core.ShellServices;
import java.net.URLClassLoader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Direct runtime admission and teardown for the framework shell.
 *
 * <p>The shell is not a plugin: it has no jar, no classloader of its own, and no plugin
 * lifecycle verdicts, so it never produces plugin lifecycle events. The runtime still
 * assembles the same {@code PluginContext} service bundle for it (scoped disposables, event
 * owner, localization, task and log attribution under the reserved {@code turboism.core}
 * identity), constructs the shell through the composition-supplied {@link ShellAdmission},
 * and closes everything through the one ordered teardown {@link AdmittedGeneration} owns.</p>
 *
 * <p>Admission and teardown are the module's, with {@link ShellGenerationContent} supplying the
 * shell's content — resource loader, admission through the composition factory, and the shell's
 * own close body. Admission runs on the bounded lifecycle lane under {@link
 * PluginLifecyclePolicy#shellLoadTimeout}, exactly like external plugin loads: a shell that exceeds
 * its deadline is fenced — its event owner stops delivering, its scope is sealed, its context's
 * admitted SDK calls are fenced — and a late worker completion cannot resurrect the timed-out
 * generation. Generations whose cleanup cannot be proven complete are retained in
 * {@link RetainedPluginGenerations} and re-driven on the lane until their resources are actually
 * released.</p>
 */
final class CoreShellRuntime implements AutoCloseable {

    private final AdmittedGeneration.Handle admitted;
    private final PluginLifecyclePolicy policy;
    private final RetainedPluginGenerations retention;
    private final KernelLog log;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private CoreShellRuntime(
            final AdmittedGeneration.Handle admitted,
            final PluginLifecyclePolicy policy,
            final RetainedPluginGenerations retention,
            final KernelLog log) {
        this.admitted = admitted;
        this.policy = policy;
        this.retention = retention;
        this.log = log;
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

    /**
     * Admits the runtime-owned shell through {@link AdmittedGeneration} and returns its handle.
     *
     * <p>The EDT-readiness wait happens on the caller thread before lane entry, so host startup
     * contention never consumes the shell's own deadline. Every failure shape of the module maps to
     * the exception this entry point has always thrown; fencing and retention of an incomplete
     * generation are the module's.
     */
    static CoreShellRuntime start(
            final KernelPluginContextFactory contexts,
            final KernelPluginShutdown hookCleanup,
            final ShellAdmission admission,
            final ShellServices services,
            final PluginLifecyclePolicy policy,
            final PluginLifecycleLane lane,
            final RetainedPluginGenerations retention,
            final KernelLog log)
            throws Exception {
        final ShellGenerationContent content =
                new ShellGenerationContent(contexts, hookCleanup, admission, services, policy, log);
        final AdmittedGeneration.Admission generationAdmission =
                new AdmittedGeneration.Admission(policy, lane, retention, log);
        ShellGenerationContent.awaitHostEventQueue(log);
        final AdmittedGeneration.Submission<ShellGenerationContent> submission =
                AdmittedGeneration.submit(content, generationAdmission, policy.shellLoadTimeout());
        final AdmittedGeneration.Outcome<ShellGenerationContent> outcome = submission.await();
        final AdmittedGeneration.Handle admitted = submission.handle();
        return switch (outcome.kind()) {
            case SUCCEEDED -> {
                if (outcome.value() == null) {
                    // The lease rejected the worker's late commit: the generation is already
                    // fenced, so retain whatever the worker built and report the deadline.
                    retainFenced(admitted, submission.workerDone());
                    throw startupDeadline(policy);
                }
                yield new CoreShellRuntime(admitted, policy, retention, log);
            }
            case FAILED -> throw propagate(outcome.failure());
            case REJECTED ->
                throw new IllegalStateException("Shell admission rejected: lifecycle lane is saturated or closed");
            case TIMED_OUT -> throw startupDeadline(policy);
        };
    }

    /**
     * Retains a fenced shell generation whose admission was lost to the deadline, so the retention
     * watcher re-drives the ordered teardown instead of dropping the references.
     */
    private static void retainFenced(
            final AdmittedGeneration.Handle admitted, final CompletableFuture<Void> workerDone) {
        admitted.fence(AdmittedGeneration.Cause.LOAD_FAILED);
        admitted.retain(workerDone, AdmittedGeneration.Cause.LOAD_FAILED, () -> true, null);
    }

    private static IllegalStateException startupDeadline(final PluginLifecyclePolicy policy) {
        return new IllegalStateException(
                "Shell startup exceeded " + policy.shellLoadTimeout(),
                new TimeoutException("shell lifecycle deadline expired"));
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
     * Tears the shell down after external plugins drain, through the module's single ordered
     * teardown: event delivery stops first so no callbacks land in a half-closed shell, hook
     * registries drop anything attributed to the shell's owner key, pending backup work quiesces,
     * admitted SDK calls must have drained, the shell's own state is released, and the disposable
     * scope finishes every registration — including the event-owner teardown callback installed by
     * {@link KernelPluginContextFactory}. A pass that cannot complete leaves the generation
     * retained for a re-drive rather than silently released.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // Same admission shape as an external-plugin close: an immediate non-blocking fence on the
        // caller thread, then the staged teardown runs on the bounded lane under the close
        // deadline. A shell close body that ignores interruption can stall the lane worker but never
        // the caller — the timed-out generation is retained until its worker exits and cleanup
        // re-drives.
        admitted.fence(AdmittedGeneration.Cause.CLOSE);
        // External plugin teardown drains before shell teardown: a plugin generation whose close
        // timed out is still running plugin code that can hold shell-contributed services, so the
        // shell body stays admitted-but-fenced until every external generation is inert.
        final AdmittedGeneration.CloseAttempt attempt = admitted.close(() -> retention.drainedExcept(ShellManifest.ID));
        switch (attempt.outcome()) {
            case SUCCEEDED -> {
                final AdmittedGeneration.TeardownReport report = attempt.report();
                if (report != null && report.complete()) {
                    log.info(ShellManifest.ID, "Shell closed");
                } else {
                    // The worker finished but some stage stayed incomplete; the module's
                    // once-per-generation retention log already explains which.
                    retainClose(attempt.workerDone());
                }
            }
            case FAILED -> {
                log.error(ShellManifest.ID, "Shell close failed; generation retained", attempt.failure());
                retainClose(attempt.workerDone());
            }
            case TIMED_OUT -> {
                log.error(
                        ShellManifest.ID,
                        "Shell close exceeded " + policy.closeTimeout()
                                + "; generation retained until its worker exits",
                        new TimeoutException("shell close deadline expired"));
                retainClose(attempt.workerDone());
            }
            case REJECTED -> {
                log.error(
                        ShellManifest.ID,
                        "Shell close admission rejected; generation retained",
                        new IllegalStateException("lifecycle lane is saturated or closed"));
                retainClose(attempt.workerDone());
            }
        }
    }

    /** Retains a close generation whose ordered teardown did not finish inside its deadline. */
    private void retainClose(final CompletableFuture<Void> workerDone) {
        admitted.retain(
                workerDone, AdmittedGeneration.Cause.CLOSE, () -> retention.drainedExcept(ShellManifest.ID), null);
    }

    /**
     * Package-private delegate to the shell adapter's resource loader, which the catalog test
     * reflects by name to assert the shell's declared i18n catalogs.
     */
    static URLClassLoader shellResourceLoader() {
        return ShellGenerationContent.shellResourceLoader();
    }
}
