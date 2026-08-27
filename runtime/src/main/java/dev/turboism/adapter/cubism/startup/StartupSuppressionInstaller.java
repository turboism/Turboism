package dev.turboism.adapter.cubism.startup;

import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import dev.turboism.mapping.verification.StartupSuppressionVerificationManifest;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Synchronous premain-only admission and lifecycle for bounded Cubism startup suppression. */
public final class StartupSuppressionInstaller {

    private StartupSuppressionInstaller() {
    }

    /**
     * Locates the single host artifact admitted from the startup class path.
     *
     * @param classPath process class path to inspect
     * @param workingDirectory directory used to resolve relative entries
     * @return the located host artifact, or empty when admission is ambiguous or unavailable
     */
    public static java.util.Optional<Path> locateHostArtifact(
        final String classPath,
        final Path workingDirectory
    ) {
        final StartupHostArtifactLocator.Result located =
            StartupHostArtifactLocator.locate(classPath, workingDirectory);
        return located.status() == StartupHostArtifactLocator.Status.FOUND
            ? java.util.Optional.of(located.artifact())
            : java.util.Optional.empty();
    }

    /**
     * Admits or refuses bounded Cubism startup suppression, and installs the class-file
     * transformer when every admission gate passes.
     *
     * <p>Runs synchronously during premain, before the target class is loaded. The gates,
     * in order: the loaded policy must actually request suppression and not be in safe
     * mode; the attachment must be premain, never agentmain; the host artifact must be
     * locatable, readable, and digest-match a reviewed profile; and the target class must
     * not already be loaded. Any gate that fails returns a completed installation with the
     * matching status and touches nothing.</p>
     *
     * <p>Fails closed throughout: an unreadable or unreviewed artifact, or a transformer
     * that could not be added, is reported as a status rather than thrown. Diagnostic
     * codes are pushed to the consumer, whose own exceptions are swallowed so reporting
     * can never disturb official startup.</p>
     *
     * @param mode             how the agent was attached; only {@code PREMAIN} is admitted
     * @param instrumentation  the JVM instrumentation used to add and later remove the transformer
     * @param turboismHome     Turboism home directory the startup policy is loaded from
     * @param classPath        class path searched for the Cubism host artifact; may be {@code null}
     * @param workingDirectory directory the artifact search is anchored at
     * @param diagnostic       sink for diagnostic codes; exceptions it throws are ignored
     * @return an installation carrying the outcome status and the loaded policy; when the
     *     status is {@code INSTALLED} it also owns the transformer and removes it on close
     * @throws NullPointerException when any argument other than {@code classPath} is {@code null}
     */
    public static Installation install(
        final AttachmentMode mode,
        final Instrumentation instrumentation,
        final Path turboismHome,
        final String classPath,
        final Path workingDirectory,
        final Consumer<String> diagnostic
    ) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(instrumentation, "instrumentation");
        Objects.requireNonNull(turboismHome, "turboismHome");
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(diagnostic, "diagnostic");

        final RuntimeStartupConfig policy = RuntimeStartupConfig.load(turboismHome, diagnostic);
        if (!requested(policy)) {
            return Installation.completed(Status.NOT_REQUESTED, policy);
        }
        if (mode == AttachmentMode.AGENTMAIN) {
            report(diagnostic, "STARTUP_SUPPRESSION_AGENTMAIN_REFUSED");
            return Installation.completed(Status.AGENTMAIN_REFUSED, policy);
        }

        final StartupHostArtifactLocator.Result located =
            StartupHostArtifactLocator.locate(classPath, workingDirectory);
        if (located.status() != StartupHostArtifactLocator.Status.FOUND) {
            report(diagnostic, "STARTUP_SUPPRESSION_ARTIFACT_" + located.status().name());
            return Installation.completed(Status.ARTIFACT_REJECTED, policy);
        }

        final HostArtifactDigest digest;
        final long digestStartedNanos = System.nanoTime();
        try {
            digest = HostArtifactDigest.from(located.artifact());
        } catch (IOException | RuntimeException failure) {
            report(diagnostic, "STARTUP_SUPPRESSION_ARTIFACT_UNREADABLE");
            return Installation.completed(Status.ARTIFACT_REJECTED, policy);
        } finally {
            report(
                diagnostic,
                "STARTUP_SUPPRESSION_ARTIFACT_HASH_MILLIS_"
                    + elapsedMillis(digestStartedNanos)
            );
        }
        final Optional<StartupSuppressionProfile> admitted =
            runtimeProfileForArtifact(digest);
        if (admitted.isEmpty()) {
            report(diagnostic, "STARTUP_SUPPRESSION_ARTIFACT_NOT_RUNTIME_ADMITTED");
            return Installation.completed(Status.ARTIFACT_REJECTED, policy);
        }
        final StartupSuppressionProfile profile = admitted.orElseThrow();
        if (targetAlreadyLoaded(instrumentation, profile.targetOwner())) {
            report(diagnostic, "STARTUP_SUPPRESSION_TARGET_ALREADY_LOADED");
            return Installation.completed(Status.TARGET_ALREADY_LOADED, policy);
        }

