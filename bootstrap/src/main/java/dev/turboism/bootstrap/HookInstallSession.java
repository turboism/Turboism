package dev.turboism.bootstrap;

import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Shared install/restore/teardown lifecycle for selector- and profile-driven hook installers whose
 * targets load lazily and need no {@link WeaveSpec} digest attestation.
 */
final class HookInstallSession implements AutoCloseable {

    enum RestorePolicy {
        /** Removes transformers and uninstalls bridges without retransforming owners. */
        NONE,
        /**
         * Removes transformers once, retransforms loaded owners before uninstalling bridges, and
         * retains open state for retry if restoration fails.
         */
        RETRYABLE_BEFORE_BRIDGE_CLOSE,
        /** Removes transformers, retransforms loaded owners, and wraps restoration failures. */
        ALWAYS,
        /** Removes transformers, uninstalls bridges, and swallows non-fatal restoration failures. */
        BEST_EFFORT_AFTER_BRIDGE_UNINSTALL
    }

    @FunctionalInterface
    interface LoadedRetransform {
        void retransform(
                Instrumentation instrumentation,
                LoadedClassIndex loadedClasses,
                Collection<String> targetClassNames,
                ClassLoader hostClassLoader)
                throws Exception;
    }

    static final LoadedRetransform DEFAULT_RETRANSFORM = (instrumentation, loaded, targets, loader) ->
            VerifiedHookInstallerSupport.retransformIfLoaded(instrumentation, loaded, targets, loader);

    private final Instrumentation instrumentation;
    private final ClassLoader hostClassLoader;
    private final LoadedClassIndex loadedClasses;
    private final Supplier<List<ClassFileTransformer>> transformerFactory;
    private final Collection<String> targetClassNames;
    private final Runnable installBridge;
    private final Runnable uninstallBridge;
    private final LoadedRetransform retransformOnInstall;
    private final RestorePolicy restorePolicy;
    private final boolean useSnapshotOnRestore;
    private final String retransformUnavailableMessage;
    private final String installFailureMessage;
    private final String restoreFailureMessage;
    private final AtomicBoolean installed = new AtomicBoolean();
    private final List<ClassFileTransformer> activeTransformers = new ArrayList<>();
    private boolean transformersRemoved;

    private HookInstallSession(final Builder builder) {
        this.instrumentation = Objects.requireNonNull(builder.instrumentation, "instrumentation");
        this.hostClassLoader = Objects.requireNonNull(builder.hostClassLoader, "hostClassLoader");
        this.loadedClasses = builder.loadedClasses;
        this.transformerFactory = Objects.requireNonNull(builder.transformerFactory, "transformerFactory");
        this.targetClassNames = List.copyOf(Objects.requireNonNull(builder.targetClassNames, "targetClassNames"));
        this.installBridge = builder.installBridge != null ? builder.installBridge : () -> {};
        this.uninstallBridge = builder.uninstallBridge != null ? builder.uninstallBridge : () -> {};
        this.retransformOnInstall =
                builder.retransformOnInstall != null ? builder.retransformOnInstall : DEFAULT_RETRANSFORM;
        this.restorePolicy = Objects.requireNonNull(builder.restorePolicy, "restorePolicy");
        this.useSnapshotOnRestore = builder.useSnapshotOnRestore;
        this.retransformUnavailableMessage =
                Objects.requireNonNull(builder.retransformUnavailableMessage, "retransformUnavailableMessage");
        this.installFailureMessage = builder.installFailureMessage;
        this.restoreFailureMessage = builder.restoreFailureMessage;
    }

    static Builder builder(
            final Instrumentation instrumentation,
            final ClassLoader hostClassLoader,
            final Collection<String> targetClassNames) {
        return new Builder(instrumentation, hostClassLoader, targetClassNames);
    }

    /**
     * Installs bridges, registers transformers, and retransforms already-loaded owners, wrapping any
     * non-fatal failure in {@link IllegalStateException} when configured.
     */
    void installWrapped() {
        try {
            install();
        } catch (Exception failure) {
            if (failure instanceof IllegalStateException ise
                    && installFailureMessage != null
                    && installFailureMessage.equals(ise.getMessage())) {
                throw ise;
            }
            throw new IllegalStateException(
                    Objects.requireNonNull(installFailureMessage, "installFailureMessage"), failure);
        }
    }

    /**
     * Installs bridges, registers transformers, and retransforms already-loaded owners, returning
     * {@code false} when retransformation is unavailable or installation fails.
     */
    boolean installQuietly() {
        if (!installed.compareAndSet(false, true)) {
            return true;
        }
        if (!instrumentation.isRetransformClassesSupported()) {
            installed.set(false);
            return false;
        }
        try {
            runInstall();
            return true;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            rollbackOnInstallFailure(failure);
            installed.set(false);
            return false;
        }
    }

