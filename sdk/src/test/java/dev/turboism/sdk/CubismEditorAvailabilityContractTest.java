package dev.turboism.sdk;

import dev.turboism.sdk.cubism.CubismEditorApiUnavailableException;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CubismEditorAvailabilityContractTest {

    @Test
    void annotationIsRuntimeVisibleOnTypesAndMethods() throws Exception {
        assertEquals(
            RetentionPolicy.RUNTIME,
            CubismEditor.class.getAnnotation(Retention.class).value()
        );
        assertArrayEquals(
            new ElementType[] {ElementType.TYPE, ElementType.METHOD},
            CubismEditor.class.getAnnotation(Target.class).value()
        );
        assertArrayEquals(
            new String[] {"5.2.03", "5.3.02"},
            Example.class.getAnnotation(CubismEditor.class).value()
        );
        assertArrayEquals(
            new String[] {"5.3.02"},
            Example.class.getMethod("narrow").getAnnotation(CubismEditor.class).value()
        );
    }

    @Test
    void unavailableExceptionExposesStructuredImmutableDetails() {
        final var failure = new CubismEditorApiUnavailableException(
            "dev.turboism.sdk.Example#narrow()",
            Optional.of("5.2.03"),
            List.of("5.3.02")
        );

        assertEquals("dev.turboism.sdk.Example#narrow()", failure.apiId());
        assertEquals(Optional.of("5.2.03"), failure.activeVersion());
        assertEquals(List.of("5.3.02"), failure.supportedVersions());
        assertTrue(failure.getMessage().contains("5.2.03"));
        assertTrue(failure.getMessage().contains("5.3.02"));
    }

    @CubismEditor({"5.2.03", "5.3.02"})
    private interface Example {
        @CubismEditor("5.3.02")
        void narrow();
    }
}
