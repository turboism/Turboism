package dev.turboism.bootstrap;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.sdk.ui.context.ContextMenuRegistry.Location;
import org.junit.jupiter.api.Test;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VerifiedObjectContextMenuHookInstallerTest {

    @Test
    void installsExactBindingsAndRemovesTransformersInReverseOrder() {
        final List<String> calls = new ArrayList<>();
        final List<ClassFileTransformer> installed = new ArrayList<>();
        final Instrumentation instrumentation = instrumentation(calls, installed, false);
        final String owner = Target.class.getName().replace('.', '/');

        try (VerifiedObjectContextMenuHookInstaller installer =
                 new VerifiedObjectContextMenuHookInstaller(
                     instrumentation,
                     List.of(
                         VerifiedObjectContextMenuHookInstaller.Binding.returnPoint(
                             methodSelector("parts", owner, "parts", "(Ljava/lang/Object;)Ljava/lang/Object;"),
                             Location.PART_TAB
                         ),
                         VerifiedObjectContextMenuHookInstaller.Binding.appendPoint(
                             methodSelector("deformer", owner, "deformer", "(Ljava/lang/Object;)V"),
                             methodSelector("append", "fixture/Menu", "append", "(Ljava/lang/Object;Ljava/lang/Object;)V"),
                             Location.DEFORMER_TAB
                         )
                     ),
                     Target.class.getClassLoader()
                 )) {
            installer.install();
        }

        assertEquals(List.of(
            "add:0", "add:1", "retransform:" + Target.class.getName(), "remove:1", "remove:0"
        ), calls);
    }

    @Test
    void rollsBackAllInstalledTransformersWhenRetransformationFails() {
        final List<String> calls = new ArrayList<>();
        final List<ClassFileTransformer> installed = new ArrayList<>();
        final Instrumentation instrumentation = instrumentation(calls, installed, true);
        final String owner = Target.class.getName().replace('.', '/');
        final VerifiedObjectContextMenuHookInstaller installer =
            new VerifiedObjectContextMenuHookInstaller(
                instrumentation,
                List.of(
                    VerifiedObjectContextMenuHookInstaller.Binding.returnPoint(
                        methodSelector("parts", owner, "parts", "(Ljava/lang/Object;)Ljava/lang/Object;"),
                        Location.PART_TAB
                    ),
                    VerifiedObjectContextMenuHookInstaller.Binding.appendPoint(
                        methodSelector("deformer", owner, "deformer", "(Ljava/lang/Object;)V"),
                        methodSelector("append", "fixture/Menu", "append", "(Ljava/lang/Object;Ljava/lang/Object;)V"),
                        Location.DEFORMER_TAB
                    )
                ),
                Target.class.getClassLoader()
            );

        assertThrows(IllegalStateException.class, installer::install);
        assertEquals(List.of(
            "add:0", "add:1", "retransform:" + Target.class.getName(), "remove:1", "remove:0"
        ), calls);
    }

    @Test
    void rejectsStaticOrMismatchedSelectorsBeforeInstallation() {
        final String owner = Target.class.getName().replace('.', '/');
        final StaticSelector staticOperation = StaticSelector.staticMethod(
            "static", owner, "deformer", "(Ljava/lang/Object;)V", StaticSelector.ACCESS_PUBLIC
        );

        assertThrows(IllegalArgumentException.class, () ->
            VerifiedObjectContextMenuHookInstaller.Binding.appendPoint(
                staticOperation,
                methodSelector("append", "fixture/Menu", "append", "(Ljava/lang/Object;Ljava/lang/Object;)V"),
                Location.DEFORMER_TAB
            )
        );
    }

    private static Instrumentation instrumentation(
        final List<String> calls,
        final List<ClassFileTransformer> installed,
        final boolean failRetransform
    ) {
        return (Instrumentation) Proxy.newProxyInstance(
            VerifiedObjectContextMenuHookInstallerTest.class.getClassLoader(),
            new Class<?>[]{Instrumentation.class},
            (proxy, method, arguments) -> {
                switch (method.getName()) {
                    case "isRetransformClassesSupported" -> { return true; }
                    case "addTransformer" -> {
                        installed.add((ClassFileTransformer) arguments[0]);
                        calls.add("add:" + (installed.size() - 1));
                        return null;
                    }
                    case "getAllLoadedClasses" -> { return new Class<?>[]{Target.class}; }
                    case "isModifiableClass" -> { return true; }
                    case "retransformClasses" -> {
                        calls.add("retransform:" + ((Class<?>[]) arguments[0])[0].getName());
                        if (failRetransform) throw new IllegalStateException("fixture failure");
                        return null;
                    }
                    case "removeTransformer" -> {
                        calls.add("remove:" + installed.indexOf(arguments[0]));
                        return true;
                    }
                    default -> { return defaultValue(method.getReturnType()); }
                }
            }
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
        public Object parts(final Object source) {
            return source;
        }

        public void deformer(final Object source) {
        }
    }
}
