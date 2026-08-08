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
            "animationDocuments", "autoYure", "canvas", "currentModelInstance", "defaultKeyformLocked", "deformers", "drawables", "editLevel", "glues", "id", "mocInfo", "modelEditing", "modelInstances", "name", "parameterBindingBatch", "parameterBindings", "parameterDefinitions", "parameterGroups", "parameters", "parts", "physicsSettings", "profile", "rotationDeformers", "setDefaultKeyformLocked", "setEditLevel", "setName", "statistics", "textures", "update", "warpDeformers");
        assertMethods(Canvas.class,
            "heightPixels", "originXPixels", "originYPixels", "pixelsPerUnit", "widthPixels");
        assertMethods(Part.class,
            "alphaComposition", "childIds", "defaultOrder", "editColor", "getOpacity", "id", "index", "locked", "lockedInHierarchy", "maskIds", "morphTargets", "name", "parentId", "parentIndex", "setAlphaComposition", "setDefaultOrder", "setEditColor", "setId", "setLocked", "setMaskIds", "setName", "setOpacity", "setParent", "setShortName", "setSketch", "setVisible", "shortName", "sketch", "ui", "visible", "visibleInHierarchy");
        assertMethods(Drawable.class,
            "blendMode", "constantFlag", "culling", "doubleSided", "drawOrder", "dynamicFlag", "evaluationState", "geometry", "getOpacity", "getParameterBindings", "guid", "id", "index", "indices", "invertedMask", "locked", "lockedInHierarchy", "maskIds", "masks", "morphTargets", "multiplyColor", "name", "parameterIds", "parameters",
            "parentDeformerId", "parentDeformerIndex", "parentPartId", "parentPartIndex", "renderOrder", "replaceGeometry", "screenColor", "setAlphaComposition", "setClippingMaskIds", "setColorComposition", "setCulling", "setDrawOrder", "setId", "setInvertedMask", "setLocked", "setMultiplyColor", "setName", "setOpacity", "setParent", "setScreenColor", "setTargetDeformer", "setUserData", "setVisible", "textureIndex", "ui", "userData", "vertexPositions", "vertexUvs", "visible", "visibleInHierarchy");
        assertMethods(Deformer.class,
            "getOpacity", "getParameterBindings", "id", "index", "locked", "lockedInHierarchy", "multiplyColor", "name", "parameterIds", "parameters", "parentDeformerId", "parentDeformerIndex", "parentPartId", "parentPartIndex", "screenColor", "setId", "setLocked", "setMultiplyColor", "setName", "setOpacity", "setParent", "setScreenColor", "setTargetDeformer", "setVisible", "ui", "visible", "visibleInHierarchy");
        assertMethods(Parameter.class,
            "combined", "combinedWith", "combineWith", "getDefaultValue", "getMaximumValue", "getMinimumValue",
            "getParameterBindings", "getValue", "id", "index", "isBlendShape", "keyValues", "name", "repeat",
            "resetToDefault", "setValue", "type", "ui", "uncombine", "updateDefinition");
        assertMethods(ParameterGroup.class, "childGroupIds", "id", "name", "parameterIds", "parentId", "rename", "ui");
        assertMethods(WarpDeformer.class, "grid", "replaceGrid");
        assertMethods(RotationDeformer.class, "baseAngle", "form", "replaceForm", "setBaseAngle");
        assertMethods(Glue.class,
            "drawableA", "drawableAId", "drawableB", "drawableBId", "id", "index", "intensity", "name", "parameterIds", "parameters", "setDrawableA", "setDrawableB", "setId", "setIntensity", "setName");
        assertMethods(Parts.class, "add", "all", "copy", "create", "find", "remove");
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
