package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.profile.ContextMenuBindingSpec;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.sdk.ui.context.ContextMenuRegistry.Location;
import dev.turboism.ui.context.ObjectContextMenuAppendNativeMethodTransformer;
import dev.turboism.ui.context.ObjectContextMenuNativeMethodTransformer;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Installs exact-version object context-menu transformers as one reversible unit. */
final class VerifiedObjectContextMenuHookInstaller implements AutoCloseable {

    private final HookInstallSession session;

    VerifiedObjectContextMenuHookInstaller(
            final Instrumentation instrumentation, final List<Binding> bindings, final ClassLoader hostClassLoader) {
        this(instrumentation, bindings, hostClassLoader, null);
    }

    /**
     * @param loadedClasses the loaded-class snapshot of this install phase, or {@code null} to
     *     enumerate the loaded classes on install
     */
    VerifiedObjectContextMenuHookInstaller(
            final Instrumentation instrumentation,
            final List<Binding> bindings,
            final ClassLoader hostClassLoader,
            final LoadedClassIndex loadedClasses) {
        Objects.requireNonNull(instrumentation, "instrumentation");
        final List<Binding> verifiedBindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
        Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        if (verifiedBindings.isEmpty()) {
            throw new IllegalArgumentException("object context-menu bindings must not be empty");
        }
        final Set<String> targetClassNames = new LinkedHashSet<>();
        for (final Binding binding : verifiedBindings) {
            targetClassNames.add(binding.operation().ownerInternalName().replace('/', '.'));
        }
        this.session = HookInstallSession.builder(instrumentation, hostClassLoader, targetClassNames)
                .loadedClasses(loadedClasses)
                .transformerFactory(() -> verifiedBindings.stream()
                        .map(binding -> binding.transformer(hostClassLoader))
                        .toList())
                .restorePolicy(HookInstallSession.RestorePolicy.ALWAYS)
                .retransformUnavailableMessage("Object context-menu retransformation is unavailable")
                .installFailureMessage("Verified object context-menu hook installation failed")
                .restoreFailureMessage("Verified object context-menu hook restoration failed")
                .build();
    }

    void install() {
        session.installWrapped();
    }

    @Override
    public void close() {
        session.close();
    }

    record Binding(
            StaticSelector operation,
            StaticSelector append,
            Location location,
            Shape shape,
            int expectedAppendPoints,
            int injectionPoint,
            int[] sourceLocals) {
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
                throw new IllegalArgumentException(
                        "append cardinality and injection point must be positive and bounded");
            }
            if (shape == Shape.APPEND_POINT
                    && (sourceLocals.length == 0
                            || java.util.Arrays.stream(sourceLocals).anyMatch(value -> value < 0))) {
                throw new IllegalArgumentException("append-point source locals must be non-empty and non-negative");
            }
            if (shape == Shape.RETURN_POINT
                    && (expectedAppendPoints != 0 || injectionPoint != 0 || sourceLocals.length != 0)) {
                throw new IllegalArgumentException("return-point binding must not declare append cardinality");
            }
        }

        static Binding returnPoint(final StaticSelector operation, final Location location) {
            return new Binding(operation, null, location, Shape.RETURN_POINT, 0, 0, new int[0]);
        }

        /**
         * Adapts one reviewed profile binding to the transformer-facing form.
         *
         * <p>The profile names host classes and cardinalities; this record adds the exact selectors
         * the transformers consume, so the profile stays free of selector construction.</p>
         *
         * @param spec the reviewed binding
         * @return the binding the transformers install
         */
        static Binding of(final ContextMenuBindingSpec spec) {
            final StaticSelector operation = StaticSelector.method(
                    "object-context-menu." + spec.location(),
                    spec.operationOwner(),
                    spec.operationMethod(),
                    spec.operationDescriptor(),
                    0);
            if (spec.shape() == ContextMenuBindingSpec.Shape.RETURN_POINT) {
                return returnPoint(operation, locationOf(spec.location()));
            }
            return appendPoint(
                    operation,
                    StaticSelector.method(
                            "object-context-menu." + spec.location() + ".append",
                            spec.appendOwner(),
                            spec.appendMethod(),
                            spec.appendDescriptor(),
                            0),
                    locationOf(spec.location()),
                    spec.expectedAppendPoints(),
                    spec.injectionPoint(),
                    spec.sourceLocals());
        }

        private static Location locationOf(final ContextMenuBindingSpec.Location location) {
            return switch (location) {
                case DEFORMER_TAB -> Location.DEFORMER_TAB;
                case PARAMETER_TAB -> Location.PARAMETER_TAB;
                case PART_TAB -> Location.PART_TAB;
                case WORKSPACE_OBJECT -> Location.WORKSPACE_OBJECT;
            };
        }

        static Binding appendPoint(
                final StaticSelector operation,
                final StaticSelector append,
                final Location location,
                final int expectedAppendPoints,
                final int injectionPoint,
                final int... sourceLocals) {
            return new Binding(
                    operation,
                    append,
                    location,
                    Shape.APPEND_POINT,
                    expectedAppendPoints,
                    injectionPoint,
                    sourceLocals.clone());
        }

        ClassFileTransformer transformer(final ClassLoader hostClassLoader) {
            if (shape == Shape.RETURN_POINT) {
                return new ObjectContextMenuNativeMethodTransformer(
                        operation.ownerInternalName(),
                        operation.memberName(),
                        operation.descriptor(),
                        hostClassLoader,
                        location);
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
                    sourceLocals);
        }

        private static StaticSelector requireInstanceMethod(final StaticSelector selector, final String name) {
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
