package dev.turboism.bootstrap;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.ui.panel.FloatingTabCloseNativeMethodTransformer;
import java.lang.instrument.Instrumentation;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Installs the exact floating-tab close interception transformer for verified selectors. */
final class VerifiedFloatingTabCloseHookInstaller implements AutoCloseable {

    private final HookInstallSession session;

    VerifiedFloatingTabCloseHookInstaller(
            final Instrumentation instrumentation,
            final StaticSelector operation,
            final StaticSelector paletteField,
            final ClassLoader hostClassLoader) {
        this(instrumentation, operation, paletteField, hostClassLoader, null);
    }

    /**
     * @param loadedClasses the loaded-class snapshot of this install phase, or {@code null} to
     *     enumerate the loaded classes on install
     */
    VerifiedFloatingTabCloseHookInstaller(
            final Instrumentation instrumentation,
            final StaticSelector operation,
            final StaticSelector paletteField,
            final ClassLoader hostClassLoader,
            final LoadedClassIndex loadedClasses) {
        Objects.requireNonNull(instrumentation, "instrumentation");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(paletteField, "paletteField");
        Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        if (operation.kind() != StaticSelector.Kind.METHOD
                || paletteField.kind() != StaticSelector.Kind.FIELD
                || !operation.ownerInternalName().equals(paletteField.ownerInternalName())
                || (operation.forbiddenAccessFlags() & StaticSelector.ACCESS_STATIC) == 0
                || (paletteField.forbiddenAccessFlags() & StaticSelector.ACCESS_STATIC) == 0) {
            throw new IllegalArgumentException("Floating-tab close selectors do not describe exact instance members");
        }
        this.session = HookInstallSession.builder(
                        instrumentation,
                        hostClassLoader,
                        Set.of(operation.ownerInternalName().replace('/', '.')))
                .loadedClasses(loadedClasses)
                .transformerFactory(() -> List.of(new FloatingTabCloseNativeMethodTransformer(
                        operation.ownerInternalName(),
                        operation.memberName(),
                        operation.descriptor(),
                        paletteField.memberName(),
                        paletteField.descriptor(),
                        hostClassLoader)))
                .retransformUnavailableMessage("Floating-tab close retransformation is unavailable")
                .installFailureMessage("Verified floating-tab close hook installation failed")
                .build();
    }

    void install() {
        session.installWrapped();
    }

    @Override
    public void close() {
        session.close();
    }
}
