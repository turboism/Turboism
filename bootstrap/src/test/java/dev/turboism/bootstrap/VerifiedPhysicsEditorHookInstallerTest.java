package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.physics.PhysicsEditorCoordinator;
import dev.turboism.adapter.cubism.physics.PhysicsEditorHostProfile;
import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VerifiedPhysicsEditorHookInstallerTest {

    @Test
    void installsOneExactTransformerAndRetransformsAnAlreadyLoadedPanel() throws Exception {
        final List<String> calls = new ArrayList<>();
        final Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[]{Instrumentation.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "isRetransformClassesSupported" -> true;
                case "addTransformer" -> { calls.add("add:" + arguments[1]); yield null; }
                case "getAllLoadedClasses" -> new Class<?>[]{TargetPanel.class};
                case "isModifiableClass" -> true;
                case "retransformClasses" -> {
                    calls.add("retransform:" + ((Class<?>[]) arguments[0])[0].getName());
                    yield null;
                }
                case "removeTransformer" -> { calls.add("remove"); yield true; }
                default -> defaultValue(method.getReturnType());
            }
        );
        final PhysicsEditorHostProfile profile = new PhysicsEditorHostProfile(
            TargetPanel.class.getName().replace('.', '/'),
            "getTableArea", "this$0", "l", "getSources", "getEnable", "setEnable", "getGuid",
            "b", "n", "d"
        );

        try (VerifiedPhysicsEditorHookInstaller installer = new VerifiedPhysicsEditorHookInstaller(
            instrumentation,
            TargetPanel.class.getClassLoader(),
            new PhysicsEditorCoordinator(),
            profile
        )) {
            installer.install();
        }

        assertEquals(List.of(
            "add:true",
            "retransform:" + TargetPanel.class.getName(),
            "remove"
        ), calls);
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

    public static final class TargetPanel { }
}
