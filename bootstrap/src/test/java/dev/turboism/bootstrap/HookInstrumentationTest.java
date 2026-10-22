package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.runtime.log.RuntimeDiagnostics;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HookInstrumentationTest {
    @Test
    void logsOncePerHookAndClassAndPreservesRemovalAndFatalErrors() throws Exception {
        final List<ClassFileTransformer> registered = new ArrayList<>();
        final Instrumentation raw = (Instrumentation) Proxy.newProxyInstance(
                Instrumentation.class.getClassLoader(),
                new Class<?>[] {Instrumentation.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("addTransformer")) {
                        registered.add((ClassFileTransformer) args[0]);
                        return null;
                    }
                    if (method.getName().equals("removeTransformer")) return registered.remove(args[0]);
                    throw new UnsupportedOperationException(method.getName());
                });
        final List<Throwable> failures = new ArrayList<>();
        final LinkageError failure = new NoClassDefFoundError("broken transformer");
        final ClassFileTransformer transformer = new ClassFileTransformer() {
            @Override
            public byte[] transform(
                    final ClassLoader loader,
                    final String name,
                    final Class<?> type,
                    final ProtectionDomain domain,
                    final byte[] bytes) {
                if ("fatal".equals(name)) throw new OutOfMemoryError("fatal");
                throw failure;
            }
        };
        RuntimeDiagnostics.clear();
        RuntimeDiagnostics.install((level, component, message, thrown) -> failures.add(thrown));
        try {
            final Instrumentation first = HookInstrumentation.wrap(raw, "first");
            first.addTransformer(transformer, true);
            first.addTransformer(transformer);
            assertNull(registered.get(0).transform(null, "Example", null, null, new byte[0]));
            assertNull(registered.get(1).transform(getClass().getModule(), null, "Example", null, null, new byte[0]));
            assertEquals(1, failures.size());
            assertSame(failure, failures.get(0));
            HookInstrumentation.wrap(raw, "second").addTransformer(transformer);
            assertNull(registered.get(2).transform(null, "Example", null, null, new byte[0]));
            assertNull(registered.get(0).transform(null, "Other", null, null, new byte[0]));
            assertEquals(3, failures.size());
            assertThrows(
                    OutOfMemoryError.class, () -> registered.get(0).transform(null, "fatal", null, null, new byte[0]));
            assertTrue(first.removeTransformer(transformer));
        } finally {
            RuntimeDiagnostics.clear();
        }
    }
}