        final AtomicReference<StartupSuppressionClassFileTransformer> reference =
            new AtomicReference<>();
        final StartupSuppressionClassFileTransformer transformer =
            new StartupSuppressionClassFileTransformer(
                located.artifact(),
                profile,
                policy,
                ignored -> {
                    final StartupSuppressionClassFileTransformer installed = reference.get();
                    if (installed != null) {
                        instrumentation.removeTransformer(installed);
                    }
                },
                diagnostic
            );
        reference.set(transformer);
        try {
            instrumentation.addTransformer(transformer, false);
            report(diagnostic, "STARTUP_SUPPRESSION_INSTALLED_" + profile.cubismVersion());
            return Installation.installed(policy, instrumentation, transformer);
        } catch (RuntimeException failure) {
            instrumentation.removeTransformer(transformer);
            report(diagnostic, "STARTUP_SUPPRESSION_INSTALL_FAILED");
            return Installation.completed(Status.INSTALL_FAILED, policy);
        }
    }

    static Optional<StartupSuppressionProfile> runtimeProfileForArtifact(
        final HostArtifactDigest artifact
    ) {
        Objects.requireNonNull(artifact, "artifact");
        final Optional<String> version = ReviewedHostArtifacts.cubismVersionOf(artifact);
        if (version.isEmpty()) {
            return Optional.empty();
        }
        final String exactVersion = version.orElseThrow();
        if (!ReviewedHostArtifacts.admitsFullRuntime(exactVersion)
            && !("5.3.03".equals(exactVersion)
                && StartupSuppressionVerificationManifest.admits5303ValidationCandidate())) {
            return Optional.empty();
        }
        return StartupSuppressionProfile.forArtifact(artifact);
    }

    private static boolean requested(final RuntimeStartupConfig policy) {
        return !policy.safeMode()
            && (policy.skipStartupUpdateCheck()
                || policy.skipStartupSplash()
                || policy.skipStartupInformation());
    }

    private static boolean targetAlreadyLoaded(
        final Instrumentation instrumentation,
        final String targetOwner
    ) {
        final String binaryName = targetOwner.replace('/', '.');
        try {
            for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                if (loaded.getName().equals(binaryName)) {
                    return true;
                }
            }
        } catch (RuntimeException failure) {
            return true;
        }
        return false;
    }

    private static long elapsedMillis(final long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static void report(final Consumer<String> diagnostic, final String code) {
        try {
            diagnostic.accept(code);
        } catch (RuntimeException ignored) {
            // Diagnostics must not block official startup behavior.
        }
    }

    public enum AttachmentMode {
        PREMAIN,
        AGENTMAIN
    }

    public enum Status {
        NOT_REQUESTED,
        AGENTMAIN_REFUSED,
        ARTIFACT_REJECTED,
        TARGET_ALREADY_LOADED,
        INSTALLED,
        INSTALL_FAILED
    }

    public static final class Installation implements AutoCloseable {
        private final Status status;
        private final RuntimeStartupConfig policy;
        private final Instrumentation instrumentation;
        private final StartupSuppressionClassFileTransformer transformer;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private Installation(
            final Status status,
            final RuntimeStartupConfig policy,
            final Instrumentation instrumentation,
            final StartupSuppressionClassFileTransformer transformer
        ) {
            this.status = Objects.requireNonNull(status, "status");
            this.policy = Objects.requireNonNull(policy, "policy");
            this.instrumentation = instrumentation;
            this.transformer = transformer;
        }

        private static Installation completed(
            final Status status,
            final RuntimeStartupConfig policy
        ) {
            return new Installation(status, policy, null, null);
        }

        private static Installation installed(
            final RuntimeStartupConfig policy,
            final Instrumentation instrumentation,
            final StartupSuppressionClassFileTransformer transformer
        ) {
            return new Installation(Status.INSTALLED, policy, instrumentation, transformer);
        }

        /**
         * @return why suppression was or was not installed; only {@code INSTALLED} means the
         *     transformer is active
         */
        public Status status() {
            return status;
        }

        /**
         * @return the startup policy that was loaded from Turboism home, present whatever the
         *     admission outcome
         */
        public RuntimeStartupConfig policy() {
            return policy;
        }

        /**
         * @return the transformer own outcome name, or {@code "NOT_INSTALLED"} when no
         *     transformer was ever added because admission stopped earlier
         */
        public String transformOutcome() {
            return transformer == null ? "NOT_INSTALLED" : transformer.outcome().name();
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true) && transformer != null) {
                instrumentation.removeTransformer(transformer);
            }
        }
    }
}
