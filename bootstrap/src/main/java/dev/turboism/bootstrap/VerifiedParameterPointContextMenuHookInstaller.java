package dev.turboism.bootstrap;

import dev.turboism.ui.context.ParameterPointContextMenuNativeMethodTransformer;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Reversible exact-version Q-menu show hooks. */
final class VerifiedParameterPointContextMenuHookInstaller implements AutoCloseable {

    private final Instrumentation instrumentation;
    private final String owner;
    private final String contextDescriptor;
    private final ClassLoader loader;
    private final List<ClassFileTransformer> transformers = new ArrayList<>();
    private final AtomicBoolean installed = new AtomicBoolean();

    VerifiedParameterPointContextMenuHookInstaller(
        final Instrumentation instrumentation,
        final String owner,
        final String contextDescriptor,
        final ClassLoader loader
    ) {
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.contextDescriptor = Objects.requireNonNull(contextDescriptor, "contextDescriptor");
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    void install() {
        if (!installed.compareAndSet(false, true)) return;
        try {
            if (!instrumentation.isRetransformClassesSupported()) {
                throw new IllegalStateException("Parameter-point context-menu retransformation is unavailable");
            }
            transformers.add(new ParameterPointContextMenuNativeMethodTransformer(
                owner, "a", "(" + contextDescriptor + "II)V", "i", loader
            ));
            transformers.add(new ParameterPointContextMenuNativeMethodTransformer(
                owner, "b", "(" + contextDescriptor + "II)V", "h", loader
            ));
            transformers.forEach(value -> instrumentation.addTransformer(value, true));
            for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                if (loaded.getClassLoader() == loader
                    && owner.equals(loaded.getName().replace('.', '/'))
                    && instrumentation.isModifiableClass(loaded)) {
                    instrumentation.retransformClasses(loaded);
                }
            }
        } catch (Throwable failure) {
            close();
            throw new IllegalStateException("Verified parameter-point context-menu hook installation failed", failure);
        }
    }

    @Override
    public void close() {
        if (!installed.compareAndSet(true, false)) return;
        for (int index = transformers.size() - 1; index >= 0; index--) {
            instrumentation.removeTransformer(transformers.get(index));
        }
        transformers.clear();
        try {
            for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                if (loaded.getClassLoader() == loader
                    && owner.equals(loaded.getName().replace('.', '/'))
                    && instrumentation.isModifiableClass(loaded)) {
                    instrumentation.retransformClasses(loaded);
                }
            }
        } catch (Throwable failure) {
            throw new IllegalStateException("Verified parameter-point context-menu hook restoration failed", failure);
        }
    }
}
