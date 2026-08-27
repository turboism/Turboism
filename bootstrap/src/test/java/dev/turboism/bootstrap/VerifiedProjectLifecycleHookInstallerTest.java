package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.lifecycle.EditorLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.NativeProjectLifecycleBridge;
import dev.turboism.adapter.cubism.lifecycle.ProjectFileLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.ProjectLifecycleHostProfile;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class VerifiedProjectLifecycleHookInstallerTest {

    @Test
    void installsOneExactTransformerAndRetransformsLoadedTargetClasses() throws Exception {
        final List<String> calls = new ArrayList<>();
        final Instrumentation instrumentation = instrumentation(calls, new Class<?>[]{Target.class});
        final ProjectFileLifecycleCoordinator projectFiles = new ProjectFileLifecycleCoordinator();
        final EditorLifecycleCoordinator editor = new EditorLifecycleCoordinator();
        final ProjectLifecycleHostProfile profile = new ProjectLifecycleHostProfile(
            "5.3.03",
            List.of(dev.turboism.adapter.cubism.lifecycle
                .ProjectLifecycleNativeMethodTransformer.Binding.editorExit(
                    Target.class.getName().replace('.', '/'),
                    "exit",
                    "()Z"
                ))
        );

        try (VerifiedProjectLifecycleHookInstaller installer =
                 new VerifiedProjectLifecycleHookInstaller(
                     instrumentation,
                     Target.class.getClassLoader(),
                     profile,
                     projectFiles,
                     editor
                 )) {
            installer.install();
        } finally {
            editor.close();
            projectFiles.close();
        }

        assertEquals(List.of(
            "add:true",
            "retransform:" + Target.class.getName(),
            "remove"
        ), calls);
    }

    @Test
    void sanitized5303ProfileIsInstallableAfterOpeningTheFullRuntimeGate() throws Exception {
        final List<String> calls = new ArrayList<>();
        final Instrumentation instrumentation = instrumentation(calls, new Class<?>[0]);
        final ProjectFileLifecycleCoordinator projectFiles = new ProjectFileLifecycleCoordinator();
        final EditorLifecycleCoordinator editor = new EditorLifecycleCoordinator();
        final ProjectLifecycleHostProfile profile = ProjectLifecycleHostProfile.forArtifact(
            ReviewedHostArtifacts.CUBISM_5_3_03
        ).orElseThrow();

        try (VerifiedProjectLifecycleHookInstaller installer =
                 new VerifiedProjectLifecycleHookInstaller(
                     instrumentation,
                     Target.class.getClassLoader(),
                     profile,
                     projectFiles,
                     editor
                 )) {
            installer.install();
        } finally {
            NativeProjectLifecycleBridge.completeBoolean(true);
            editor.close();
            projectFiles.close();
        }

        assertEquals(List.of("add:true", "remove"), calls);
        org.junit.jupiter.api.Assertions.assertTrue(
            ReviewedHostArtifacts.admitsFullRuntime("5.3.03")
        );
    }

    private Instrumentation instrumentation(
        final List<String> calls,
        final Class<?>[] loadedClasses
    ) {
        return (Instrumentation) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[]{Instrumentation.class},
            (proxy, method, arguments) -> {
                switch (method.getName()) {
                    case "isRetransformClassesSupported" -> { return true; }
                    case "addTransformer" -> {
                        calls.add("add:" + arguments[1]);
                        return null;
                    }
                    case "getAllLoadedClasses" -> { return loadedClasses; }
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
        public boolean exit() {
            return true;
        }
    }
}
