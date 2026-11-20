package dev.turboism.sdk.cubism.hook;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.CubismEditor;
import java.lang.reflect.Method;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProjectFileHooksAvailabilityContractTest {

    private static final String EXPECTED_FROM = "5.0.00";
    private static final Set<String> UNPROVEN_MODEL_CREATE =
            Set.of("beforeCreateModel", "onModelCreated", "afterCreateModel");

    @Test
    void declaresOnlyHostProvenProjectFileMethods() {
        assertUnrestrictive(ModelFileHooks.class);
        assertUnrestrictive(AnimationFileHooks.class);
        assertUnrestrictive(EditorLifecycleHooks.class);

        for (Method method : ModelFileHooks.class.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            if (UNPROVEN_MODEL_CREATE.contains(method.getName())) {
                assertNull(method.getAnnotation(CubismEditor.class), method.getName());
            } else {
                assertFrom50(method);
            }
        }
        for (Method method : AnimationFileHooks.class.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            assertFrom50(method);
        }
        for (Method method : EditorLifecycleHooks.class.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            if (method.getName().equals("beforeEditorExit")) {
                assertFrom50(method);
            } else {
                assertNull(method.getAnnotation(CubismEditor.class), method.getName());
            }
        }
    }

    private static void assertFrom50(final Method method) {
        final CubismEditor declaration = method.getAnnotation(CubismEditor.class);
        assertNotNull(declaration, method.getName());
        assertArrayEquals(new String[0], declaration.value(), method.getName());
        assertEquals(EXPECTED_FROM, declaration.from(), method.getName());
    }

    private static void assertUnrestrictive(final Class<?> type) {
        final CubismEditor declaration = type.getAnnotation(CubismEditor.class);
        assertNotNull(declaration);
        assertEquals(0, declaration.value().length);
        assertTrue(declaration.from().isEmpty());
        assertTrue(declaration.to().isEmpty());
        assertEquals(0, declaration.exclude().length);
    }
}
