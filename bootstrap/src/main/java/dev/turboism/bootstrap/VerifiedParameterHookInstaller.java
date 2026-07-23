package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.lifecycle.NativeParameterLifecycleBridge;
import dev.turboism.adapter.cubism.lifecycle.ParameterLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.ParameterNativeMethodTransformer;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.VerifiedMemberResolver;

import java.lang.instrument.Instrumentation;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Installs and owns the exact native parameter lifecycle transformer. */
final class VerifiedParameterHookInstaller implements AutoCloseable {

    static final String PARAMETER_OPERATION_ALIAS =
        "cubism.editor-model.parameter-operation.set-value";
    static final String PARAMETER_SOURCE_ID_ALIAS =
        "cubism.editor-model.parameter-source.id";
    static final String PARAMETER_ID_VALUE_ALIAS =
        "cubism.editor-model.id.value";

    private final Instrumentation instrumentation;
    private final String targetClassName;
    private final ClassLoader hostClassLoader;
    private final ParameterNativeMethodTransformer transformer;
    private final NativeParameterLifecycleBridge bridge;
    private final AtomicBoolean installed = new AtomicBoolean(false);

    VerifiedParameterHookInstaller(
        final Instrumentation instrumentation,
        final StaticSelector selector,
        final StaticSelector sourceId,
        final StaticSelector idValue,
        final ClassLoader hostClassLoader,
        final ParameterLifecycleCoordinator coordinator,
        final CubismModelAccess modelAccess
    ) {
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
        this.targetClassName = selector.ownerInternalName().replace('/', '.');
        this.hostClassLoader = Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        this.transformer = new ParameterNativeMethodTransformer(
            selector.ownerInternalName(),
            selector.memberName(),
            selector.descriptor(),
            hostClassLoader,
            sourceId.ownerInternalName(),
            sourceId.memberName(),
            sourceId.descriptor(),
            idValue.ownerInternalName(),
            idValue.memberName(),
            idValue.descriptor()
        );
        this.bridge = new NativeParameterLifecycleBridge(coordinator, modelAccess);
    }

    static VerifiedParameterHookInstaller fromVerifiedResolver(
        final Instrumentation instrumentation,
        final VerifiedMemberResolver resolver,
        final ClassLoader hostClassLoader,
        final ParameterLifecycleCoordinator coordinator,
        final CubismModelAccess modelAccess
    ) {
        final StaticSelector selector = Objects.requireNonNull(resolver, "resolver")
            .verifiedSelector(PARAMETER_OPERATION_ALIAS);
        final StaticSelector sourceId = resolver.verifiedSelector(PARAMETER_SOURCE_ID_ALIAS);
        final StaticSelector idValue = resolver.verifiedSelector(PARAMETER_ID_VALUE_ALIAS);
        if (selector.kind() != StaticSelector.Kind.METHOD
            || sourceId.kind() != StaticSelector.Kind.METHOD
            || idValue.kind() != StaticSelector.Kind.METHOD
            || (selector.forbiddenAccessFlags() & StaticSelector.ACCESS_STATIC) == 0
            || (sourceId.forbiddenAccessFlags() & StaticSelector.ACCESS_STATIC) == 0
            || (idValue.forbiddenAccessFlags() & StaticSelector.ACCESS_STATIC) == 0) {
            throw new IllegalArgumentException(
                "Verified parameter operation must be an instance method."
            );
        }
        return new VerifiedParameterHookInstaller(
            instrumentation,
            selector,
            sourceId,
            idValue,
            hostClassLoader,
            coordinator,
            modelAccess
        );
    }

    void install() throws Exception {
        if (!installed.compareAndSet(false, true)) {
            return;
        }
        if (!instrumentation.isRetransformClassesSupported()) {
            installed.set(false);
            throw new IllegalStateException("Class retransformation is unavailable.");
        }
        NativeParameterLifecycleBridge.install(bridge);
        instrumentation.addTransformer(transformer, true);
        try {
            for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                if (loaded.getName().equals(targetClassName)
                    && loaded.getClassLoader() == hostClassLoader
                    && instrumentation.isModifiableClass(loaded)) {
                    instrumentation.retransformClasses(loaded);
                    break;
                }
            }
        } catch (Throwable failure) {
            close();
            throw failure;
        }
    }

    @Override
    public void close() {
        if (!installed.compareAndSet(true, false)) {
            return;
        }
        instrumentation.removeTransformer(transformer);
        NativeParameterLifecycleBridge.uninstall(bridge);
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
