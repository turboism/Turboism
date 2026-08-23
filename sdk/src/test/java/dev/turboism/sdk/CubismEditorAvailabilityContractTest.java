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
        final CubismEditor ranged = RangeExample.class.getAnnotation(CubismEditor.class);
        assertArrayEquals(new String[0], ranged.value());
        assertEquals("5.2.03", ranged.from());
        assertEquals("5.5.01", ranged.to());
        assertArrayEquals(new String[] {"5.3.03"}, ranged.exclude());
        final CubismEditor inverse = InverseExample.class.getAnnotation(CubismEditor.class);
        assertArrayEquals(new String[0], inverse.value());
        assertEquals("", inverse.from());
        assertEquals("", inverse.to());
        assertArrayEquals(new String[] {"5.3.03"}, inverse.exclude());
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

        final var prohibited = new CubismEditorApiUnavailableException(
            "dev.turboism.sdk.Example#prohibited()",
            Optional.of("5.3.02"),
            List.of()
        );
        assertEquals(List.of(), prohibited.supportedVersions());
    }

    @CubismEditor({"5.2.03", "5.3.02"})
    private interface Example {
        @CubismEditor("5.3.02")
        void narrow();
    }

    @CubismEditor(from = "5.2.03", to = "5.5.01", exclude = "5.3.03")
    private interface RangeExample {
    }

    @CubismEditor(exclude = "5.3.03")
    private interface InverseExample {
    }
}
