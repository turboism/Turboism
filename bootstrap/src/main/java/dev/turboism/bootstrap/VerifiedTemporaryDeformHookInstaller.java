package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.startup.StartupSuppressionInstaller.AttachmentMode;
import dev.turboism.adapter.cubism.temporarydeform.NativeTemporaryDeformBridge;
import dev.turboism.adapter.cubism.temporarydeform.TemporaryDeformCoordinator;
import dev.turboism.adapter.cubism.temporarydeform.TemporaryDeformHostProfile;
import dev.turboism.adapter.cubism.temporarydeform.TemporaryDeformNativeTransformer;
import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.runtime.log.RuntimeDiagnostics;
import dev.turboism.sdk.plugin.Registration;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Installs and retracts the loader-neutral temporary deform bridge and bytecode transformer for one
 * exact reviewed Cubism host session.
 */
final class VerifiedTemporaryDeformHookInstaller implements AutoCloseable {

    private static final String LOG_COMPONENT = "temporary-deform";

    private final Instrumentation instrumentation;
    private final AttachmentMode attachmentMode;
    private final ClassLoader hostClassLoader;
    private final TemporaryDeformCoordinator coordinator;
    private final TemporaryDeformNativeTransformer transformer;
    private final LoadedClassIndex loadedClasses;

    private Registration bridgeRegistration;
    private boolean transformerAdded;
    private boolean installed;

    VerifiedTemporaryDeformHookInstaller(
            final Instrumentation instrumentation,
            final AttachmentMode attachmentMode,
            final ClassLoader hostClassLoader,
            final TemporaryDeformHostProfile profile,
            final Path expectedArtifact,
            final TemporaryDeformCoordinator coordinator,
            final LoadedClassIndex loadedClasses) {
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
        this.attachmentMode = Objects.requireNonNull(attachmentMode, "attachmentMode");
        this.hostClassLoader = Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.transformer = new TemporaryDeformNativeTransformer(
                Objects.requireNonNull(profile, "profile"),
                hostClassLoader,
                Objects.requireNonNull(expectedArtifact, "expectedArtifact"),
                message -> RuntimeDiagnostics.debug(LOG_COMPONENT, message));
        this.loadedClasses = loadedClasses;
    }

    /** Installs the bridge and transformer, then retransforms already-loaded exact targets. */
    synchronized void install() {
        if (installed) {
            return;
        }
        if (attachmentMode != AttachmentMode.PREMAIN) {
            throw new IllegalStateException("temporary deform hook is premain-only");
        }
        if (!instrumentation.isRetransformClassesSupported()) {
            throw new IllegalStateException("temporary deform retransformation is unavailable");
        }
        final List<Class<?>> alreadyLoaded = findLoadedTargets();
        try {
            bridgeRegistration = NativeTemporaryDeformBridge.install(coordinator);
            transformer.resetTransformedCount();
            instrumentation.addTransformer(transformer, true);
            transformerAdded = true;
            if (!alreadyLoaded.isEmpty()) {
                try {
                    VerifiedHookInstallerSupport.retransformAll(instrumentation, alreadyLoaded);
                } catch (Exception failure) {
                    throw new IllegalStateException("temporary deform target retransform failed", failure);
                }
                if (transformer.transformedCount() != alreadyLoaded.size()) {
                    throw new IllegalStateException("temporary deform retransform count mismatch: expected "
                            + alreadyLoaded.size()
                            + ", got "
                            + transformer.transformedCount());
                }
            }
            installed = true;
        } catch (RuntimeException | Error failure) {
            // @containment-exempt: cleans up transformer and bridge before rethrowing with fatal prioritized
            Throwable toThrow = failure;
            try {
                closeLocked();
            } catch (Throwable cleanup) {
                // @containment-exempt: cleanup failure merged, prioritizing fatal error
                toThrow = FatalErrors.mergeForCleanup(toThrow, cleanup);
            }
            throw FatalErrors.propagate(toThrow);
        }
    }

    @Override
    public synchronized void close() {
        closeLocked();
    }

    private void closeLocked() {
        installed = false;
        RuntimeException cleanupFailure = null;
        if (transformerAdded) {
            try {
                instrumentation.removeTransformer(transformer);
                final List<Class<?>> loaded = findLoadedTargets();
                if (!loaded.isEmpty()) {
                    VerifiedHookInstallerSupport.retransformAll(instrumentation, loaded);
                }
            } catch (Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                cleanupFailure = new IllegalStateException("temporary deform bytecode restore failed", failure);
            } finally {
                transformerAdded = false;
            }
        }
        if (bridgeRegistration != null) {
            try {
                bridgeRegistration.close();
            } catch (Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                final RuntimeException wrapped =
                        new IllegalStateException("temporary deform bridge close failed", failure);
                if (cleanupFailure == null) {
                    cleanupFailure = wrapped;
                } else {
                    cleanupFailure.addSuppressed(wrapped);
                }
            } finally {
                bridgeRegistration = null;
            }
        }
        if (cleanupFailure != null) {
            throw cleanupFailure;
        }
    }

    private List<Class<?>> findLoadedTargets() {
        final Class<?>[] all =
                (loadedClasses != null ? loadedClasses : LoadedClassIndex.snapshot(instrumentation)).all();
        final var expectedBinaryNames = transformer.targetClassNames().stream()
                .map(name -> name.replace('/', '.'))
                .collect(java.util.stream.Collectors.toSet());
        final var seen = new HashSet<String>();
        final List<Class<?>> exact = new ArrayList<>();
        for (final Class<?> type : all) {
            if (type == null || !expectedBinaryNames.contains(type.getName())) {
                continue;
            }
            if (type.getClassLoader() != hostClassLoader) {
                throw new IllegalStateException("TEMPORARY_DEFORM_LOADER_MISMATCH: " + type.getName());
            }
            if (!instrumentation.isModifiableClass(type)) {
                throw new IllegalStateException("TEMPORARY_DEFORM_TARGET_UNMODIFIABLE: " + type.getName());
            }
            if (!seen.add(type.getName())) {
                throw new IllegalStateException("TEMPORARY_DEFORM_TARGET_DUPLICATE: " + type.getName());
            }
            exact.add(type);
        }
        return List.copyOf(exact);
    }
}
