package dev.turboism.bootstrap;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.sdk.ui.context.ContextMenuRegistry.Location;
import dev.turboism.ui.context.ObjectContextMenuAppendNativeMethodTransformer;
import dev.turboism.ui.context.ObjectContextMenuNativeMethodTransformer;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Installs exact-version object context-menu transformers as one reversible unit. */
final class VerifiedObjectContextMenuHookInstaller implements AutoCloseable {

    private final Instrumentation instrumentation;
    private final List<Binding> bindings;
    private final ClassLoader hostClassLoader;
    private final AtomicBoolean installed = new AtomicBoolean();
    private final List<ClassFileTransformer> transformers = new ArrayList<>();

    VerifiedObjectContextMenuHookInstaller(
        final Instrumentation instrumentation,
        final List<Binding> bindings,
        final ClassLoader hostClassLoader
    ) {
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
        this.bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
        this.hostClassLoader = Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        if (this.bindings.isEmpty()) {
            throw new IllegalArgumentException("object context-menu bindings must not be empty");
        }
    }

    void install() {
        if (!installed.compareAndSet(false, true)) return;
        try {
            if (!instrumentation.isRetransformClassesSupported()) {
                throw new IllegalStateException("Object context-menu retransformation is unavailable");
            }
            final Set<String> owners = new LinkedHashSet<>();
            for (Binding binding : bindings) {
                final ClassFileTransformer transformer = binding.transformer(hostClassLoader);
                transformers.add(transformer);
                instrumentation.addTransformer(transformer, true);
                owners.add(binding.operation().ownerInternalName());
            }
            for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                if (loaded.getClassLoader() == hostClassLoader
                    && owners.contains(loaded.getName().replace('.', '/'))
                    && instrumentation.isModifiableClass(loaded)) {
                    instrumentation.retransformClasses(loaded);
                }
            }
        } catch (Throwable failure) {
            close();
            throw new IllegalStateException("Verified object context-menu hook installation failed", failure);
        }
    }


    @Override
    public void close() {
        if (!installed.compareAndSet(true, false)) return;
        final Set<String> owners = new LinkedHashSet<>();
        bindings.forEach(binding -> owners.add(binding.operation().ownerInternalName()));
        for (int index = transformers.size() - 1; index >= 0; index--) {
            instrumentation.removeTransformer(transformers.get(index));
        }
        transformers.clear();
        try {
            for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                if (loaded.getClassLoader() == hostClassLoader
                    && owners.contains(loaded.getName().replace('.', '/'))
                    && instrumentation.isModifiableClass(loaded)) {
                    instrumentation.retransformClasses(loaded);
                }
            }
        } catch (Throwable failure) {
            throw new IllegalStateException("Verified object context-menu hook restoration failed", failure);
        }
    }

    record Binding(
        StaticSelector operation,
        StaticSelector append,
        Location location,
        Shape shape,
        int expectedAppendPoints,
        int injectionPoint,
        int[] sourceLocals
    ) {
        Binding {
            operation = requireInstanceMethod(operation, "operation");
            location = Objects.requireNonNull(location, "location");
            shape = Objects.requireNonNull(shape, "shape");
            if (shape == Shape.APPEND_POINT) {
                append = requireInstanceMethod(append, "append");
            } else if (append != null) {
                throw new IllegalArgumentException("return-point binding must not declare append selector");
            }
            if (shape == Shape.APPEND_POINT
                && (expectedAppendPoints <= 0 || injectionPoint <= 0 || injectionPoint > expectedAppendPoints)) {
                throw new IllegalArgumentException("append cardinality and injection point must be positive and bounded");
            }
            if (shape == Shape.APPEND_POINT && (sourceLocals.length == 0
                || java.util.Arrays.stream(sourceLocals).anyMatch(value -> value < 0))) {
                throw new IllegalArgumentException("append-point source locals must be non-empty and non-negative");
            }
            if (shape == Shape.RETURN_POINT && (expectedAppendPoints != 0 || injectionPoint != 0 || sourceLocals.length != 0)) {
                throw new IllegalArgumentException("return-point binding must not declare append cardinality");
            }
        }

        static Binding returnPoint(final StaticSelector operation, final Location location) {
            return new Binding(operation, null, location, Shape.RETURN_POINT, 0, 0, new int[0]);
        }

        static Binding appendPoint(
            final StaticSelector operation,
            final StaticSelector append,
            final Location location,
            final int expectedAppendPoints,
            final int injectionPoint,
            final int... sourceLocals
        ) {
            return new Binding(
                operation, append, location, Shape.APPEND_POINT,
                expectedAppendPoints, injectionPoint, sourceLocals.clone()
            );
        }

        ClassFileTransformer transformer(final ClassLoader hostClassLoader) {
            if (shape == Shape.RETURN_POINT) {
                return new ObjectContextMenuNativeMethodTransformer(
                    operation.ownerInternalName(),
                    operation.memberName(),
                    operation.descriptor(),
                    hostClassLoader,
                    location
                );
            }
            return new ObjectContextMenuAppendNativeMethodTransformer(
                operation.ownerInternalName(),
                operation.memberName(),
                operation.descriptor(),
                hostClassLoader,
                append.ownerInternalName(),
                append.memberName(),
                append.descriptor(),
                location,
                expectedAppendPoints,
                injectionPoint,
                sourceLocals
            );
        }

        private static StaticSelector requireInstanceMethod(
            final StaticSelector selector,
            final String name
        ) {
            Objects.requireNonNull(selector, name);
            if (selector.kind() != StaticSelector.Kind.METHOD
                || (selector.forbiddenAccessFlags() & StaticSelector.ACCESS_STATIC) == 0) {
                throw new IllegalArgumentException(name + " selector must be an exact instance method");
            }
            return selector;
        }
    }

    private enum Shape {
        RETURN_POINT,
        APPEND_POINT
    }
}
