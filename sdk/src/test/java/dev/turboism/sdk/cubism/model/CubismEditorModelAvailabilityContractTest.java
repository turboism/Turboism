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

    private static final String[] ALL_DECLARED = {
        "5.0.00", "5.0.01", "5.0.02", "5.0.03", "5.0.04", "5.0.05", "5.0.06", "5.1.00", "5.1.01", "5.1.02", "5.1.03",
        "5.2.00", "5.2.01", "5.2.02", "5.2.03", "5.3.00", "5.3.01", "5.3.02", "5.3.03", "5.3.04"
    };
    private static final String[] EXACT_5_3_ALL = {"5.3.00", "5.3.01", "5.3.02", "5.3.03", "5.3.04"};
    private static final String[] TEXTURE_WRITE_SET = {"5.2.03", "5.3.00", "5.3.01", "5.3.02", "5.3.03", "5.3.04"};

    @Test
    void exact5303CoreReadsAndPartAppearanceDeclareNarrowAvailability() throws Exception {
        assertArrayEquals(
                new String[0],
                CubismModel.class
                        .getMethod("id")
                        .getAnnotation(CubismEditor.class)
                        .value());
        assertEquals(
                "5.0.00",
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
                CubismModel.class
                        .getMethod("canvas")
                        .getAnnotation(CubismEditor.class)
                        .value());
        assertArrayEquals(
                ALL_DECLARED,
                Part.class.getMethod("ui").getAnnotation(CubismEditor.class).value());
        assertArrayEquals(
                ALL_DECLARED, Part.class.getAnnotation(CubismEditor.class).value());
        assertArrayEquals(
                ALL_DECLARED, Drawable.class.getAnnotation(CubismEditor.class).value());
        assertArrayEquals(
                new String[0],
                ModelTextures.class.getAnnotation(CubismEditor.class).value());
        assertEquals(
                "5.0.00", ModelTextures.class.getAnnotation(CubismEditor.class).from());
        assertArrayEquals(
                EXACT_5_3_ALL,
                AlphaComposition.class.getAnnotation(CubismEditor.class).value());
    }

    @Test
    void partOverridesMatchReviewedHostDifferences() throws Exception {
        assertExact53(Part.class.getMethod("maskIds"));
        assertExact53(Part.class.getMethod("setMaskIds", List.class));
        assertExact53(Part.class.getMethod("alphaComposition"));
        assertExact53(Part.class.getMethod("setAlphaComposition", AlphaComposition.class));
        assertExact53(Part.class.getMethod("setOpacity", float.class));
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
                EXACT_5_3_ALL,
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
                    TEXTURE_WRITE_SET, method.getAnnotation(CubismEditor.class).value());
        }
    }

    private static void assertExact53(final Method method) {
        assertArrayEquals(
                EXACT_5_3_ALL, method.getAnnotation(CubismEditor.class).value());
    }
}
