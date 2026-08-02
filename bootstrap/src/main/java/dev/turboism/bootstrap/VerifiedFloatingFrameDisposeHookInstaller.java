package dev.turboism.bootstrap;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.ui.panel.FloatingFrameDisposeNativeMethodTransformer;

import java.lang.instrument.Instrumentation;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Installs the exact palette-frame disposal transformer only for verified selectors. */
final class VerifiedFloatingFrameDisposeHookInstaller implements AutoCloseable {

    private final Instrumentation instrumentation;
    private final StaticSelector dispose;
    private final ClassLoader hostClassLoader;
    private final AtomicBoolean installed = new AtomicBoolean();
    private FloatingFrameDisposeNativeMethodTransformer transformer;

    VerifiedFloatingFrameDisposeHookInstaller(
        final Instrumentation instrumentation,
        final StaticSelector dispose,
        final ClassLoader hostClassLoader
    ) {
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
        this.dispose = Objects.requireNonNull(dispose, "dispose");
        this.hostClassLoader = Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        if (dispose.kind() != StaticSelector.Kind.METHOD
            || (dispose.forbiddenAccessFlags() & StaticSelector.ACCESS_STATIC) == 0) {
            throw new IllegalArgumentException("Floating-frame dispose selector does not describe an exact instance method");
        }
    }

    void install() {
        if (!installed.compareAndSet(false, true)) return;
        try {
            transformer = new FloatingFrameDisposeNativeMethodTransformer(
                dispose.ownerInternalName(),
                dispose.memberName(),
                dispose.descriptor(),
                hostClassLoader
            );
            instrumentation.addTransformer(transformer, true);
            if (!instrumentation.isRetransformClassesSupported()) {
                throw new IllegalStateException("Floating-frame dispose retransformation is unavailable");
            }
            for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                if (loaded.getClassLoader() == hostClassLoader
                    && loaded.getName().replace('.', '/').equals(dispose.ownerInternalName())
                    && instrumentation.isModifiableClass(loaded)) {
                    instrumentation.retransformClasses(loaded);
                }
            }
        } catch (Throwable failure) {
            close();
            throw new IllegalStateException("Verified floating-frame dispose hook installation failed", failure);
        }
    }

    @Override
    public void close() {
        if (!installed.compareAndSet(true, false)) return;
        final FloatingFrameDisposeNativeMethodTransformer current = transformer;
        transformer = null;
        if (current != null) instrumentation.removeTransformer(current);
    }
}
