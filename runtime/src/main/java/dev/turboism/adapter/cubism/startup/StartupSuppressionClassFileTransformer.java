package dev.turboism.adapter.cubism.startup;

import dev.turboism.config.RuntimeStartupConfig;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

final class StartupSuppressionClassFileTransformer implements ClassFileTransformer {

    private final Path pinnedArtifact;
    private final StartupSuppressionProfile profile;
    private final StartupSuppressionTransformer transformer;
    private final Consumer<StartupSuppressionClassFileTransformer> cleanup;
    private final Consumer<String> diagnostic;
    private final AtomicBoolean targetAttempted = new AtomicBoolean(false);
    private final AtomicReference<Outcome> outcome = new AtomicReference<>(Outcome.PENDING);

    StartupSuppressionClassFileTransformer(
        final Path pinnedArtifact,
        final StartupSuppressionProfile profile,
        final RuntimeStartupConfig policy,
        final Consumer<StartupSuppressionClassFileTransformer> cleanup,
        final Consumer<String> diagnostic
    ) {
        this.pinnedArtifact = Objects.requireNonNull(pinnedArtifact, "pinnedArtifact")
            .toAbsolutePath()
            .normalize();
        this.profile = Objects.requireNonNull(profile, "profile");
        this.transformer = new StartupSuppressionTransformer(profile, policy);
        this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
        this.diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
    }

    @Override
    public byte[] transform(
        final Module module,
        final ClassLoader loader,
        final String className,
        final Class<?> classBeingRedefined,
        final ProtectionDomain protectionDomain,
        final byte[] classfileBuffer
    ) throws IllegalClassFormatException {
        if (!profile.targetOwner().equals(className) || targetAttempted.get()) {
            return null;
        }
        if (!targetAttempted.compareAndSet(false, true)) {
            return null;
        }
        try {
            if (classBeingRedefined != null) {
                outcome.set(Outcome.RETRANSFORM_REJECTED);
                return null;
            }
            if (loader == null) {
                outcome.set(Outcome.BOOTSTRAP_LOADER_REJECTED);
                return null;
            }
            if (!pinnedArtifact.equals(codeSourcePath(protectionDomain))) {
                outcome.set(Outcome.CODE_SOURCE_REJECTED);
                return null;
            }
            final byte[] transformed = transformer.transformClass(classfileBuffer);
            outcome.set(Outcome.TRANSFORMED);
            return transformed;
        } catch (StartupSuppressionTransformer.TransformationRejectedException failure) {
            outcome.set(Outcome.TRANSFORMATION_REJECTED);
            return null;
        } catch (RuntimeException failure) {
            outcome.set(Outcome.TRANSFORMATION_FAILED);
            return null;
        } finally {
            reportOutcome();
            try {
                cleanup.accept(this);
            } catch (RuntimeException ignored) {
                outcome.compareAndSet(Outcome.TRANSFORMED, Outcome.CLEANUP_FAILED);
                report("STARTUP_SUPPRESSION_TRANSFORM_CLEANUP_FAILED");
            }
    }
    }

    Outcome outcome() {
        return outcome.get();
    }

    private void reportOutcome() {
        report("STARTUP_SUPPRESSION_TRANSFORM_" + outcome.get().name());
    }

    private void report(final String code) {
        try {
            diagnostic.accept(code);
        } catch (RuntimeException ignored) {
            // Diagnostics must not affect the official host startup path.
        }
    }

    private static Path codeSourcePath(final ProtectionDomain protectionDomain) {
        if (protectionDomain == null
            || protectionDomain.getCodeSource() == null
            || protectionDomain.getCodeSource().getLocation() == null) {
            return null;
        }
        try {
            final URI location = protectionDomain.getCodeSource().getLocation().toURI();
            if (!"file".equalsIgnoreCase(location.getScheme())) {
                return null;
            }
            return Path.of(location).toAbsolutePath().normalize();
        } catch (URISyntaxException | RuntimeException failure) {
            return null;
        }
    }

    enum Outcome {
        PENDING,
        TRANSFORMED,
        RETRANSFORM_REJECTED,
        BOOTSTRAP_LOADER_REJECTED,
        CODE_SOURCE_REJECTED,
        TRANSFORMATION_REJECTED,
        TRANSFORMATION_FAILED,
        CLEANUP_FAILED
    }
}
