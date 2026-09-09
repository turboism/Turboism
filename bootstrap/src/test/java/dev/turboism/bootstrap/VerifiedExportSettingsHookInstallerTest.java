package dev.turboism.bootstrap;

import dev.turboism.mapping.verification.StaticSelector;
import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Reversible bootstrap installer tests: exact transformer admission, owner
 * retransformation and restoration ordering.
 */
class VerifiedExportSettingsHookInstallerTest {

    private static final String OWNER = "com/live2d/cubism/doc/model/exporter/e";
    private static final String WINDOW = "com/live2d/ui/window/y";
    private static final String TARGET = Target.class.getName().replace('.', '/');

    @Test
    void installsOneExactTransformerAndRestoresTheOwnerBeforeTeardown() throws Exception {
        final List<String> calls = new ArrayList<>();
        final Instrumentation instrumentation = instrumentation(calls, 0);

        try (VerifiedExportSettingsHookInstaller installer = fromVerifiedResolver(instrumentation, TARGET)) {
            installer.install();
            installer.install();
        }

        assertEquals(List.of(
            "add:true",
            "retransform:" + Target.class.getName(),
            "remove",
            "retransform:" + Target.class.getName()
        ), calls, "teardown must remove the transformer and retransform the exact owner");
    }

    @Test
    void registersTransformerBeforeScanningAlreadyLoadedClasses() throws Exception {
        final List<String> calls = new ArrayList<>();
        final boolean[] transformerAdded = {false};
        final Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[]{Instrumentation.class},
            (proxy, method, arguments) -> {
                switch (method.getName()) {
                    case "isRetransformClassesSupported" -> { return true; }
                    case "addTransformer" -> {
                        transformerAdded[0] = true;
                        calls.add("add:" + arguments[1]);
                        return null;
                    }
                    case "getAllLoadedClasses" -> {
                        calls.add("scan:" + transformerAdded[0]);
                        return new Class<?>[0];
                    }
                    case "removeTransformer" -> {
                        calls.add("remove");
                        return true;
                    }
                    default -> { return defaultValue(method.getReturnType()); }
                }
            }
        );
        final VerifiedExportSettingsHookInstaller installer = fromVerifiedResolver(instrumentation, TARGET);
        try {
            installer.install();
        } finally {
            installer.close();
        }
        assertEquals(
            List.of("add:true", "scan:true", "remove", "scan:true"),
            calls,
            "first-load installation must register before scanning loaded classes"
        );
    }

    @Test
    void acceptsOnlyTheReviewedExactCubismVersion() {
        assertEquals(true, VerifiedExportSettingsHookInstaller
            .supportsExactCubismVersion("5.3.02"));
        assertEquals(false, VerifiedExportSettingsHookInstaller
            .supportsExactCubismVersion("5.3.03"));
        assertEquals(false, VerifiedExportSettingsHookInstaller
            .supportsExactCubismVersion("5.3.02-preview"));
        assertEquals(false, VerifiedExportSettingsHookInstaller
            .supportsExactCubismVersion(null));
    }

    @Test
    void retriesCleanupAfterARestorationFailureWithoutRemovingTheBridgeEarly() throws Exception {
        final List<String> calls = new ArrayList<>();
        final AtomicInteger retransforms = new AtomicInteger();
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
                        if (retransforms.incrementAndGet() == 2) {
                            throw new IllegalStateException("cleanup failure");
                        }
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
        final VerifiedExportSettingsHookInstaller installer = fromVerifiedResolver(instrumentation, TARGET);
        installer.install();
        assertThrows(IllegalStateException.class, installer::close);
        installer.close();
        assertEquals(List.of(
            "add:true",
            "retransform:" + Target.class.getName(),
            "remove",
            "retransform:" + Target.class.getName(),
            "retransform:" + Target.class.getName()
        ), calls);
    }

    @Test
    void installFailsClosedWhenRetransformationIsUnsupported() throws Exception {
        final List<String> calls = new ArrayList<>();
        final Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[]{Instrumentation.class},
            (proxy, method, arguments) -> {
                if ("isRetransformClassesSupported".equals(method.getName())) {
                    return false;
                }
                calls.add(method.getName());
                return defaultValue(method.getReturnType());
            }
        );
        final VerifiedExportSettingsHookInstaller installer = fromVerifiedResolver(instrumentation, TARGET);
        assertThrows(IllegalStateException.class, installer::install);
        assertEquals(List.of(), calls, "no transformer may be added without retransformation support");
    }

    @Test
    void installRollsBackTheTransformerWhenRetransformationFails() throws Exception {
        final List<String> calls = new ArrayList<>();
        final AtomicInteger retransforms = new AtomicInteger();
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
                        if (retransforms.incrementAndGet() == 1) {
                            throw new IllegalStateException("retransform failure");
                        }
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
        final VerifiedExportSettingsHookInstaller installer = fromVerifiedResolver(instrumentation, TARGET);
        assertThrows(IllegalStateException.class, installer::install);
        assertEquals(List.of(
            "add:true",
            "retransform:" + Target.class.getName(),
            "remove",
            "retransform:" + Target.class.getName()
        ), calls, "a failed retransformation must remove the transformer and restore the owner");
    }

    @Test
    void rejectsSelectorsThatDoNotMatchTheExactDialogShape() {
        final List<String> calls = new ArrayList<>();
        final Instrumentation instrumentation = instrumentation(calls, 0);
        final StaticSelector owner = classSelector(OWNER);
        final StaticSelector constructor = constructorSelector(OWNER);
        final StaticSelector show = methodSelector(OWNER, "a",
            "(Lcom/live2d/ui/window/V;Lcom/live2d/cubism/doc/model/exporter/CModelExportSettingDialogData;"
                + "Lcom/live2d/cubism/doc/model/exporter/CModelExportSettingDialogData;)Z");
        final StaticSelector contentBuilder = methodSelector(OWNER, "b", "()V");
        final StaticSelector windowField = fieldSelector(OWNER, "c");
        final StaticSelector windowClass = classSelector(WINDOW);
        // The jdialog accessor is pinned to a different owner than the window class.
        final StaticSelector mismatchedJdialog = methodSelector("com/live2d/ui/window/other", "e",
            "()Ljavax/swing/JDialog;");
        assertThrows(
            IllegalArgumentException.class,
            () -> new VerifiedExportSettingsHookInstaller(
                instrumentation,
                owner,
                constructor,
                show,
                contentBuilder,
                windowField,
                windowClass,
                mismatchedJdialog,
                Target.class.getClassLoader()
            ),
            "selectors that do not belong to the exact dialog shape must be rejected"
        );
        assertEquals(List.of(), calls);
    }

    private static VerifiedExportSettingsHookInstaller fromVerifiedResolver(
        final Instrumentation instrumentation,
        final String owner
    ) {
        return new VerifiedExportSettingsHookInstaller(
            instrumentation,
            classSelector(owner),
            constructorSelector(owner),
            methodSelector(owner, "a",
                "(Lcom/live2d/ui/window/V;Lcom/live2d/cubism/doc/model/exporter/CModelExportSettingDialogData;"
                    + "Lcom/live2d/cubism/doc/model/exporter/CModelExportSettingDialogData;)Z"),
            methodSelector(owner, "b", "()V"),
            fieldSelector(owner, "c"),
            classSelector(WINDOW),
            methodSelector(WINDOW, "e", "()Ljavax/swing/JDialog;"),
            Target.class.getClassLoader()
        );
    }

    private static Instrumentation instrumentation(
        final List<String> calls,
        final int retransformFailures
    ) {
        final AtomicInteger retransforms = new AtomicInteger();
        return (Instrumentation) Proxy.newProxyInstance(
            VerifiedExportSettingsHookInstallerTest.class.getClassLoader(),
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
                        if (retransforms.incrementAndGet() <= retransformFailures) {
                            throw new IllegalStateException("retransform failure");
                        }
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
    }

    private static StaticSelector classSelector(final String owner) {
        return StaticSelector.classSelector(owner, owner);
    }

    private static StaticSelector constructorSelector(final String owner) {
        return StaticSelector.constructor(
            owner, owner,
            "(Lcom/live2d/cubism/doc/model/CModelSource;)V",
            StaticSelector.ACCESS_PUBLIC
        );
    }

    private static StaticSelector methodSelector(
        final String owner,
        final String name,
        final String descriptor
    ) {
        return StaticSelector.method(
            owner + "." + name,
            owner,
            name,
            descriptor,
            StaticSelector.ACCESS_PUBLIC
        );
    }

    private static StaticSelector fieldSelector(final String owner, final String name) {
        return new StaticSelector(
            owner + "." + name,
            owner + "." + name,
            StaticSelector.Kind.FIELD,
            owner,
            name,
            "L" + WINDOW + ";",
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

    /** Any host-loader class; the retransform matching is name-driven. */
    public static final class Target {
    }
}
