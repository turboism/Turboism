package dev.turboism.bootstrap;

import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.jar.JarFile;

/**
 * The one install protocol every reference-attested hook shares: admit, attest, bind, register,
 * retransform once, prove the weave, and prove the restoration on close.
 *
 * <p>The protocol lives here instead of inside each hook, where the copies had drifted apart:</p>
 *
 * <ol>
 *   <li>{@link #admits} gates the request, the JVM predicate and — unless the hook never consults it —
 *       the startup policy, then the hook's own reviewed-target expression; a refusal is a value, not
 *       an exception;</li>
 *   <li>every target class is loaded without initialization and attested against the artifact and
 *       loader, and its reference bytes come from the one {@link JarFile} this attempt opened;</li>
 *   <li>the hook's slot bridge is installed after that preparation and before any transformer is
 *       registered, all transformers are added in {@link WeavePlan} order — one transformer declared
 *       against several classes is registered once — and one {@code retransformClasses} call covers
 *       them all: a woven site calling an unpublished slot is the one ordering that can fault inside
 *       the host;</li>
 *   <li>every target's match count and recorded failure, then the plan's post-check, must all hold;
 *       otherwise every transformer this attempt registered is removed again and the failure is
 *       rethrown;</li>
 *   <li>{@link #close()} removes every transformer (never abandoning the rest when one removal fails,
 *       and treating a {@code false} removal verdict as the failure it is), withdraws the hook's side
 *       effects, restores the original JVM bytecode of every rewritten class through one
 *       {@code retransformClasses} call, and only then closes the bridge. {@link #restored()} is
 *       readable after {@code close()}; a registration that could not be removed keeps the handle open
 *       for a retry instead of reporting a settled weave.</li>
 * </ol>
 *
 * <p>Per-hook isolation stays where it was: the routine throws on failure and leaves no transformer
 * registered, so {@code TurboismAgent}'s per-contributor containment still decides whether a failing hook
 * can stop startup.</p>
 *
 * @param <T> the target/profile type the hook installs with
 */
final class VerifiedInstall<T> implements AutoCloseable {

    /** The refusal code a contributor passes to {@code refused(...)} when its spec does not admit the host. */
    static final String ADMISSION_REFUSED = "host-not-supported-or-hook-disabled";

    private final WeaveSpec<T> spec;
    private final InstallContext context;
    private final T contract;
    private final BridgeLayer bridge;
    private final List<WeaveTarget> registered = new ArrayList<>();
    private final Set<ClassFileTransformer> registrations = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean closed;
    private boolean restored;

    private VerifiedInstall(final WeaveSpec<T> spec, final InstallContext context, final T contract) throws Exception {
        this.spec = spec;
        this.context = context;
        this.contract = contract;
        this.bridge = spec.bridge().create(contract, context);
    }

    /**
     * The shared admission gate: the request, then the hook's JVM predicate, then its policy check.
     *
     * @param admission the hook's admission row
     * @param policy the launch startup policy, tolerated {@code null} for {@link PolicyCheck#SKIP}
     * @param requested whether the hook's own preference or switch requested it
     * @param jvmFeature {@code Runtime.version().feature()} of the running JVM
     * @return whether those three facts admit the hook
     */
    static boolean admits(
            final Admission admission,
            final RuntimeStartupConfig policy,
            final boolean requested,
            final int jvmFeature) {
        return requested
                && admission.jvmCheck().admits(jvmFeature)
                && admission.policyCheck().admits(policy, admission.hookId());
    }

    /**
     * The whole admission row: the shared gate, then the hook's reviewed-target expression. The target
     * expression is lazy so a refused hook never opens the artifact.
     *
     * @param admission the hook's admission row
     * @param policy the launch startup policy, tolerated {@code null} for {@link PolicyCheck#SKIP}
     * @param requested whether the hook's own preference or switch requested it
     * @param jvmFeature {@code Runtime.version().feature()} of the running JVM
     * @param target the hook's reviewed-target expression
     * @return whether the hook is admitted for this artifact
     */
    static boolean admits(
            final Admission admission,
            final RuntimeStartupConfig policy,
            final boolean requested,
            final int jvmFeature,
            final BooleanSupplier target) {
        return admits(admission, policy, requested, jvmFeature) && target.getAsBoolean();
    }

    /**
     * Admits, attests, binds, registers, retransforms and proves the weave. Refusal is a value, failure
     * is an exception — the split the contributors report ({@code NOT_ADMITTED} vs {@code FAILED}).
     *
     * @param spec everything the hook declares
     * @param context the launch facts for this attempt
     * @param <T> the target/profile type the hook installs with
     * @return the installed handle, or the refusal code for an unadmitted host
     * @throws Exception when the host is admitted but verification, registration, retransformation or a
     *     post-check fails. On throw every transformer this call registered has been removed and every
     *     rewritten class re-captured; a cleanup failure is suppressed onto the installation failure
     *     with fatal JVM conditions prioritized
     */
    static <T> Attempt<T> install(final WeaveSpec<T> spec, final InstallContext context) throws Exception {
        final Attempt<T> attempt = bind(spec, context);
        if (attempt instanceof Attempt.Installed<T> installed) {
            installed.hook().weave();
        }
        return attempt;
    }

