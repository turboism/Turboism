package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.lifecycle.NativeParameterLifecycleBridge;
import dev.turboism.adapter.cubism.lifecycle.ParameterLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.ParameterNativeMethodTransformer;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import java.lang.instrument.Instrumentation;
import java.util.Objects;
import java.util.Set;

/** Installs and owns the exact native parameter lifecycle transformer. */
final class VerifiedParameterHookInstaller implements AutoCloseable {

    static final String PARAMETER_OPERATION_ALIAS = "cubism.editor-model.parameter-operation.set-value";
    static final String PARAMETER_SOURCE_ID_ALIAS = "cubism.editor-model.parameter-source.id";
    static final String PARAMETER_ID_VALUE_ALIAS = "cubism.editor-model.id.value";

    private final HookInstallSession session;

    VerifiedParameterHookInstaller(
            final Instrumentation instrumentation,
            final StaticSelector selector,
            final StaticSelector sourceId,
            final StaticSelector idValue,
            final ClassLoader hostClassLoader,
            final ParameterLifecycleCoordinator coordinator,
            final CubismModelAccess modelAccess) {
        this(instrumentation, selector, sourceId, idValue, hostClassLoader, coordinator, modelAccess, null);
    }

    /**
     * @param loadedClasses the loaded-class snapshot of this install phase, or {@code null} to
     *     enumerate the loaded classes on install
     */
    VerifiedParameterHookInstaller(
            final Instrumentation instrumentation,
            final StaticSelector selector,
            final StaticSelector sourceId,
            final StaticSelector idValue,
            final ClassLoader hostClassLoader,
            final ParameterLifecycleCoordinator coordinator,
            final CubismModelAccess modelAccess,
            final LoadedClassIndex loadedClasses) {
        Objects.requireNonNull(instrumentation, "instrumentation");
        Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        final ParameterNativeMethodTransformer transformer = new ParameterNativeMethodTransformer(
                selector.ownerInternalName(),
                selector.memberName(),
                selector.descriptor(),
                hostClassLoader,
                sourceId.ownerInternalName(),
                sourceId.memberName(),
                sourceId.descriptor(),
                idValue.ownerInternalName(),
                idValue.memberName(),
                idValue.descriptor());
        final NativeParameterLifecycleBridge bridge = new NativeParameterLifecycleBridge(coordinator, modelAccess);
        this.session = HookInstallSession.builder(
                        instrumentation,
                        hostClassLoader,
                        Set.of(selector.ownerInternalName().replace('/', '.')))
                .loadedClasses(loadedClasses)
                .transformer(transformer)
                .bridge(
                        () -> NativeParameterLifecycleBridge.install(bridge),
                        () -> NativeParameterLifecycleBridge.uninstall(bridge))
                .build();
    }

    static VerifiedParameterHookInstaller fromVerifiedResolver(
            final Instrumentation instrumentation,
            final VerifiedMemberResolver resolver,
            final ClassLoader hostClassLoader,
            final ParameterLifecycleCoordinator coordinator,
            final CubismModelAccess modelAccess) {
        return fromVerifiedResolver(instrumentation, resolver, hostClassLoader, coordinator, modelAccess, null);
    }

    /**
     * @param loadedClasses the loaded-class snapshot of this install phase, or {@code null} to
     *     enumerate the loaded classes on install
     */
    static VerifiedParameterHookInstaller fromVerifiedResolver(
            final Instrumentation instrumentation,
            final VerifiedMemberResolver resolver,
            final ClassLoader hostClassLoader,
            final ParameterLifecycleCoordinator coordinator,
            final CubismModelAccess modelAccess,
            final LoadedClassIndex loadedClasses) {
        final StaticSelector selector =
                Objects.requireNonNull(resolver, "resolver").verifiedSelector(PARAMETER_OPERATION_ALIAS);
        final StaticSelector sourceId = resolver.verifiedSelector(PARAMETER_SOURCE_ID_ALIAS);
        final StaticSelector idValue = resolver.verifiedSelector(PARAMETER_ID_VALUE_ALIAS);
        if (selector.kind() != StaticSelector.Kind.METHOD
                || sourceId.kind() != StaticSelector.Kind.METHOD
                || idValue.kind() != StaticSelector.Kind.METHOD
                || (selector.forbiddenAccessFlags() & StaticSelector.ACCESS_STATIC) == 0
                || (sourceId.forbiddenAccessFlags() & StaticSelector.ACCESS_STATIC) == 0
                || (idValue.forbiddenAccessFlags() & StaticSelector.ACCESS_STATIC) == 0) {
            throw new IllegalArgumentException("Verified parameter operation must be an instance method.");
        }
        return new VerifiedParameterHookInstaller(
                instrumentation, selector, sourceId, idValue, hostClassLoader, coordinator, modelAccess, loadedClasses);
    }

    void install() throws Exception {
        session.install();
    }

    @Override
    public void close() {
        session.close();
    }
}
