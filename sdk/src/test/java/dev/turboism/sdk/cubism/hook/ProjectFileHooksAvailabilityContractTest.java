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

    private static final String[] REVIEWED_VERSIONS = {
        "5.0.00", "5.0.01", "5.0.02", "5.0.03", "5.0.04", "5.0.05", "5.0.06", "5.1.00", "5.1.01", "5.1.02", "5.1.03",
        "5.2.00", "5.2.01", "5.2.02", "5.2.03", "5.3.00", "5.3.01", "5.3.02", "5.3.03", "5.3.04"
    };
    private static final Set<String> UNPROVEN_MODEL_CREATE =
            Set.of("beforeCreateModel", "onModelCreated", "afterCreateModel");

    @Test
    void declaresOnlyExactHostProvenProjectFileMethods() {
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
                assertExact53(method);
            }
        }
        for (Method method : AnimationFileHooks.class.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            assertExact53(method);
        }
        for (Method method : EditorLifecycleHooks.class.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            if (method.getName().equals("beforeEditorExit")) {
                assertExact53(method);
            } else {
                assertNull(method.getAnnotation(CubismEditor.class), method.getName());
            }
        }
    }

    private static void assertExact53(final Method method) {
        assertArrayEquals(
                REVIEWED_VERSIONS, method.getAnnotation(CubismEditor.class).value(), method.getName());
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
