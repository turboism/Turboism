package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.id.RawImageId;
import dev.turboism.sdk.cubism.id.ModelImageId;
import dev.turboism.sdk.cubism.id.TextureAtlasId;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CubismEditorModelAvailabilityContractTest {

    private static final String[] ALL_DECLARED = {"5.2.03", "5.3.02", "5.3.03"};
    private static final String[] ONLY_5_3_02 = {"5.3.02"};
    private static final String[] EXACT_5_3 = {"5.3.02", "5.3.03"};

    @Test
    void exact5303CoreReadsAndPartAppearanceDeclareNarrowAvailability() throws Exception {
        assertArrayEquals(new String[0], CubismModel.class.getMethod("id").getAnnotation(CubismEditor.class).value());
        assertEquals("5.2.03", CubismModel.class.getMethod("id").getAnnotation(CubismEditor.class).from());
        assertArrayEquals(
            ALL_DECLARED,
            CubismModel.class.getMethod("parameterDefinitions")
                .getAnnotation(CubismEditor.class).value()
        );
        assertArrayEquals(
            ALL_DECLARED,
            CubismModel.class.getMethod("canvas").getAnnotation(CubismEditor.class).value()
        );
        assertArrayEquals(ALL_DECLARED, Part.class.getMethod("ui")
            .getAnnotation(CubismEditor.class).value());
        assertArrayEquals(ALL_DECLARED, Part.class.getAnnotation(CubismEditor.class).value());
        assertArrayEquals(ALL_DECLARED, Drawable.class.getAnnotation(CubismEditor.class).value());
        assertArrayEquals(new String[0], ModelTextures.class.getAnnotation(CubismEditor.class).value());
        assertEquals("5.2.03", ModelTextures.class.getAnnotation(CubismEditor.class).from());
        assertArrayEquals(ONLY_5_3_02, AlphaComposition.class.getAnnotation(CubismEditor.class).value());
    }

    @Test
    void partOverridesMatchReviewedHostDifferences() throws Exception {
        assertOnly5302(Part.class.getMethod("maskIds"));
        assertOnly5302(Part.class.getMethod("setMaskIds", List.class));
        assertOnly5302(Part.class.getMethod("alphaComposition"));
        assertOnly5302(Part.class.getMethod("setAlphaComposition", AlphaComposition.class));
        assertOnly5302(Part.class.getMethod("setOpacity", float.class));
    }

    @Test
    void glueReadsAndWritesDeclareAllThreeExactReviewedVersions() throws Exception {
        assertArrayEquals(ALL_DECLARED, Glue.class.getAnnotation(CubismEditor.class).value());
        assertArrayEquals(ALL_DECLARED, Glues.class.getAnnotation(CubismEditor.class).value());
        assertArrayEquals(
            ALL_DECLARED,
            CubismModel.class.getMethod("glues").getAnnotation(CubismEditor.class).value()
        );
    }

    @Test
    void drawableOverrideIncludesTheDeclared5303Contract() throws Exception {
        assertArrayEquals(
            EXACT_5_3,
            Drawable.class.getMethod("setAlphaComposition", AlphaComposition.class)
                .getAnnotation(CubismEditor.class).value()
        );
    }

    @Test
    void textureAuthoringAllowsCompatibleVersionsAboveItsDeclaredFloor() throws Exception {
        for (Method method : List.of(
            ModelTextures.class.getMethod("addModelImageGroup", String.class),
            ModelTextures.class.getMethod("removeModelImage", ModelImageId.class),
            ModelTextures.class.getMethod("addTextureAtlas", String.class, int.class, int.class),
            ModelTextures.class.getMethod("removeTextureAtlas", TextureAtlasId.class),
            ModelTextures.class.getMethod("removeRawImage", RawImageId.class)
        )) {
            assertArrayEquals(new String[0], method.getAnnotation(CubismEditor.class).value());
            assertEquals("5.2.03", method.getAnnotation(CubismEditor.class).from());
        }
    }

    private static void assertOnly5302(final Method method) {
        assertArrayEquals(ONLY_5_3_02, method.getAnnotation(CubismEditor.class).value());
    }
}