    /**
     * Installs bridges, registers transformers, and retransforms already-loaded owners.
     *
     * @throws Exception when retransformation is unavailable or installation fails
     */
    void install() throws Exception {
        if (!installed.compareAndSet(false, true)) {
            return;
        }
        if (!instrumentation.isRetransformClassesSupported()) {
            installed.set(false);
            throw new IllegalStateException(retransformUnavailableMessage);
        }
        try {
            runInstall();
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            rollbackOnInstallFailure(failure);
            if (failure instanceof Exception exception) {
                throw exception;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(
                    installFailureMessage != null ? installFailureMessage : "Hook installation failed", failure);
        }
    }

    private void runInstall() throws Exception {
        installBridge.run();
        final List<ClassFileTransformer> built = transformerFactory.get();
        activeTransformers.clear();
        transformersRemoved = false;
        for (final ClassFileTransformer transformer : built) {
            activeTransformers.add(transformer);
            instrumentation.addTransformer(transformer, true);
        }
        retransformOnInstall.retransform(instrumentation, loadedClasses(), targetClassNames, hostClassLoader);
    }

    private void rollbackOnInstallFailure(final Throwable failure) {
        try {
            close();
        } catch (Throwable cleanupFailure) {
            FatalErrors.rethrowIfFatal(cleanupFailure);
            failure.addSuppressed(cleanupFailure);
        }
    }

    @Override
    public synchronized void close() {
        if (restorePolicy == RestorePolicy.RETRYABLE_BEFORE_BRIDGE_CLOSE) {
            closeRetryable();
            return;
        }
        if (!installed.compareAndSet(true, false)) {
            return;
        }
        removeActiveTransformers();
        switch (restorePolicy) {
            case NONE -> uninstallBridge.run();
            case ALWAYS -> {
                retransformOnClose();
                uninstallBridge.run();
            }
            case BEST_EFFORT_AFTER_BRIDGE_UNINSTALL -> {
                uninstallBridge.run();
                try {
                    retransformLoadedForRestore();
                } catch (Throwable failure) {
                    FatalErrors.rethrowIfFatal(failure);
                }
            }
            case RETRYABLE_BEFORE_BRIDGE_CLOSE -> throw new AssertionError();
        }
    }

    private void closeRetryable() {
        if (!installed.get()) {
            return;
        }
        if (!transformersRemoved) {
            removeActiveTransformers();
            transformersRemoved = true;
        }
        retransformOnClose();
        uninstallBridge.run();
        installed.set(false);
    }

    private void removeActiveTransformers() {
        for (int index = activeTransformers.size() - 1; index >= 0; index--) {
            instrumentation.removeTransformer(activeTransformers.get(index));
        }
        activeTransformers.clear();
    }

    private void retransformOnClose() {
        try {
            retransformLoadedForRestore();
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            throw new IllegalStateException(
                    restoreFailureMessage != null ? restoreFailureMessage : "Hook restoration failed", failure);
        }
    }

    private void retransformLoadedForRestore() throws Exception {
        final LoadedClassIndex index =
                useSnapshotOnRestore ? loadedClasses() : LoadedClassIndex.snapshot(instrumentation);
        VerifiedHookInstallerSupport.retransformIfLoaded(instrumentation, index, targetClassNames, hostClassLoader);
    }

    private LoadedClassIndex loadedClasses() {
        return loadedClasses != null ? loadedClasses : LoadedClassIndex.snapshot(instrumentation);
    }

    static final class Builder {
        private final Instrumentation instrumentation;
        private final ClassLoader hostClassLoader;
        private final Collection<String> targetClassNames;
        private LoadedClassIndex loadedClasses;
        private Supplier<List<ClassFileTransformer>> transformerFactory;
        private Runnable installBridge;
        private Runnable uninstallBridge;
        private LoadedRetransform retransformOnInstall;
        private RestorePolicy restorePolicy = RestorePolicy.NONE;
        private boolean useSnapshotOnRestore;
        private String retransformUnavailableMessage = "Class retransformation is unavailable.";
        private String installFailureMessage;
        private String restoreFailureMessage;

        private Builder(
                final Instrumentation instrumentation,
                final ClassLoader hostClassLoader,
                final Collection<String> targetClassNames) {
            this.instrumentation = instrumentation;
            this.hostClassLoader = hostClassLoader;
            this.targetClassNames = targetClassNames;
        }

        Builder loadedClasses(final LoadedClassIndex loadedClasses) {
            this.loadedClasses = loadedClasses;
            return this;
        }

        Builder transformer(final ClassFileTransformer transformer) {
            final List<ClassFileTransformer> fixed = List.of(Objects.requireNonNull(transformer, "transformer"));
            this.transformerFactory = () -> fixed;
            return this;
        }

        Builder transformers(final List<ClassFileTransformer> transformers) {
            final List<ClassFileTransformer> fixed = List.copyOf(Objects.requireNonNull(transformers, "transformers"));
            this.transformerFactory = () -> fixed;
            return this;
        }

        Builder transformerFactory(final Supplier<List<ClassFileTransformer>> transformerFactory) {
            this.transformerFactory = transformerFactory;
            return this;
        }

        Builder bridge(final Runnable installBridge, final Runnable uninstallBridge) {
            this.installBridge = installBridge;
            this.uninstallBridge = uninstallBridge;
            return this;
        }

        Builder retransformOnInstall(final LoadedRetransform retransformOnInstall) {
            this.retransformOnInstall = retransformOnInstall;
            return this;
        }

        Builder restorePolicy(final RestorePolicy restorePolicy) {
            this.restorePolicy = restorePolicy;
            return this;
        }

        Builder useSnapshotOnRestore() {
            this.useSnapshotOnRestore = true;
            return this;
        }

        Builder retransformUnavailableMessage(final String retransformUnavailableMessage) {
            this.retransformUnavailableMessage = retransformUnavailableMessage;
            return this;
        }

        Builder installFailureMessage(final String installFailureMessage) {
            this.installFailureMessage = installFailureMessage;
            return this;
        }

        Builder restoreFailureMessage(final String restoreFailureMessage) {
            this.restoreFailureMessage = restoreFailureMessage;
            return this;
        }

        HookInstallSession build() {
            return new HookInstallSession(this);
        }
    }
}
