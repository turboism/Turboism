package dev.turboism.bootstrap;

import dev.turboism.mapping.verification.StaticSelector;
import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VerifiedDockTabPopupHookInstallerTest {

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

        try (VerifiedDockTabPopupHookInstaller installer = new VerifiedDockTabPopupHookInstaller(
            instrumentation,
            methodSelector("operation", Target.class.getName().replace('.', '/'), "open", "(Ljava/lang/Object;)V"),
            fieldSelector("palette", Target.class.getName().replace('.', '/'), "palette", "Ljava/lang/Object;"),
            methodSelector("append", "fixture/Menu", "append", "(Ljava/lang/Object;)V"),
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

    private static StaticSelector fieldSelector(
        final String alias,
        final String owner,
        final String name,
        final String descriptor
    ) {
        return new StaticSelector(
            alias,
            alias,
            StaticSelector.Kind.FIELD,
            owner,
            name,
            descriptor,
            0,
            StaticSelector.ACCESS_STATIC
        );
    }

    private static Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }

    public static final class Target {
        private final Object palette = new Object();

        public void open(final Object event) {
        }
    }
}
