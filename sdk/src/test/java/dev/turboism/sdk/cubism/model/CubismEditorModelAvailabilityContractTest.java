package dev.turboism.sdk.cubism.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.id.ModelImageId;
import dev.turboism.sdk.cubism.id.RawImageId;
import dev.turboism.sdk.cubism.id.TextureAtlasId;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

class CubismEditorModelAvailabilityContractTest {

    private static final String EXPECTED_FROM_5_0 = "5.0.00";
    private static final String EXPECTED_FROM_5_3 = "5.3.00";
    private static final String[] TEXTURE_WRITE_SET = {
        "5.0.00", "5.0.01", "5.0.02", "5.0.03", "5.0.04", "5.0.05", "5.0.06", "5.1.00", "5.1.01", "5.1.02", "5.1.03",
        "5.2.00", "5.2.01", "5.2.02", "5.2.03", "5.3.00", "5.3.01", "5.3.02", "5.3.03", "5.3.04", "5.3.05"
    };

    @Test
    void exact5303CoreReadsAndPartAppearanceDeclareNarrowAvailability() throws Exception {
        assertFrom(CubismModel.class.getMethod("id").getAnnotation(CubismEditor.class), EXPECTED_FROM_5_0);
        assertFrom(
                CubismModel.class.getMethod("parameterDefinitions").getAnnotation(CubismEditor.class),
                EXPECTED_FROM_5_0);
        assertFrom(CubismModel.class.getMethod("canvas").getAnnotation(CubismEditor.class), EXPECTED_FROM_5_0);
        assertFrom(Part.class.getMethod("ui").getAnnotation(CubismEditor.class), EXPECTED_FROM_5_0);
        assertArrayEquals(
                TEXTURE_WRITE_SET, Part.class.getAnnotation(CubismEditor.class).value());
        assertFrom(Drawable.class.getAnnotation(CubismEditor.class), EXPECTED_FROM_5_0);
        assertFrom(ModelTextures.class.getAnnotation(CubismEditor.class), EXPECTED_FROM_5_0);
        assertFrom(AlphaComposition.class.getAnnotation(CubismEditor.class), EXPECTED_FROM_5_3);
    }

    @Test
    void partOverridesMatchReviewedHostDifferences() throws Exception {
        assertFrom53(Part.class.getMethod("maskIds"));
        assertFrom53(Part.class.getMethod("setMaskIds", List.class));
        assertFrom53(Part.class.getMethod("alphaComposition"));
        assertFrom53(Part.class.getMethod("setAlphaComposition", AlphaComposition.class));
        assertFrom53(Part.class.getMethod("setOpacity", float.class));
    }

    @Test
    void glueReadsAndWritesDeclareFrom50Availability() throws Exception {
        assertFrom(Glue.class.getAnnotation(CubismEditor.class), EXPECTED_FROM_5_0);
        assertFrom(Glues.class.getAnnotation(CubismEditor.class), EXPECTED_FROM_5_0);
        assertFrom(CubismModel.class.getMethod("glues").getAnnotation(CubismEditor.class), EXPECTED_FROM_5_0);
    }

    @Test
    void drawableOverrideIncludesTheDeclared5300Contract() throws Exception {
        assertFrom53(Drawable.class.getMethod("setAlphaComposition", AlphaComposition.class));
    }

    @Test
    void textureAuthoringDeclaresEveryReviewedExactInstallation() throws Exception {
        for (Method method : List.of(
                ModelTextures.class.getMethod("addModelImageGroup", String.class),
                ModelTextures.class.getMethod("removeModelImage", ModelImageId.class),
                ModelTextures.class.getMethod("addTextureAtlas", String.class, int.class, int.class),
                ModelTextures.class.getMethod("removeTextureAtlas", TextureAtlasId.class),
                ModelTextures.class.getMethod("removeRawImage", RawImageId.class))) {
            assertArrayEquals(
                    TEXTURE_WRITE_SET, method.getAnnotation(CubismEditor.class).value());
        }
    }

    private static void assertFrom53(final Method method) {
        assertFrom(method.getAnnotation(CubismEditor.class), EXPECTED_FROM_5_3);
    }

    private static void assertFrom(final CubismEditor annotation, final String expectedFrom) {
        assertArrayEquals(new String[0], annotation.value());
        assertEquals(expectedFrom, annotation.from());
    }
}
