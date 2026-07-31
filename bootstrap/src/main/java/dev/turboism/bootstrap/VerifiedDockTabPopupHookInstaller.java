package dev.turboism.bootstrap;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.ui.panel.DockTabPopupNativeMethodTransformer;

import java.lang.instrument.Instrumentation;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Installs the exact-version dock-tab popup transformer and removes it reversibly. */
final class VerifiedDockTabPopupHookInstaller implements AutoCloseable {

    private final Instrumentation instrumentation;
    private final StaticSelector operation;
    private final StaticSelector paletteField;
    private final StaticSelector menuAppend;
    private final ClassLoader hostClassLoader;
    private final AtomicBoolean installed = new AtomicBoolean();
    private DockTabPopupNativeMethodTransformer transformer;

    VerifiedDockTabPopupHookInstaller(
        final Instrumentation instrumentation,
        final StaticSelector operation,
        final StaticSelector paletteField,
        final StaticSelector menuAppend,
        final ClassLoader hostClassLoader
    ) {
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
        this.operation = Objects.requireNonNull(operation, "operation");
        this.paletteField = Objects.requireNonNull(paletteField, "paletteField");
        this.menuAppend = Objects.requireNonNull(menuAppend, "menuAppend");
        this.hostClassLoader = Objects.requireNonNull(hostClassLoader, "hostClassLoader");
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
                paletteField.descriptor()
            );
            instrumentation.addTransformer(transformer, true);
            if (!instrumentation.isRetransformClassesSupported()) {
                throw new IllegalStateException("Dock-tab popup retransformation is unavailable");
            }
            for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                if (loaded.getClassLoader() == hostClassLoader
                    && loaded.getName().replace('.', '/').equals(operation.ownerInternalName())
                    && instrumentation.isModifiableClass(loaded)) {
                    instrumentation.retransformClasses(loaded);
                }
            }
        } catch (Throwable failure) {
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
}