    /**
     * Admits the host and binds the hook without weaving, so a hook that must prove its own
     * restoration after a failed weave still holds the handle that proves it.
     *
     * @param spec everything the hook declares
     * @param context the launch facts for this attempt
     * @param <T> the target/profile type the hook installs with
     * @return the bound handle, or the refusal code for an unadmitted host
     * @throws Exception when the artifact cannot be read or the hook's bridge cannot be built
     */
    static <T> Attempt<T> bind(final WeaveSpec<T> spec, final InstallContext context) throws Exception {
        if (!admits(spec.admission(), context.policy(), true, context.jvmFeature())) {
            return new Attempt.Refused<>(ADMISSION_REFUSED);
        }
        final Optional<T> bound = spec.resolver().resolve(context.artifact());
        if (bound.isEmpty()) {
            return new Attempt.Refused<>(ADMISSION_REFUSED);
        }
        return new Attempt.Installed<>(new VerifiedInstall<>(spec, context, bound.orElseThrow()));
    }

    /**
     * @return the contract bound for this artifact, for a hook that still needs it after installation
     */
    T contract() {
        return contract;
    }

    /**
     * @return the launch facts this handle was installed with
     */
    InstallContext context() {
        return context;
    }

    /**
     * @return the registered targets in registration order, for the hook's own post-check and report
     *     line
     */
    List<WeaveTarget> targets() {
        return List.copyOf(registered);
    }

    /**
     * @return whether every class this hook rewrote was restored to its original JVM definition;
     *     readable after {@link #close()}
     */
    boolean restored() {
        return restored;
    }

    @Override
    public void close() {
        final Throwable failure = release();
        if (failure != null) {
            throw FatalErrors.propagate(failure);
        }
    }

    /**
     * Runs the weave for a handle that was obtained from {@link #bind}: on failure every transformer this
     * attempt registered is removed again, and the handle stays open so a close can still prove the
     * restoration of the bytes that were rewritten before the failure.
     *
     * @throws Exception when registration, retransformation or the match/post-check proof fails
     */
    void weave() throws Exception {
        try {
            runProtocol();
        } catch (Exception | Error failure) {
            VerifiedHookInstallerSupport.closeOnInstallFailure(failure, this);
            throw failure;
        }
    }

    private void runProtocol() throws Exception {
        final Instrumentation instrumentation = context.instrumentation();
        if (!instrumentation.isRetransformClassesSupported()) {
            throw new IllegalStateException(spec.feature() + " host cannot be transformed");
        }
        // Preparation first: every target is built and attested from the artifact before the bridge
        // publishes a single slot, so a refused weave never leaves a callback behind. Registration then
        // happens after the bridge, because a woven site calling an unpublished slot is the one ordering
        // that can fault inside the host.
        try (JarFile jar = new JarFile(context.artifact().toFile())) {
            final WeaveSource source = new WeaveSource(spec.feature(), context, jar);
            for (final WeaveTarget target : spec.plan().targets().create(contract, source)) {
                if (target.type() != null && !instrumentation.isModifiableClass(target.type())) {
                    throw new IllegalStateException(spec.feature() + " target unmodifiable: "
                            + target.type().getName());
                }
                registered.add(target);
            }
        }
        bridge.install();
        for (final WeaveTarget target : registered) {
            if (registrations.add(target.transformer())) {
                instrumentation.addTransformer(target.transformer(), true);
            }
        }
        // A target may declare no class of its own: a guard transformer observes the classes it is
        // asked about, so it is registered without joining the single retransformation call.
        final Class<?>[] retransformed = registered.stream()
                .map(WeaveTarget::type)
                .filter(Objects::nonNull)
                .toArray(Class<?>[]::new);
        if (retransformed.length > 0) {
            instrumentation.retransformClasses(retransformed);
        }
        verifyWeave();
        spec.effects().confirmed(this);
    }

    private void verifyWeave() {
        for (final WeaveTarget target : registered) {
            final String failure = target.failure().get();
            if (failure != null) {
                throw new IllegalStateException(spec.feature() + " rewrite was not admitted: " + failure);
            }
            final int matches = target.matches().getAsInt();
            if (target.expectedMatches() >= 0 && matches != target.expectedMatches()) {
                throw new IllegalStateException(spec.feature() + " rewrite was not admitted: matched " + matches
                        + " site(s) in " + target.type().getName() + ", expected " + target.expectedMatches());
            }
        }
        final String unproven = spec.plan().postCheck().unsatisfied(registered);
        if (unproven != null) {
            throw new IllegalStateException(spec.feature() + " post-check failed: " + unproven);
        }
    }

