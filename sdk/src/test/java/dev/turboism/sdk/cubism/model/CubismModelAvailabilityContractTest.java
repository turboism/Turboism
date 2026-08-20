package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.Cubism;
import dev.turboism.sdk.cubism.id.RawImageId;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class CubismModelAvailabilityContractTest {

    private static final String[] BOTH = {"5.2.03", "5.3.02"};
    private static final String[] ONLY_5_3_02 = {"5.3.02"};

    @Test
    void reviewedModelTypesDeclareExactDefaults() {
        assertArrayEquals(BOTH, Part.class.getAnnotation(Cubism.class).value());
        assertArrayEquals(BOTH, Drawable.class.getAnnotation(Cubism.class).value());
        assertArrayEquals(BOTH, ModelTextures.class.getAnnotation(Cubism.class).value());
        assertArrayEquals(ONLY_5_3_02, AlphaComposition.class.getAnnotation(Cubism.class).value());
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
    void drawableAndTextureOverridesMatchReviewedHostDifferences() throws Exception {
        assertOnly5302(Drawable.class.getMethod("setAlphaComposition", AlphaComposition.class));
        assertOnly5302(ModelTextures.class.getMethod("removeRawImage", RawImageId.class));
    }

    private static void assertOnly5302(final Method method) {
        assertArrayEquals(ONLY_5_3_02, method.getAnnotation(Cubism.class).value());
    }
}
