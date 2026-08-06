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
            "canvas", "defaultKeyformLocked", "deformers", "drawables", "editLevel", "glues", "id", "mocInfo", "name", "parameterBindingBatch", "parameterBindings", "parameterDefinitions", "parameterGroups", "parameters", "parts", "rotationDeformers", "setDefaultKeyformLocked", "setEditLevel", "setName", "statistics", "update", "warpDeformers");
        assertMethods(Canvas.class,
            "heightPixels", "originXPixels", "originYPixels", "pixelsPerUnit", "widthPixels");
        assertMethods(Part.class,
            "childIds", "defaultOrder", "editColor", "getOpacity", "id", "index", "locked", "lockedInHierarchy", "name", "parentId", "parentIndex", "setDefaultOrder", "setEditColor", "setLocked", "setName", "setOpacity", "setParent", "setShortName", "setSketch", "setVisible", "shortName", "sketch", "ui", "visible", "visibleInHierarchy");
        assertMethods(Drawable.class,
            "blendMode", "constantFlag", "culling", "doubleSided", "drawOrder", "dynamicFlag", "evaluationState", "geometry", "getOpacity", "getParameterBindings", "id", "index",
            "indices", "invertedMask", "locked", "lockedInHierarchy", "maskIds", "masks", "multiplyColor", "name", "parameterIds", "parameters",
            "parentDeformerId", "parentDeformerIndex", "parentPartId", "parentPartIndex", "renderOrder", "replaceGeometry", "screenColor", "setLocked",
            "setName", "setOpacity", "setParent", "setVisible", "textureIndex", "ui", "userData", "vertexPositions", "vertexUvs", "visible",
            "visibleInHierarchy");
        assertMethods(Deformer.class,
            "getOpacity", "getParameterBindings", "id", "index", "locked", "lockedInHierarchy", "multiplyColor", "name", "parameterIds", "parameters", "parentDeformerId", "parentDeformerIndex", "parentPartId", "parentPartIndex", "screenColor", "setLocked", "setName", "setOpacity", "setParent", "setVisible", "ui", "visible", "visibleInHierarchy");
        assertMethods(Parameter.class,
            "combined", "combinedWith", "combineWith", "getDefaultValue", "getMaximumValue", "getMinimumValue",
            "getParameterBindings", "getValue", "id", "index", "isBlendShape", "keyValues", "name", "repeat",
            "resetToDefault", "setValue", "type", "ui", "uncombine", "updateDefinition");
        assertMethods(ParameterGroup.class, "childGroupIds", "id", "name", "parameterIds", "parentId", "ui");
        assertMethods(WarpDeformer.class, "grid", "replaceGrid");
        assertMethods(RotationDeformer.class, "baseAngle", "form", "replaceForm", "setBaseAngle");
        assertMethods(Glue.class,
            "drawableA", "drawableAId", "drawableB", "drawableBId", "id", "index", "parameterIds", "parameters");
        assertMethods(Parts.class, "all", "create", "find", "remove");
        assertMethods(Deformers.class, "all", "createRotation", "createWarp", "find", "remove");
        assertMethods(Drawables.class, "all", "create", "find", "remove");

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
