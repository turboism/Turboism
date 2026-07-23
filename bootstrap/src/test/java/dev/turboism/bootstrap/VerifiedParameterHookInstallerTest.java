package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.lifecycle.NativeParameterLifecycleBridge;
import dev.turboism.adapter.cubism.lifecycle.ParameterLifecycleCoordinator;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import org.junit.jupiter.api.Test;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VerifiedParameterHookInstallerTest {

    @Test
    void installsOneExactTransformerAndRetransformsAnAlreadyLoadedTarget() throws Exception {
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
        final ParameterLifecycleCoordinator coordinator = new ParameterLifecycleCoordinator();
        final CubismModelAccess access = () -> { throw new IllegalStateException(); };

        try (VerifiedParameterHookInstaller installer = new VerifiedParameterHookInstaller(
            instrumentation,
            methodSelector(
                "target",
                Target.class.getName().replace('.', '/'),
                "set",
                "(Ljava/lang/Object;F)V"
            ),
            methodSelector(
                "source-id",
                "java/lang/Object",
                "toString",
                "()Ljava/lang/String;"
            ),
            methodSelector(
                "id-value",
                "java/lang/String",
                "toString",
                "()Ljava/lang/String;"
            ),
            Target.class.getClassLoader(),
            coordinator,
            access
        )) {
            installer.install();
        }

        assertEquals(List.of(
            "add:true",
            "retransform:" + Target.class.getName(),
            "remove"
        ), calls);
    }

    private static dev.turboism.mapping.verification.StaticSelector methodSelector(
        final String alias,
        final String owner,
        final String name,
        final String descriptor
    ) {
        return dev.turboism.mapping.verification.StaticSelector.method(
            alias,
            owner,
            name,
            descriptor,
            dev.turboism.mapping.verification.StaticSelector.ACCESS_PUBLIC
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
        public void set(final Object source, final float value) {
        }
    }
}
