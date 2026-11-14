package dev.turboism.bootstrap;

import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.ui.panel.DockTabPopupNativeMethodTransformer;
import java.lang.instrument.Instrumentation;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Installs the exact-version dock-tab popup transformer and removes it reversibly. */
final class VerifiedDockTabPopupHookInstaller implements AutoCloseable {

    private final Instrumentation instrumentation;
    private final StaticSelector operation;
    private final StaticSelector paletteField;
    private final StaticSelector menuAppend;
    private final ClassLoader hostClassLoader;
    private final LoadedClassIndex loadedClasses;
    private final AtomicBoolean installed = new AtomicBoolean();
    private DockTabPopupNativeMethodTransformer transformer;

    VerifiedDockTabPopupHookInstaller(
            final Instrumentation instrumentation,
            final StaticSelector operation,
            final StaticSelector paletteField,
            final StaticSelector menuAppend,
            final ClassLoader hostClassLoader) {
        this(instrumentation, operation, paletteField, menuAppend, hostClassLoader, null);
    }

    /**
     * @param loadedClasses the loaded-class snapshot of this install phase, or {@code null} to
     *     enumerate the loaded classes on install
     */
    VerifiedDockTabPopupHookInstaller(
            final Instrumentation instrumentation,
            final StaticSelector operation,
            final StaticSelector paletteField,
            final StaticSelector menuAppend,
            final ClassLoader hostClassLoader,
            final LoadedClassIndex loadedClasses) {
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
        this.operation = Objects.requireNonNull(operation, "operation");
        this.paletteField = Objects.requireNonNull(paletteField, "paletteField");
        this.menuAppend = Objects.requireNonNull(menuAppend, "menuAppend");
        this.hostClassLoader = Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        this.loadedClasses = loadedClasses;
        if (operation.kind() != StaticSelector.Kind.METHOD
                || paletteField.kind() != StaticSelector.Kind.FIELD
                || menuAppend.kind() != StaticSelector.Kind.METHOD
                || !operation.ownerInternalName().equals(paletteField.ownerInternalName())
                || (operation.forbiddenAccessFlags() & StaticSelector.ACCESS_STATIC) == 0
                || (paletteField.forbiddenAccessFlags() & StaticSelector.ACCESS_STATIC) == 0
                || (menuAppend.forbiddenAccessFlags() & StaticSelector.ACCESS_STATIC) == 0) {
            throw new IllegalArgumentException("Dock-tab popup selectors do not describe exact instance members");
        }
    }

    void install() {
        if (!installed.compareAndSet(false, true)) return;
        try {
            transformer = new DockTabPopupNativeMethodTransformer(
                    operation.ownerInternalName(),
                    operation.memberName(),
                    operation.descriptor(),
                    hostClassLoader,
                    menuAppend.ownerInternalName(),
                    menuAppend.memberName(),
                    menuAppend.descriptor(),
                    paletteField.memberName(),
                    paletteField.descriptor());
            instrumentation.addTransformer(transformer, true);
            if (!instrumentation.isRetransformClassesSupported()) {
                throw new IllegalStateException("Dock-tab popup retransformation is unavailable");
            }
            VerifiedHookInstallerSupport.retransformIfLoaded(
                    instrumentation,
                    loadedClasses(),
                    Set.of(operation.ownerInternalName().replace('/', '.')),
                    hostClassLoader);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            close();
            throw new IllegalStateException("Verified dock-tab popup hook installation failed", failure);
        }
    }

    @Override
    public void close() {
        if (!installed.compareAndSet(true, false)) return;
        final DockTabPopupNativeMethodTransformer current = transformer;
        transformer = null;
        if (current != null) instrumentation.removeTransformer(current);
    }

    private LoadedClassIndex loadedClasses() {
        return loadedClasses != null ? loadedClasses : LoadedClassIndex.snapshot(instrumentation);
    }
}
