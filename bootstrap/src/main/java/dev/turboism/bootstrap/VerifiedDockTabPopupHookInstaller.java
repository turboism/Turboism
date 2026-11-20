package dev.turboism.bootstrap;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.ui.panel.DockTabPopupNativeMethodTransformer;
import java.lang.instrument.Instrumentation;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Installs the exact-version dock-tab popup transformer and removes it reversibly. */
final class VerifiedDockTabPopupHookInstaller implements AutoCloseable {

    private final HookInstallSession session;

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
        Objects.requireNonNull(instrumentation, "instrumentation");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(paletteField, "paletteField");
        Objects.requireNonNull(menuAppend, "menuAppend");
        Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        if (operation.kind() != StaticSelector.Kind.METHOD
                || paletteField.kind() != StaticSelector.Kind.FIELD
                || menuAppend.kind() != StaticSelector.Kind.METHOD
                || !operation.ownerInternalName().equals(paletteField.ownerInternalName())
                || (operation.forbiddenAccessFlags() & StaticSelector.ACCESS_STATIC) == 0
                || (paletteField.forbiddenAccessFlags() & StaticSelector.ACCESS_STATIC) == 0
                || (menuAppend.forbiddenAccessFlags() & StaticSelector.ACCESS_STATIC) == 0) {
            throw new IllegalArgumentException("Dock-tab popup selectors do not describe exact instance members");
        }
        this.session = HookInstallSession.builder(
                        instrumentation,
                        hostClassLoader,
                        Set.of(operation.ownerInternalName().replace('/', '.')))
                .loadedClasses(loadedClasses)
                .transformerFactory(() -> List.of(new DockTabPopupNativeMethodTransformer(
                        operation.ownerInternalName(),
                        operation.memberName(),
                        operation.descriptor(),
                        hostClassLoader,
                        menuAppend.ownerInternalName(),
                        menuAppend.memberName(),
                        menuAppend.descriptor(),
                        paletteField.memberName(),
                        paletteField.descriptor())))
                .retransformUnavailableMessage("Dock-tab popup retransformation is unavailable")
                .installFailureMessage("Verified dock-tab popup hook installation failed")
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
