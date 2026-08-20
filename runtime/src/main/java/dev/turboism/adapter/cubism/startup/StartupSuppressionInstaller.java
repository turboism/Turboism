package dev.turboism.adapter.cubism.startup;

import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.HostArtifactDigest;

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
            StartupSuppressionProfile.forArtifact(digest);
        if (admitted.isEmpty()) {
            report(diagnostic, "STARTUP_SUPPRESSION_ARTIFACT_UNREVIEWED");
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

        public Status status() {
            return status;
        }

        public RuntimeStartupConfig policy() {
            return policy;
        }

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
