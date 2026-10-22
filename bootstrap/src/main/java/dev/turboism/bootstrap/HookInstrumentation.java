package dev.turboism.bootstrap;

import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.runtime.log.RuntimeDiagnostics;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.security.ProtectionDomain;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps transformer failures visible at the shared hook installation boundary. */
final class HookInstrumentation {
    private static final Map<String, Set<String>> REPORTED = new ConcurrentHashMap<>();

    private HookInstrumentation() {}

    static Instrumentation wrap(final Instrumentation instrumentation, final String hookId) {
        final Map<ClassFileTransformer, ClassFileTransformer> transformers =
                Collections.synchronizedMap(new IdentityHashMap<>());
        final Set<String> reported = REPORTED.computeIfAbsent(hookId, ignored -> ConcurrentHashMap.newKeySet());
        return (Instrumentation) Proxy.newProxyInstance(
                Instrumentation.class.getClassLoader(),
                new Class<?>[] {Instrumentation.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("addTransformer")) {
                        final ClassFileTransformer transformer = (ClassFileTransformer) args[0];
                        args[0] = transformers.computeIfAbsent(transformer, delegate -> new ClassFileTransformer() {
                            @Override
                            public byte[] transform(
                                    final ClassLoader loader,
                                    final String className,
                                    final Class<?> redefining,
                                    final ProtectionDomain domain,
                                    final byte[] bytes) {
                                return transform(null, loader, className, redefining, domain, bytes);
                            }

                            @Override
                            public byte[] transform(
                                    final Module module,
                                    final ClassLoader loader,
                                    final String className,
                                    final Class<?> redefining,
                                    final ProtectionDomain domain,
                                    final byte[] bytes) {
                                try {
                                    return delegate.transform(module, loader, className, redefining, domain, bytes);
                                } catch (Throwable failure) {
                                    FatalErrors.rethrowIfFatal(failure);
                                    if (reported.add(String.valueOf(className))) {
                                        RuntimeDiagnostics.error(
                                                "bootstrap",
                                                "Turboism transformer failed: " + hookId + " class=" + className,
                                                failure);
                                    }
                                    return null;
                                }
                            }
                        });
                    } else if (method.getName().equals("removeTransformer")) {
                        args[0] = transformers.getOrDefault(args[0], (ClassFileTransformer) args[0]);
                    }
                    try {
                        return method.invoke(instrumentation, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }
}
