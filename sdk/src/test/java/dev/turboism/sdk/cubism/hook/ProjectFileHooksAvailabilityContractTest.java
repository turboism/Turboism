package dev.turboism.sdk.cubism.hook;

import dev.turboism.sdk.CubismEditor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProjectFileHooksAvailabilityContractTest {

    private static final String[] EXACT_5_3 = {"5.3.02", "5.3.03"};
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
            EXACT_5_3,
            method.getAnnotation(CubismEditor.class).value(),
            method.getName()
        );
    }
}
