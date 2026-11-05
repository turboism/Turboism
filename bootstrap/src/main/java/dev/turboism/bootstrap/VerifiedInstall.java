package dev.turboism.bootstrap;

import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 *   <li>the hook's slot bridge is installed before any transformer is registered, all transformers are
 *       added in {@link WeavePlan} order, and one {@code retransformClasses} call covers them all — a
 *       woven site calling an unpublished slot is the one ordering that can fault inside the host;</li>
 *   <li>every target's match count and recorded failure, then the plan's post-check, must all hold;
 *       otherwise every transformer this attempt registered is removed again and the failure is
 *       rethrown;</li>
 *   <li>{@link #close()} removes every transformer (never abandoning the rest when one removal fails),
 *       withdraws the hook's side effects, re-captures each rewritten class through one transient
 *       observer, compares it with the hash the transformer observed before the rewrite, and only then
 *       closes the bridge. {@link #restored()} is readable after {@code close()}.</li>
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
    private final List<WeaveTarget> registered = new ArrayList<>();
    private boolean closed;
    private boolean restored;

    private VerifiedInstall(final WeaveSpec<T> spec, final InstallContext context, final T contract) {
        this.spec = spec;
        this.context = context;
        this.contract = contract;
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
        if (!admits(spec.admission(), context.policy(), true, context.jvmFeature())) {
            return new Attempt.Refused<>(ADMISSION_REFUSED);
        }
        final Optional<T> bound = spec.resolver().resolve(context.artifact());
        if (bound.isEmpty()) {
            return new Attempt.Refused<>(ADMISSION_REFUSED);
        }
        final VerifiedInstall<T> hook = new VerifiedInstall<>(spec, context, bound.orElseThrow());
        try {
            hook.weave();
        } catch (Exception | Error failure) {
            VerifiedHookInstallerSupport.closeOnInstallFailure(failure, hook);
            throw failure;
        }
        return new Attempt.Installed<>(hook);
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
     * @return whether every class this hook rewrote was proven to hash back to its pre-rewrite bytes;
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

    private void weave() throws Exception {
        final Instrumentation instrumentation = context.instrumentation();
        if (!instrumentation.isRetransformClassesSupported()) {
            throw new IllegalStateException(spec.feature() + " host cannot be transformed");
        }
        spec.bridge().install();
        try (JarFile jar = new JarFile(context.artifact().toFile())) {
            final WeaveSource source = new WeaveSource(spec.feature(), context, jar);
            for (final WeaveTarget target : spec.plan().targets().create(contract, source)) {
                if (!instrumentation.isModifiableClass(target.type())) {
                    throw new IllegalStateException(spec.feature() + " target unmodifiable: "
                            + target.type().getName());
                }
                registered.add(target);
                instrumentation.addTransformer(target.transformer(), true);
            }
        }
        instrumentation.retransformClasses(
                registered.stream().map(WeaveTarget::type).toArray(Class<?>[]::new));
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
     * Removes every registration, withdraws the hook's side effects, proves the restoration closure and
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
        final Throwable proof = attempt(this::verifyRestoration);
        final Throwable bridge = attempt(spec.bridge()::close);
        // The restoration proof is the only retryable step: a failed proof leaves the handle open so a
        // second close can re-capture and settle.
        closed = proof == null;
        return FatalErrors.mergeForCleanup(
                FatalErrors.mergeForCleanup(removed, withdrawn), FatalErrors.mergeForCleanup(proof, bridge));
    }

    /** Removes every registration in reverse spec order, never abandoning the rest when one removal fails. */
    private Throwable removeEveryTransformer() {
        final List<WeaveTarget> reverse = new ArrayList<>(registered);
        Collections.reverse(reverse);
        Throwable failure = null;
        for (final WeaveTarget target : reverse) {
            failure = FatalErrors.mergeForCleanup(
                    failure, attempt(() -> context.instrumentation().removeTransformer(target.transformer())));
        }
        return failure;
    }

    private void verifyRestoration() throws Exception {
        if (registered.isEmpty()) return;
        final Map<String, String> observed = captureOwners();
        for (final WeaveTarget target : registered) {
            for (final String owner : target.touchedOwners()) {
                final String before = target.beforeSha256().apply(owner);
                if (before == null || !before.equals(observed.get(owner))) {
                    throw new IllegalStateException(spec.feature() + " restoration not proven: " + owner);
                }
            }
        }
        restored = true;
    }

    /**
     * Re-captures every rewritten class through one transient observer. Every class is retransformed even
     * when one of them fails, so a single unmodifiable class never leaves the others unproven.
     *
     * @return the observed digest per internal name
     */
    private Map<String, String> captureOwners() throws Exception {
        final Map<String, Class<?>> owners = new LinkedHashMap<>();
        for (final WeaveTarget target : registered) {
            for (final String owner : target.touchedOwners()) {
                owners.put(owner, Class.forName(owner.replace('/', '.'), false, context.hostLoader()));
            }
        }
        final Map<String, byte[]> observed = new LinkedHashMap<>();
        final ClassFileTransformer observer = new ClassFileTransformer() {
            @Override
            public byte[] transform(
                    final Module module,
                    final ClassLoader loader,
                    final String name,
                    final Class<?> redefined,
                    final ProtectionDomain domain,
                    final byte[] bytes) {
                if (redefined != null && bytes != null) {
                    observed.put(WeaveTarget.internalName(redefined), bytes.clone());
                }
                return null;
            }
        };
        context.instrumentation().addTransformer(observer, true);
        Throwable failure = null;
        try {
            for (final Class<?> owner : owners.values()) {
                failure = FatalErrors.mergeForCleanup(
                        failure, attempt(() -> context.instrumentation().retransformClasses(owner)));
            }
        } finally {
            final Throwable removal = failure;
            failure = FatalErrors.mergeForCleanup(
                    removal, attempt(() -> context.instrumentation().removeTransformer(observer)));
        }
        if (failure != null) {
            throw FatalErrors.propagate(failure);
        }
        final Map<String, String> digests = new LinkedHashMap<>();
        for (final String owner : owners.keySet()) {
            final byte[] bytes = observed.get(owner);
            if (bytes == null) {
                throw new IllegalStateException(spec.feature() + " restoration capture absent: " + owner);
            }
            digests.put(owner, VerifiedHookInstallerSupport.sha256Hex(bytes));
        }
        return digests;
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
