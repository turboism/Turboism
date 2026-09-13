package dev.turboism.bootstrap;

import dev.turboism.exportsettings.ProtectedExportChooserProfile;
import dev.turboism.exportsettings.ProtectedExportChooserRedirectTransformer;
import dev.turboism.mapping.verification.StaticSelector;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thin, reversible installer for the exact 5.3.02 protected-export chooser-redirect
 * transformer.
 *
 * <p>Owns only the bytecode seam on {@code com/live2d/cubism/doc/model/exporter/b}; the
 * bridge callback it reaches is published by {@link VerifiedExportSettingsHookInstaller},
 * which must already be installed. With no armed orchestration session the redirect
 * callback passes every pick through unchanged, so native export stays byte-identical.</p>
 */
final class VerifiedProtectedExportHookInstaller implements AutoCloseable {
    static final String SUPPORTED_CUBISM_VERSION =
        ProtectedExportChooserProfile.CUBISM_5_3_02.hostVersion();

    private final Instrumentation instrumentation;
    private final String targetClassName;
    private final ClassLoader hostClassLoader;
    private final ProtectedExportChooserRedirectTransformer transformer;
    private final AtomicBoolean installed = new AtomicBoolean();
    private boolean transformerRemoved;

    VerifiedProtectedExportHookInstaller(
        final Instrumentation instrumentation,
        final StaticSelector exporterOwner,
        final StaticSelector moc3Continuation,
        final StaticSelector moc3Chooser,
        final StaticSelector gatedContinuation,
        final StaticSelector gatedChooser,
        final ClassLoader hostClassLoader
    ) {
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
        this.targetClassName = requireExactExporterShape(
            exporterOwner, moc3Continuation, moc3Chooser, gatedContinuation, gatedChooser
        );
        this.hostClassLoader = Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        this.transformer = new ProtectedExportChooserRedirectTransformer(
            exporterOwner.ownerInternalName(),
            moc3Continuation.memberName(),
            moc3Continuation.descriptor(),
            moc3Chooser.memberName(),
            moc3Chooser.descriptor(),
            gatedContinuation.memberName(),
            gatedContinuation.descriptor(),
            gatedChooser.memberName(),
            gatedChooser.descriptor(),
            hostClassLoader
        );
    }

    /**
     * Builds the installer from a reviewed host profile.
     *
     * @return an uninstalled installer; callers must call {@link #install()}
     * @throws IllegalArgumentException if the profile is not the exact supported release
     */
    static VerifiedProtectedExportHookInstaller fromHostProfile(
        final Instrumentation instrumentation,
        final ProtectedExportChooserProfile profile,
        final ClassLoader hostClassLoader
    ) {
        final ProtectedExportChooserProfile requested =
            Objects.requireNonNull(profile, "profile");
        if (!SUPPORTED_CUBISM_VERSION.equals(requested.hostVersion())) {
            throw new IllegalArgumentException(
                "protected export chooser hook requires exact Cubism " + SUPPORTED_CUBISM_VERSION
            );
        }
        return new VerifiedProtectedExportHookInstaller(
            instrumentation,
            requested.exporterOwner(),
            requested.moc3Continuation(),
            requested.moc3Chooser(),
            requested.gatedContinuation(),
            requested.gatedChooser(),
            Objects.requireNonNull(hostClassLoader, "hostClassLoader")
        );
    }

    private static String requireExactExporterShape(
        final StaticSelector exporterOwner,
        final StaticSelector moc3Continuation,
        final StaticSelector moc3Chooser,
        final StaticSelector gatedContinuation,
        final StaticSelector gatedChooser
    ) {
        Objects.requireNonNull(exporterOwner, "exporterOwner");
        Objects.requireNonNull(moc3Continuation, "moc3Continuation");
        Objects.requireNonNull(moc3Chooser, "moc3Chooser");
        Objects.requireNonNull(gatedContinuation, "gatedContinuation");
        Objects.requireNonNull(gatedChooser, "gatedChooser");
        final String owner = exporterOwner.ownerInternalName();
        if (exporterOwner.kind() != StaticSelector.Kind.CLASS
            || !isPrivateInstance(moc3Continuation) || !ownedBy(moc3Continuation, owner)
            || !isPrivateInstance(moc3Chooser) || !ownedBy(moc3Chooser, owner)
            || !isPrivateInstance(gatedContinuation) || !ownedBy(gatedContinuation, owner)
            || !isPrivateInstance(gatedChooser) || !ownedBy(gatedChooser, owner)) {
            throw new IllegalArgumentException(
                "Verified protected-export selectors do not match the exact exporter shape."
            );
        }
        return owner.replace('/', '.');
    }

    private static boolean isPrivateInstance(final StaticSelector selector) {
        return selector.kind() == StaticSelector.Kind.METHOD
            && (selector.requiredAccessFlags() & 0x0002) != 0
            && (selector.forbiddenAccessFlags() & StaticSelector.ACCESS_STATIC) != 0;
    }

    private static boolean ownedBy(final StaticSelector selector, final String owner) {
        return selector.ownerInternalName().equals(owner);
    }

    /**
     * Registers the transformer and retransforms an already-loaded exporter class.
     *
     * @return {@code true} only when the exporter bytes carry the redirect seam afterwards
     */
    boolean install() {
        if (!installed.compareAndSet(false, true)) {
            return true;
        }
        if (!instrumentation.isRetransformClassesSupported()) {
            installed.set(false);
            return false;
        }
        try {
            instrumentation.addTransformer(transformer, true);
            for (Class<?> loaded : loadedTargets()) {
                instrumentation.retransformClasses(loaded);
            }
            return true;
        } catch (Throwable failure) {
            try {
                close();
            } catch (Throwable ignored) {
                // Preserve the install failure; cleanup failure is secondary.
            }
            installed.set(false);
            return false;
        }
    }

    @Override
    public synchronized void close() {
        if (!installed.get()) {
            return;
        }
        if (!transformerRemoved) {
            instrumentation.removeTransformer(transformer);
            transformerRemoved = true;
        }
        try {
            for (Class<?> loaded : loadedTargets()) {
                instrumentation.retransformClasses(loaded);
            }
        } catch (Throwable ignored) {
            // Restoration failure leaves transformed bytes; callers treat close as best-effort
            // and the armed-session gate refuses orchestration without a live seam anyway.
        }
        installed.set(false);
    }

    private List<Class<?>> loadedTargets() {
        final List<Class<?>> targets = new ArrayList<>();
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            if (loaded.getName().equals(targetClassName)
                && loaded.getClassLoader() == hostClassLoader
                && instrumentation.isModifiableClass(loaded)) {
                targets.add(loaded);
            }
        }
        return targets;
    }
}
