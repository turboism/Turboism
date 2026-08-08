package dev.turboism.bootstrap;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.ui.panel.FloatingTabCloseNativeMethodTransformer;

import java.lang.instrument.Instrumentation;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Installs the exact floating-tab close interception transformer for verified selectors. */
final class VerifiedFloatingTabCloseHookInstaller implements AutoCloseable {

    private final Instrumentation instrumentation;
    private final StaticSelector operation;
    private final StaticSelector paletteField;
    private final ClassLoader hostClassLoader;
    private final AtomicBoolean installed = new AtomicBoolean();
    private FloatingTabCloseNativeMethodTransformer transformer;

    VerifiedFloatingTabCloseHookInstaller(
        final Instrumentation instrumentation,
        final StaticSelector operation,
        final StaticSelector paletteField,
        final ClassLoader hostClassLoader
    ) {
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
        this.operation = Objects.requireNonNull(operation, "operation");
        this.paletteField = Objects.requireNonNull(paletteField, "paletteField");
        this.hostClassLoader = Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        if (operation.kind() != StaticSelector.Kind.METHOD
            || paletteField.kind() != StaticSelector.Kind.FIELD
            || !operation.ownerInternalName().equals(paletteField.ownerInternalName())
            || (operation.forbiddenAccessFlags() & StaticSelector.ACCESS_STATIC) == 0
            || (paletteField.forbiddenAccessFlags() & StaticSelector.ACCESS_STATIC) == 0) {
            throw new IllegalArgumentException(
                "Floating-tab close selectors do not describe exact instance members"
            );
        }
    }

    void install() {
        if (!installed.compareAndSet(false, true)) return;
        try {
            transformer = new FloatingTabCloseNativeMethodTransformer(
                operation.ownerInternalName(),
                operation.memberName(),
                operation.descriptor(),
                paletteField.memberName(),
                paletteField.descriptor(),
                hostClassLoader
            );
            instrumentation.addTransformer(transformer, true);
            if (!instrumentation.isRetransformClassesSupported()) {
                throw new IllegalStateException("Floating-tab close retransformation is unavailable");
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
            throw new IllegalStateException("Verified floating-tab close hook installation failed", failure);
        }
    }

    @Override
    public void close() {
        if (!installed.compareAndSet(true, false)) return;
        final FloatingTabCloseNativeMethodTransformer current = transformer;
        transformer = null;
        if (current != null) instrumentation.removeTransformer(current);
    }
}
