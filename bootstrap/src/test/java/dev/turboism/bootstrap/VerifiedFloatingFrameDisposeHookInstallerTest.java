package dev.turboism.bootstrap;

import dev.turboism.mapping.verification.StaticSelector;
import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VerifiedFloatingFrameDisposeHookInstallerTest {

    @Test
    void installsOneExactTransformerAndRetransformsAnAlreadyLoadedTarget() {
        final List<String> calls = new ArrayList<>();
        final Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[]{Instrumentation.class},
            (proxy, method, arguments) -> {
                switch (method.getName()) {
                    case "isRetransformClassesSupported" -> { return true; }
                    case "addTransformer" -> {
                        calls.add("add:" + arguments[1]);
                        return null;
                    }
                    case "getAllLoadedClasses" -> { return new Class<?>[]{Target.class}; }
                    case "isModifiableClass" -> { return true; }
                    case "retransformClasses" -> {
                        calls.add("retransform:" + ((Class<?>[]) arguments[0])[0].getName());
                        return null;
                    }
                    case "removeTransformer" -> {
                        calls.add("remove");
                        return true;
                    }
                    default -> { return defaultValue(method.getReturnType()); }
                }
            }
        );

        try (VerifiedFloatingFrameDisposeHookInstaller installer = new VerifiedFloatingFrameDisposeHookInstaller(
            instrumentation,
            methodSelector("dispose", Target.class.getName().replace('.', '/'), "disposeFrame", "()V"),
            Target.class.getClassLoader()
        )) {
            installer.install();
        }

        assertEquals(List.of(
            "add:true",
            "retransform:" + Target.class.getName(),
            "remove"
        ), calls);
    }

    @Test
    void rejectsStaticDisposeSelectorsFailClosed() {
        final Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[]{Instrumentation.class},
            (proxy, method, arguments) -> defaultValue(method.getReturnType())
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new VerifiedFloatingFrameDisposeHookInstaller(
                instrumentation,
                new StaticSelector(
                    "dispose",
                    "dispose",
                    StaticSelector.Kind.METHOD,
                    Target.class.getName().replace('.', '/'),
                    "disposeFrame",
                    "()V",
                    StaticSelector.ACCESS_PUBLIC,
                    0
                ),
                Target.class.getClassLoader()
            )
        );
    }

    private static StaticSelector methodSelector(
        final String alias,
        final String owner,
        final String name,
        final String descriptor
    ) {
        return StaticSelector.method(
            alias, owner, name, descriptor, StaticSelector.ACCESS_PUBLIC
        );
    }

    private static Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return (char) 0;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        return null;
    }

    static class Target {
        public void disposeFrame() {
        }
    }
}
