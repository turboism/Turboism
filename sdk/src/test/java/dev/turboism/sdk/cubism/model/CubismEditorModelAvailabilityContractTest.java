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

    private static final String[] ALL_DECLARED = {"5.2.03", "5.3.02", "5.3.03"};
    private static final String[] EXACT_5_3 = {"5.3.02", "5.3.03"};

    @Test
    void exact5303CoreReadsAndPartAppearanceDeclareNarrowAvailability() throws Exception {
        assertArrayEquals(
                new String[0],
                CubismModel.class
                        .getMethod("id")
                        .getAnnotation(CubismEditor.class)
                        .value());
        assertEquals(
                "5.2.03",
                CubismModel.class
                        .getMethod("id")
                        .getAnnotation(CubismEditor.class)
                        .from());
        assertArrayEquals(
                ALL_DECLARED,
                CubismModel.class
                        .getMethod("parameterDefinitions")
                        .getAnnotation(CubismEditor.class)
                        .value());
        assertArrayEquals(
            ALL_DECLARED,
            CubismModel.class.getMethod("canvas").getAnnotation(CubismEditor.class).value()
        );
        assertArrayEquals(ALL_DECLARED, Part.class.getMethod("ui")
            .getAnnotation(CubismEditor.class).value());
        assertArrayEquals(ALL_DECLARED, Part.class.getAnnotation(CubismEditor.class).value());
        assertArrayEquals(ALL_DECLARED, Drawable.class.getAnnotation(CubismEditor.class).value());
        assertArrayEquals(ALL_DECLARED, ModelTextures.class.getAnnotation(CubismEditor.class).value());
        assertArrayEquals(EXACT_5_3, AlphaComposition.class.getAnnotation(CubismEditor.class).value());
    }

    @Test
    void partOverridesMatchReviewedHostDifferences() throws Exception {
        assertReviewed53(Part.class.getMethod("maskIds"));
        assertReviewed53(Part.class.getMethod("setMaskIds", List.class));
        assertReviewed53(Part.class.getMethod("alphaComposition"));
        assertReviewed53(Part.class.getMethod("setAlphaComposition", AlphaComposition.class));
        assertReviewed53(Part.class.getMethod("setOpacity", float.class));
    }

    @Test
    void glueReadsAndWritesDeclareAllThreeExactReviewedVersions() throws Exception {
        assertArrayEquals(
                ALL_DECLARED, Glue.class.getAnnotation(CubismEditor.class).value());
        assertArrayEquals(
                ALL_DECLARED, Glues.class.getAnnotation(CubismEditor.class).value());
        assertArrayEquals(
                ALL_DECLARED,
                CubismModel.class
                        .getMethod("glues")
                        .getAnnotation(CubismEditor.class)
                        .value());
    }

    @Test
    void drawableOverrideIncludesTheDeclared5303Contract() throws Exception {
        assertArrayEquals(
                EXACT_5_3,
                Drawable.class
                        .getMethod("setAlphaComposition", AlphaComposition.class)
                        .getAnnotation(CubismEditor.class)
                        .value());
    }

    @Test
    void textureAuthoringAllowsCompatibleVersionsAboveItsDeclaredFloor() throws Exception {
        for (Method method : List.of(
                ModelTextures.class.getMethod("addModelImageGroup", String.class),
                ModelTextures.class.getMethod("removeModelImage", ModelImageId.class),
                ModelTextures.class.getMethod("addTextureAtlas", String.class, int.class, int.class),
                ModelTextures.class.getMethod("removeTextureAtlas", TextureAtlasId.class),
                ModelTextures.class.getMethod("removeRawImage", RawImageId.class))) {
            assertArrayEquals(
                    new String[0], method.getAnnotation(CubismEditor.class).value());
            assertEquals("5.2.03", method.getAnnotation(CubismEditor.class).from());
        }
    }

    private static void assertReviewed53(final Method method) {
        assertArrayEquals(EXACT_5_3, method.getAnnotation(CubismEditor.class).value());
    }
}