    /**
     * Removes every registration, withdraws the hook's side effects, restores the original bytecode and
     * closes the bridge last.
     *
     * @return the merged failure, or {@code null} when the handle is settled
     */
    private synchronized Throwable release() {
        if (closed) {
            return null;
        }
        final Throwable removed = attempt(this::removeEveryTransformer);
        final Throwable withdrawn = attempt(() -> spec.effects().withdrawn(this));
        final Throwable restoredBytes = attempt(this::restoreOriginalDefinitions);
        final Throwable bridgeFailure = attempt(bridge::close);
        // A registration that could not be removed and a restoration that failed are the two
        // retryable steps: either one leaves the handle open so a second close can finish the job.
        closed = removed == null && restoredBytes == null;
        return FatalErrors.mergeForCleanup(
                FatalErrors.mergeForCleanup(removed, withdrawn),
                FatalErrors.mergeForCleanup(restoredBytes, bridgeFailure));
    }

    /**
     * Removes every registration in reverse spec order, never abandoning the rest when one removal
     * fails. A transformer declared against several classes is removed once.
     *
     * @throws RuntimeException the merged removal failure, thrown after every registration was attempted
     */
    private void removeEveryTransformer() {
        final List<WeaveTarget> reverse = new ArrayList<>(registered);
        Collections.reverse(reverse);
        Throwable failure = null;
        for (final WeaveTarget target : reverse) {
            final ClassFileTransformer transformer = target.transformer();
            if (!registrations.contains(transformer)) continue;
            final Throwable removal = attempt(() -> remove(transformer));
            if (removal == null) registrations.remove(transformer);
            failure = FatalErrors.mergeForCleanup(failure, removal);
        }
        if (failure != null) {
            throw FatalErrors.propagate(failure);
        }
    }

    /**
     * Removes one registration and treats a {@code false} verdict as a failure: an instrumentation that
     * reports the transformer as never registered has not proven that this hook's weave is gone.
     *
     * @param transformer the registration to remove
     */
    private void remove(final ClassFileTransformer transformer) {
        if (!context.instrumentation().removeTransformer(transformer)) {
            throw new IllegalStateException(spec.feature() + " transformer removal not proven: "
                    + transformer.getClass().getName());
        }
    }

    /**
     * Restores the original JVM definition of every class this hook rewrote. Removing the registration
     * already detaches the weave, so one {@code retransformClasses} call over the touched owners is the
     * whole proof: the JVM replays the chain on the pre-weave bytes and the host definition returns to
     * its official body.
     *
     * <p>A registration that survived removal still rewrites the class during that replay, so the
     * handle reports an unproven restoration and stays open for a retry.</p>
     */
    private void restoreOriginalDefinitions() throws Exception {
        final Set<Class<?>> owners = new LinkedHashSet<>();
        for (final WeaveTarget target : registered) {
            if (target.type() != null) {
                owners.add(target.type());
            }
        }
        if (!owners.isEmpty()) {
            context.instrumentation().retransformClasses(owners.toArray(Class<?>[]::new));
        }
        restored = registrations.isEmpty();
    }

    /**
     * Runs one cleanup step, keeping its failure for the merged report. The fatal rethrow is delayed until
     * every mandatory step has run: {@link FatalErrors#mergeForCleanup} then prioritises the fatal failure
     * over the ordinary ones, so a fatal condition never abandons a class that still has to be restored.
     *
     * @param step the step to run
     * @return the step's failure, or {@code null}
     */
    private static Throwable attempt(final Step step) {
        try {
            step.run();
            return null;
        } catch (Exception | Error failure) {
            // @containment-exempt: the close sequence runs every step and rethrows via FatalErrors.propagate of the
            // merged failure
            return failure;
        }
    }

    /** One cleanable step of the close sequence. */
    @FunctionalInterface
    private interface Step {
        void run() throws Exception;
    }

    /**
     * The outcome of {@link #install}: an installed handle, or the refusal code for an unadmitted host.
     * Refusal is a value so a contributor can report {@code NOT_ADMITTED} instead of {@code FAILED}.
     *
     * @param <T> the target/profile type the hook installs with
     */
    sealed interface Attempt<T> permits Attempt.Installed, Attempt.Refused {

        /**
         * @param hook the installed handle
         * @param <T> the target/profile type the hook installs with
         */
        record Installed<T>(VerifiedInstall<T> hook) implements Attempt<T> {}

        /**
         * @param reason the refusal code the contributor passes to {@code refused(...)}
         * @param <T> the target/profile type the hook installs with
         */
        record Refused<T>(String reason) implements Attempt<T> {}
    }
}
