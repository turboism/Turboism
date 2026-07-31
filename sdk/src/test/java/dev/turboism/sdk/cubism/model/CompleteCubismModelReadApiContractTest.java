package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompleteCubismModelReadApiContractTest {

    @Test
    void modelAndChildrenExposeTheCompleteSupportedReadShapeWithoutRawHostTypes() throws Exception {
        assertMethods(CubismModel.class,
            "canvas", "defaultKeyformLocked", "deformers", "drawables", "glues", "id", "parameterBindingBatch", "parameterBindings", "parameterGroups", "parameters", "parts", "rotationDeformers", "setDefaultKeyformLocked", "update", "warpDeformers");
        assertMethods(Canvas.class,
            "heightPixels", "originXPixels", "originYPixels", "pixelsPerUnit", "widthPixels");
        assertMethods(Part.class,
            "getOpacity", "id", "name", "parentIndex", "setName", "setOpacity");
        assertMethods(Drawable.class,
            "blendMode", "constantFlag", "culling", "drawOrder", "dynamicFlag", "geometry", "getOpacity", "getParameterBindings", "id",
            "indices", "invertedMask", "locked", "lockedInHierarchy", "masks", "multiplyColor", "name", "parameters",
            "parentDeformerIndex", "parentPartIndex", "renderOrder", "replaceGeometry", "screenColor", "setLocked",
            "setOpacity", "setVisible", "textureIndex", "userData", "vertexPositions", "vertexUvs", "visible",
            "visibleInHierarchy");
        assertMethods(Deformer.class,
            "getOpacity", "getParameterBindings", "id", "locked", "lockedInHierarchy", "multiplyColor", "name", "parameters", "parentDeformerIndex", "parentPartIndex", "screenColor", "setLocked", "setOpacity", "setVisible", "visible", "visibleInHierarchy");
        assertMethods(WarpDeformer.class, "grid", "replaceGrid");
        assertMethods(RotationDeformer.class, "baseAngle", "form", "replaceForm", "setBaseAngle");
        assertMethods(Glue.class,
            "drawableA", "drawableB", "id", "parameters");

        assertEquals(ArtMeshId.class, returnType(Drawable.class, "id"));
        assertEquals(DeformerId.class, returnType(Deformer.class, "id"));
        assertEquals(int.class, IntSequence.class.getMethod("get", int.class).getReturnType());
    }

    private static void assertMethods(final Class<?> type, final String... names) {
        assertEquals(
            Set.copyOf(Arrays.asList(names)),
            Arrays.stream(type.getDeclaredMethods()).filter(method -> Modifier.isPublic(method.getModifiers())).map(Method::getName).collect(java.util.stream.Collectors.toSet()),
            type.getName()
        );
        Arrays.stream(type.getDeclaredMethods()).filter(method -> Modifier.isPublic(method.getModifiers())).forEach(method -> {
            assertNoRawHostType(method.getReturnType());
            Arrays.stream(method.getParameterTypes()).forEach(
                CompleteCubismModelReadApiContractTest::assertNoRawHostType
            );
        });
    }

    private static Class<?> returnType(final Class<?> type, final String method) throws Exception {
        return type.getMethod(method).getReturnType();
    }

    private static void assertNoRawHostType(final Class<?> type) {
        final String name = type.getName();
        if (name.startsWith("com.live2d.")) {
            throw new AssertionError("raw host type leaked: " + name);
        }
    }
}
