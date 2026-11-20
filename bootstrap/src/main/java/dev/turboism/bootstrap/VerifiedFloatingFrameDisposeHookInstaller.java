package dev.turboism.bootstrap;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.ui.panel.FloatingFrameDisposeNativeMethodTransformer;
import java.lang.instrument.Instrumentation;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Installs the exact palette-frame disposal transformer only for verified selectors. */
final class VerifiedFloatingFrameDisposeHookInstaller implements AutoCloseable {

    private final HookInstallSession session;

    VerifiedFloatingFrameDisposeHookInstaller(
            final Instrumentation instrumentation, final StaticSelector dispose, final ClassLoader hostClassLoader) {
        this(instrumentation, dispose, hostClassLoader, null);
    }

    /**
     * @param loadedClasses the loaded-class snapshot of this install phase, or {@code null} to
     *     enumerate the loaded classes on install
     */
    VerifiedFloatingFrameDisposeHookInstaller(
            final Instrumentation instrumentation,
            final StaticSelector dispose,
            final ClassLoader hostClassLoader,
            final LoadedClassIndex loadedClasses) {
        Objects.requireNonNull(instrumentation, "instrumentation");
        Objects.requireNonNull(dispose, "dispose");
        Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        if (dispose.kind() != StaticSelector.Kind.METHOD
                || (dispose.forbiddenAccessFlags() & StaticSelector.ACCESS_STATIC) == 0) {
            throw new IllegalArgumentException(
                    "Floating-frame dispose selector does not describe an exact instance method");
        }
        this.session = HookInstallSession.builder(
                        instrumentation,
                        hostClassLoader,
                        Set.of(dispose.ownerInternalName().replace('/', '.')))
                .loadedClasses(loadedClasses)
                .transformerFactory(() -> List.of(new FloatingFrameDisposeNativeMethodTransformer(
                        dispose.ownerInternalName(), dispose.memberName(), dispose.descriptor(), hostClassLoader)))
                .retransformUnavailableMessage("Floating-frame dispose retransformation is unavailable")
                .installFailureMessage("Verified floating-frame dispose hook installation failed")
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
