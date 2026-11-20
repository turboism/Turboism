package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.startup.StartupSuppressionInstaller.AttachmentMode;
import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntSupplier;

/**
 * Shared premain lifecycle session for exact-artifact tool hooks that bind a loader-checked
 * bridge, retransform already-loaded owners, and retain teardown state across retries.
 */
final class PremainLifecycleHookSession implements AutoCloseable {

    record Labels(
            String hookLabel,
            String subjectLabel,
            Class<?> bridgeClass,
            String targetDuplicateCode,
            String targetLoaderMismatchCode,
            String targetUnmodifiableCode,
            String targetEnumerationFailedCode,
            String transformDeltaMismatchCode) {}

    private final Instrumentation instrumentation;
    private final AttachmentMode attachmentMode;
    private final ClassLoader hostClassLoader;
    private final ClassFileTransformer transformer;
    private final Set<String> targetInternalNames;
    private final Runnable resetTransformedCount;
    private final IntSupplier transformedCount;
    private final Runnable bindBridge;
    private final Runnable unbindBridge;
    private final LoadedClassIndex loadedClasses;
    private final Labels labels;
    private boolean restorePending;
    private boolean installed;
    private boolean transformerAdded;
    private boolean bridgeInstalled;

    PremainLifecycleHookSession(
            final Instrumentation instrumentation,
            final AttachmentMode attachmentMode,
            final ClassLoader hostClassLoader,
            final ClassFileTransformer transformer,
            final Collection<String> targetInternalNames,
            final Runnable resetTransformedCount,
            final IntSupplier transformedCount,
            final Runnable bindBridge,
            final Runnable unbindBridge,
            final LoadedClassIndex loadedClasses,
            final Labels labels) {
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
        this.attachmentMode = Objects.requireNonNull(attachmentMode, "attachmentMode");
        this.hostClassLoader = Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        this.transformer = Objects.requireNonNull(transformer, "transformer");
        this.targetInternalNames = Set.copyOf(Objects.requireNonNull(targetInternalNames, "targetInternalNames"));
        this.resetTransformedCount = Objects.requireNonNull(resetTransformedCount, "resetTransformedCount");
        this.transformedCount = Objects.requireNonNull(transformedCount, "transformedCount");
        this.bindBridge = Objects.requireNonNull(bindBridge, "bindBridge");
        this.unbindBridge = Objects.requireNonNull(unbindBridge, "unbindBridge");
        this.loadedClasses = loadedClasses;
        this.labels = Objects.requireNonNull(labels, "labels");
    }

    /**
     * Installs the bridge and transformer, then retransforms already-loaded exact owners.
     */
    synchronized void install() {
        if (installed) return;
        if (transformerAdded || restorePending || bridgeInstalled) {
            throw new IllegalStateException(labels.hookLabel() + " cleanup must finish before reinstalling.");
        }
        if (attachmentMode != AttachmentMode.PREMAIN) {
            throw new IllegalStateException(labels.hookLabel() + " lifecycle hook is premain-only.");
        }
        if (!instrumentation.isRetransformClassesSupported()) {
            throw new IllegalStateException(labels.hookLabel() + " retransformation is unavailable.");
        }
        requireBridgeLinkage();
        final List<Class<?>> targets = preflight();
        try {
            bindBridge.run();
            bridgeInstalled = true;
            resetTransformedCount.run();
            instrumentation.addTransformer(transformer, true);
            transformerAdded = true;
            try {
                VerifiedHookInstallerSupport.retransformAll(instrumentation, targets);
            } catch (Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw new IllegalStateException(labels.subjectLabel() + " target retransform failed", failure);
            }
            if (transformedCount.getAsInt() != targets.size()) {
                throw new IllegalStateException(labels.transformDeltaMismatchCode()
                        + ": expected " + targets.size()
                        + " transformed owner(s), got " + transformedCount.getAsInt());
            }
            installed = true;
        } catch (RuntimeException | Error failure) {
            // @containment-exempt: closeLocked runs cleanup before rethrowing with fatal error prioritized
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
        if (transformerAdded) {
            removeTransformer();
            transformerAdded = false;
            restorePending = true;
        }
        if (restorePending) {
            restoreLoadedOwners();
            restorePending = false;
        }
        if (bridgeInstalled) {
            unbindBridge.run();
            bridgeInstalled = false;
        }
    }

    private List<Class<?>> preflight() {
        final Class<?>[] loaded;
        try {
            loaded = loadedClasses().all();
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            throw new IllegalStateException(labels.targetEnumerationFailedCode(), failure);
        }
        final List<String> targetNames =
                targetInternalNames.stream().map(name -> name.replace('/', '.')).toList();
        final var seen = new HashSet<String>();
        final List<Class<?>> exact = new ArrayList<>();
        for (final Class<?> type : loaded) {
            if (type == null || !targetNames.contains(type.getName())) continue;
            final String targetName = type.getName();
            if (type.getClassLoader() != hostClassLoader) {
                throw new IllegalStateException(labels.targetLoaderMismatchCode() + ": " + targetName);
            }
            if (!instrumentation.isModifiableClass(type)) {
                throw new IllegalStateException(labels.targetUnmodifiableCode() + ": " + targetName);
            }
            if (!seen.add(targetName)) {
                throw new IllegalStateException(labels.targetDuplicateCode() + ": " + targetName);
            }
            exact.add(type);
        }
        return List.copyOf(exact);
    }

    private void requireBridgeLinkage() {
        try {
            if (Class.forName(labels.bridgeClass().getName(), false, hostClassLoader) != labels.bridgeClass()) {
                throw new IllegalStateException(labels.hookLabel() + " bridge class identity mismatch.");
            }
        } catch (ClassNotFoundException | LinkageError failure) {
            throw new IllegalStateException(labels.hookLabel() + " bridge is not visible to the host loader.", failure);
        }
    }

    private void removeTransformer() {
        final boolean removed;
        try {
            removed = instrumentation.removeTransformer(transformer);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            throw new IllegalStateException(labels.subjectLabel() + " transformer removal failed", failure);
        }
        if (!removed) {
            throw new IllegalStateException(labels.subjectLabel() + " transformer removal failed");
        }
    }

    private void restoreLoadedOwners() {
        try {
            final List<Class<?>> targets = loadedClasses()
                    .select(
                            hostClassLoader,
                            type -> targetInternalNames.contains(type.getName().replace('.', '/')));
            for (final Class<?> type : targets) {
                if (!instrumentation.isModifiableClass(type)) {
                    throw new IllegalStateException(labels.targetUnmodifiableCode());
                }
            }
            VerifiedHookInstallerSupport.retransformAll(instrumentation, targets);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            throw new IllegalStateException(labels.subjectLabel() + " bytecode restore failed", failure);
        }
    }

    private LoadedClassIndex loadedClasses() {
        return loadedClasses != null ? loadedClasses : LoadedClassIndex.snapshot(instrumentation);
    }
}
