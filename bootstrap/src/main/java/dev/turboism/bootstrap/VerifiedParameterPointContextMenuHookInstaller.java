package dev.turboism.bootstrap;

import dev.turboism.ui.context.ParameterPointContextMenuNativeMethodTransformer;
import java.lang.instrument.Instrumentation;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Reversible exact-version Q-menu show hooks. */
final class VerifiedParameterPointContextMenuHookInstaller implements AutoCloseable {

    private final HookInstallSession session;

    VerifiedParameterPointContextMenuHookInstaller(
            final Instrumentation instrumentation,
            final String owner,
            final String contextDescriptor,
            final ClassLoader loader) {
        this(instrumentation, owner, contextDescriptor, loader, null);
    }

    /**
     * @param loadedClasses the loaded-class snapshot of this install phase, or {@code null} to
     *     enumerate the loaded classes on install and on restore
     */
    VerifiedParameterPointContextMenuHookInstaller(
            final Instrumentation instrumentation,
            final String owner,
            final String contextDescriptor,
            final ClassLoader loader,
            final LoadedClassIndex loadedClasses) {
        Objects.requireNonNull(instrumentation, "instrumentation");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(contextDescriptor, "contextDescriptor");
        Objects.requireNonNull(loader, "loader");
        this.session = HookInstallSession.builder(instrumentation, loader, Set.of(owner.replace('/', '.')))
                .loadedClasses(loadedClasses)
                .transformerFactory(() -> List.of(
                        new ParameterPointContextMenuNativeMethodTransformer(
                                owner, "a", "(" + contextDescriptor + "II)V", "i", loader),
                        new ParameterPointContextMenuNativeMethodTransformer(
                                owner, "b", "(" + contextDescriptor + "II)V", "h", loader)))
                .restorePolicy(HookInstallSession.RestorePolicy.ALWAYS)
                .retransformUnavailableMessage("Parameter-point context-menu retransformation is unavailable")
                .installFailureMessage("Verified parameter-point context-menu hook installation failed")
                .restoreFailureMessage("Verified parameter-point context-menu hook restoration failed")
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
