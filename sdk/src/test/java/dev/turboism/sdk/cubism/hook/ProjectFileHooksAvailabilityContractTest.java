package dev.turboism.sdk.cubism.hook;

import dev.turboism.sdk.CubismEditor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProjectFileHooksAvailabilityContractTest {

    private static final String[] REVIEWED_VERSIONS = {"5.0.00", "5.0.01", "5.0.02", "5.0.03", "5.0.04", "5.0.05", "5.0.06", "5.1.00", "5.1.01", "5.1.02", "5.1.03", "5.2.00", "5.2.01", "5.2.02", "5.2.03", "5.3.00", "5.3.01", "5.3.02", "5.3.03", "5.3.04"};
    private static final Set<String> UNPROVEN_MODEL_CREATE = Set.of(
        "beforeCreateModel",
        "onModelCreated",
        "afterCreateModel"
    );

    @Test
    void declaresOnlyExactHostProvenProjectFileMethods() {
        assertNull(ModelFileHooks.class.getAnnotation(CubismEditor.class));
        assertNull(AnimationFileHooks.class.getAnnotation(CubismEditor.class));
        assertNull(EditorLifecycleHooks.class.getAnnotation(CubismEditor.class));

        for (Method method : ModelFileHooks.class.getDeclaredMethods()) {
            if (UNPROVEN_MODEL_CREATE.contains(method.getName())) {
                assertNull(method.getAnnotation(CubismEditor.class), method.getName());
            } else {
                assertExact53(method);
            }
        }
        for (Method method : AnimationFileHooks.class.getDeclaredMethods()) {
            assertExact53(method);
        }
        for (Method method : EditorLifecycleHooks.class.getDeclaredMethods()) {
            if (method.getName().equals("beforeEditorExit")) {
                assertExact53(method);
            } else {
                assertNull(method.getAnnotation(CubismEditor.class), method.getName());
            }
        }
    }

    private static void assertExact53(final Method method) {
        assertArrayEquals(
            REVIEWED_VERSIONS,
            method.getAnnotation(CubismEditor.class).value(),
            method.getName()
        );
    }
}
